package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.util.LruCache
import com.example.model.ActivityKind
import com.example.model.PlaceCategory
import com.example.model.PlaceSnapshot
import com.example.model.PlaceSnapshotCluster
import com.example.model.SavedPlace
import com.example.util.ImageUtils
import kotlin.math.min

private val markerDrawableCache = LruCache<String, Drawable>(120)

fun clusterSnapshots(snapshots: List<PlaceSnapshot>, thresholdMeters: Double = 30.0): List<PlaceSnapshotCluster> {
    val clusters = mutableListOf<MutableList<PlaceSnapshot>>()
    for (snap in snapshots) {
        if (snap.latitude == 0.0 && snap.longitude == 0.0) continue
        var placed = false
        for (cluster in clusters) {
            val rep = cluster.first()
            val dist = FloatArray(1)
            Location.distanceBetween(snap.latitude, snap.longitude, rep.latitude, rep.longitude, dist)
            if (dist[0] <= thresholdMeters) { cluster.add(snap); placed = true; break }
        }
        if (!placed) clusters.add(mutableListOf(snap))
    }
    return clusters.mapIndexed { index, list ->
        PlaceSnapshotCluster(
            id = "cluster_$index",
            centerLatitude = list.map { it.latitude }.average(),
            centerLongitude = list.map { it.longitude }.average(),
            snapshots = list.sortedByDescending { it.timestamp }
        )
    }
}

internal fun createMemberMarkerDrawable(
    ctx: Context,
    name: String,
    battery: Int,
    isSelf: Boolean,
    speedKmH: Int,
    photoBase64: String?,
    activityType: String,
    isFollowed: Boolean = false
): Drawable {
    val cacheKey = "member_${name}_${battery}_${isSelf}_${speedKmH}_${photoBase64?.hashCode() ?: 0}_${activityType}_$isFollowed"
    markerDrawableCache.get(cacheKey)?.let { return it }

    val density = ctx.resources.displayMetrics.density
    val size = (52 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val radius = (18 * density)

    canvas.drawCircle(center, center, radius + (5 * density), Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelf) AndroidColor.argb(70, 79, 70, 229) else AndroidColor.argb(70, 16, 185, 129)
        style = Paint.Style.FILL
    })

    if (isFollowed) {
        canvas.drawCircle(center, center, radius + (7 * density), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(99, 102, 241); style = Paint.Style.STROKE; strokeWidth = 3.5f * density
        })
        canvas.drawCircle(center, center, radius + (7 * density), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 1f * density
        })
    }

    canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelf) AndroidColor.rgb(79, 70, 229) else AndroidColor.rgb(16, 185, 129)
        style = Paint.Style.STROKE; strokeWidth = 3 * density
    })

    val userAvatarBitmap = ImageUtils.base64ToBitmap(photoBase64)
    if (userAvatarBitmap != null) {
        val squared = centerCropSquare(userAvatarBitmap)
        val side = (radius * 2).toInt().coerceAtLeast(1)
        val circleCropBmp = getCircularBitmap(Bitmap.createScaledBitmap(squared, side, side, true))
        canvas.drawBitmap(circleCropBmp, center - radius, center - radius, null)
    } else {
        canvas.drawCircle(center, center, radius - (1.5f * density), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSelf) AndroidColor.rgb(99, 102, 241) else AndroidColor.rgb(52, 211, 153)
            style = Paint.Style.FILL
        })
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE; textSize = 14 * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        canvas.drawText(
            name.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
            center, center - (textPaint.descent() + textPaint.ascent()) / 2, textPaint
        )
    }

    val badgeRadius = (7 * density)
    val badgeX = center + (12 * density)
    val badgeY = center + (12 * density)
    canvas.drawCircle(badgeX, badgeY, badgeRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when { battery > 50 -> AndroidColor.rgb(34, 197, 94); battery > 20 -> AndroidColor.rgb(234, 179, 8); else -> AndroidColor.rgb(239, 68, 68) }
        style = Paint.Style.FILL
    })
    canvas.drawCircle(badgeX, badgeY, badgeRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    })
    val batteryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE; textSize = 7 * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    canvas.drawText("${min(battery, 99)}", badgeX, badgeY - (batteryTextPaint.descent() + batteryTextPaint.ascent()) / 2, batteryTextPaint)

    activityGlyphFor(activityType)?.let { glyph ->
        val aRadius = (10 * density); val aX = center - (12 * density); val aY = center - (12 * density)
        canvas.drawCircle(aX, aY, aRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; style = Paint.Style.FILL })
        canvas.drawCircle(aX, aY, aRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(30, 41, 59); style = Paint.Style.STROKE; strokeWidth = 1.5f * density })
        val aTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * density; textAlign = Paint.Align.CENTER }
        canvas.drawText(glyph, aX, aY - (aTextPaint.descent() + aTextPaint.ascent()) / 2, aTextPaint)
    }

    val result = BitmapDrawable(ctx.resources, bitmap)
    markerDrawableCache.put(cacheKey, result)
    return result
}

private fun activityGlyphFor(activityType: String): String? = when (activityType) {
    ActivityKind.VEHICLE -> "🚗"
    ActivityKind.BICYCLE -> "🚲"
    ActivityKind.RUNNING -> "🏃"
    ActivityKind.WALKING -> "🚶"
    else -> null
}

internal fun createSnapshotMarkerDrawable(ctx: Context, cluster: PlaceSnapshotCluster): Drawable {
    val cacheKey = "snapshot_${cluster.id}_${cluster.count}_${cluster.latestSnapshot?.id}"
    markerDrawableCache.get(cacheKey)?.let { return it }

    val density = ctx.resources.displayMetrics.density
    val size = (52 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val radius = (18 * density)

    canvas.drawCircle(center, center, radius + (4 * density), Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(80, 234, 88, 12); style = Paint.Style.FILL
    })
    canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(234, 88, 12); style = Paint.Style.STROKE; strokeWidth = 3 * density
    })

    val thumbBmp = ImageUtils.base64ToBitmap(cluster.latestSnapshot?.photoBase64)
    if (thumbBmp != null) {
        canvas.drawBitmap(
            getCircularBitmap(Bitmap.createScaledBitmap(thumbBmp, (radius * 2).toInt(), (radius * 2).toInt(), true)),
            center - radius, center - radius, null
        )
    } else {
        canvas.drawCircle(center, center, radius - (1.5f * density), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(249, 115, 22); style = Paint.Style.FILL
        })
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; textSize = 14 * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        canvas.drawText("📷", center, center - (iconPaint.descent() + iconPaint.ascent()) / 2, iconPaint)
    }

    if (cluster.count > 1) {
        val bR = (8 * density); val bX = center + (12 * density); val bY = center - (12 * density)
        canvas.drawCircle(bX, bY, bR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(220, 38, 38); style = Paint.Style.FILL })
        canvas.drawCircle(bX, bY, bR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f * density })
        val cPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; textSize = 8 * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        canvas.drawText("${cluster.count}", bX, bY - (cPaint.descent() + cPaint.ascent()) / 2, cPaint)
    }

    val result = BitmapDrawable(ctx.resources, bitmap)
    markerDrawableCache.put(cacheKey, result)
    return result
}

internal fun createPlaceMarkerDrawable(ctx: Context, place: SavedPlace): Drawable {
    val cacheKey = "place_${place.id}_${place.category.name}"
    markerDrawableCache.get(cacheKey)?.let { return it }

    val density = ctx.resources.displayMetrics.density
    val width = (36 * density).toInt()
    val height = (44 * density).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val mainColor = when (place.category) {
        PlaceCategory.HOME -> AndroidColor.rgb(79, 70, 229)
        PlaceCategory.WORK -> AndroidColor.rgb(13, 148, 136)
        PlaceCategory.SCHOOL -> AndroidColor.rgb(217, 119, 6)
        PlaceCategory.GYM -> AndroidColor.rgb(220, 38, 38)
        PlaceCategory.OTHER -> AndroidColor.rgb(100, 116, 139)
    }

    val headRadius = (14 * density)
    val headCenterX = width / 2f
    val headCenterY = headRadius + (2 * density)

    canvas.drawCircle(headCenterX, headCenterY, headRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mainColor; style = Paint.Style.FILL })
    canvas.drawCircle(headCenterX, headCenterY, headRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * density })

    val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; textSize = 12 * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    val symbol = when (place.category) {
        PlaceCategory.HOME -> "🏠"; PlaceCategory.WORK -> "💼"; PlaceCategory.SCHOOL -> "🏫"
        PlaceCategory.GYM -> "🏋"; PlaceCategory.OTHER -> "📍"
    }
    canvas.drawText(symbol, headCenterX, headCenterY - (symbolPaint.descent() + symbolPaint.ascent()) / 2, symbolPaint)

    val result = BitmapDrawable(ctx.resources, bitmap)
    markerDrawableCache.put(cacheKey, result)
    return result
}

private fun centerCropSquare(source: Bitmap): Bitmap {
    val side = min(source.width, source.height)
    if (source.width == source.height) return source
    return try {
        Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    } catch (_: Throwable) { source }
}

private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
    val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawRoundRect(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), bitmap.width / 2f, bitmap.height / 2f, paint)
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    return output
}
