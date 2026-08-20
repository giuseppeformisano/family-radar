package com.example.model

import android.content.Context
import com.example.R

/** Come è nato il viaggio: premuto da chi guida, o rilevato dall'app. */
enum class TripSource {
    MANUAL,
    AUTO;

    fun label(ctx: Context): String = ctx.getString(when (this) {
        MANUAL -> R.string.trip_source_manual
        AUTO -> R.string.trip_source_auto
    })

    companion object {
        fun fromRaw(raw: String?): TripSource =
            entries.firstOrNull { it.name == raw } ?: MANUAL
    }
}

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
    val pointCount: Int = 0,
    val source: TripSource = TripSource.MANUAL,
    val maxSpeedMs: Float = 0f,
    val movingMs: Long = 0L,
    val startPlaceName: String? = null,
    val endPlaceName: String? = null,
    val isLive: Boolean = false,
    val isPrivate: Boolean = false,
    val activityKind: String = "",
    val points: List<TripPoint> = emptyList(),
    val liveTrack: List<TripPoint> = emptyList()
) {
    val averageSpeedMs: Float
        get() = if (movingMs > 0) (distanceMeters / (movingMs / 1000.0)).toFloat() else 0f

    val stoppedMs: Long
        get() = (durationMs - movingMs).coerceAtLeast(0L)

    fun activityLabel(ctx: Context): String? = when (activityKind) {
        ActivityKind.VEHICLE -> ctx.getString(R.string.trip_activity_vehicle)
        ActivityKind.BICYCLE -> ctx.getString(R.string.trip_activity_bicycle)
        ActivityKind.RUNNING -> ctx.getString(R.string.trip_activity_running)
        ActivityKind.WALKING -> ctx.getString(R.string.trip_activity_walking)
        else -> null
    }
}

data class ActiveTripState(
    val startTime: Long = System.currentTimeMillis(),
    val points: List<TripPoint> = emptyList(),
    val lastLat: Double = 0.0,
    val lastLon: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val source: TripSource = TripSource.MANUAL,
    val maxSpeedMs: Float = 0f,
    val movingMs: Long = 0L,
    val lastFixAt: Long = 0L,
    val liveTripId: String? = null,
    val lastLiveWriteAt: Long = 0L,
    val startPlaceName: String? = null,
    val activityKind: String = ""
)
