package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.repository.FirebaseRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Service to handle incoming Firebase Cloud Messaging (FCM) push notifications
 * and token refreshes for the Family Radar application.
 */
class FamilyRadarMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Token received: $token")
        saveTokenLocallyAndSync(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Message received from: ${remoteMessage.from}")

        val data = remoteMessage.data

        // Filter out notifications triggered by the current user to exclude self
        val senderId = data["userId"] ?: data["senderId"]
        val currentUserId = FirebaseRepository.getInstance(applicationContext).currentUserState.value?.uid
        if (!senderId.isNullOrBlank() && !currentUserId.isNullOrBlank() && senderId == currentUserId) {
            Log.d(TAG, "Ignoring self-generated FCM push notification for user: $currentUserId")
            return
        }

        // 1. Extract notification title and body from payload or data
        var title = remoteMessage.notification?.title
        var body = remoteMessage.notification?.body
        if (data.isNotEmpty()) {
            Log.d(TAG, "FCM Message data payload: $data")
            if (title.isNullOrBlank()) {
                title = when (data["type"]) {
                    "geofence_entry" -> "Arrivo a destinazione"
                    "geofence_exit" -> "Partenza registrata"
                    "sos_alert" -> "Allerta SOS"
                    "low_battery" -> "Avviso batteria"
                    "chat_message" -> data["senderName"] ?: "Nuovo messaggio"
                    else -> data["title"] ?: data["senderName"] ?: "Radar Famiglia"
                }
            }
            if (body.isNullOrBlank()) {
                body = when (data["type"]) {
                    "geofence_entry" -> "${data["userName"] ?: "Un membro"} è arrivato presso ${data["placeName"] ?: "la destinazione"}"
                    "geofence_exit" -> "${data["userName"] ?: "Un membro"} si è allontanato da ${data["placeName"] ?: "la zona"}"
                    "sos_alert" -> "Richiesta di soccorso inviata da ${data["userName"] ?: "un membro del gruppo"}"
                    "low_battery" -> "Livello batteria basso per ${data["userName"] ?: "un membro"} (${data["batteryLevel"] ?: ""}%)"
                    "chat_message" -> data["text"] ?: "Nuovo messaggio ricevuto"
                    else -> data["body"] ?: data["message"] ?: "Nuovo aggiornamento sulla mappa"
                }
            }
        }

        if (title.isNullOrBlank()) {
            title = "Radar Famiglia"
        }
        if (body.isNullOrBlank()) {
            body = "Hai una nuova notifica dal tuo gruppo famiglia."
        }

        sendPushNotification(title, body, data)
    }

    private fun sendPushNotification(title: String, body: String, data: Map<String, String>) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val isSos = data["type"] == "sos_alert"
            val channelId = if (isSos) "family_radar_sos_headsup_v2" else "family_radar_headsup_channel_v2"
            
            createNotificationChannel(notificationManager, channelId, isSos)

            val destination = data["destination"] ?: when (data["type"]) {
                "chat_message" -> "CHAT"
                "sos_alert" -> "ALERT"
                "geofence_entry", "geofence_exit" -> "MAP"
                "join_request" -> "MEMBERS"
                "low_battery" -> "MEMBERS"
                else -> "MAP"
            }

            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("destination", destination)
                data.forEach { (k, v) -> putExtra(k, v) }
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                (System.currentTimeMillis() % 100000).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val notificationBuilder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setPriority(NotificationCompat.PRIORITY_MAX) // Forza il banner popup
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(if (isSos) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pendingIntent, false) // Forza visualizzazione a video
                .setContentIntent(pendingIntent)

            val notificationId = (System.currentTimeMillis() % 100000).toInt()
            notificationManager.notify(notificationId, notificationBuilder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying notification: ${e.message}", e)
        }
    }

    private fun createNotificationChannel(notificationManager: NotificationManager, channelId: String, isSos: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(if (isSos) android.media.AudioAttributes.USAGE_ALARM else android.media.AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val channel = NotificationChannel(
                channelId,
                if (isSos) "Allerte SOS Radar" else "Notifiche Radar Famiglia",
                NotificationManager.IMPORTANCE_HIGH // Necessario per l'heads-up
            ).apply {
                description = "Notifiche in tempo reale a schermo per geofence, messaggi e allerte"
                enableLights(true)
                enableVibration(true)
                setSound(defaultSoundUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun saveTokenLocallyAndSync(token: String) {
        try {
            val prefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("fcm_token", token).apply()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val repository = FirebaseRepository.getInstance(applicationContext)
                    repository.updateFcmToken(token)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync FCM token to repository: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving FCM token: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FamilyRadarFCM"
        const val CHANNEL_ID = "family_radar_push_channel"
    }
}
