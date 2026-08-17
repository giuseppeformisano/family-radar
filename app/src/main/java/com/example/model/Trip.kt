package com.example.model

data class TripPoint(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = 0L
)

data class Trip(
    val id: String = "",
    val groupId: String = "",
    val userId: String = "",
    val userName: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val durationMs: Long = 0L,
    val distanceMeters: Double = 0.0,
    val points: List<TripPoint> = emptyList()
)

data class ActiveTripState(
    val startTime: Long = System.currentTimeMillis(),
    val points: List<TripPoint> = emptyList(),
    val lastLat: Double = 0.0,
    val lastLon: Double = 0.0,
    val distanceMeters: Double = 0.0
)
