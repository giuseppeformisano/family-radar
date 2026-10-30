package com.example.widget

import android.content.Context
import com.example.model.UserLocation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Istantanea leggera di un familiare, pensata per il widget sulla home.
 * Niente collegamenti a Firebase dal widget: l'app salva qui l'ultimo stato
 * conosciuto e il widget legge solo da queste SharedPreferences locali.
 */
data class WidgetMember(
    val userId: String,
    val name: String,
    val placeName: String?,
    val battery: Int,
    val timestamp: Long,
    val activityType: String,
    val speed: Float,
    val latitude: Double,
    val longitude: Double,
    val photoBase64: String?
)

object WidgetCache {

    private const val PREFS_NAME = "family_radar_widget_prefs"
    private const val KEY_MEMBERS = "members_json"
    private const val KEY_SIG = "members_sig"
    private const val KEY_SEL_PREFIX = "sel_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readMembers(context: Context): List<WidgetMember> = try {
        val raw = prefs(context).getString(KEY_MEMBERS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            WidgetMember(
                userId = o.optString("u"),
                name = o.optString("n"),
                placeName = o.optString("p").takeIf { it.isNotEmpty() },
                battery = o.optInt("b", 100),
                timestamp = o.optLong("t", 0L),
                activityType = o.optString("a", ""),
                speed = o.optDouble("s", 0.0).toFloat(),
                latitude = o.optDouble("la", 0.0),
                longitude = o.optDouble("lo", 0.0),
                photoBase64 = o.optString("img").takeIf { it.isNotEmpty() }
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun findMember(context: Context, userId: String): WidgetMember? =
        readMembers(context).firstOrNull { it.userId == userId }

    /**
     * Salva l'istantanea dei membri. Scrive solo se qualcosa di rilevante e' cambiato
     * (luogo, batteria, attivita', avatar, o quando scatta una nuova finestra di ~5 min
     * per tenere fresco lo stato online/offline). Ritorna true se ha aggiornato.
     */
    fun save(context: Context, locations: List<UserLocation>): Boolean {
        val sig = locations.joinToString("|") {
            val bucket = it.timestamp / 300_000L // ~5 minuti
            // Includiamo anche la posizione (arrotondata a ~11 m): durante un
            // movimento fuori dai luoghi noti il widget mostra le coordinate, e
            // senza questo non si aggiornava perché luogo/batteria restavano uguali.
            val la = String.format(java.util.Locale.US, "%.4f", it.latitude)
            val lo = String.format(java.util.Locale.US, "%.4f", it.longitude)
            "${it.userId}:${it.currentPlaceName}:${it.batteryLevel}:${it.activityType}:" +
                "${it.photoBase64?.length ?: 0}:$la,$lo:$bucket"
        }
        val p = prefs(context)
        if (p.getString(KEY_SIG, null) == sig && p.contains(KEY_MEMBERS)) return false
        val arr = JSONArray()
        locations.forEach { loc ->
            val o = JSONObject()
            o.put("u", loc.userId)
            o.put("n", loc.nickname?.takeIf { it.isNotBlank() } ?: loc.userName)
            o.put("p", loc.currentPlaceName ?: "")
            o.put("b", loc.batteryLevel)
            o.put("t", loc.timestamp)
            o.put("a", loc.activityType)
            o.put("s", loc.speed.toDouble())
            o.put("la", loc.latitude)
            o.put("lo", loc.longitude)
            o.put("img", loc.photoBase64 ?: "")
            arr.put(o)
        }
        p.edit().putString(KEY_MEMBERS, arr.toString()).putString(KEY_SIG, sig).apply()
        return true
    }

    // --- Scelta del familiare per singolo widget (uno per widget) ---

    fun setSelection(context: Context, appWidgetId: Int, userId: String) {
        prefs(context).edit().putString(KEY_SEL_PREFIX + appWidgetId, userId).apply()
    }

    fun getSelection(context: Context, appWidgetId: Int): String? =
        prefs(context).getString(KEY_SEL_PREFIX + appWidgetId, null)

    fun clearSelection(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(KEY_SEL_PREFIX + appWidgetId).apply()
    }
}
