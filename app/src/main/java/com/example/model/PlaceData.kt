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
    val createdAt: Long = System.currentTimeMillis()
)

data class GeofenceEvent(
    val id: String = "",
    val placeName: String = "",
    val userName: String = "",
    val isInside: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)
