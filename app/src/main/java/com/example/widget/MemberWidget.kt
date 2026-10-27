package com.example.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.unit.ColorProvider
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.MainActivity
import com.example.model.ActivityKind
import com.example.model.UserLocation
import com.example.util.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Widget sulla home: mostra un familiare scelto (luogo, stato, batteria). */
class MemberWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val userId = WidgetCache.getSelection(context, appWidgetId)
            val member = userId?.let { WidgetCache.findMember(context, it) }
            WidgetUi(member)
        }
    }

    companion object {
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
private val bgColor = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1B1C1E))
private val textPrimary = ColorProvider(day = Color(0xFF1A1C1E), night = Color(0xFFE6E6E6))
private val textSecondary = ColorProvider(day = Color(0xFF5A5F66), night = Color(0xFFABB1B8))
private val accent = ColorProvider(day = Color(0xFF3B6FD4), night = Color(0xFF5B8DEF))

@Composable
private fun WidgetUi(member: WidgetMember?) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bgColor)
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        if (member == null) {
            Box(
                modifier = GlanceModifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Apri Family Radar\ne scegli un familiare",
                    style = TextStyle(color = textSecondary, fontSize = 13.sp)
                )
            }
            return@Column
        }

        val now = System.currentTimeMillis()
        val elapsed = now - member.timestamp
        val (dot, status) = when {
            elapsed < 12 * 60_000L -> "🟢" to "Online"
            elapsed < 60 * 60_000L -> "🟠" to "Inattivo"
            else -> "⚫" to "Offline"
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(member)
            Spacer(GlanceModifier.width(10.dp))
            Column {
                Text(
                    member.name,
                    style = TextStyle(color = textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    whereLabel(member),
                    style = TextStyle(color = textSecondary, fontSize = 13.sp),
                    maxLines = 1
                )
            }
        }

        Spacer(GlanceModifier.height(12.dp))

        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$dot $status",
                style = TextStyle(color = textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                "🔋 ${member.battery}%",
                style = TextStyle(color = textSecondary, fontSize = 13.sp)
            )
        }

        Spacer(GlanceModifier.height(4.dp))
        Text(
            lastSeenLabel(elapsed),
            style = TextStyle(color = textSecondary, fontSize = 11.sp)
        )
    }
}

@Composable
private fun Avatar(member: WidgetMember) {
    val bmp = member.photoBase64
        ?.let { ImageUtils.base64ToBitmap(it) }
        ?.let { circularBitmap(it) }
    if (bmp != null) {
        Image(
            provider = ImageProvider(bmp),
            contentDescription = null,
            modifier = GlanceModifier.size(48.dp)
        )
    } else {
        Box(
            modifier = GlanceModifier.size(48.dp).cornerRadius(24.dp).background(accent),
            contentAlignment = Alignment.Center
        ) {
            val initial = member.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            Text(
                initial,
                style = TextStyle(
                    color = ColorProvider(day = Color.White, night = Color.White),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

private fun whereLabel(member: WidgetMember): String {
    val place = member.placeName
    return when {
        !place.isNullOrBlank() -> place
        member.activityType == ActivityKind.VEHICLE -> "In viaggio"
        member.activityType == ActivityKind.BICYCLE -> "In bici"
        member.activityType == ActivityKind.RUNNING -> "Di corsa"
        member.activityType == ActivityKind.WALKING -> "A piedi"
        member.speed * 3.6f > 2f -> "In movimento"
        else -> "Fuori dai luoghi noti"
    }
}

private fun lastSeenLabel(elapsed: Long): String {
    if (elapsed < 60_000L) return "Aggiornato ora"
    val min = elapsed / 60_000L
    if (min < 60) return "Aggiornato $min min fa"
    val h = min / 60
    if (h < 24) return "Aggiornato $h h fa"
    return "Aggiornato ${h / 24} g fa"
}

/** Ritaglio circolare dell'avatar (il widget non ha clip composabile affidabile su tutte le versioni). */
private fun circularBitmap(src: Bitmap): Bitmap {
    val size = minOf(src.width, src.height)
    val left = (src.width - size) / 2
    val top = (src.height - size) / 2
    val square = Rect(left, top, left + size, top + size)
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val dst = RectF(0f, 0f, size.toFloat(), size.toFloat())
    canvas.drawRoundRect(dst, size / 2f, size / 2f, paint)
    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(src, square, dst, paint)
    return out
}
