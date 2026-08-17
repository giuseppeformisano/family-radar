package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun RadarPulseAnimation(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF648AC8)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_pulse")

    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse1"
    )

    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, delayMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse2"
    )

    val pulse3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, delayMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse3"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "radar_sweep"
    )

    Canvas(modifier = modifier.size(100.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = size.minDimension / 2

        // Fixed concentric grid rings
        drawCircle(
            color = color.copy(alpha = 0.2f),
            radius = maxRadius * 0.33f,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = color.copy(alpha = 0.2f),
            radius = maxRadius * 0.66f,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = color.copy(alpha = 0.25f),
            radius = maxRadius,
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Pulsing expanding rings
        if (pulse1 > 0f) {
            drawCircle(
                color = color.copy(alpha = (1f - pulse1) * 0.6f),
                radius = maxRadius * pulse1,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }
        if (pulse2 > 0f) {
            drawCircle(
                color = color.copy(alpha = (1f - pulse2) * 0.6f),
                radius = maxRadius * pulse2,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }
        if (pulse3 > 0f) {
            drawCircle(
                color = color.copy(alpha = (1f - pulse3) * 0.6f),
                radius = maxRadius * pulse3,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Center dot
        drawCircle(
            color = color,
            radius = 4.dp.toPx(),
            center = center
        )
    }
}
