package com.example

import android.app.Application
import android.content.Context
import android.util.Log
import com.example.util.ErrorLogger
import com.example.util.UpdateCheckWorker
import com.google.firebase.FirebaseApp

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
            UpdateCheckWorker.schedule(this)
        } catch (t: Throwable) {
            Log.w("FamilyRadarApp", "UpdateCheckWorker schedule warning: ${t.message}")
        }

    }
}
