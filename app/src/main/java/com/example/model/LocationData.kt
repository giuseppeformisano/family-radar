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
    val activityType: String = ""
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
