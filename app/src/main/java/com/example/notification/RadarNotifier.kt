package com.example.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Punto unico per costruire e pubblicare le notifiche dell'app.
 *
 * Prima questa logica era duplicata fra [com.example.repository.FirebaseRepository]
 * (notifiche generate dai listener Firestore) e
 * [com.example.service.FamilyRadarMessagingService] (push FCM), con canali e ID
 * diversi. Il risultato era che le due strade non si vedevano fra loro: impossibile
 * raggrupparle o cancellarle insieme. Ora entrambe passano da qui.
 *
 * Tre canali, tutti IMPORTANCE_HIGH perché devono comparire come banner:
 *  - [CHANNEL_CHAT]   messaggi di gruppo, raggruppati per gruppo
 *  - [CHANNEL_PLACES] arrivi e partenze dai luoghi (geofence), con full-screen intent
 *  - [CHANNEL_SOS]    emergenze, suono da sveglia e vibrazione insistente
 */
object RadarNotifier {

    private const val TAG = "RadarNotifier"

    const val CHANNEL_CHAT = "radar_chat_v3"
    const val CHANNEL_PLACES = "radar_places_v3"
    const val CHANNEL_SOS = "radar_sos_v3"

    /** Quante righe mostrare nella notifica cumulativa espansa. */
    private const val INBOX_MAX_LINES = 6

    /**
     * Messaggi di chat ancora visibili in status bar, per gruppo.
     * Servono a ricostruire l'InboxStyle della notifica riassuntiva a ogni nuovo
     * arrivo e a sapere quali ID cancellare quando l'utente apre la chat.
     */
    private data class PendingChat(val ids: MutableList<Int>, val lines: MutableList<CharSequence>)

    private val pendingChats = ConcurrentHashMap<String, PendingChat>()

    // ------------------------------------------------------------------
    // CANALI
    // ------------------------------------------------------------------

    /** Crea i canali. Idempotente: ricrearli non altera le preferenze dell'utente. */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationAudio = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val alarmAudio = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val channels = listOf(
            NotificationChannel(CHANNEL_CHAT, "Messaggi del gruppo", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nuovi messaggi nella chat dei tuoi gruppi"
                enableLights(true)
                enableVibration(true)
                setSound(sound, notificationAudio)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
            NotificationChannel(CHANNEL_PLACES, "Arrivi e partenze", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avvisi quando un membro entra o esce da un luogo salvato"
                enableLights(true)
                enableVibration(true)
                setSound(sound, notificationAudio)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            },
            NotificationChannel(CHANNEL_SOS, "Allerte SOS", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Richieste di soccorso immediate dai membri del gruppo"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: sound, alarmAudio)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
        )
        channels.forEach { runCatching { manager.createNotificationChannel(it) } }
    }

    // ------------------------------------------------------------------
    // CHAT — notifiche cumulative
    // ------------------------------------------------------------------

    /**
     * Pubblica un messaggio di chat come figlio di una notifica riassuntiva per gruppo.
     * Più messaggi dello stesso gruppo si impilano sotto un'unica voce espandibile.
     */
    fun notifyChatMessage(
        context: Context,
        groupId: String,
        groupName: String?,
        senderName: String,
        body: String,
        timestamp: Long,
        senderId: String?
    ) {
        if (groupId.isBlank()) return
        ensureChannels(context)

        val childId = childNotificationId(groupId, senderId, timestamp)
        val groupKey = chatGroupKey(groupId)
        val state = pendingChats.getOrPut(groupId) { PendingChat(mutableListOf(), mutableListOf()) }
        synchronized(state) {
            state.ids.add(childId)
            state.lines.add("$senderName: $body")
            while (state.lines.size > INBOX_MAX_LINES) state.lines.removeAt(0)
        }
        val intent = contentIntent(
            context = context,
            destination = "CHAT",
            groupId = groupId,
            senderId = senderId,
            requestCode = childId
        )

        val child = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_radar_notification)
            .setContentTitle(senderName)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(timestamp)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(groupKey)
            // Solo il riepilogo suona e mostra il banner. Senza questa riga
            // alertano anche i figli: due avvisi per messaggio, che e' esattamente
            // l'effetto "non sono raggruppate".
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setContentIntent(intent)
            .build()

        // Il conteggio non puo' basarsi solo sulla mappa in memoria: una push FCM
        // puo' risvegliare un processo nuovo, azzerandola, mentre in status bar le
        // notifiche precedenti ci sono ancora. Si prende il massimo fra i due.
        val count = maxOf(state.ids.size, activeChildCount(context, groupKey) + 1)
        val summaryTitle = groupName?.takeIf { it.isNotBlank() } ?: "Chat del gruppo"

        // Niente `.apply { }` su NotificationCompat.Style: la classe espone un
        // metodo Java pubblico che si chiama anch'esso `apply`, e in Kotlin il
        // membro della classe ha la precedenza sull'extension function. Il blocco
        // verrebbe interpretato come SAM di quel metodo, con un receiver diverso.
        val inboxStyle = NotificationCompat.InboxStyle()
        inboxStyle.setBigContentTitle(summaryTitle)
        synchronized(state) { state.lines.forEach { line -> inboxStyle.addLine(line) } }
        if (count > INBOX_MAX_LINES) {
            inboxStyle.setSummaryText("+${count - INBOX_MAX_LINES} altri")
        }

        val summary = NotificationCompat.Builder(context, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_radar_notification)
            .setContentTitle(summaryTitle)
            .setContentText(
                if (count == 1) "1 nuovo messaggio" else "$count nuovi messaggi"
            )
            .setStyle(inboxStyle)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setContentIntent(
                contentIntent(context, "CHAT", groupId, null, summaryNotificationId(groupId))
            )
            .build()

        post(context, childId, child)
        post(context, summaryNotificationId(groupId), summary)
    }

    /**
     * Rimuove dalla status bar tutte le notifiche di chat del gruppo.
     * Da chiamare quando l'utente apre la scheda Chat: le ha appena lette.
     */
    fun clearChatNotifications(context: Context, groupId: String) {
        if (groupId.isBlank()) return
        val manager = NotificationManagerCompat.from(context)
        val state = pendingChats.remove(groupId)
        val groupKey = chatGroupKey(groupId)

        runCatching {
            state?.ids?.forEach { manager.cancel(it) }
            manager.cancel(summaryNotificationId(groupId))

            // Gli ID tracciati coprono solo le notifiche create da questo processo.
            // Quelle rimaste da un processo precedente si ritrovano solo chiedendo
            // al sistema cosa c'e' davvero in status bar.
            forEachActiveInGroup(context, groupKey, includeSummary = true) { id ->
                manager.cancel(id)
            }
        }.onFailure { Log.w(TAG, "clearChatNotifications: ${it.message}") }
    }

    /** Quanti messaggi di questo gruppo sono ancora visibili in status bar (esclusa la riepilogativa). */
    private fun activeChildCount(context: Context, groupKey: String): Int {
        var count = 0
        forEachActiveInGroup(context, groupKey, includeSummary = false) { count++ }
        return count
    }

    private inline fun forEachActiveInGroup(
        context: Context,
        groupKey: String,
        includeSummary: Boolean,
        action: (Int) -> Unit
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            manager.activeNotifications.forEach { sbn ->
                val notification = sbn.notification ?: return@forEach
                if (notification.group != groupKey) return@forEach
                val isSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
                if (isSummary && !includeSummary) return@forEach
                action(sbn.id)
            }
        }.onFailure { Log.w(TAG, "Lettura notifiche attive fallita: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // LUOGHI E SOS — banner immediati
    // ------------------------------------------------------------------

    /**
     * Avviso di ingresso/uscita da un luogo salvato.
     *
     * Usa `setFullScreenIntent(..., false)`: su Android è il modo supportato per
     * forzare l'heads-up anche quando il sistema declasserebbe la notifica.
     * Da Android 14 l'uso a schermo intero vero e proprio è riservato a sveglie e
     * chiamate, quindi qui degrada — correttamente — a banner.
     */
    fun notifyPlaceEvent(
        context: Context,
        groupId: String?,
        title: String,
        body: String,
        latitude: Double?,
        longitude: Double?,
        senderId: String?
    ) {
        ensureChannels(context)
        val id = (System.currentTimeMillis() % 100_000).toInt()
        val intent = contentIntent(context, "MAP", groupId, senderId, id, latitude, longitude)

        val notification = NotificationCompat.Builder(context, CHANNEL_PLACES)
            .setSmallIcon(R.drawable.ic_radar_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(intent)
            .setFullScreenIntent(intent, false)
            .build()

        post(context, id, notification)
    }

    /** Allerta SOS: massima priorità, canale con suono da sveglia. */
    fun notifySos(
        context: Context,
        groupId: String?,
        title: String,
        body: String,
        latitude: Double?,
        longitude: Double?,
        senderId: String?
    ) {
        ensureChannels(context)
        val id = SOS_NOTIFICATION_ID
        val intent = contentIntent(context, "ALERT", groupId, senderId, id, latitude, longitude)

        val notification = NotificationCompat.Builder(context, CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_radar_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(intent)
            .setFullScreenIntent(intent, true)
            .build()

        post(context, id, notification)
    }

    /** Notifica generica non raggruppata (richieste di adesione, batteria scarica…). */
    fun notifyGeneric(
        context: Context,
        destination: String,
        title: String,
        body: String,
        groupId: String?,
        senderId: String?
    ) {
        ensureChannels(context)
        val id = (System.currentTimeMillis() % 100_000).toInt()
        val intent = contentIntent(context, destination, groupId, senderId, id)

        val notification = NotificationCompat.Builder(context, CHANNEL_PLACES)
            .setSmallIcon(R.drawable.ic_radar_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(intent)
            .build()

        post(context, id, notification)
    }

    // ------------------------------------------------------------------
    // INTERNI
    // ------------------------------------------------------------------

    private fun post(context: Context, id: Int, notification: Notification) {
        runCatching {
            // POST_NOTIFICATIONS può essere negato su Android 13+: notify() lancia
            // SecurityException, che qui viene assorbita senza far cadere il chiamante
            // (spesso è un listener Firestore o un callback GPS).
            NotificationManagerCompat.from(context).notify(id, notification)
        }.onFailure { Log.w(TAG, "notify($id) fallita: ${it.message}") }
    }

    private fun contentIntent(
        context: Context,
        destination: String,
        groupId: String?,
        senderId: String?,
        requestCode: Int,
        latitude: Double? = null,
        longitude: Double? = null
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("destination", destination)
            if (!groupId.isNullOrBlank()) putExtra("groupId", groupId)
            if (!senderId.isNullOrBlank()) putExtra("senderId", senderId)
            if (latitude != null && !latitude.isNaN()) putExtra("latitude", latitude)
            if (longitude != null && !longitude.isNaN()) putExtra("longitude", longitude)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun chatGroupKey(groupId: String) = "com.example.CHAT_$groupId"

    private fun summaryNotificationId(groupId: String) = ("summary_$groupId").hashCode()

    private fun childNotificationId(groupId: String, senderId: String?, timestamp: Long) =
        ("$groupId|${senderId.orEmpty()}|$timestamp").hashCode()

    const val SOS_NOTIFICATION_ID = 9_001
}
