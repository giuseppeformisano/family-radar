package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.PlaceCategory
import com.example.model.SavedPlace
import com.example.ui.theme.*

@Composable
fun PlaceDetailSheet(
    place: SavedPlace,
    onDismiss: () -> Unit,
    onShowOnMap: (SavedPlace) -> Unit,
    onDeletePlace: (SavedPlace) -> Unit,
    onEditPlace: (SavedPlace) -> Unit = {},
    /** Accende o spegne gli avvisi senza dover aprire il dialog di modifica. */
    onToggleGeofence: (SavedPlace, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val catColor = when (place.category) {
        PlaceCategory.HOME -> RadarSemantic.PlaceHome
        PlaceCategory.WORK -> RadarSemantic.PlaceWork
        PlaceCategory.SCHOOL -> RadarSemantic.PlaceSchool
        PlaceCategory.GYM -> RadarSemantic.PlaceGym
        PlaceCategory.OTHER -> RadarSemantic.PlaceOther
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            shape = RoundedCornerShape(Radius.xl),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header: title + close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dettaglio luogo",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Chiudi")
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                // Category Icon Badge
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(catColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (place.category) {
                            PlaceCategory.HOME -> Icons.Default.Home
                            PlaceCategory.WORK -> Icons.Default.Work
                            PlaceCategory.SCHOOL -> Icons.Default.School
                            PlaceCategory.GYM -> Icons.Default.FitnessCenter
                            PlaceCategory.OTHER -> Icons.Default.Place
                        },
                        contentDescription = null,
                        tint = catColor,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.md))

                Text(
                    text = place.name.ifBlank { "Luogo di Gruppo" },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(Spacing.xs))

                Surface(
                    color = catColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(Radius.md)
                ) {
                    Text(
                        text = "${place.category.label} • Raggio ${place.radiusMeters.toInt()}m",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = catColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = Spacing.xs)
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xl))

                // Details Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radius.lg),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Coordinate GPS",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = String.format(java.util.Locale.US, "%.5f, %.5f", place.latitude, place.longitude),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Geofencing Notifiche",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Attivo (Entrata/Uscita)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = RadarSemantic.BatteryOk
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.xl))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Button(
                        onClick = {
                            try {
                                val uri = Uri.parse("google.navigation:q=${place.latitude},${place.longitude}")
                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                    setPackage("com.google.android.apps.maps")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val uri = Uri.parse("geo:${place.latitude},${place.longitude}?q=${place.latitude},${place.longitude}(${Uri.encode(place.name)})")
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                } catch (e2: Exception) {
                                    Toast.makeText(context, "Nessuna app di navigazione installata", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Radius.md),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                    ) {
                        Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text("Indicazioni")
                    }

                    Button(
                        onClick = {
                            onShowOnMap(place)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Radius.md),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) {
                        Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text("Vedi Mappa")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Interruttore rapido degli avvisi: e' la modifica piu' frequente,
                // non vale la pena passare dal dialog completo per farla.
                Surface(
                    shape = RoundedCornerShape(Radius.md),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (place.geofenceEnabled) Icons.Default.NotificationsActive
                            else Icons.Default.NotificationsOff,
                            contentDescription = null,
                            tint = if (place.geofenceEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Sizes.iconMd)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Avvisi arrivo e partenza",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                if (place.geofenceEnabled) "Attivi per questo luogo"
                                else "Disattivati: nessuna notifica",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = place.geofenceEnabled,
                            onCheckedChange = { onToggleGeofence(place, it) },
                            modifier = Modifier.testTag("place_geofence_switch")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    OutlinedButton(
                        onClick = {
                            onEditPlace(place)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("edit_place_button"),
                        shape = RoundedCornerShape(Radius.md)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text("Modifica")
                    }

                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Radius.md),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text("Elimina")
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.md))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            icon = {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            },
            title = {
                Text(
                    "Eliminare questo luogo?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            },
            text = {
                Text(
                    "Sei sicuro di voler rimuovere '${place.name}' dai luoghi sicuri del gruppo? I membri non riceveranno più notifiche di arrivo e partenza.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeletePlace(place)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Elimina")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteConfirm = false },
                    shape = RoundedCornerShape(Radius.md)
                ) {
                    Text("Annulla")
                }
            }
        )
    }
}
