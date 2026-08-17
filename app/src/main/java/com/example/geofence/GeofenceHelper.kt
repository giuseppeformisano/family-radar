package com.example.geofence

import android.location.Location
import com.example.model.SavedPlace
import com.example.model.UserLocation
import kotlin.math.roundToInt

object GeofenceHelper {

    /**
     * Calculates the distance between two coordinates in meters
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    /**
     * Checks if a given coordinate is within a place radius
     */
    fun isInsidePlace(location: UserLocation, place: SavedPlace): Boolean {
        if (location.latitude == 0.0 && location.longitude == 0.0) return false
        val distance = calculateDistanceMeters(
            location.latitude,
            location.longitude,
            place.latitude,
            place.longitude
        )
        return distance <= place.radiusMeters
    }

    /**
     * Finds the nearest place if within radius, otherwise returns null
     */
    fun findCurrentPlace(location: UserLocation, places: List<SavedPlace>): SavedPlace? {
        return places.firstOrNull { isInsidePlace(location, it) }
    }

    /**
     * Formats distance nicely (e.g. "150 m" or "3.4 km")
     */
    fun formatDistance(meters: Float): String {
        return if (meters < 1000) {
            "${meters.roundToInt()} m"
        } else {
            String.format("%.1f km", meters / 1000f)
        }
    }
}
