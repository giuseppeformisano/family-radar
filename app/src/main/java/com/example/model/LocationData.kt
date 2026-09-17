package com.example.model

data class UserLocation(
    val userId: String = "",
    val userName: String = "",
    val nickname: String? = null,
    val photoBase64: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0.0f,
    val speed: Float = 0.0f, // in m/s (or converted to km/h in UI)
    val bearing: Float = 0.0f, // gradi da nord, 0-360
    val altitude: Double = 0.0,
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val isOnline: Boolean = true,
    val currentPlaceName: String? = null,
    /**
     * Come si sta muovendo chi ha inviato la posizione, secondo il riconoscimento
     * di attivita' di Android: uno fra [ActivityKind]. Vuoto quando il permesso
     * manca o non e' ancora arrivata una transizione.
     */
    val activityType: String = "",
    /**
     * Ultimi ~90 secondi di punti raccolti da chi invia, allegati all'aggiornamento.
     * Servono a ricostruire la scia senza buchi quando la rete e' andata e venuta:
     * i punti che non erano stati spediti in tempo reale arrivano qui col primo
     * aggiornamento buono. Vuoto se non ci sono punti recenti.
     */
    val recentPoints: List<TrailPoint> = emptyList()
)

/** Un punto della scia recente: posizione + istante in cui e' stato raccolto. */
data class TrailPoint(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val t: Long = 0L
)

/**
 * Modi di spostarsi riconosciuti, come stringhe perche' e' cosi' che finiscono
 * nel documento Firestore. Ogni dispositivo riconosce solo il proprio stato: gli
 * altri membri lo leggono da `locations/{uid}`.
 */
object ActivityKind {
    const val VEHICLE = "VEHICLE"
    const val BICYCLE = "BICYCLE"
    const val WALKING = "WALKING"
    const val RUNNING = "RUNNING"
    const val STILL = "STILL"
}
