package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.Radius
import com.example.ui.theme.Sizes
import com.example.ui.theme.Spacing
import com.valentinilk.shimmer.ShimmerBounds
import com.valentinilk.shimmer.rememberShimmer
import com.valentinilk.shimmer.shimmer

/**
 * Placeholder animati mostrati mentre arrivano i primi dati da Firestore.
 *
 * Perché non uno spinner: i listener del repository popolano le liste in modo
 * progressivo, e uno scheletro che ha già la forma del contenuto finale fa
 * percepire l'attesa come più breve ed evita il salto di layout all'arrivo dei dati.
 */

/** Blocco grigio animato. Mattoncino di base per tutti gli scheletri. */
@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    cornerRadius: Dp = Radius.xs
) {
    val shimmerInstance = rememberShimmer(shimmerBounds = ShimmerBounds.View)
    Box(
        modifier = modifier
            .height(height)
            .shimmer(shimmerInstance)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f))
    )
}

/** Scheletro di una riga membro: avatar + due righe di testo. */
@Composable
fun MemberRowSkeleton(modifier: Modifier = Modifier) {
    val shimmerInstance = rememberShimmer(shimmerBounds = ShimmerBounds.View)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shimmer(shimmerInstance)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            modifier = Modifier
                .size(Sizes.avatarMd)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f))
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            )
        }
    }
}

/** Scheletro della lista membri nel bottom sheet. */
@Composable
fun MemberListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = 4
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        repeat(rows) { MemberRowSkeleton() }
    }
}

/** Scheletro di una card luogo. */
@Composable
fun PlaceCardSkeleton(modifier: Modifier = Modifier) {
    val shimmerInstance = rememberShimmer(shimmerBounds = ShimmerBounds.View)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shimmer(shimmerInstance)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            modifier = Modifier
                .size(Sizes.avatarMd)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f))
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            )
        }
    }
}

/** Scheletro di una bolla di chat, allineata a sinistra o a destra. */
@Composable
fun ChatBubbleSkeleton(
    isMe: Boolean,
    modifier: Modifier = Modifier
) {
    val shimmerInstance = rememberShimmer(shimmerBounds = ShimmerBounds.View)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shimmer(shimmerInstance),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .height(44.dp)
                .clip(RoundedCornerShape(Radius.md))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
        )
    }
}
