package com.example.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
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

/** Widget sulla home: mostra un familiare scelto (luogo, stato, batteria). */
class MemberWidget : GlanceAppWidget() {

    // Dimensione fissa (1x2): un unico layout verticale.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val userId = prefs[KEY_USER_ID]
            val member = userId?.let { WidgetCache.findMember(context, it) }
            WidgetUi(member)
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
                    val app = context.applicationContext
                    MemberWidget().updateAll(app)
                    MemberWidgetSmall().updateAll(app)
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

/** Variante 1x1 compatta (avatar + stato + batteria). Stesso stato per-widget. */
class MemberWidgetSmall : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val userId = prefs[MemberWidget.KEY_USER_ID]
            val member = userId?.let { WidgetCache.findMember(context, it) }
            SmallUi(member)
        }
    }
}

class MemberWidgetSmallReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemberWidgetSmall()
}

// ---- Colori (chiaro / scuro automatico) ----
private val textPrimary = ColorProvider(day = Color(0xFF1A1C1E), night = Color(0xFFE6E6E6))
private val textSecondary = ColorProvider(day = Color(0xFF5A5F66), night = Color(0xFFABB1B8))
private val accent = ColorProvider(day = Color(0xFF3B6FD4), night = Color(0xFF5B8DEF))

@Composable
private fun WidgetUi(member: WidgetMember?) {
    val context = LocalContext.current
    // Al tap: apre l'app e centra/segue la persona sulla mappa (deep link MAP + userId).
    val clickIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (member != null) {
            putExtra("destination", "MAP")
            putExtra("userId", member.userId)
        }
    }
    // Sfondo trasparente come richiesto.
    val base = GlanceModifier
        .fillMaxSize()
        .clickable(actionStartActivity(clickIntent))

    if (member == null) {
        Box(modifier = base.padding(8.dp), contentAlignment = Alignment.Center) {
            Text(
                "Scegli\nun familiare",
                style = TextStyle(color = textSecondary, fontSize = 12.sp)
            )
        }
        return
    }

    val elapsed = System.currentTimeMillis() - member.timestamp
    val (dot, status) = when {
        elapsed < 12 * 60_000L -> "🟢" to "Online"
        elapsed < 60 * 60_000L -> "🟠" to "Inattivo"
        else -> "⚫" to "Offline"
    }

    // Layout fisso orizzontale (2x1): avatar a sinistra, info a destra.
    Row(
        modifier = base.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(member, 46.dp)
        Spacer(GlanceModifier.width(10.dp))
        Column {
            Text(
                member.name,
                style = TextStyle(color = textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
            Text(
                whereLabel(member),
                style = TextStyle(color = textSecondary, fontSize = 12.sp),
                maxLines = 1
            )
            Spacer(GlanceModifier.height(3.dp))
            Text(
                "$dot $status  ·  🔋 ${member.battery}%",
                style = TextStyle(color = textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SmallUi(member: WidgetMember?) {
    val context = LocalContext.current
    val clickIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        if (member != null) {
            putExtra("destination", "MAP")
            putExtra("userId", member.userId)
        }
    }
    val base = GlanceModifier
        .fillMaxSize()
        .clickable(actionStartActivity(clickIntent))

    if (member == null) {
        Box(modifier = base.padding(4.dp), contentAlignment = Alignment.Center) {
            Text("—", style = TextStyle(color = textSecondary, fontSize = 12.sp))
        }
        return
    }

    val elapsed = System.currentTimeMillis() - member.timestamp
    val dot = when {
        elapsed < 12 * 60_000L -> "🟢"
        elapsed < 60 * 60_000L -> "🟠"
        else -> "⚫"
    }

    val shortPlace = member.placeName?.takeIf { it.isNotBlank() } ?: "In giro"

    Column(
        modifier = base.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Avatar(member, 30.dp)
        Spacer(GlanceModifier.height(2.dp))
        Text(
            "📍 $shortPlace",
            style = TextStyle(color = textSecondary, fontSize = 9.sp),
            maxLines = 1
        )
        Text(
            "$dot ${member.battery}%",
            style = TextStyle(color = textPrimary, fontSize = 10.sp, fontWeight = FontWeight.Medium),
            maxLines = 1
        )
    }
}

@Composable
private fun Avatar(member: WidgetMember, sizeDp: Dp) {
    val context = LocalContext.current
    // Riduciamo l'immagine alla dimensione effettiva: i widget hanno un tetto di
    // memoria per le immagini e una foto a piena risoluzione verrebbe scartata.
    val targetPx = (sizeDp.value * context.resources.displayMetrics.density)
        .toInt().coerceIn(48, 240)
    val bmp = member.photoBase64
        ?.let { ImageUtils.base64ToBitmap(it) }
        ?.let { circularBitmap(it, targetPx) }
    if (bmp != null) {
        Image(
            provider = ImageProvider(bmp),
            contentDescription = null,
            modifier = GlanceModifier.size(sizeDp)
        )
    } else {
        Box(
            modifier = GlanceModifier.size(sizeDp).cornerRadius(sizeDp / 2).background(accent),
            contentAlignment = Alignment.Center
        ) {
            val initial = member.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            Text(
                initial,
                style = TextStyle(
                    color = ColorProvider(day = Color.White, night = Color.White),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

private fun whereLabel(member: WidgetMember): String {
    val place = member.placeName
    if (!place.isNullOrBlank()) return "📍 $place"
    // Fuori dai luoghi noti: coordinate brevi.
    return String.format(java.util.Locale.US, "📍 %.4f, %.4f", member.latitude, member.longitude)
}

/**
 * Ritaglio circolare dell'avatar, ridimensionato a [targetPx] (il widget non ha
 * clip composabile affidabile su tutte le versioni e ha un tetto di memoria per
 * le immagini: una bitmap grande verrebbe scartata e l'avatar resterebbe vuoto).
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
