package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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

// ─────────────────────────── GOCCIA DI VETRO (stile marker) ───────────────────────────
// Tutti i pallini (persone, istantanee, luoghi) condividono questa forma a goccia con
// sfumatura radiale, bordo bianco, riflesso in alto e ombra morbida colorata. La punta
// e' esattamente in basso al centro del bitmap: i livelli mappa la ancorano li' (iconAnchor
// "bottom"), cosi' indica il punto preciso.

private const val MARKER_STYLE = "v2glass"

// Misure comuni (in dp); il bitmap e' W=60 x H=56, testa centrata in alto, punta in fondo.
private const val PIN_BODY_R = 20f
private const val PIN_WHITE_R = 17f
private const val PIN_FACE_R = 15.5f
private const val PIN_CX = 30f
private const val PIN_CY = 26f
private const val PIN_TIP = 30f   // distanza testa→punta
private const val PIN_W = 60f
private const val PIN_H = 56f      // = PIN_CY + PIN_TIP, punta al bordo inferiore

private fun blend(c: Int, with: Int, t: Float): Int = AndroidColor.rgb(
    (AndroidColor.red(c) * (1 - t) + AndroidColor.red(with) * t).toInt(),
    (AndroidColor.green(c) * (1 - t) + AndroidColor.green(with) * t).toInt(),
    (AndroidColor.blue(c) * (1 - t) + AndroidColor.blue(with) * t).toInt()
)

/**
 * Disegna la goccia di vetro (corpo + ombra + bordo bianco + faccia + riflesso) e lascia
 * che [drawFace] riempia la faccia interna (foto/iniziale/emoji) tra la base e il riflesso.
 */
private fun drawGlassPin(canvas: Canvas, d: Float, baseColor: Int, drawFace: (Canvas, Float, Float, Float) -> Unit) {
    val cx = PIN_CX * d; val cy = PIN_CY * d
    val bodyR = PIN_BODY_R * d; val whiteR = PIN_WHITE_R * d; val faceR = PIN_FACE_R * d
    val light = blend(baseColor, AndroidColor.WHITE, 0.38f)
    val dark = blend(baseColor, AndroidColor.BLACK, 0.24f)

    // Silhouette = testa (cerchio) + coda (triangolo), unite in un'unica forma.
    val pin = Path().apply {
        val head = Path().apply { addCircle(cx, cy, bodyR, Path.Direction.CW) }
        val tail = Path().apply {
            moveTo(cx, cy + PIN_TIP * d)
            lineTo(cx - bodyR * 0.80f, cy + bodyR * 0.10f)
            lineTo(cx + bodyR * 0.80f, cy + bodyR * 0.10f)
            close()
        }
        op(head, tail, Path.Op.UNION)
    }
    canvas.drawPath(pin, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(cx, cy - bodyR * 0.3f, bodyR * 1.25f,
            intArrayOf(light, baseColor, dark), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        setShadowLayer(6f * d, 0f, 3f * d, AndroidColor.argb(120, AndroidColor.red(dark), AndroidColor.green(dark), AndroidColor.blue(dark)))
    })

    // Bordo bianco + faccia con sfumatura.
    canvas.drawCircle(cx, cy, whiteR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE })
    canvas.drawCircle(cx, cy, faceR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(cx, cy - faceR * 0.35f, faceR * 1.2f,
            intArrayOf(light, baseColor, dark), floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
    })

    // Contenuto (foto/iniziale/emoji) ritagliato nella faccia.
    canvas.save()
    canvas.clipPath(Path().apply { addCircle(cx, cy, faceR, Path.Direction.CW) })
    drawFace(canvas, cx, cy, faceR)
    // Riflesso di vetro in alto, sopra al contenuto.
    canvas.drawRect(cx - faceR, cy - faceR, cx + faceR, cy + faceR * 0.15f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(cx, cy - faceR, cx, cy + faceR * 0.1f,
            AndroidColor.argb(110, 255, 255, 255), AndroidColor.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
    })
    canvas.restore()
}

private fun newPinBitmap(d: Float): Pair<Bitmap, Canvas> {
    val bmp = Bitmap.createBitmap((PIN_W * d).toInt(), (PIN_H * d).toInt(), Bitmap.Config.ARGB_8888)
    return bmp to Canvas(bmp)
}

private fun drawCircleBadge(canvas: Canvas, cx: Float, cy: Float, r: Float, fill: Int, text: String, textSp: Float, d: Float) {
    canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
    canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.6f * d })
    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; textSize = textSp * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    canvas.drawText(text, cx, cy - (tp.descent() + tp.ascent()) / 2, tp)
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
    val cacheKey = "member_${MARKER_STYLE}_${name}_${battery}_${isSelf}_${speedKmH}_${photoBase64?.hashCode() ?: 0}_${activityType}_$isFollowed"
    markerDrawableCache.get(cacheKey)?.let { return it }

    val d = ctx.resources.displayMetrics.density
    val (bitmap, canvas) = newPinBitmap(d)
    // Stesso colore per tutti i membri, me compreso: la mia identita' si capisce
    // dall'avatar/iniziale, non da un colore diverso.
    val baseColor = AndroidColor.rgb(16, 185, 129)

    drawGlassPin(canvas, d, baseColor) { c, fcx, fcy, fr ->
        val avatar = ImageUtils.base64ToBitmapScaled(photoBase64, 192)
        if (avatar != null) {
            val side = (fr * 2).toInt().coerceAtLeast(1)
            val circ = getCircularBitmap(Bitmap.createScaledBitmap(centerCropSquare(avatar), side, side, true))
            c.drawBitmap(circ, fcx - fr, fcy - fr, null)
        } else {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; textSize = 15 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            c.drawText(name.firstOrNull()?.uppercaseChar()?.toString() ?: "U", fcx, fcy - (tp.descent() + tp.ascent()) / 2, tp)
        }
    }

    val cx = PIN_CX * d; val cy = PIN_CY * d
    // Anello "seguito" attorno alla testa.
    if (isFollowed) {
        canvas.drawCircle(cx, cy, (PIN_BODY_R + 1.5f) * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(99, 102, 241); style = Paint.Style.STROKE; strokeWidth = 3f * d
        })
    }
    // Badge batteria in basso a destra della testa.
    val battFill = when { battery > 50 -> AndroidColor.rgb(34, 197, 94); battery > 20 -> AndroidColor.rgb(234, 179, 8); else -> AndroidColor.rgb(239, 68, 68) }
    drawCircleBadge(canvas, cx + 13 * d, cy + 11 * d, 8.5f * d, battFill, "${min(battery, 99)}", 8f, d)

    // Pallino attivita' in alto a sinistra.
    activityGlyphFor(activityType)?.let { glyph ->
        val aX = cx - 13 * d; val aY = cy - 13 * d; val aR = 9.5f * d
        canvas.drawCircle(aX, aY, aR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE })
        canvas.drawCircle(aX, aY, aR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(30, 41, 59); style = Paint.Style.STROKE; strokeWidth = 1.4f * d })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * d; textAlign = Paint.Align.CENTER }
        canvas.drawText(glyph, aX, aY - (tp.descent() + tp.ascent()) / 2, tp)
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
    val latest = cluster.latestSnapshot
    // Miniatura inline (viaggia tra dispositivi); ripiego sul Base64 pieno delle vecchie.
    val thumbSource = latest?.thumbBase64?.takeIf { it.isNotBlank() } ?: latest?.photoBase64
    val cacheKey = "snapshot_${MARKER_STYLE}_${cluster.id}_${cluster.count}_${latest?.id}_${thumbSource?.hashCode() ?: 0}"
    markerDrawableCache.get(cacheKey)?.let { return it }

    val d = ctx.resources.displayMetrics.density
    val (bitmap, canvas) = newPinBitmap(d)

    drawGlassPin(canvas, d, AndroidColor.rgb(234, 88, 12)) { c, fcx, fcy, fr ->
        val thumb = ImageUtils.base64ToBitmapScaled(thumbSource, 192)
        if (thumb != null) {
            val side = (fr * 2).toInt().coerceAtLeast(1)
            val circ = getCircularBitmap(Bitmap.createScaledBitmap(centerCropSquare(thumb), side, side, true))
            c.drawBitmap(circ, fcx - fr, fcy - fr, null)
        } else {
            val ip = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; textAlign = Paint.Align.CENTER }
            c.drawText("📷", fcx, fcy - (ip.descent() + ip.ascent()) / 2, ip)
        }
    }

    if (cluster.count > 1) {
        val cx = PIN_CX * d; val cy = PIN_CY * d
        drawCircleBadge(canvas, cx + 13 * d, cy - 13 * d, 8.5f * d, AndroidColor.rgb(220, 38, 38), "${cluster.count}", 8f, d)
    }

    val result = BitmapDrawable(ctx.resources, bitmap)
    markerDrawableCache.put(cacheKey, result)
    return result
}

internal fun createPlaceMarkerDrawable(ctx: Context, place: SavedPlace): Drawable {
    val cacheKey = "place_${MARKER_STYLE}_${place.id}_${place.category.name}"
    markerDrawableCache.get(cacheKey)?.let { return it }

    val d = ctx.resources.displayMetrics.density
    val (bitmap, canvas) = newPinBitmap(d)

    val mainColor = when (place.category) {
        PlaceCategory.HOME -> AndroidColor.rgb(99, 102, 241)
        PlaceCategory.WORK -> AndroidColor.rgb(13, 148, 136)
        PlaceCategory.SCHOOL -> AndroidColor.rgb(217, 119, 6)
        PlaceCategory.GYM -> AndroidColor.rgb(220, 38, 38)
        PlaceCategory.OTHER -> AndroidColor.rgb(100, 116, 139)
    }
    val symbol = when (place.category) {
        PlaceCategory.HOME -> "🏠"; PlaceCategory.WORK -> "💼"; PlaceCategory.SCHOOL -> "🏫"
        PlaceCategory.GYM -> "🏋"; PlaceCategory.OTHER -> "📍"
    }

    drawGlassPin(canvas, d, mainColor) { c, fcx, fcy, fr ->
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; textAlign = Paint.Align.CENTER }
        c.drawText(symbol, fcx, fcy - (sp.descent() + sp.ascent()) / 2, sp)
    }

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
