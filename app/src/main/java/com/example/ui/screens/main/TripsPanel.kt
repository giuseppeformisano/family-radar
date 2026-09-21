@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.example.ui.screens.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.R
import com.example.model.*
import com.example.repository.FirebaseRepository
import com.example.service.LocationTrackingService
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.BuildConfig
import com.example.util.AppUpdater
import com.example.util.CheckResult
import com.example.util.ImageUtils
import com.example.util.VoiceUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.media.MediaPlayer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.example.util.UpdateInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

// ============================================================================
// PANNELLO: VIAGGI
// ============================================================================

@Composable
internal fun TripsPanel(
    trips: List<Trip>,
    activeTrip: ActiveTripState?,
    currentUserId: String,
    selectedTripId: String?,
    onTripSelected: (String) -> Unit,
    onDeleteTrip: (String) -> Unit,
    onStartTrip: () -> Unit,
    onStopTrip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dateFormat = remember { java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.ITALY) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg, end = Spacing.lg,
            top = Spacing.sm, bottom = Spacing.xxxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (activeTrip != null) {
            item {
                var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(activeTrip.startTime) {
                    while (true) {
                        nowMs = System.currentTimeMillis()
                        delay(1000)
                    }
                }
                val elapsedMs = nowMs - activeTrip.startTime
                val elapsedMin = (elapsedMs / 60000).toInt()
                val elapsedSec = ((elapsedMs / 1000) % 60).toInt()
                val km = activeTrip.distanceMeters / 1000.0

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, RadarSemantic.Sos)
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            RadarPulseAnimation(
                                color = RadarSemantic.Sos,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                stringResource(R.string.trip_recording),
                                style = MaterialTheme.typography.labelLarge,
                                color = RadarSemantic.Sos
                            )
                        }
                        Text(
                            "%02d:%02d  •  %.2f km  •  %d punti".format(
                                elapsedMin, elapsedSec, km, activeTrip.points.size
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = onStopTrip,
                            colors = ButtonDefaults.buttonColors(containerColor = RadarSemantic.Sos),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.action_stop_and_save))
                        }
                    }
                }
            }
        } else {
            item {
                Button(
                    onClick = onStartTrip,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.action_start_trip))
                }
            }
        }

        if (trips.isEmpty() && activeTrip == null) {
            item {
                EmptyState(
                    icon = Icons.Default.Route,
                    title = stringResource(R.string.empty_trips_title),
                    description = stringResource(R.string.empty_trips_body)
                )
            }
        }

        items(trips, key = { it.id }) { trip ->
            val isSelected = trip.id == selectedTripId
            val isMine = trip.userId == currentUserId
            val km = trip.distanceMeters / 1000.0
            val durationMin = (trip.durationMs / 60000).toInt()

            Surface(
                onClick = { onTripSelected(trip.id) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(
                    if (isSelected) 1.5.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Icon(
                        if (trip.isLive) Icons.Default.DirectionsCar else Icons.Default.Route,
                        contentDescription = null,
                        tint = when {
                            trip.isLive -> RadarSemantic.Sos
                            isSelected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(Sizes.iconMd)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(
                                trip.userName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            TripBadge(
                                text = if (trip.isLive) stringResource(R.string.trip_badge_live) else trip.source.label(context).uppercase(),
                                color = when {
                                    trip.isLive -> RadarSemantic.Sos
                                    trip.source == TripSource.AUTO -> RadarSemantic.Online
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                            if (trip.isPrivate) {
                                TripBadge(
                                    text = stringResource(R.string.trip_badge_private),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        val route = listOfNotNull(trip.startPlaceName, trip.endPlaceName)
                        Text(
                            if (route.size == 2) "${route[0]} → ${route[1]}"
                            else dateFormat.format(java.util.Date(trip.startTime)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "%.1f km  •  %d min".format(km, durationMin) +
                                (trip.activityLabel(context)?.let { "  •  $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isMine && !trip.isLive) {
                        IconButton(onClick = { onDeleteTrip(trip.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.content_desc_delete_place),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(Sizes.iconSm)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Etichetta compatta: manuale / automatico / in corso / privato. */
@Composable
private fun TripBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 1.dp)
        )
    }
}

/**
 * Scheda di dettaglio di un viaggio.
 *
 * Il tap sull'elenco apre prima questa: la traccia sulla mappa e' un passo
 * successivo e volontario, perche' disegnarla chiude il pannello e sposta
 * l'inquadratura, e non e' detto che sia quello che si voleva.
 */
@Composable
internal fun TripDetailDialog(
    trip: Trip,
    isOnMap: Boolean,
    onDismiss: () -> Unit,
    onShowOnMap: () -> Unit,
    onHideFromMap: () -> Unit
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ITALY) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.ITALY) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            shape = RoundedCornerShape(Radius.xl),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                // Icona centrata
                Box(
                    modifier = Modifier
                        .size(Sizes.avatarLg)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .align(Alignment.CenterHorizontally),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (trip.source == TripSource.AUTO) Icons.Default.AutoMode else Icons.Default.Route,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Sizes.iconLg)
                    )
                }
                Spacer(Modifier.height(Spacing.xs))
                // Titolo centrato sotto l'icona
                Text(
                    text = listOfNotNull(trip.startPlaceName, trip.endPlaceName)
                        .takeIf { it.size == 2 }?.joinToString(" → ")
                        ?: stringResource(R.string.trip_title_of, trip.userName),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = dateFormat.format(Date(trip.startTime)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                // Stat tiles
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TripStatTile(
                        label = stringResource(R.string.trip_stat_distance),
                        value = "%.1f".format(trip.distanceMeters / 1000.0),
                        unit = "km",
                        modifier = Modifier.weight(1f)
                    )
                    TripStatTile(
                        label = stringResource(R.string.trip_stat_duration),
                        value = "${trip.durationMs / 60000}",
                        unit = "min",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TripStatTile(
                        label = stringResource(R.string.trip_stat_avg_speed),
                        value = "${(trip.averageSpeedMs * 3.6f).toInt()}",
                        unit = "km/h",
                        modifier = Modifier.weight(1f)
                    )
                    TripStatTile(
                        label = stringResource(R.string.trip_stat_max_speed),
                        value = "${(trip.maxSpeedMs * 3.6f).toInt()}",
                        unit = "km/h",
                        modifier = Modifier.weight(1f)
                    )
                }

                HairlineDivider()

                TripDetailRow(stringResource(R.string.trip_detail_departure), timeFormat.format(Date(trip.startTime)))
                if (trip.endTime > 0) {
                    TripDetailRow(stringResource(R.string.trip_detail_arrival), timeFormat.format(Date(trip.endTime)))
                }
                if (trip.stoppedMs > 60_000) {
                    TripDetailRow(stringResource(R.string.trip_detail_stopped), stringResource(R.string.trip_detail_stopped_value, (trip.stoppedMs / 60000).toInt()))
                }
                trip.activityLabel(LocalContext.current)?.let { TripDetailRow(stringResource(R.string.trip_detail_activity), it) }
                TripDetailRow(stringResource(R.string.trip_detail_source), trip.source.label(LocalContext.current))
                TripDetailRow(stringResource(R.string.trip_detail_by), trip.userName)

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text(stringResource(R.string.action_close)) }
                    Spacer(Modifier.width(Spacing.xs))
                    if (isOnMap) {
                        OutlinedButton(onClick = onHideFromMap, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                            Icon(Icons.Default.LayersClear, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.action_remove_from_map))
                        }
                    } else {
                        Button(onClick = onShowOnMap, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.action_show_on_map))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripStatTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(text = value, style = MetricTextStyle, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(2.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TripDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
