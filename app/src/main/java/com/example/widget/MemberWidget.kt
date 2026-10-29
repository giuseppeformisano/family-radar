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
 * È ridimensionabile: da 1x1 (quadrato, compatto) a 2x1 (orizzontale). Il layout
 * si sceglie in base alla dimensione reale della cella, non ci sono due widget.
 */
class MemberWidget : GlanceAppWidget() {

    // Exact: Glance ricalcola il contenuto a ogni ridimensionamento e LocalSize
    // riflette la cella effettiva, così possiamo scegliere 1x1 vs 2x1.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val userId = prefs[KEY_USER_ID]
            val member = userId?.let { WidgetCache.findMember(context, it) }
            // Sotto una certa larghezza è un 1x1: layout verticale compatto.
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
// Testo ben contrastato e sfondo semi-opaco: senza il pannello le scritte
// risultavano troppo deboli sopra gli sfondi chiari della home.
private val textPrimary = ColorProvider(day = Color(0xFF14181F), night = Color(0xFFF2F4F7))
private val textSecondary = ColorProvider(day = Color(0xFF3F4650), night = Color(0xFFC6CCD4))
private val accent = ColorProvider(day = Color(0xFF3B6FD4), night = Color(0xFF5B8DEF))
private val panelBg = ColorProvider(day = Color(0xF2FFFFFF), night = Color(0xF21B2430))
private val trackBg = ColorProvider(day = Color(0x1A000000), night = Color(0x33FFFFFF))

/** Verde/arancio/grigio in base a quanto è recente l'ultima posizione. */
private data class Presence(val color: androidx.glance.unit.ColorProvider, val label: String)

private fun presenceOf(member: WidgetMember): Presence {
    val elapsed = System.currentTimeMillis() - member.timestamp
    return when {
        elapsed < 12 * 60_000L -> Presence(ColorProvider(Color(0xFF16A34A), Color(0xFF34D27B)), "Online")
        elapsed < 60 * 60_000L -> Presence(ColorProvider(Color(0xFFE08600), Color(0xFFF2A738)), "Inattivo")
        else -> Presence(ColorProvider(Color(0xFF8A9099), Color(0xFF8A9099)), "Offline")
    }
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

    Row(
        modifier = base
            .background(panelBg)
            .cornerRadius(22.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(member, 48.dp, presence.color)
        Spacer(GlanceModifier.width(12.dp))
        Column {
            Text(
                member.name,
                style = TextStyle(color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
            Text(
                whereLabel(member),
                style = TextStyle(color = textSecondary, fontSize = 12.sp),
                maxLines = 1
            )
            Spacer(GlanceModifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(presence.color)) {}
                Spacer(GlanceModifier.width(5.dp))
                Text(
                    presence.label,
                    style = TextStyle(color = presence.color, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
                Spacer(GlanceModifier.width(8.dp))
                BatteryBar(member.battery)
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    "${member.battery}%",
                    style = TextStyle(color = textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1
                )
            }
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
    val shortPlace = member.placeName?.takeIf { it.isNotBlank() } ?: "In giro"

    // Dimensiona il contenuto in proporzione alla cella reale: a misura fissa
    // resta mezzo vuoto quando la cella 1x1 del launcher è più grande.
    val size = LocalSize.current
    val minSide = minOf(size.width.value, size.height.value)
    val avatarDp = (minSide * 0.52f).coerceIn(40f, 92f).dp
    val placeSp = (minSide * 0.11f).coerceIn(11f, 15f).sp
    val batterySp = (minSide * 0.13f).coerceIn(12f, 18f).sp

    Column(
        modifier = base
            .background(panelBg)
            .cornerRadius(22.dp)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Avatar(member, avatarDp, presence.color)
        Spacer(GlanceModifier.height(4.dp))
        Text(
            member.name,
            style = TextStyle(color = textPrimary, fontSize = placeSp, fontWeight = FontWeight.Bold),
            maxLines = 1
        )
        Text(
            shortPlace,
            style = TextStyle(color = textSecondary, fontSize = placeSp),
            maxLines = 1
        )
        Spacer(GlanceModifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.size(8.dp).cornerRadius(4.dp).background(presence.color)) {}
            Spacer(GlanceModifier.width(5.dp))
            Text(
                "${member.battery}%",
                style = TextStyle(color = textPrimary, fontSize = batterySp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun EmptyPanel(base: GlanceModifier) {
    Box(
        modifier = base.background(panelBg).cornerRadius(22.dp).padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Scegli\nun familiare",
            style = TextStyle(color = textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        )
    }
}

/** Barra batteria orizzontale, larga ~44dp. Verde se carica, arancio se bassa. */
@Composable
private fun BatteryBar(battery: Int) {
    val fill = when {
        battery <= 20 -> ColorProvider(Color(0xFFE2443B), Color(0xFFF2675E))
        battery <= 40 -> ColorProvider(Color(0xFFE08600), Color(0xFFF2A738))
        else -> ColorProvider(Color(0xFF16A34A), Color(0xFF34D27B))
    }
    val totalDp = 44
    val fillDp = (totalDp * battery / 100).coerceIn(3, totalDp)
    Box(
        modifier = GlanceModifier.width(totalDp.dp).height(6.dp).cornerRadius(3.dp).background(trackBg)
    ) {
        Box(GlanceModifier.width(fillDp.dp).height(6.dp).cornerRadius(3.dp).background(fill)) {}
    }
}

@Composable
private fun Avatar(member: WidgetMember, sizeDp: Dp, ring: androidx.glance.unit.ColorProvider) {
    val context = LocalContext.current
    // Riduciamo l'immagine alla dimensione effettiva: i widget hanno un tetto di
    // memoria per le immagini e una foto a piena risoluzione verrebbe scartata.
    val targetPx = (sizeDp.value * context.resources.displayMetrics.density)
        .toInt().coerceIn(48, 240)
    val bmp = member.photoBase64
        ?.let { ImageUtils.base64ToBitmap(it) }
        ?.let { circularBitmap(it, targetPx) }
    // Anello di stato attorno all'avatar.
    Box(
        modifier = GlanceModifier.size(sizeDp + 6.dp).cornerRadius((sizeDp + 6.dp) / 2).background(ring),
        contentAlignment = Alignment.Center
    ) {
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
                        fontSize = (sizeDp.value * 0.4f).coerceIn(16f, 34f).sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

private fun whereLabel(member: WidgetMember): String {
    val place = member.placeName
    if (!place.isNullOrBlank()) return place
    // Fuori dai luoghi noti: coordinate brevi.
    return String.format(java.util.Locale.US, "%.4f, %.4f", member.latitude, member.longitude)
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
