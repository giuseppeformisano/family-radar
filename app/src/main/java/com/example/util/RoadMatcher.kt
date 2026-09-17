package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * "Adattatore" strada: prende una sequenza di punti GPS (una scia) e la aggancia
 * alle strade reali (map-matching), restituendo il percorso stradale piu' probabile.
 *
 * Regola fuori-strada: se il matching e' troppo incerto (parcheggi, zone pedonali,
 * dentro edifici, punti lontani da qualsiasi strada) restituisce null e il chiamante
 * tiene la scia grezza — non si forza nessuno sulla carreggiata.
 *
 * Usa il servizio pubblico OSRM. E' un buon punto di partenza per l'esperimento:
 * i punti escono verso un server esterno e c'e' un limite d'uso; per un uso serio
 * e privato si sposta su un server proprio con la stessa identica API.
 */
object RoadMatcher {

    private val client = OkHttpClient()

    // Raggio (m) entro cui un punto puo' essere agganciato a una strada. Oltre, il
    // punto e' considerato fuori strada e OSRM lo scarta dal matching.
    private const val MATCH_RADIUS_M = 25

    // Sotto questa confidenza il risultato non e' affidabile: si tiene il grezzo.
    private const val MIN_CONFIDENCE = 0.30

    // OSRM match accetta un numero limitato di punti per richiesta.
    private const val MAX_POINTS = 100

    /**
     * Ritorna la scia agganciata alle strade come lista di (lat, lon), oppure null
     * se il matching non e' affidabile o fallisce (in tal caso: si usa il grezzo).
     */
    suspend fun matchToRoads(points: List<Pair<Double, Double>>): List<Pair<Double, Double>>? =
        withContext(Dispatchers.IO) {
            if (points.size < 2) return@withContext null
            val pts = if (points.size > MAX_POINTS) points.takeLast(MAX_POINTS) else points
            try {
                val coords = pts.joinToString(";") { "${it.second},${it.first}" } // lon,lat
                val radiuses = pts.joinToString(";") { MATCH_RADIUS_M.toString() }
                val url = "https://router.project-osrm.org/match/v1/driving/$coords" +
                    "?geometries=geojson&overview=full&tidy=true&radiuses=$radiuses"
                val req = Request.Builder().url(url).header("User-Agent", "FamilyRadar/1.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    if (json.optString("code") != "Ok") return@withContext null
                    val matchings = json.optJSONArray("matchings") ?: return@withContext null
                    if (matchings.length() == 0) return@withContext null
                    val first = matchings.getJSONObject(0)
                    if (first.optDouble("confidence", 0.0) < MIN_CONFIDENCE) return@withContext null
                    val coordsArr = first.getJSONObject("geometry").getJSONArray("coordinates")
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
