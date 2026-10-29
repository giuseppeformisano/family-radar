package com.example.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.MainActivity
import com.example.model.UserLocation
import com.example.util.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Widget unico sulla home: mostra un familiare scelto (luogo, stato, batteria).
 * È ridimensionabile: da 1x1 (quadrato) a 2x1 (orizzontale). Tutto si adatta alla
 * dimensione reale della cella, così entra bene su qualunque telefono.
 *
 * La carica è un anello attorno alla foto (non una barra che si taglia); avatar,
 * anello e pallino di stato sono disegnati come un'unica immagine dimensionata
 * sulla cella, così non c'è nulla che sfora i bordi.
 */
class MemberWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val userId = prefs[KEY_USER_ID]
            val member = userId?.let { WidgetCache.findMember(context, it) }
            val size = LocalSize.current
            if (size.width < 150.dp) SmallUi(member) else WidgetUi(member)
        }
    }

    companion object {
        /** Chiave dello stato per-widget: quale familiare mostra questa istanza. */
        val KEY_USER_ID = stringPreferencesKey("widget_user_id")

        /** L'app chiama questo quando cambiano le posizioni: salva la cache e ridisegna i widget. */
        fun publish(context: Context, locations: List<UserLocation>) {
            val changed = try {
                WidgetCache.save(context.applicationContext, locations)
            } catch (e: Exception) {
                false
            }
            if (!changed) return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    MemberWidget().updateAll(context.applicationContext)
                } catch (e: Exception) {
                    // ignora: widget non ancora posizionato o aggiornamento non critico
                }
            }
        }
    }
}

class MemberWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemberWidget()
}

// ---- Colori (chiaro / scuro automatico) ----
private val textPrimary = ColorProvider(day = Color(0xFF14181F), night = Color(0xFFF2F4F7))
private val textSecondary = ColorProvider(day = Color(0xFF3F4650), night = Color(0xFFC6CCD4))
private val panelBg = ColorProvider(day = Color(0xF2FFFFFF), night = Color(0xF21B2430))

private fun isNight(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

/** Stato presenza: colore del pallino + etichetta, in base a quanto è recente il fix. */
private data class Presence(
    val colorDay: Int,
    val colorNight: Int,
    val label: String,
    val textColor: androidx.glance.unit.ColorProvider
)

private fun presenceOf(member: WidgetMember): Presence {
    val elapsed = System.currentTimeMillis() - member.timestamp
    return when {
        elapsed < 12 * 60_000L -> Presence(0xFF16A34A.toInt(), 0xFF34D27B.toInt(), "Online",
            ColorProvider(Color(0xFF16A34A), Color(0xFF34D27B)))
        elapsed < 60 * 60_000L -> Presence(0xFFE08600.toInt(), 0xFFF2A738.toInt(), "Inattivo",
            ColorProvider(Color(0xFFE08600), Color(0xFFF2A738)))
        else -> Presence(0xFF8A9099.toInt(), 0xFF8A9099.toInt(), "Offline", textSecondary)
    }
}

/** Colore della carica: verde pieno, arancio sotto il 40%, rosso sotto il 20%. */
private fun batteryColor(battery: Int, night: Boolean): Int = when {
    battery <= 20 -> if (night) 0xFFF2675E.toInt() else 0xFFE2443B.toInt()
    battery <= 40 -> if (night) 0xFFF2A738.toInt() else 0xFFE08600.toInt()
    else -> if (night) 0xFF34D27B.toInt() else 0xFF16A34A.toInt()
}

private fun clickIntent(context: Context, member: WidgetMember?): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (member != null) {
            putExtra("destination", "MAP")
            putExtra("userId", member.userId)
        }
    }

@Composable
private fun WidgetUi(member: WidgetMember?) {
    val context = LocalContext.current
    val base = GlanceModifier
        .fillMaxSize()
        .clickable(actionStartActivity(clickIntent(context, member)))

    if (member == null) {
        EmptyPanel(base)
        return
    }

    val presence = presenceOf(member)
    val size = LocalSize.current
    // Tutto scala con l'altezza reale della cella (il 2x1 cambia da telefono a telefono).
    val h = size.height.value
    val avatarDp = (h * 0.62f).coerceIn(40f, 76f)
    val nameSp = (h * 0.17f).coerceIn(13f, 20f)
    val subSp = (h * 0.12f).coerceIn(11f, 15f)
    val bmp = avatarRingBitmap(context, member, presence, avatarDp)

    Row(
        modifier = base.background(panelBg).cornerRadius(22.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(provider = ImageProvider(bmp), contentDescription = null,
            modifier = GlanceModifier.size((avatarDp + 8f).dp))
        Spacer(GlanceModifier.width(11.dp))
        Column {
            Text(member.name,
                style = TextStyle(color = textPrimary, fontSize = nameSp.sp, fontWeight = FontWeight.Bold),
                maxLines = 1)
            Text(whereLabel(member),
                style = TextStyle(color = textSecondary, fontSize = subSp.sp),
                maxLines = 2)
            Spacer(GlanceModifier.height(3.dp))
            Text("${presence.label} · ${member.battery}%",
                style = TextStyle(color = presence.textColor, fontSize = subSp.sp, fontWeight = FontWeight.Medium),
                maxLines = 1)
        }
    }
}

@Composable
private fun SmallUi(member: WidgetMember?) {
    val context = LocalContext.current
    val base = GlanceModifier
        .fillMaxSize()
        .clickable(actionStartActivity(clickIntent(context, member)))

    if (member == null) {
        EmptyPanel(base)
        return
    }

    val presence = presenceOf(member)
    val size = LocalSize.current
    val minSide = minOf(size.width.value, size.height.value)
    // Avatar più contenuto nel quadrato, così restano righe sotto senza sforare.
    val avatarDp = (minSide * 0.46f).coerceIn(36f, 74f)
    val nameSp = (minSide * 0.12f).coerceIn(11f, 16f)
    val subSp = (minSide * 0.10f).coerceIn(10f, 14f)
    val shortPlace = member.placeName?.takeIf { it.isNotBlank() } ?: "In giro"
    val bmp = avatarRingBitmap(context, member, presence, avatarDp)

    Column(
        modifier = base.background(panelBg).cornerRadius(22.dp).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(provider = ImageProvider(bmp), contentDescription = null,
            modifier = GlanceModifier.size((avatarDp + 8f).dp))
        Spacer(GlanceModifier.height(3.dp))
        Text(member.name,
            style = TextStyle(color = textPrimary, fontSize = nameSp.sp, fontWeight = FontWeight.Bold),
            maxLines = 1)
        Text("$shortPlace · ${member.battery}%",
            style = TextStyle(color = textSecondary, fontSize = subSp.sp),
            maxLines = 1)
    }
}

@Composable
private fun EmptyPanel(base: GlanceModifier) {
    Box(
        modifier = base.background(panelBg).cornerRadius(22.dp).padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Scegli\nun familiare",
            style = TextStyle(color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium))
    }
}

private fun whereLabel(member: WidgetMember): String {
    val place = member.placeName
    if (!place.isNullOrBlank()) return place
    return String.format(java.util.Locale.US, "%.4f, %.4f", member.latitude, member.longitude)
}

/**
 * Disegna in un'unica immagine: anello batteria (traccia + arco proporzionale alla
 * carica), avatar al centro (foto ritagliata o iniziale), e il pallino di stato in
 * basso a destra. La dimensione in pixel segue [avatarDp] e la densità dello schermo,
 * così è nitido e proporzionato su qualunque dispositivo.
 */
private fun avatarRingBitmap(
    context: Context,
    member: WidgetMember,
    presence: Presence,
    avatarDp: Float
): Bitmap {
    val night = isNight(context)
    val density = context.resources.displayMetrics.density
    val outer = (avatarDp + 8f) * density
    val px = outer.toInt().coerceIn(56, 320)
    val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)

    val ringWidth = px * 0.075f
    val pad = ringWidth / 2f + px * 0.02f
    val ringRect = RectF(pad, pad, px - pad, px - pad)

    // Traccia dell'anello.
    val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = if (night) 0x33FFFFFF else 0x1A000000
    }
    canvas.drawArc(ringRect, 0f, 360f, false, track)

    // Arco proporzionale alla carica, partendo dall'alto.
    val frac = (member.battery.coerceIn(0, 100)) / 100f
    val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        strokeCap = Paint.Cap.ROUND
        color = batteryColor(member.battery, night)
    }
    if (frac > 0f) canvas.drawArc(ringRect, -90f, 360f * frac, false, arc)

    // Avatar interno.
    val gap = px * 0.03f
    val avX = pad + ringWidth / 2f + gap
    val avRect = RectF(avX, avX, px - avX, px - avX)
    val avSide = (avRect.width()).toInt().coerceAtLeast(1)
    val photo = member.photoBase64
        ?.let { ImageUtils.base64ToBitmap(it) }
        ?.let { circularBitmap(it, avSide) }
    if (photo != null) {
        canvas.drawBitmap(photo, null, avRect, Paint(Paint.ANTI_ALIAS_FLAG))
    } else {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (night) 0xFF5B8DEF.toInt() else 0xFF3B6FD4.toInt()
        }
        canvas.drawOval(avRect, bg)
        val initial = member.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AColor.WHITE
            textAlign = Paint.Align.CENTER
            textSize = avRect.height() * 0.46f
            isFakeBoldText = true
        }
        val cy = avRect.centerY() - (tp.descent() + tp.ascent()) / 2f
        canvas.drawText(initial, avRect.centerX(), cy, tp)
    }

    // Pallino di stato in basso a destra, con bordo del colore del pannello.
    val dotR = px * 0.11f
    val at = Math.toRadians(45.0)
    val cx = avRect.centerX() + (avRect.width() / 2f) * Math.cos(at).toFloat()
    val cyDot = avRect.centerY() + (avRect.height() / 2f) * Math.sin(at).toFloat()
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) 0xFF1B2430.toInt() else AColor.WHITE
    }
    canvas.drawCircle(cx, cyDot, dotR + px * 0.02f, border)
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) presence.colorNight else presence.colorDay
    }
    canvas.drawCircle(cx, cyDot, dotR, dot)

    return out
}

/**
 * Ritaglio circolare dell'avatar, ridimensionato a [targetPx] (una bitmap grande
 * verrebbe scartata dal widget per il tetto di memoria delle immagini).
 */
private fun circularBitmap(src: Bitmap, targetPx: Int): Bitmap {
    val side = minOf(src.width, src.height)
    val left = (src.width - side) / 2
    val top = (src.height - side) / 2
    val square = Rect(left, top, left + side, top + side)
    val out = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val dst = RectF(0f, 0f, targetPx.toFloat(), targetPx.toFloat())
    canvas.drawRoundRect(dst, targetPx / 2f, targetPx / 2f, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(src, square, dst, paint)
    return out
}
