package com.example.model

import android.content.Context
import androidx.annotation.StringRes
import com.example.R

enum class PlaceCategory(@StringRes val labelRes: Int, val iconName: String) {
    HOME(R.string.place_cat_home, "home"),
    WORK(R.string.place_cat_work, "work"),
    SCHOOL(R.string.place_cat_school, "school"),
    GYM(R.string.place_cat_gym, "fitness_center"),
    OTHER(R.string.place_cat_other, "place");

    fun label(ctx: Context): String = ctx.getString(labelRes)
}

data class SavedPlace(
    val id: String = "",
    val name: String = "",
    val category: PlaceCategory = PlaceCategory.HOME,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Double = 100.0,
    val createdBy: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val geofenceEnabled: Boolean = true
)

data class GeofenceEvent(
    val id: String = "",
    val placeName: String = "",
    val userName: String = "",
    val isInside: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)
