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
import com.example.ui.theme.LanguagePreferences
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
 *
 * Sulla batteria agisce invece cambiando la *precisione* richiesta: alta quando ci
 * si muove, bilanciata dopo qualche minuto di immobilità (vedi [adjustPriorityFor]).
 * È lì che sta il risparmio vero, non nel numero di fix.
 *
 * Se l'utente attiva il risparmio energia, la precisione resta bilanciata anche in
 * movimento: la posizione arriva da WiFi e celle invece che dal GPS, quindi il
 * tracciamento non si interrompe mai — cambia solo la sorgente e il raggio di errore.
 */
class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var repository: FirebaseRepository

    private var currentIntervalMs: Long = FirebaseRepository.DEFAULT_TRACKING_INTERVAL_SEC * 1000L
    private var lastFixAtMillis: Long = 0L
    private var lastKnownLocation: Location? = null

    // Precisione adattiva: il GPS ad alta precisione e' la voce piu' pesante sulla
    // batteria e da fermi non serve a nulla. Si scala a BALANCED dopo qualche
    // minuto di immobilita' e si risale appena il dispositivo riparte.
    private var currentPriority: Int = Priority.PRIORITY_HIGH_ACCURACY
    private var stationarySinceMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        try {
            repository = FirebaseRepository.getInstance(this)
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            // Il default del campo e' alta precisione: se il servizio parte con
            // il risparmio energia gia' attivo, senza questa riga accenderebbe
            // il GPS fino al primo fix, cioe' proprio cio' che si vuole evitare.
            currentPriority = repository.locationPriority()

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

            ACTION_UPDATE_POWER_MODE -> {
                // La precisione viene riletta dal repository dentro
                // adjustPriorityFor; qui basta riemettere subito la richiesta
                // per non restare sulla vecchia fino al prossimo fix.
                currentPriority = repository.locationPriority()
                stationarySinceMillis = 0L
                requestLocationUpdates()
            }

            ACTION_FORCE_SYNC -> forcePushLastKnownLocation()
        }

        return START_STICKY
    }

    private fun intervalMsFrom(intent: Intent?): Long {
        val ms = intent?.getLongExtra(EXTRA_INTERVAL_MS, -1L) ?: -1L
        if (ms > 0) return ms.coerceIn(200L, 86_400_000L)
        val seconds = intent?.getIntExtra(EXTRA_INTERVAL_SEC, FirebaseRepository.DEFAULT_TRACKING_INTERVAL_SEC)
            ?: FirebaseRepository.DEFAULT_TRACKING_INTERVAL_SEC
        return (seconds.coerceIn(1, 86_400) * 1000).toLong()
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

            val request = LocationRequest.Builder(currentPriority, currentIntervalMs)
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

        adjustPriorityFor(location)

        val (batteryLevel, isCharging) = getBatteryStatus()

        val userLocation = UserLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speed = if (location.hasSpeed()) location.speed else 0.0f,
            bearing = if (location.hasBearing()) location.bearing else 0.0f,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            timestamp = lastFixAtMillis,
            isOnline = true
        )

        serviceScope.launch { repository.updateLocation(userLocation) }
    }

    /**
     * Sceglie la precisione in base al movimento e riavvia la richiesta solo se
     * cambia davvero: `requestLocationUpdates` stacca e riattacca il listener,
     * farlo a ogni fix costerebbe piu' di quanto si risparmia.
     */
    private fun adjustPriorityFor(location: Location) {
        // In risparmio energia la precisione resta bilanciata sempre, anche in
        // movimento: e' il punto stesso della modalita'. La posizione continua
        // ad arrivare da WiFi e celle, quindi il tracciamento non si interrompe.
        if (repository.isPowerSavingMode.value) {
            if (currentPriority != Priority.PRIORITY_BALANCED_POWER_ACCURACY) {
                currentPriority = Priority.PRIORITY_BALANCED_POWER_ACCURACY
                Log.d(TAG, "Precisione: bilanciata (risparmio energia)")
                requestLocationUpdates()
            }
            return
        }

        val speed = if (location.hasSpeed()) location.speed else 0f
        val now = System.currentTimeMillis()

        val target = if (speed > FirebaseRepository.MOVING_SPEED_THRESHOLD_MS) {
            stationarySinceMillis = 0L
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            if (stationarySinceMillis == 0L) stationarySinceMillis = now
            if (now - stationarySinceMillis >= STATIONARY_GRACE_MS) {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            } else {
                currentPriority
            }
        }

        if (target != currentPriority) {
            currentPriority = target
            Log.d(
                TAG,
                "Precisione GPS: " +
                    if (target == Priority.PRIORITY_HIGH_ACCURACY) "alta (in movimento)"
                    else "bilanciata (fermo)"
            )
            requestLocationUpdates()
        }
    }

    /**
     * Rete di sicurezza per l'heartbeat: se il sistema smette di consegnare fix
     * (doze, dispositivo appoggiato e immobile), ogni tanto ripresentiamo l'ultima
     * posizione nota. Il repository decide comunque se scriverla, ma così lo stato
     * online e il livello di batteria non restano indietro.
     */
    private fun forcePushLastKnownLocation() {
        val location = lastKnownLocation
        if (location != null) {
            handleNewLocation(location)
        } else {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) handleNewLocation(loc)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "forcePushLastKnownLocation fallback failed: ${t.message}")
            }
        }
    }

    /**
     * Tiene aggiornato il testo della notifica persistente e innesca l'heartbeat.
     * Un minuto è un compromesso: abbastanza reattivo da mostrare un orario
     * credibile, abbastanza raro da non pesare.
     */
    private fun startNotificationRefreshLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_REFRESH_MS)
                try {
                    val silentFor = System.currentTimeMillis() - lastFixAtMillis
                    if (silentFor >= HEARTBEAT_SAFETY_MS) {
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

    /** La notifica persistente resta visibile per ore: deve stare nella lingua dell'app. */
    private fun localizedResources(): Context = LanguagePreferences.localizedContext(this)

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
            localizedResources().getString(R.string.channel_location_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = localizedResources().getString(R.string.channel_location_desc)
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

        val res = localizedResources()
        val text = res.getString(R.string.service_tracking_active)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(res.getString(R.string.service_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radar_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
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
        const val ACTION_UPDATE_POWER_MODE = "com.example.action.UPDATE_POWER_MODE"
        const val ACTION_FORCE_SYNC = "com.example.action.FORCE_SYNC"
        const val EXTRA_INTERVAL_SEC = "extra_interval_sec"
        const val EXTRA_INTERVAL_MS = "extra_interval_ms"

        /** Oltre questo silenzio dal GPS ripresentiamo l'ultima posizione nota. */
        private const val HEARTBEAT_SAFETY_MS = 4 * 60_000L

        /** Quanto restare fermi prima di scalare la precisione del GPS. */
        private const val STATIONARY_GRACE_MS = 3 * 60_000L

        /** Cadenza di aggiornamento del testo della notifica persistente. */
        private const val NOTIFICATION_REFRESH_MS = 5 * 60_000L

        fun start(context: Context, intervalSec: Int = FirebaseRepository.DEFAULT_TRACKING_INTERVAL_SEC) {
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

        fun updateIntervalMs(context: Context, intervalMs: Long) {
            sendAction(context, ACTION_UPDATE_INTERVAL) { putExtra(EXTRA_INTERVAL_MS, intervalMs) }
        }

        fun updatePowerMode(context: Context) = sendAction(context, ACTION_UPDATE_POWER_MODE)

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
