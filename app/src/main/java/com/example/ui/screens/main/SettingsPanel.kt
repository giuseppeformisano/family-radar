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
// PANNELLO: IMPOSTAZIONI
// ============================================================================

@Composable
internal fun SettingsPanel(
    currentUser: UserData?,
    currentGroup: GroupData?,
    currentUserId: String,
    memberLocations: List<UserLocation> = emptyList(),
    myMember: GroupMember?,
    isOwnerOrAdmin: Boolean,
    activeMemberCount: Int,
    pendingMemberCount: Int,
    trackingIntervalSec: Int,
    isTrackingEnabled: Boolean,
    isGlobalGhostMode: Boolean,
    isPowerSavingMode: Boolean,
    isAutoTripEnabled: Boolean,
    isAutoTripShared: Boolean,
    isHighPrecisionMovement: Boolean,
    filterQ: Float = 3.0f,
    onSetFilterQ: (Float) -> Unit = {},
    isSimulationRunning: Boolean,
    isVoiceAutoplayEnabled: Boolean,
    onToggleVoiceAutoplay: (Boolean) -> Unit,
    onEditProfileClick: () -> Unit,
    onEditGroupClick: () -> Unit,
    onSwitchGroup: () -> Unit,
    onUpdateInterval: (Int) -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onTogglePowerSaving: (Boolean) -> Unit,
    onToggleAutoTrip: (Boolean) -> Unit,
    onToggleAutoTripShared: (Boolean) -> Unit,
    onToggleHighPrecisionMovement: (Boolean) -> Unit,
    onToggleGlobalGhostMode: (Boolean) -> Unit,
    onToggleGroupTracking: (Boolean) -> Unit,
    onToggleAccessPolicy: (Boolean) -> Unit,
    onToggleSimulation: (Boolean) -> Unit,
    onRequestLeaveGroup: () -> Unit,
    onRequestDeleteGroup: () -> Unit,
    onLogout: () -> Unit,
    onSendFeedback: suspend (String) -> Unit,
    onFetchFeedback: suspend () -> List<com.example.model.FeedbackEntry>,
    onUpdateFeedbackStatus: suspend (String, String) -> Unit
) {
    val context = LocalContext.current
    val currentThemeMode by ThemePreferences.themeModeFlow.collectAsState()
    val currentMapColorMode by ThemePreferences.mapColorModeFlow.collectAsState()
    val currentUseNewMap by ThemePreferences.useNewMapFlow.collectAsState()
    val currentMapStyle by ThemePreferences.mapStyleFlow.collectAsState()
    val currentTerrain by ThemePreferences.terrainFlow.collectAsState()
    val currentLanguage by LanguagePreferences.languageFlow.collectAsState()

    var intervalUnit by remember {
        mutableStateOf(
            when {
                trackingIntervalSec % 3600 == 0 -> TrackingTimeUnit.HOURS
                trackingIntervalSec % 60 == 0 -> TrackingTimeUnit.MINUTES
                else -> TrackingTimeUnit.SECONDS
            }
        )
    }
    var intervalText by remember {
        mutableStateOf(
            when (intervalUnit) {
                TrackingTimeUnit.HOURS -> (trackingIntervalSec / 3600).coerceAtLeast(1).toString()
                TrackingTimeUnit.MINUTES -> (trackingIntervalSec / 60).coerceAtLeast(1).toString()
                TrackingTimeUnit.SECONDS -> trackingIntervalSec.toString()
            }
        )
    }

    var intervalSaved by remember { mutableStateOf(false) }
    LaunchedEffect(intervalSaved) {
        if (intervalSaved) {
            kotlinx.coroutines.delay(1500)
            intervalSaved = false
        }
    }

    fun applyInterval(raw: String, unit: TrackingTimeUnit) {
        val num = raw.toIntOrNull() ?: return
        onUpdateInterval((num * unit.multiplier).coerceIn(1, 86400))
        intervalSaved = true
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            top = Spacing.sm,
            bottom = Spacing.xxxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // Ordine: prima ciò che si tocca spesso e definisce il comportamento
        // dell'app (privacy, tracciamento), poi il contesto del gruppo, infine
        // cosmetica e azioni di uscita. Ogni scheda ha un'intestazione: senza,
        // il pannello era un muro di interruttori senza capire cosa raggruppa cosa.

        // ---- Profilo ----
        item {
            SettingsCard {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_profile), icon = Icons.Default.Person)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    RadarAvatar(
                        name = currentUser?.displayName ?: stringResource(R.string.label_user_name_fallback),
                        photoBase64 = currentUser?.photoBase64,
                        size = Sizes.avatarLg
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentUser?.displayName ?: stringResource(R.string.label_user_name_fallback),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val secondary = currentUser?.email?.takeIf { it.isNotBlank() }
                            ?: currentUser?.phoneNumber?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.label_anonymous_account)
                        Text(
                            text = secondary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FilledTonalButton(
                        onClick = onEditProfileClick,
                        shape = RoundedCornerShape(Radius.sm),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.action_edit_short))
                    }
                }
            }
        }

        // ---- Privacy ----
        item {
            SettingsCard {
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_section_privacy),
                    subtitle = stringResource(R.string.settings_privacy_subtitle),
                    icon = if (isGlobalGhostMode) Icons.Default.VisibilityOff else Icons.Default.Visibility
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_ghost_mode_title),
                    description = stringResource(R.string.settings_ghost_mode_desc),
                    icon = if (isGlobalGhostMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    iconTint = if (isGlobalGhostMode) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    checked = isGlobalGhostMode,
                    onCheckedChange = onToggleGlobalGhostMode,
                    testTag = "global_ghost_mode_switch"
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_group_share_title),
                    description = stringResource(R.string.settings_group_share_desc, currentGroup?.name ?: stringResource(R.string.settings_group_share_fallback)),
                    icon = Icons.Default.ShareLocation,
                    checked = myMember?.isTrackingActive ?: true,
                    onCheckedChange = onToggleGroupTracking,
                    testTag = "group_tracking_switch"
                )
                SettingsToggleRow(
                    title = "Riproduci vocali automaticamente",
                    description = "Le note vocali dei membri partono da sole mentre guardi la mappa",
                    icon = Icons.Default.RecordVoiceOver,
                    checked = isVoiceAutoplayEnabled,
                    onCheckedChange = onToggleVoiceAutoplay,
                    testTag = "voice_autoplay_switch"
                )
            }
        }

        // ---- Tracciamento ----
        // Sta subito sotto Privacy perche' e' lo stesso argomento visto
        // dall'altro lato: la' si decide CHI ti vede, qui COME vieni rilevato.
        // Prima erano separati dalla scheda Aspetto, che non c'entra nulla.
        item {
            SettingsCard {
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_section_tracking),
                    subtitle = stringResource(R.string.settings_tracking_subtitle),
                    icon = Icons.Default.GpsFixed
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_bg_tracking_title),
                    description = stringResource(R.string.settings_bg_tracking_desc),
                    icon = Icons.Default.GpsFixed,
                    checked = isTrackingEnabled,
                    onCheckedChange = onToggleTracking,
                    testTag = "tracking_switch"
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_power_saving_title),
                    description = stringResource(R.string.settings_power_saving_desc),
                    icon = Icons.Default.BatterySaver,
                    iconTint = if (isPowerSavingMode) RadarSemantic.BatteryOk
                    else MaterialTheme.colorScheme.primary,
                    checked = isPowerSavingMode,
                    onCheckedChange = onTogglePowerSaving,
                    testTag = "power_saving_switch"
                )
                SettingsToggleRow(
                    title = "Alta precisione in movimento",
                    description = "L'app rileva da sola quando ti muovi e aggiorna la posizione ogni secondo — più fluido, consuma più batteria. Da fermo torna normale.",
                    icon = Icons.Default.Speed,
                    checked = isHighPrecisionMovement,
                    onCheckedChange = onToggleHighPrecisionMovement,
                    testTag = "high_precision_switch"
                )
                BatteryReliabilityCard()
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.settings_update_frequency),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_update_frequency_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { input ->
                            val filtered = input.filter { it.isDigit() }.take(5)
                            intervalText = filtered
                            if (filtered.isNotBlank()) applyInterval(filtered, intervalUnit)
                        },
                        label = { Text(stringResource(R.string.label_value)) },
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.sm),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        modifier = Modifier
                            .width(110.dp)
                            .testTag("interval_input_field")
                    )
                    TrackingTimeUnit.entries.forEach { unit ->
                        PillChip(
                            label = stringResource(unit.labelRes).take(3),
                            selected = intervalUnit == unit,
                            onClick = {
                                intervalUnit = unit
                                if (intervalText.isNotBlank()) applyInterval(intervalText, unit)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                val effective = (intervalText.toIntOrNull() ?: 0) * intervalUnit.multiplier
                androidx.compose.animation.AnimatedContent(
                    targetState = intervalSaved,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.85f))
                            .togetherWith(androidx.compose.animation.fadeOut())
                    },
                    label = "interval_saved"
                ) { saved ->
                    if (saved) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = RadarSemantic.Online
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Salvato",
                                style = MaterialTheme.typography.labelMedium,
                                color = RadarSemantic.Online
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.settings_effective_interval, formatInterval(effective, context)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.settings_trip_speed_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Gruppo ----
        item {
            SettingsCard {
                // Intestazione dedicata invece di SectionHeader: quello rende il
                // titolo in titleMedium, lo stesso stile di "Privacy" o "Aspetto",
                // e cosi' il NOME del gruppo si confondeva con le etichette delle
                // sezioni. Qui l'etichetta fissa fa da soprattitolo e il nome
                // prende un peso tipografico suo, da nome proprio.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    val groupBitmap = remember(currentGroup?.photoBase64) {
                        ImageUtils.base64ToBitmap(currentGroup?.photoBase64?.ifBlank { null })
                    }
                    Box(
                        modifier = Modifier
                            .size(Sizes.avatarMd)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (groupBitmap != null) {
                            Image(
                                bitmap = groupBitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.Group,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(Sizes.iconMd)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.label_active_group),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(Spacing.xxs))
                        Text(
                            text = currentGroup?.name ?: stringResource(R.string.label_no_group),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(Spacing.xxs))
                        Text(
                            text = if (pendingMemberCount > 0)
                                stringResource(R.string.label_active_members_pending, activeMemberCount, pendingMemberCount)
                            else
                                stringResource(R.string.label_active_members, activeMemberCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.md))

                // Tutte le azioni sul gruppo stanno qui, dove si vede di quale
                // gruppo si parla. Abbandona stava in "Account", cioe' in una
                // sezione che parla dell'utente e non del gruppo: chi cercava
                // come uscire da QUESTO gruppo non lo trovava dove guardava.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Button(
                        onClick = onSwitchGroup,
                        shape = RoundedCornerShape(Radius.sm),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm)
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.action_change_short))
                    }
                    if (isOwnerOrAdmin && currentGroup != null) {
                        Button(
                            onClick = onEditGroupClick,
                            shape = RoundedCornerShape(Radius.sm),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("edit_group_button"),
                            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.action_edit_short))
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.sm))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // Il proprietario non abbandona: se ne andasse lascerebbe il
                    // gruppo senza padrone. Per lui l'azione giusta e' eliminarlo.
                    if (currentGroup != null && currentGroup.ownerId != currentUserId) {
                        OutlinedButton(
                            onClick = onRequestLeaveGroup,
                            shape = RoundedCornerShape(Radius.sm),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("leave_group_button"),
                            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm)
                        ) {
                            Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.action_leave_group))
                        }
                    }
                    if (isOwnerOrAdmin && currentGroup != null) {
                        Button(
                            onClick = onRequestDeleteGroup,
                            shape = RoundedCornerShape(Radius.sm),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("delete_group_button"),
                            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm)
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.action_delete_group))
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.md))

                Surface(
                    shape = RoundedCornerShape(Radius.sm),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.label_invite_code_section),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = currentGroup?.joinCode ?: "——————",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(context.getString(R.string.label_invite_code_section), currentGroup?.joinCode ?: "")
                                )
                                Toast.makeText(context, context.getString(R.string.toast_code_copied), Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(Radius.sm)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.action_copy))
                        }
                    }
                }

                if (isOwnerOrAdmin && currentGroup != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_approval_title),
                        description = if (currentGroup.requiresApproval)
                            stringResource(R.string.settings_approval_on_desc)
                        else
                            stringResource(R.string.settings_approval_off_desc),
                        icon = Icons.Default.AdminPanelSettings,
                        checked = currentGroup.requiresApproval,
                        onCheckedChange = onToggleAccessPolicy,
                        testTag = "access_policy_switch"
                    )
                }
            }
        }

        // ---- Aspetto ----
        // Cosmetica: si imposta una volta e non si tocca piu', quindi sta in
        // fondo e non piu' in mezzo alle impostazioni di posizione.
        item {
            SettingsCard {
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_appearance),
                    subtitle = stringResource(R.string.settings_appearance_subtitle),
                    icon = Icons.Default.Palette
                )
                SettingsSelectorGroup(label = "Tema") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        listOf(
                            Triple(ThemeMode.SYSTEM, R.string.theme_system, Icons.Default.BrightnessAuto),
                            Triple(ThemeMode.LIGHT, R.string.theme_light, Icons.Default.LightMode),
                            Triple(ThemeMode.DARK, R.string.theme_dark, Icons.Default.DarkMode)
                        ).forEach { (mode, labelRes, icon) ->
                            PillChip(
                                label = stringResource(labelRes),
                                icon = icon,
                                selected = currentThemeMode == mode,
                                onClick = { ThemePreferences.setThemeMode(context, mode) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                SettingsSelectorGroup(label = stringResource(R.string.settings_language)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        listOf(
                            AppLanguage.SYSTEM to R.string.language_system,
                            AppLanguage.ITALIAN to R.string.language_italian,
                            AppLanguage.ENGLISH to R.string.language_english
                        ).forEach { (language, labelRes) ->
                            PillChip(
                                label = stringResource(labelRes),
                                selected = currentLanguage == language,
                                onClick = {
                                    if (currentLanguage != language) {
                                        LanguagePreferences.setLanguage(context, language)
                                        context.findActivityOrNull()?.recreate()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // ---- Mappa ----
        item {
            SettingsCard {
                SettingsSectionHeader(
                    title = "Mappa",
                    subtitle = "Aspetto e sorgente della mappa",
                    icon = Icons.Default.Map
                )
                SettingsToggleRow(
                    title = "Usa nuova mappa",
                    description = "Mappa vettoriale mondiale (MapLibre + OpenFreeMap) con negozi e punti d'interesse, al posto di quella attuale.",
                    icon = Icons.Default.Map,
                    checked = currentUseNewMap,
                    onCheckedChange = { ThemePreferences.setUseNewMap(context, it) }
                )
                if (currentUseNewMap) {
                    SettingsSelectorGroup(
                        label = "Stile mappa",
                        description = "L'aspetto della mappa, indipendente dal tema dell'app."
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            com.example.ui.theme.MapStyle.values().forEach { style ->
                                PillChip(
                                    label = style.title,
                                    selected = currentMapStyle == style,
                                    onClick = { ThemePreferences.setMapStyle(context, style) }
                                )
                            }
                        }
                    }
                    SettingsToggleRow(
                        title = "Rilievo 3D",
                        description = "Montagne e colline con ombre e altezza reale. In modalita' 3D cam il terreno si alza.",
                        icon = Icons.Default.Landscape,
                        checked = currentTerrain,
                        onCheckedChange = { ThemePreferences.setTerrain(context, it) }
                    )
                }
            }
        }

        // ---- Account ----
        // Qui resta solo cio' che riguarda l'ACCOUNT. Abbandona il gruppo e'
        // passato alla scheda del gruppo, insieme alle altre azioni sul gruppo.
        item {
            SettingsCard {
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_section_account),
                    subtitle = stringResource(R.string.settings_account_subtitle),
                    icon = Icons.Default.ManageAccounts
                )
                Spacer(Modifier.height(Spacing.xs))
                Button(
                    onClick = onLogout,
                    shape = RoundedCornerShape(Radius.sm),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("logout_app_button")
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(Sizes.iconMd))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.action_sign_out_account))
                }
            }
        }

        // ---- App ----
        item {
            SettingsCard {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_app), icon = Icons.Default.Info)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.label_version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                var checking by remember { mutableStateOf(false) }
                var checkResult by remember { mutableStateOf<CheckResult?>(null) }
                val checkScope = rememberCoroutineScope()

                Button(
                    onClick = {
                        if (!checking) {
                            checking = true
                            checkResult = null
                            checkScope.launch {
                                checkResult = AppUpdater.checkDetailed()
                                checking = false
                            }
                        }
                    },
                    shape = RoundedCornerShape(Radius.sm),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Sizes.iconMd),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                    }
                    Text(if (checking) stringResource(R.string.checking_updates) else stringResource(R.string.action_check_updates))
                }

                when (val result = checkResult) {
                    is CheckResult.Available -> Dialog(onDismissRequest = { checkResult = null }) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                            shape = RoundedCornerShape(Radius.xl),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(Spacing.xxl),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.md)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(Sizes.avatarLg)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.SystemUpdate, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Sizes.iconLg))
                                }
                                Text(
                                    text = stringResource(R.string.update_available_body, result.info.versionName),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    OutlinedButton(onClick = { checkResult = null }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Radius.sm), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(stringResource(R.string.action_later)) }
                                    Button(onClick = { checkResult = null; AppUpdater.downloadAndInstall(context, result.info.apkUrl) },
                                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(Radius.sm), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) { Text(stringResource(R.string.action_update)) }
                                }
                            }
                        }
                    }
                    CheckResult.UpToDate -> Dialog(onDismissRequest = { checkResult = null }) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                            shape = RoundedCornerShape(Radius.xl),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(Spacing.xxl),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.md)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(Sizes.avatarLg)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Sizes.iconLg))
                                }
                                Text(
                                    text = stringResource(R.string.up_to_date_body, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(onClick = { checkResult = null }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.sm), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) { Text(stringResource(R.string.action_ok)) }
                            }
                        }
                    }
                    CheckResult.NetworkError -> Dialog(onDismissRequest = { checkResult = null }) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                            shape = RoundedCornerShape(Radius.xl),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(Spacing.xxl),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.md)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(Sizes.avatarLg)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.WifiOff, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(Sizes.iconLg))
                                }
                                Text(
                                    text = stringResource(R.string.update_network_error_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(onClick = { checkResult = null }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.sm), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) { Text(stringResource(R.string.action_ok)) }
                            }
                        }
                    }
                    null -> Unit
                }
            }
        }

        // ---- Feedback ----
        item {
            val feedbackScope = rememberCoroutineScope()
            var feedbackText by remember { mutableStateOf("") }
            var feedbackSent by remember { mutableStateOf(false) }
            var feedbackSending by remember { mutableStateOf(false) }

            SettingsCard {
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_section_feedback),
                    subtitle = stringResource(R.string.settings_feedback_subtitle),
                    icon = Icons.Default.Feedback
                )
                if (feedbackSent) {
                    Text(
                        text = stringResource(R.string.feedback_thanks),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = Spacing.xs)
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(
                        onClick = { feedbackSent = false },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        shape = RoundedCornerShape(Radius.sm)
                    ) { Text(stringResource(R.string.action_send_more_feedback)) }
                } else {
                    OutlinedTextField(
                        value = feedbackText,
                        onValueChange = { feedbackText = it },
                        placeholder = { Text(stringResource(R.string.feedback_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.sm),
                        minLines = 3,
                        maxLines = 6,
                        enabled = !feedbackSending,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Button(
                        onClick = {
                            if (feedbackText.isBlank()) return@Button
                            feedbackSending = true
                            feedbackScope.launch {
                                onSendFeedback(feedbackText)
                                feedbackSending = false
                                feedbackSent = true
                                feedbackText = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.sm),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                        enabled = !feedbackSending && feedbackText.isNotBlank()
                    ) {
                        if (feedbackSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Sizes.iconMd),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
                            )
                            Spacer(Modifier.width(Spacing.sm))
                        }
                        Text(stringResource(R.string.action_send_feedback))
                    }
                }
            }
        }

        // ---- Sviluppo ----
        // Ultimo di tutti e qualificato: e' uno strumento di test, non una
        // funzionalita'. Prima stava in mezzo alle impostazioni vere senza
        // nemmeno un titolo che lo distinguesse.
        item {
            SettingsCard {
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_section_dev),
                    subtitle = stringResource(R.string.settings_dev_subtitle),
                    icon = Icons.Default.Code
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_sim_title),
                    description = stringResource(R.string.settings_sim_desc),
                    icon = if (isSimulationRunning) Icons.Default.DirectionsRun else Icons.Default.PlayCircle,
                    checked = isSimulationRunning,
                    onCheckedChange = onToggleSimulation,
                    testTag = "simulation_toggle_button"
                )

                SettingsSelectorGroup(
                    label = "Reattività filtro posizione — ${String.format(Locale.US, "%.1f", filterQ)}",
                    description = "Basso = pallino più stabile e liscio (ma più molle). Alto = segue in fretta (ma più nervoso). Per taratura."
                ) {
                    Slider(
                        value = filterQ,
                        onValueChange = onSetFilterQ,
                        valueRange = 0.5f..15f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                var showDevFeedbackDialog by remember { mutableStateOf(false) }
                SettingsClickRow(
                    title = stringResource(R.string.settings_dev_feedback_title),
                    description = stringResource(R.string.settings_dev_feedback_desc),
                    icon = Icons.Default.Feedback,
                    onClick = { showDevFeedbackDialog = true }
                )
                if (showDevFeedbackDialog) {
                    FeedbackDevDialog(
                        onDismiss = { showDevFeedbackDialog = false },
                        onFetchFeedback = onFetchFeedback,
                        onUpdateFeedbackStatus = onUpdateFeedbackStatus
                    )
                }
            }
        }
    }
}

@Composable
private fun FeedbackDevDialog(
    onDismiss: () -> Unit,
    onFetchFeedback: suspend () -> List<com.example.model.FeedbackEntry>,
    onUpdateFeedbackStatus: suspend (String, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var wrongPassword by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<com.example.model.FeedbackEntry>>(emptyList()) }

    fun tryUnlock() {
        if (password == "radarfeedback") {
            wrongPassword = false
            showList = true
            loading = true
            scope.launch { list = onFetchFeedback(); loading = false }
        } else {
            wrongPassword = true
        }
    }

    // Dialog password
    if (!showList) {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.xl),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(Sizes.avatarLg).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Sizes.iconLg))
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(stringResource(R.string.dev_area_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(stringResource(R.string.dev_area_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.lg))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; wrongPassword = false },
                        label = { Text(stringResource(R.string.label_password)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.sm),
                        isError = wrongPassword,
                        supportingText = if (wrongPassword) {{ Text(stringResource(R.string.err_wrong_password), color = MaterialTheme.colorScheme.error) }} else null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { tryUnlock() })
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    Button(onClick = { tryUnlock() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.sm), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        Text(stringResource(R.string.action_sign_in))
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    } else {
        // Dialog lista feedback
        val dateFormat = remember { java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.ITALY) }
        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
                shape = RoundedCornerShape(Radius.xl),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(Spacing.xl)) {
                    Box(
                        modifier = Modifier.size(Sizes.avatarLg).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Sizes.iconLg))
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(stringResource(R.string.dev_feedback_list_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(Spacing.md))
                    if (loading) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            RadarProgressIndicator(size = 36.dp, strokeWidth = 3.dp)
                        }
                    } else if (list.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.dev_feedback_empty), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            items(list, key = { it.id }) { entry ->
                                Surface(
                                    shape = RoundedCornerShape(Radius.md),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(Spacing.md)) {
                                        Row(modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(entry.userName, style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary)
                                                Text(dateFormat.format(java.util.Date(entry.timestamp)),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text("v${entry.versionName}", style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Row {
                                                IconButton(onClick = {
                                                    list = list.filter { it.id != entry.id }
                                                    scope.launch { onUpdateFeedbackStatus(entry.id, "done") }
                                                }) {
                                                    Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.content_desc_mark_done),
                                                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Sizes.iconMd))
                                                }
                                                IconButton(onClick = {
                                                    list = list.filter { it.id != entry.id }
                                                    scope.launch { onUpdateFeedbackStatus(entry.id, "discarded") }
                                                }) {
                                                    Icon(Icons.Default.Cancel, contentDescription = stringResource(R.string.content_desc_discard),
                                                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(Sizes.iconMd))
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(Spacing.xs))
                                        Text(entry.text, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.sm), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

/**
 * Intestazione delle schede di Impostazioni.
 *
 * Non riusa SectionHeader perche' li' il titolo e' in titleMedium, cioe' quasi
 * lo stesso peso dei titoli degli interruttori sotto: il risultato era che
 * titolo di sezione e voci sembravano un blocco unico. Qui il titolo sale a
 * titleLarge e una riga sottile lo stacca dalle voci che governa.
 */
@Composable
private fun SettingsSectionHeader(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
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
    Spacer(Modifier.height(Spacing.md))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            content = content
        )
    }
}

/**
 * Gruppo "etichetta + controllo" con la stessa spaziatura verticale delle righe
 * (padding sm sopra/sotto) e un gap interno piccolo e uniforme. Serve a tenere
 * tutte le voci delle impostazioni allineate, senza Spacer manuali di misure diverse.
 */
@Composable
private fun SettingsSelectorGroup(
    label: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        if (!description.isNullOrBlank()) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

// Colori Switch a tema (indaco), per non ereditare l'azzurrino di default di
// Material / dynamic color. Usato da tutti gli interruttori delle impostazioni.
@Composable
private fun radarSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
)

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    testTag: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(Sizes.iconMd)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = radarSwitchColors(),
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
        )
    }
}

/**
 * Card di affidabilità: dice se il telefono blocca l'app in background (la causa
 * numero uno dei buchi nel tracciamento) e porta l'utente all'impostazione giusta.
 * Autonoma: legge da sola lo stato e si ricontrolla al ritorno sulla schermata.
 */
@Composable
private fun BatteryReliabilityCard() {
    val context = LocalContext.current
    var isExempt by remember { mutableStateOf(isIgnoringBatteryOpt(context)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isExempt = isIgnoringBatteryOpt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm)
            .clip(RoundedCornerShape(Radius.md))
            .background(
                if (isExempt) RadarSemantic.BatteryOk.copy(alpha = 0.10f)
                else RadarSemantic.BatteryLow.copy(alpha = 0.12f)
            )
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                imageVector = if (isExempt) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isExempt) RadarSemantic.BatteryOk else RadarSemantic.BatteryLow,
                modifier = Modifier.size(Sizes.iconMd)
            )
            Text(
                text = if (isExempt) "Tracciamento affidabile" else "Tracciamento a rischio",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = if (isExempt)
                "Il telefono non blocca l'app in background: la tua posizione resta aggiornata anche a schermo spento."
            else
                "Il telefono può bloccare l'app a schermo spento e la tua posizione smette di aggiornarsi. Tocca \"Rendi affidabile\" e scegli Consenti / Non ottimizzare. Alcuni telefoni (Xiaomi, Samsung, Huawei) hanno anche un blocco extra: nelle impostazioni batteria del telefono metti questa app su \"Nessuna restrizione\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!isExempt) {
            Button(
                onClick = { requestIgnoreBatteryOpt(context) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Rendi affidabile")
            }
        }
        OutlinedButton(onClick = { openAppDetailsSettings(context) }) {
            Text("Impostazioni batteria del telefono")
        }
    }
}

private fun isIgnoringBatteryOpt(context: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager ?: return true
    return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
}

private fun requestIgnoreBatteryOpt(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
        )
    }
}

private fun openAppDetailsSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
        )
    }
}

@Composable
private fun SettingsClickRow(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(Sizes.iconMd))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Sizes.iconMd))
        }
    }
}
