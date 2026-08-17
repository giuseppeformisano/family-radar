package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.model.UserLocation
import com.example.repository.FirebaseRepository
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Servizio in foreground che tiene viva la posizione anche ad app chiusa.
 *
 * Divisione delle responsabilità: qui si raccolgono i fix e si mantiene la notifica
 * persistente; la decisione se un fix meriti di finire su Firestore sta tutta in
 * [FirebaseRepository.updateLocation], perché è l'unico punto attraversato anche
 * dal tracciamento in-app silenzioso. Duplicare il filtro qui vorrebbe dire due
 * soglie che prima o poi divergono.
 *
 * Il servizio richiede quindi fix a cadenza piena e lascia filtrare al repository:
 * chiedere meno fix (`setMinUpdateDistanceMeters`) risparmierebbe poco e romperebbe
 * l'heartbeat, che serve proprio quando il dispositivo è immobile.
 */
class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var repository: FirebaseRepository

    private var currentIntervalMs: Long = 30_000L
    private var lastFixAtMillis: Long = 0L
    private var lastKnownLocation: Location? = null

    override fun onCreate() {
        super.onCreate()
        try {
            repository = FirebaseRepository.getInstance(this)
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            createNotificationChannel()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { handleNewLocation(it) }
                }
            }

            startNotificationRefreshLoop()
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate error: ${t.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                currentIntervalMs = intervalMsFrom(intent)
                startForegroundTracking()
            }

            ACTION_STOP -> {
                stopTracking()
                try {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(NOTIFICATION_ID)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (t: Throwable) {
                    Log.w(TAG, "stopForeground error: ${t.message}")
                }
                stopSelf()
            }

            ACTION_UPDATE_INTERVAL -> {
                currentIntervalMs = intervalMsFrom(intent)
                requestLocationUpdates()
            }

            ACTION_FORCE_SYNC -> forcePushLastKnownLocation()
        }

        return START_STICKY
    }

    private fun intervalMsFrom(intent: Intent?): Long {
        val seconds = intent?.getIntExtra(EXTRA_INTERVAL_SEC, 30) ?: 30
        return (seconds.coerceIn(5, 86_400) * 1000).toLong()
    }

    private fun startForegroundTracking() {
        try {
            val notification = buildForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            requestLocationUpdates()
        } catch (t: Throwable) {
            Log.e(TAG, "startForegroundTracking error: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)

            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMs)
                .setMinUpdateIntervalMillis(currentIntervalMs / 2)
                .setWaitForAccurateLocation(false)
                .setMaxUpdateDelayMillis(currentIntervalMs)
                // Esplicito: nessun filtro di distanza a livello di sistema, altrimenti
                // da fermi non arriverebbero fix e l'heartbeat non scatterebbe mai.
                .setMinUpdateDistanceMeters(0f)
                .build()

            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            Log.d(TAG, "Location updates attivi, intervallo ${currentIntervalMs}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permessi di localizzazione mancanti: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Errore avvio location updates: ${e.message}")
        }
    }

    private fun stopTracking() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Errore rimozione location updates: ${e.message}")
        }
    }

    private fun handleNewLocation(location: Location) {
        lastKnownLocation = location
        lastFixAtMillis = System.currentTimeMillis()

        val (batteryLevel, isCharging) = getBatteryStatus()

        val userLocation = UserLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speed = if (location.hasSpeed()) location.speed else 0.0f,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            timestamp = lastFixAtMillis,
            isOnline = true
        )

        serviceScope.launch { repository.updateLocation(userLocation) }
    }

    /**
     * Rete di sicurezza per l'heartbeat: se il sistema smette di consegnare fix
     * (doze, dispositivo appoggiato e immobile), ogni tanto ripresentiamo l'ultima
     * posizione nota. Il repository decide comunque se scriverla, ma così lo stato
     * online e il livello di batteria non restano indietro.
     */
    private fun forcePushLastKnownLocation() {
        val location = lastKnownLocation ?: return
        handleNewLocation(location)
    }

    /**
     * Tiene aggiornato il testo della notifica persistente e innesca l'heartbeat.
     * Un minuto è un compromesso: abbastanza reattivo da mostrare un orario
     * credibile, abbastanza raro da non pesare.
     */
    private fun startNotificationRefreshLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(60_000L)
                try {
                    val silentFor = System.currentTimeMillis() - lastFixAtMillis
                    if (lastKnownLocation != null && silentFor >= HEARTBEAT_SAFETY_MS) {
                        forcePushLastKnownLocation()
                    }
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    manager?.notify(NOTIFICATION_ID, buildForegroundNotification())
                } catch (t: Throwable) {
                    Log.w(TAG, "Refresh notifica fallito: ${t.message}")
                }
            }
        }
    }

    private fun getBatteryStatus(): Pair<Int, Boolean> {
        return try {
            val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 100

            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            Pair(pct, charging)
        } catch (e: Exception) {
            Pair(100, false)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Servizio di localizzazione",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifica persistente del radar di famiglia"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (lastFixAtMillis > 0L) {
            val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastFixAtMillis))
            "Ultima posizione alle $clock"
        } else {
            "In attesa del primo segnale GPS…"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Radar attivo")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radar_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTracking()
        serviceScope.cancel()
    }

    companion object {
        const val TAG = "LocationTrackingService"
        const val CHANNEL_ID = "family_radar_location_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.action.START_TRACKING"
        const val ACTION_STOP = "com.example.action.STOP_TRACKING"
        const val ACTION_UPDATE_INTERVAL = "com.example.action.UPDATE_INTERVAL"
        const val ACTION_FORCE_SYNC = "com.example.action.FORCE_SYNC"
        const val EXTRA_INTERVAL_SEC = "extra_interval_sec"

        /** Oltre questo silenzio dal GPS ripresentiamo l'ultima posizione nota. */
        private const val HEARTBEAT_SAFETY_MS = 4 * 60_000L

        fun start(context: Context, intervalSec: Int = 30) {
            try {
                val fine = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (!fine && !coarse) {
                    Log.w(TAG, "Servizio non avviato: permessi di localizzazione mancanti")
                    return
                }

                val intent = Intent(context, LocationTrackingService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_INTERVAL_SEC, intervalSec)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Avvio servizio fallito: ${t.message}")
            }
        }

        fun updateInterval(context: Context, intervalSec: Int) {
            sendAction(context, ACTION_UPDATE_INTERVAL) { putExtra(EXTRA_INTERVAL_SEC, intervalSec) }
        }

        fun forceSync(context: Context) = sendAction(context, ACTION_FORCE_SYNC)

        fun stop(context: Context) = sendAction(context, ACTION_STOP)

        private fun sendAction(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            try {
                context.startService(
                    Intent(context, LocationTrackingService::class.java).apply {
                        this.action = action
                        extras()
                    }
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Azione $action fallita: ${t.message}")
            }
        }
    }
}
