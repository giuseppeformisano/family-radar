package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

/**
 * Animazione radar disegnata su Canvas: onde concentriche in espansione più un
 * settore che ruota, come lo sweep di un radar reale.
 *
 * È scritta in Compose puro e non come Lottie di proposito: è l'elemento di
 * identità dell'app, deve poter assumere il colore del tema (anche quello dinamico
 * di Material You) e un `.json` avrebbe i colori cotti dentro.
 */
@Composable
fun RadarPulseAnimation(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    waveCount: Int = 3,
    showSweep: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "radar")

    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_angle"
    )

    val corePulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_pulse"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = minOf(size.width, size.height) / 2f

            // Onde concentriche: ognuna sfasata per avere un flusso continuo.
            repeat(waveCount) { index ->
                val phase = (wavePhase + index.toFloat() / waveCount) % 1f
                val radius = maxRadius * phase
                val alpha = (1f - phase).coerceIn(0f, 1f) * 0.55f
                if (radius > 0.5f) {
                    drawCircle(
                        color = color.copy(alpha = alpha),
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2f)
                    )
                }
            }

            // Settore rotante con gradiente in dissolvenza.
            if (showSweep) {
                val sweepBrush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0.0f to color.copy(alpha = 0.30f),
                        0.12f to color.copy(alpha = 0.06f),
                        0.30f to Color.Transparent,
                        1.0f to Color.Transparent
                    ),
                    center = center
                )
                rotate(sweepAngle, center) {
                    drawCircle(
                        brush = sweepBrush,
                        radius = maxRadius,
                        center = center
                    )
                }
            }

            // Anello di contorno.
            drawCircle(
                color = color.copy(alpha = 0.22f),
                radius = maxRadius,
                center = center,
                style = Stroke(width = 1.5f)
            )

            // Nucleo pulsante.
            val coreRadius = maxRadius * 0.13f * corePulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0f)),
                    center = center,
                    radius = coreRadius * 2.6f
                ),
                radius = coreRadius * 2.6f,
                center = center
            )
            drawCircle(color = color, radius = coreRadius, center = center)
        }
    }
}

/**
 * Variante compatta usata nella barra superiore: solo onde, niente sweep,
 * per non rubare attenzione alla mappa.
 */
@Composable
fun RadarPulseCompact(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    RadarPulseAnimation(
        modifier = modifier,
        color = color,
        waveCount = 2,
        showSweep = false
    )
}
