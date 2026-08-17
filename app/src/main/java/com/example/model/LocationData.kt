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
    val altitude: Double = 0.0,
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val isOnline: Boolean = true,
    val currentPlaceName: String? = null
)
