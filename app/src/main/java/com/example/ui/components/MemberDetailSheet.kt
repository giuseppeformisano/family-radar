package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.model.ActivityKind
import com.example.model.UserLocation
import com.example.ui.theme.*
import com.example.util.ImageUtils
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MemberDetailSheet(
    location: UserLocation,
    isSelf: Boolean,
    onDismiss: () -> Unit,
    onNavigateToChat: () -> Unit,
    onEditProfileClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val speedKmH = (location.speed * 3.6f).toInt()
    val timeFormatted = SimpleDateFormat("HH:mm:ss (dd MMM)", Locale.getDefault()).format(Date(location.timestamp))
    val avatarBitmap = ImageUtils.base64ToBitmap(location.photoBase64)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            shape = RoundedCornerShape(Radius.xl),
            colors = CardDefaults.cardColors(containerColor = RadarDark.Bg),
            border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.CardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Member Avatar
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background((if (isSelf) RadarDark.Accent else RadarDark.AccentLight).copy(alpha = 0.20f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.member_avatar_desc, location.userName),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = location.userName.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = RadarDark.AccentLight,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                Text(
                    text = if (isSelf) stringResource(R.string.member_self_label, location.userName) else location.userName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = RadarDark.TextPrimary
                )

                if (!location.nickname.isNullOrBlank()) {
                    Text(
                        text = "“${location.nickname}”",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = RadarDark.AccentLight,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Current Place Pill if available
                if (!location.currentPlaceName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Surface(
                        shape = RoundedCornerShape(Radius.lg),
                        color = RadarDark.Surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.SurfaceBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                modifier = Modifier.size(Sizes.iconSm),
                                tint = RadarDark.AccentLight
                            )
                            Text(
                                text = stringResource(R.string.member_at_place, location.currentPlaceName ?: ""),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = RadarDark.TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.xl))

                // Metrics Grid (Battery, Speed, Accuracy, Last fix)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    MetricCard(
                        icon = Icons.Default.BatteryChargingFull,
                        label = stringResource(R.string.label_battery),
                        value = "${location.batteryLevel}%",
                        iconColor = when {
                            location.batteryLevel > 50 -> RadarSemantic.BatteryOk
                            location.batteryLevel > 20 -> RadarSemantic.BatteryMid
                            else -> RadarSemantic.BatteryLow
                        },
                        modifier = Modifier.weight(1f)
                    )

                    MetricCard(
                        icon = Icons.Default.Speed,
                        label = stringResource(R.string.label_speed),
                        value = if (speedKmH > 2) "$speedKmH km/h" else stringResource(R.string.speed_stationary),
                        iconColor = RadarDark.AccentLight,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    MetricCard(
                        icon = Icons.Default.GpsFixed,
                        label = stringResource(R.string.label_accuracy),
                        value = "±${location.accuracy.toInt()} m",
                        iconColor = Color(0xFF22D3EE),
                        modifier = Modifier.weight(1f)
                    )

                    MetricCard(
                        icon = Icons.Default.AccessTime,
                        label = stringResource(R.string.label_last_fix),
                        value = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(location.timestamp)),
                        iconColor = Color(0xFFA78BFA),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Activity type row — shown only when the activity is known
                if (location.activityType.isNotBlank()) {
                    Spacer(modifier = Modifier.height(Spacing.md))
                    val activityIcon = when (location.activityType) {
                        ActivityKind.VEHICLE  -> Icons.Default.DriveEta
                        ActivityKind.BICYCLE  -> Icons.Default.DirectionsBike
                        ActivityKind.RUNNING  -> Icons.Default.DirectionsRun
                        ActivityKind.WALKING  -> Icons.Default.DirectionsWalk
                        else -> null
                    }
                    val activityLabel = when (location.activityType) {
                        ActivityKind.VEHICLE  -> stringResource(R.string.trip_activity_vehicle)
                        ActivityKind.BICYCLE  -> stringResource(R.string.trip_activity_bicycle)
                        ActivityKind.RUNNING  -> stringResource(R.string.trip_activity_running)
                        ActivityKind.WALKING  -> stringResource(R.string.trip_activity_walking)
                        else -> null
                    }
                    if (activityIcon != null && activityLabel != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Icon(
                                imageVector = activityIcon,
                                contentDescription = null,
                                tint = RadarDark.AccentLight,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = activityLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = RadarDark.TextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.lg))

                // GPS Coordinates info text
                Text(
                    text = "Coordinate: ${String.format(java.util.Locale.US, "%.5f, %.5f", location.latitude, location.longitude)} • $timeFormatted",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = RadarDark.TextMuted
                    )
                )

                Spacer(modifier = Modifier.height(Spacing.xl))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    // Open in External Navigation (Google Maps / Waze)
                    OutlinedButton(
                        onClick = {
                            openMapsNavigation(context, location.latitude, location.longitude, location.userName)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("navigate_button"),
                        shape = RoundedCornerShape(Radius.md),
                        border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.SurfaceBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RadarDark.TextPrimary)
                    ) {
                        Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_directions), maxLines = 1)
                    }

                    // Chat with member / group
                    Button(
                        onClick = {
                            onDismiss()
                            onNavigateToChat()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_action_button"),
                        shape = RoundedCornerShape(Radius.md),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RadarDark.Accent,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_message), maxLines = 1)
                    }
                }

                if (isSelf && onEditProfileClick != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            onDismiss()
                            onEditProfileClick()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.md),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RadarDark.Surface,
                            contentColor = RadarDark.TextPrimary
                        )
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.action_edit_my_profile))
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.lg))
            }
        }
    }
}

@Composable
private fun MetricCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    iconColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = RadarDark.Surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.SurfaceBorder),
        shape = RoundedCornerShape(Radius.lg)
    ) {
        Row(
            modifier = Modifier
                .padding(Spacing.md)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(Sizes.iconMd))
            }
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(color = RadarDark.TextMuted),
                    maxLines = 1
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = RadarDark.TextPrimary,
                    maxLines = 1
                )
            }
        }
    }
}

private fun openMapsNavigation(context: Context, lat: Double, lon: Double, label: String) {
    try {
        if (lat == 0.0 && lon == 0.0 || lat.isNaN() || lon.isNaN()) return
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri)
        mapIntent.setPackage("com.google.android.apps.maps")
        if (mapIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(mapIntent)
        } else {
            val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lon"))
            context.startActivity(genericIntent)
        }
    } catch (e: Exception) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lon"))
        context.startActivity(browserIntent)
    }
}
