package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject


/**
 * Utility di routing su strada via OSRM pubblico.
 * Il map-matching (snap pallino + scia) e' ora locale in MapLibreMapView.snapToRoad(),
 * che interroga i tile vettoriali gia' caricati senza inviare dati a server esterni.
 * Qui resta solo `route()` per calcoli di percorso punto-a-punto (es. simulazione).
 */
object RoadMatcher {

    private val client = OkHttpClient()

    /**
     * Calcola un percorso su strada da un punto a un altro (per la simulazione tragitto).
     * Ritorna la lista di (lat, lon) lungo le strade reali, o null se fallisce.
     */
    suspend fun route(
        fromLat: Double, fromLon: Double, toLat: Double, toLon: Double
    ): List<Pair<Double, Double>>? = withContext(Dispatchers.IO) {
        try {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                "$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson"
            val req = Request.Builder().url(url).header("User-Agent", "FamilyRadar/1.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (json.optString("code") != "Ok") return@withContext null
                val routes = json.optJSONArray("routes") ?: return@withContext null
                if (routes.length() == 0) return@withContext null
                val coordsArr = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
                val out = ArrayList<Pair<Double, Double>>(coordsArr.length())
                for (i in 0 until coordsArr.length()) {
                    val c = coordsArr.getJSONArray(i)
                    out.add(c.getDouble(1) to c.getDouble(0)) // lat, lon
                }
                if (out.size >= 2) out else null
            }
        } catch (_: Exception) {
            null
        }
    }

}
