package com.example.model

enum class PlaceCategory(val label: String, val iconName: String) {
    HOME("Casa", "home"),
    WORK("Lavoro", "work"),
    SCHOOL("Scuola", "school"),
    GYM("Palestra", "fitness_center"),
    OTHER("Altro", "place")
}

data class SavedPlace(
    val id: String = "",
    val name: String = "",
    val category: PlaceCategory = PlaceCategory.HOME,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Double = 100.0, // e.g. 50m - 500m
    val createdBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Se false il luogo resta sulla mappa ma non genera più avvisi di arrivo e
     * partenza. Serve per i posti che si vogliono vedere senza esserne notificati
     * di continuo — il classico luogo dove si passa dieci volte al giorno.
     *
     * Default true anche in lettura: i documenti creati prima di questo campo non
     * lo hanno, e devono continuare a comportarsi come prima.
     */
    val geofenceEnabled: Boolean = true
)

data class GeofenceEvent(
    val id: String = "",
    val placeName: String = "",
    val userName: String = "",
    val isInside: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)
