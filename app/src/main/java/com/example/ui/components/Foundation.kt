package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.BadgeTextStyle
import com.example.ui.theme.Elevation
import com.example.ui.theme.MetricTextStyle
import com.example.ui.theme.Radius
import com.example.ui.theme.RadarDark
import com.example.ui.theme.RadarSemantic
import com.example.ui.theme.RadarTheme
import com.example.ui.theme.Sizes
import com.example.ui.theme.Spacing
import com.example.util.ImageUtils
import kotlinx.coroutines.delay

// ============================================================================
// SUPERFICI
// ============================================================================

/**
 * Superficie "vetro" da sovrapporre alla mappa.
 *
 * Non è un blur reale: la mappa è una AndroidView osmdroid, disegnata dal view system
 * Android e non dal graphics layer di Compose, quindi le librerie di backdrop-blur
 * (haze & co.) non riuscirebbero a catturarla. Usiamo quindi una tinta traslucida
 * ad alta opacità più un bordo luminoso — lo stesso approccio di Google Maps —
 * che rende su tutte le API da 24 in su e non costa nulla in GPU.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.lg),
    contentPadding: Dp = Spacing.md,
    content: @Composable () -> Unit
) {
    val palette = RadarTheme.palette
    Surface(
        modifier = modifier,
        shape = shape,
        color = palette.gradients.glassTint,
        tonalElevation = Elevation.none,
        shadowElevation = Elevation.floating,
        border = BorderStroke(1.dp, palette.gradients.glassBorder)
    ) {
        Box(modifier = Modifier.padding(contentPadding)) { content() }
    }
}

// ============================================================================
// TESTATE E SEZIONI
// ============================================================================

/** Intestazione di sezione: titolo, sottotitolo facoltativo e slot azione a destra. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Sizes.iconMd)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        action?.invoke()
    }
}

// ============================================================================
// AVATAR E PRESENZA
// ============================================================================

/**
 * Avatar circolare con fallback a iniziale.
 * [photoBase64] è la stringa Base64 salvata su Firestore (niente Storage, vedi CLAUDE.md).
 */
@Composable
fun RadarAvatar(
    name: String,
    modifier: Modifier = Modifier,
    photoBase64: String? = null,
    size: Dp = Sizes.avatarMd,
    ringColor: Color? = null,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    val bitmap = remember(photoBase64) { ImageUtils.base64ToBitmap(photoBase64) }

    Box(
        modifier = modifier
            .size(size)
            .then(
                if (ringColor != null) Modifier.border(2.dp, ringColor, CircleShape)
                else Modifier
            )
            .padding(if (ringColor != null) 2.dp else 0.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = contentColor
            )
        }
    }
}

/**
 * Soglia del verde. Deve restare COMODAMENTE piu' larga dell'heartbeat del
 * repository (5 minuti), che e' la frequenza massima con cui una posizione
 * viene riscritta stando fermi.
 *
 * Prima erano entrambi 5 minuti: significava diventare gialli nei secondi
 * appena prima di ogni heartbeat, pur avendo l'app aperta e tutto funzionante.
 * Con 12 minuti si resta verdi anche saltando un heartbeat.
 */
const val PRESENCE_ONLINE_MS = 12 * 60_000L
const val PRESENCE_IDLE_MS = 60 * 60_000L

/** Pallino di presenza. Verde = visto di recente, giallo = qualche decina di minuti, grigio = da un'ora. */
@Composable
fun PresenceDot(
    lastSeenMillis: Long,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp
) {
    // Senza questo battito, "adesso" verrebbe letto una volta sola in
    // composizione: il pallino resterebbe fermo sul colore calcolato allora e
    // cambierebbe solo per caso, quando qualcos'altro fa ricomporre la lista.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastSeenMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(30_000)
        }
    }

    val elapsed = nowMillis - lastSeenMillis
    val target = when {
        elapsed < PRESENCE_ONLINE_MS -> RadarSemantic.Online
        elapsed < PRESENCE_IDLE_MS -> RadarSemantic.Idle
        else -> RadarSemantic.Offline
    }
    val color by animateColorAsState(targetValue = target, label = "presence_color")

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(1.5.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** Livello batteria: icona + percentuale, colorato per soglia. */
@Composable
fun BatteryBadge(
    level: Int,
    isCharging: Boolean = false,
    modifier: Modifier = Modifier
) {
    val color = when {
        isCharging -> RadarSemantic.BatteryOk
        level <= 20 -> RadarSemantic.BatteryLow
        level <= 50 -> RadarSemantic.BatteryMid
        else -> RadarSemantic.BatteryOk
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Icon(
            imageVector = if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(Sizes.iconSm)
        )
        Text(
            text = "$level%",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1
        )
    }
}

// ============================================================================
// BADGE E PILLOLE
// ============================================================================

/** Etichetta compatta maiuscola: ruoli, stati, conteggi. */
@Composable
fun RadarBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.xs),
        color = containerColor
    ) {
        Text(
            text = text.uppercase(),
            style = BadgeTextStyle,
            color = contentColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp)
        )
    }
}

/** Chip selezionabile con animazione di stato. Usato per tab, filtri e unità di misura. */
@Composable
fun PillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    badgeCount: Int = 0
) {
    val container by animateColorAsState(
        targetValue = if (selected) RadarDark.Accent else RadarDark.Surface,
        animationSpec = tween(180),
        label = "pill_container"
    )
    val content by animateColorAsState(
        targetValue = if (selected) Color.White else RadarDark.TextMuted,
        animationSpec = tween(180),
        label = "pill_content"
    )

    Surface(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        ),
        shape = RoundedCornerShape(Radius.pill),
        color = container
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(Sizes.iconSm)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) Color.White
                            else MaterialTheme.colorScheme.error
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (badgeCount > 9) "9+" else badgeCount.toString(),
                        style = BadgeTextStyle,
                        color = if (selected) RadarDark.Accent
                        else MaterialTheme.colorScheme.onError,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ============================================================================
// METRICHE
// ============================================================================

/** Riquadro metrica: icona, valore grande, etichetta. Usato nel dettaglio membro. */
@Composable
fun MetricTile(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(Sizes.iconMd)
            )
            Text(
                text = value,
                style = MetricTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

// ============================================================================
// STATI VUOTI E BANNER
// ============================================================================

/**
 * Stato vuoto con animazione.
 * [lottieAsset] è il nome di un file in `res/raw` (senza estensione). Se manca,
 * viene disegnata l'icona di fallback — vedi [LottieBox].
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    lottieAsset: String? = null,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        LottieBox(
            rawResName = lottieAsset,
            modifier = Modifier.size(120.dp)
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Sizes.iconXl)
                    )
                }
            }
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        action?.invoke()
    }
}

/** Banner informativo inline (errore, attesa, conferma). */
@Composable
fun InfoBanner(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.errorContainer,
    contentColor: Color = MaterialTheme.colorScheme.onErrorContainer,
    accentColor: Color = MaterialTheme.colorScheme.error,
    trailing: @Composable (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.sm),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(Sizes.iconMd)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
    }
}

// ============================================================================
// UTILITÀ VISIVE
// ============================================================================

/** Separatore sottile coerente col tema (più discreto del Divider di Material). */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    )
}

/** Maniglia del bottom sheet, animata quando il pannello è espanso. */
@Composable
fun SheetHandle(
    expanded: Boolean,
    modifier: Modifier = Modifier
) {
    val width by animateFloatAsState(
        targetValue = if (expanded) 28f else 44f,
        animationSpec = tween(220),
        label = "handle_width"
    )
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = Spacing.sm)
                .width(width.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(Radius.pill))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
    }
}

/** Titolo in grassetto con peso esplicito, per i pochi punti dove serve fuori scala. */
@Composable
fun EmphasisText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        color = color,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * Spinner circolare di caricamento coerente con l'identità visiva dell'app (nero / viola indigo).
 */
@Composable
fun RadarProgressIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    strokeWidth: Dp = 2.5.dp,
    color: Color = Color(0xFF6366F1),
    trackColor: Color = Color(0x336366F1)
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        trackColor = trackColor,
        strokeWidth = strokeWidth
    )
}

/**
 * Barra lineare di progresso / caricamento coerente col tema scuro / viola indigo.
 */
@Composable
fun RadarLinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF6366F1),
    trackColor: Color = Color(0x336366F1)
) {
    LinearProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = trackColor
    )
}
