package com.example

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.util.ErrorLogger
import com.google.firebase.FirebaseApp
import org.osmdroid.config.Configuration
import java.io.File

class FamilyRadarApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            // Safe Firebase Initialization
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
        } catch (t: Throwable) {
            Log.w("FamilyRadarApp", "FirebaseApp init warning: ${t.message}")
        }

        try {
            ErrorLogger.install(this)
        } catch (t: Throwable) {
            Log.w("FamilyRadarApp", "ErrorLogger init warning: ${t.message}")
        }

        try {
            // I canali vanno creati prima che arrivi la prima notifica: una push
            // FCM puo' raggiungere l'app da spenta, e su un canale inesistente
            // Android scarta la notifica senza dire nulla.
            com.example.notification.RadarNotifier.ensureChannels(this)
        } catch (t: Throwable) {
            Log.w("FamilyRadarApp", "Notification channels warning: ${t.message}")
        }

        try {
            // Safe osmdroid configuration
            val osmConfig = Configuration.getInstance()
            val sharedPrefs = getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
            osmConfig.load(this, sharedPrefs)
            osmConfig.userAgentValue = packageName
            try {
                // Modern Android Q+ tile cache memory configuration
                osmConfig.cacheMapTileCount = 12.toShort()
                osmConfig.cacheMapTileOvershoot = 0.toShort()
                osmConfig.isMapViewHardwareAccelerated = true
            } catch (_: Throwable) {}

            val basePath = File(cacheDir, "osmdroid")
            if (!basePath.exists()) {
                basePath.mkdirs()
            }
            osmConfig.osmdroidBasePath = basePath
            val tileCache = File(basePath, "tiles")
            if (!tileCache.exists()) {
                tileCache.mkdirs()
            }
            osmConfig.osmdroidTileCache = tileCache
        } catch (t: Throwable) {
            Log.w("FamilyRadarApp", "osmdroid config warning: ${t.message}")
        }
    }
}
