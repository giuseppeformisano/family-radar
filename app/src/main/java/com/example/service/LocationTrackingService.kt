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
import kotlinx.coroutines.launch

class LocationTrackingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var repository: FirebaseRepository

    private var currentIntervalMs: Long = 30000L // default 30s

    override fun onCreate() {
        super.onCreate()
        try {
            repository = FirebaseRepository.getInstance(this)
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            createNotificationChannel()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location: Location? = result.lastLocation
                    if (location != null) {
                        handleNewLocation(location)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate error: ${t.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_START -> {
                val intervalSec = intent?.getIntExtra(EXTRA_INTERVAL_SEC, 30) ?: 30
                currentIntervalMs = (intervalSec * 1000).toLong()
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
                val intervalSec = intent?.getIntExtra(EXTRA_INTERVAL_SEC, 30) ?: 30
                currentIntervalMs = (intervalSec * 1000).toLong()
                requestLocationUpdates()
            }
        }

        return START_STICKY
    }

    private fun startForegroundTracking() {
        try {
            val notification = buildForegroundNotification("Servizio di localizzazione radar attivo")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            requestLocationUpdates()
        } catch (t: Throwable) {
            Log.e(TAG, "startForegroundTracking warning/error: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)

            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                currentIntervalMs
            ).apply {
                setMinUpdateIntervalMillis(currentIntervalMs / 2)
                setWaitForAccurateLocation(false)
                setMaxUpdateDelayMillis(currentIntervalMs)
            }.build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Location updates requested with interval: ${currentIntervalMs}ms")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permissions: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates: ${e.message}")
        }
    }

    private fun stopTracking() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates: ${e.message}")
        }
    }

    private fun handleNewLocation(location: Location) {
        val (batteryLevel, isCharging) = getBatteryStatus()

        val userLocation = UserLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speed = if (location.hasSpeed()) location.speed else 0.0f,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            timestamp = System.currentTimeMillis(),
            isOnline = true
        )

        serviceScope.launch {
            repository.updateLocation(userLocation)
        }
    }

    private fun getBatteryStatus(): Pair<Int, Boolean> {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = registerReceiver(null, filter)
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 100

            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            Pair(batteryPct, isCharging)
        } catch (e: Exception) {
            Pair(100, false)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Family Radar Servizio di Localizzazione",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifica statica del radar di famiglia in tempo reale"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Radar Attivo")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_radar_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSound(null)
            .setVibrate(null)
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
        const val EXTRA_INTERVAL_SEC = "extra_interval_sec"

        fun start(context: Context, intervalSec: Int = 30) {
            try {
                val hasFineLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarseLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasFineLoc && !hasCoarseLoc) {
                    Log.w(TAG, "Cannot start LocationTrackingService: location permissions not yet granted")
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
                Log.e(TAG, "Failed to start service: ${t.message}")
            }
        }

        fun updateInterval(context: Context, intervalSec: Int) {
            try {
                val intent = Intent(context, LocationTrackingService::class.java).apply {
                    action = ACTION_UPDATE_INTERVAL
                    putExtra(EXTRA_INTERVAL_SEC, intervalSec)
                }
                context.startService(intent)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to update interval: ${t.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, LocationTrackingService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to stop service: ${t.message}")
            }
        }
    }
}
