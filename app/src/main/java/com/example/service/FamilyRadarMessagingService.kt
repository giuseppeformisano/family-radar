package com.example.service

import android.content.Context
import android.util.Log
import com.example.R
import com.example.ui.theme.LanguagePreferences
import com.example.notification.RadarNotifier
import com.example.repository.FirebaseRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Riceve le push FCM e le trasforma in notifiche.
 *
 * Costruisce le notifiche tramite [RadarNotifier], lo stesso oggetto usato dai
 * listener Firestore nel repository. È la ragione per cui esiste quel file: le
 * due strade producevano notifiche con canali e ID diversi, quindi i messaggi di
 * chat non si raggruppavano fra loro e aprire la chat non riusciva a cancellarli.
 *
 * Contratto del payload, condiviso con `MainActivity.handleIntent`:
 *   type        chat_message | sos_alert | geofence_entry | geofence_exit |
 *               join_request | low_battery
 *   destination CHAT | ALERT | MAP | MEMBERS   (derivata da `type` se assente)
 */
class FamilyRadarMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Nuovo token FCM ricevuto")
        saveTokenLocallyAndSync(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data

        // Le push di gruppo tornano indietro anche a chi le ha generate: senza
        // questo filtro ti notificheresti i tuoi stessi messaggi.
        val senderId = data["userId"] ?: data["senderId"]
        val currentUserId = runCatching {
            FirebaseRepository.getInstance(applicationContext).currentUserState.value?.uid
        }.getOrNull()
        if (!senderId.isNullOrBlank() && !currentUserId.isNullOrBlank() && senderId == currentUserId) {
            Log.d(TAG, "Push ignorata: generata da questo stesso utente")
            return
        }

        // Le stringhe di fallback delle push devono seguire la lingua scelta in
        // Impostazioni, non quella del dispositivo: il payload FCM arriva senza
        // lingua e il testo lo componiamo noi qui.
        val res = LanguagePreferences.localizedContext(this)

        val type = data["type"].orEmpty()
        val groupId = data["groupId"]
        val userName = data["userName"] ?: data["senderName"]
            ?: res.getString(R.string.notif_member_fallback)
        val placeName = data["placeName"] ?: res.getString(R.string.notif_place_fallback)
        val latitude = data["latitude"]?.toDoubleOrNull()
        val longitude = data["longitude"]?.toDoubleOrNull()

        val title = remoteMessage.notification?.title ?: defaultTitle(res, type, data)
        val body = remoteMessage.notification?.body ?: defaultBody(res, type, data, userName, placeName)

        RadarNotifier.ensureChannels(this)

        when (type) {
            "chat_message" -> {
                if (groupId.isNullOrBlank()) {
                    // Senza groupId non si può impilare né cancellare: meglio una
                    // notifica semplice che perdere il messaggio.
                    RadarNotifier.notifyGeneric(this, "CHAT", title, body, null, senderId)
                } else {
                    RadarNotifier.notifyChatMessage(
                        context = this,
                        groupId = groupId,
                        groupName = data["groupName"],
                        senderName = data["senderName"] ?: userName,
                        body = body,
                        timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis(),
                        senderId = senderId
                    )
                }
            }

            "sos_alert" -> RadarNotifier.notifySos(
                context = this,
                groupId = groupId,
                title = title,
                body = body,
                latitude = latitude,
                longitude = longitude,
                senderId = senderId
            )

            "geofence_entry", "geofence_exit" -> RadarNotifier.notifyPlaceEvent(
                context = this,
                groupId = groupId,
                title = title,
                body = body,
                latitude = latitude,
                longitude = longitude,
                senderId = senderId
            )

            else -> RadarNotifier.notifyGeneric(
                context = this,
                destination = data["destination"] ?: destinationFor(type),
                title = title,
                body = body,
                groupId = groupId,
                senderId = senderId
            )
        }
    }

    private fun defaultTitle(
        res: Context,
        type: String,
        data: Map<String, String>
    ): String = when (type) {
        "geofence_entry" -> res.getString(R.string.notif_place_entry_title)
        "geofence_exit" -> res.getString(R.string.notif_place_exit_title)
        "sos_alert" -> res.getString(R.string.notif_sos_title)
        "low_battery" -> res.getString(R.string.notif_low_battery_title)
        "join_request" -> res.getString(R.string.notif_join_request_title)
        "chat_message" -> data["senderName"] ?: res.getString(R.string.notif_chat_default_title)
        else -> data["title"] ?: res.getString(R.string.notif_generic_title)
    }

    private fun defaultBody(
        res: Context,
        type: String,
        data: Map<String, String>,
        userName: String,
        placeName: String
    ): String = when (type) {
        "geofence_entry" -> res.getString(R.string.notif_place_entry_body, userName, placeName)
        "geofence_exit" -> res.getString(R.string.notif_place_exit_body, userName, placeName)
        "sos_alert" -> res.getString(R.string.notif_sos_body, userName)
        "low_battery" -> res.getString(
            R.string.notif_low_battery_body, data["batteryLevel"] ?: "?", userName
        )
        "join_request" -> res.getString(R.string.notif_join_request_body, userName)
        "chat_message" -> data["text"] ?: res.getString(R.string.notif_chat_default_body)
        else -> data["body"] ?: data["message"] ?: res.getString(R.string.notif_generic_body)
    }

    private fun destinationFor(type: String): String = when (type) {
        "chat_message" -> "CHAT"
        "sos_alert" -> "ALERT"
        "geofence_entry", "geofence_exit" -> "MAP"
        "join_request", "low_battery" -> "MEMBERS"
        else -> "MAP"
    }

    private fun saveTokenLocallyAndSync(token: String) {
        try {
            getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("fcm_token", token)
                .apply()

            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    FirebaseRepository.getInstance(applicationContext).updateFcmToken(token)
                }.onFailure {
                    Log.w(TAG, "Sync del token FCM fallita: ${it.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Salvataggio token FCM fallito: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FamilyRadarFCM"
    }
}
