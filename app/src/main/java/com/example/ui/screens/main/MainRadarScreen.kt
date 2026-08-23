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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import com.example.util.UpdateInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

/** I cinque pannelli del bottom sheet sopra la mappa. */
private enum class RadarPanel(@StringRes val labelRes: Int) {
    MEMBERS(R.string.tab_members),
    CHAT(R.string.tab_chat),
    PLACES(R.string.tab_places),
    TRIPS(R.string.tab_trips),
    SETTINGS(R.string.tab_settings)
}

enum class TrackingTimeUnit(@StringRes val labelRes: Int, val multiplier: Int) {
    SECONDS(R.string.unit_seconds, 1),
    MINUTES(R.string.unit_minutes, 60),
    HOURS(R.string.unit_hours, 3600)
}

@Composable
fun MainRadarScreen(
    repository: FirebaseRepository,
    onSwitchGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Strings captured for use inside lambdas / coroutines
    val strNoPhoto = stringResource(R.string.toast_no_photo)
    val strCameraPermDenied = stringResource(R.string.toast_camera_permission_denied)
    val strPhotoFileError = stringResource(R.string.toast_photo_file_error)
    val strCameraError = stringResource(R.string.toast_camera_error)
    val strRequestApproved = stringResource(R.string.toast_request_approved)
    val strRequestRejected = stringResource(R.string.toast_request_rejected)
    val strTripSaved = stringResource(R.string.toast_trip_saved)
    val strBatterySaverOn = stringResource(R.string.toast_battery_saver_on)
    val strGpsPrecisionRestored = stringResource(R.string.toast_gps_precision_restored)
    val strAutoTripOn = stringResource(R.string.toast_trip_auto_on)
    val strAutoTripOff = stringResource(R.string.toast_trip_auto_off)
    val strBgTrackingOn = stringResource(R.string.toast_bg_tracking_on)
    val strBgTrackingOff = stringResource(R.string.toast_bg_tracking_off)
    val strGhostOn = stringResource(R.string.toast_ghost_on)
    val strGhostOff = stringResource(R.string.toast_ghost_off)
    val strFollowOff = stringResource(R.string.toast_follow_off)
    val strFollowOn = stringResource(R.string.toast_follow_on)
    val strFollowTargetUnavailable = stringResource(R.string.toast_follow_target_unavailable)
    val strPositionUnavailable = stringResource(R.string.toast_position_unavailable)
    val strTrackUnavailable = stringResource(R.string.toast_track_unavailable)
    val strInvalidPlaceCoords = stringResource(R.string.toast_invalid_place_coords)
    val strPlaceDeleted = stringResource(R.string.toast_place_deleted)
    val strPlaceAdded = stringResource(R.string.toast_place_added)
    val strPlaceUpdated = stringResource(R.string.toast_place_updated)
    val strPlaceAlertsOn = stringResource(R.string.toast_place_alerts_on)
    val strPlaceAlertsOff = stringResource(R.string.toast_place_alerts_off)
    val strProfileUpdated = stringResource(R.string.toast_profile_updated)
    val strGroupUpdated = stringResource(R.string.toast_group_updated)
    val strGroupDeleted = stringResource(R.string.toast_group_deleted)
    val strSosSent = stringResource(R.string.toast_sos_sent)

    val currentUser by repository.currentUserState.collectAsState()
    val userGroups by repository.userGroupsState.collectAsState()
    val rawLocations by repository.currentGroupLocations.collectAsState()
    val places by repository.currentGroupPlaces.collectAsState()
    val snapshots by repository.currentGroupSnapshots.collectAsState()
    val messages by repository.currentGroupMessages.collectAsState()
    val members by repository.currentGroupMembers.collectAsState()
    val geofenceAlerts by repository.activeGeofenceAlerts.collectAsState()
    val trackingIntervalSec by repository.trackingFrequencySeconds.collectAsState()
    val isTrackingEnabled by repository.isBackgroundTrackingEnabled.collectAsState()
    val isGlobalGhostMode by repository.isGlobalGhostMode.collectAsState()
    val isPowerSavingMode by repository.isPowerSavingMode.collectAsState()
    val isAutoTripEnabled by repository.isAutoTripEnabled.collectAsState()
    val isAutoTripShared by repository.isAutoTripShared.collectAsState()
    val deepLinkTarget by repository.deepLinkTarget.collectAsState()

    // GPS check — mostra dialog se GPS spento e non si è in risparmio batteria
    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    var isGpsEnabled by remember {
        mutableStateOf(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
    }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }
        }
        context.registerReceiver(receiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    var showGpsDialog by remember { mutableStateOf(false) }
    LaunchedEffect(isGpsEnabled, isPowerSavingMode) {
        if (!isGpsEnabled && !isPowerSavingMode) showGpsDialog = true
        else if (isGpsEnabled) showGpsDialog = false
    }

    // Il nome da mostrare e' quello scelto PER QUESTO GRUPPO, non quello
    // dell'account. updateLocation scrive user.displayName — cioe' il nome
    // dell'account Google — dentro il documento della posizione, mentre il nome
    // di gruppo e la sua foto vivono in members/{uid} e li cambia
    // updateGroupMemberProfile. Sulla mappa e nel carosello si leggeva quindi
    // "Giuseppe" anche dopo averlo rinominato "Giuseppe tablet".
    // Si innesta qui, una volta sola: cosi' la correzione vale per la mappa, il
    // carosello e ogni altro consumatore, e ha effetto subito invece di
    // aspettare il prossimo fix del membro.
    val locations = remember(rawLocations, members) {
        rawLocations
            .filter { loc ->
                members.any {
                    it.userId == loc.userId &&
                    !it.status.equals("PENDING", ignoreCase = true) &&
                    !it.status.equals("REJECTED", ignoreCase = true)
                }
            }
            .map { loc ->
                val member = members.find { it.userId == loc.userId } ?: return@map loc
                loc.copy(
                    userName = member.displayName.ifBlank { loc.userName },
                    nickname = member.nickname?.ifBlank { null } ?: loc.nickname,
                    photoBase64 = member.photoBase64?.ifBlank { null } ?: loc.photoBase64
                )
            }
    }

    val currentGroup = userGroups.find { it.id == currentUser?.currentGroupId } ?: userGroups.firstOrNull()
    val currentUserId = currentUser?.uid ?: ""

    // Autoripristino del caricamento infinito. All'avvio (soprattutto dopo un
    // aggiornamento in-app) puo' capitare una race per cui i listener del gruppo
    // non si agganciano: currentGroup e' risolto ma i membri restano vuoti per
    // sempre e i pannelli mostrano lo skeleton all'infinito. Ogni gruppo ha almeno
    // il proprietario tra i membri, quindi una lista vuota dopo qualche secondo
    // significa listener non attivi: si riaggancia esplicitamente selectGroup, che
    // se i listener ci sono gia' con dati e' comunque un no-op (guardia interna).
    LaunchedEffect(currentGroup?.id) {
        val gid = currentGroup?.id ?: return@LaunchedEffect
        delay(3000)
        if (repository.currentGroupMembers.value.isEmpty()) {
            repository.selectGroup(gid)
        }
    }
    val isOwnerOrAdmin = currentGroup?.ownerId == currentUserId ||
        members.find { it.userId == currentUserId }?.role in listOf("owner", "admin")
    val pendingMembers = remember(members) {
        members.filter { it.status.equals("PENDING", ignoreCase = true) }
    }
    // Attivo = "non in attesa e non rifiutato". Filtrare per status == "ACTIVE"
    // esatto nascondeva membri con status mancante o legacy (es. maiuscole/minuscole
    // diverse), facendo comparire "1 membro" in gruppi che ne hanno due.
    val activeMembers = remember(members) {
        members.filterNot {
            it.status.equals("PENDING", ignoreCase = true) ||
                it.status.equals("REJECTED", ignoreCase = true)
        }
    }

    val isSheetExpanded = false
    var panel by remember { mutableStateOf(RadarPanel.MEMBERS) }
    var activeFullPanel by remember { mutableStateOf<RadarPanel?>(null) }

    fun openPanel(target: RadarPanel) {
        panel = target
        activeFullPanel = target
    }

    fun collapseSheet() {
        activeFullPanel = null
    }

    // --- Stato UI locale ---
    var selectedMemberForSheet by remember { mutableStateOf<UserLocation?>(null) }
    var selectedPlaceForSheet by remember { mutableStateOf<SavedPlace?>(null) }
    // Non null mentre il dialog e' aperto in modifica su quel luogo.
    var placeToEdit by remember { mutableStateOf<SavedPlace?>(null) }
    var showAddPlaceDialog by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showEditGroupDialog by remember { mutableStateOf(false) }
    var memberToKick by remember { mutableStateOf<GroupMember?>(null) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showDeleteGroupDialog by remember { mutableStateOf(false) }
    var showSosConfirmDialog by remember { mutableStateOf(false) }
    var targetMapFocus by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // Il token forza il ri-centraggio anche quando le coordinate non cambiano.
    var focusToken by remember { mutableIntStateOf(0) }
    var currentMapCenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var fullScreenImageSource by remember { mutableStateOf<Any?>(null) }
    var selectedSnapshotClusterForGallery by remember { mutableStateOf<PlaceSnapshotCluster?>(null) }
    var capturedSnapshotUri by remember { mutableStateOf<Uri?>(null) }
    var capturedSnapshotBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var pendingMapCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showSnapshotSourceDialog by remember { mutableStateOf(false) }
    var pendingMapCameraAction by remember { mutableStateOf(false) }
    var isSimulationRunning by remember { mutableStateOf(false) }

    // --- Follow Mode ---
    // followedUserId != null significa inseguimento attivo. Il bersaglio e' se
    // stessi finche' non si tocca un membro nel carosello.
    var followedUserId by remember { mutableStateOf<String?>(null) }
    var focusTargetUserId by remember { mutableStateOf<String?>(null) }

    val followedLocation = followedUserId?.let { id -> locations.find { it.userId == id } }
    val followedMember = followedUserId?.let { id -> members.find { it.userId == id } }
    val followPoint = followedLocation?.let { Pair(it.latitude, it.longitude) }

    val unreadChatCount by repository.unreadChatCount.collectAsState()
    val groupTrips by repository.groupTrips.collectAsState()
    val activeTrip by repository.activeTrip.collectAsState()
    var selectedTripId by remember { mutableStateOf<String?>(null) }
    var tripForDetail by remember { mutableStateOf<Trip?>(null) }
    // Traccia del viaggio scelto, letta su richiesta: l'elenco porta solo i
    // metadati, i punti si pagano una volta sola quando servono davvero.
    var selectedTripTrack by remember { mutableStateOf<List<TripPoint>>(emptyList()) }
    // Token di inquadratura: riaprendo lo stesso viaggio i punti sono identici,
    // quindi da soli non farebbero riscattare l'effetto sulla mappa.
    var fitTripToken by remember { mutableIntStateOf(0) }

    /** Centra la mappa su un punto e, di norma, chiude il pannello per lasciarla in vista. */
    fun focusMapOn(latitude: Double, longitude: Double, collapse: Boolean = true) {
        targetMapFocus = Pair(latitude, longitude)
        focusToken++
        if (collapse) collapseSheet()
    }

    // --- Acquisizione istantanee geolocalizzate ---
    val takeSnapshotLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { isSuccess ->
        if (isSuccess && pendingMapCameraUri != null) {
            capturedSnapshotUri = pendingMapCameraUri
        } else {
            Toast.makeText(context, strNoPhoto, Toast.LENGTH_SHORT).show()
        }
    }

    val snapshotGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) capturedSnapshotUri = uri }

    val mapCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && pendingMapCameraAction) {
            pendingMapCameraAction = false
            val uri = pendingMapCameraUri ?: ImageUtils.createTempImageUri(context)
            pendingMapCameraUri = uri
            if (uri != null) {
                runCatching { takeSnapshotLauncher.launch(uri) }.onFailure {
                    Toast.makeText(context, strCameraError.format(it.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        } else if (!isGranted) {
            pendingMapCameraAction = false
            Toast.makeText(context, strCameraPermDenied, Toast.LENGTH_SHORT).show()
        }
    }

    fun launchMapCameraSafe() {
        val tempUri = ImageUtils.createTempImageUri(context)
        if (tempUri == null) {
            Toast.makeText(context, strPhotoFileError, Toast.LENGTH_SHORT).show()
            return
        }
        pendingMapCameraUri = tempUri
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            runCatching { takeSnapshotLauncher.launch(tempUri) }.onFailure {
                Toast.makeText(context, strCameraError.format(it.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingMapCameraAction = true
            mapCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // --- Navigazione da notifica ---
    LaunchedEffect(deepLinkTarget) {
        val target = deepLinkTarget ?: return@LaunchedEffect
        if (!target.groupId.isNullOrBlank() && target.groupId != currentGroup?.id) {
            repository.selectGroup(target.groupId)
        }
        when (target.destination.uppercase()) {
            "CHAT" -> openPanel(RadarPanel.CHAT)
            "ALERT" -> openPanel(RadarPanel.MEMBERS)
            "MEMBERS" -> openPanel(RadarPanel.MEMBERS)
            "SETTINGS" -> openPanel(RadarPanel.SETTINGS)
            "MAP" -> {
                activeFullPanel = null
                if (target.latitude != null && target.longitude != null &&
                    !target.latitude.isNaN() && !target.longitude.isNaN()
                ) {
                    focusMapOn(target.latitude, target.longitude, collapse = false)
                }
            }
        }
        repository.consumeDeepLinkTarget()
    }

    // Aprire la chat equivale a leggerla: azzera badge e notifiche in status bar.
    LaunchedEffect(activeFullPanel, currentGroup?.id, messages.size) {
        val gid = currentGroup?.id
        if (activeFullPanel == RadarPanel.CHAT && !gid.isNullOrBlank()) {
            repository.markChatRead(gid)
        }
    }

    // --- Servizio di tracciamento ---
    LaunchedEffect(isTrackingEnabled, trackingIntervalSec) {
        if (isTrackingEnabled) {
            LocationTrackingService.start(context, trackingIntervalSec)
        } else {
            LocationTrackingService.stop(context)
        }
    }

    // --- Simulazione movimento (utile su emulatore) ---
    LaunchedEffect(isSimulationRunning) {
        while (isSimulationRunning) {
            delay(4000)
            locations.forEach { loc ->
                if (loc.userId != currentUser?.uid) {
                    repository.updateLocation(
                        loc.copy(
                            latitude = loc.latitude + (Random.nextDouble() - 0.5) * 0.0008,
                            longitude = loc.longitude + (Random.nextDouble() - 0.5) * 0.0008,
                            speed = (Random.nextFloat() * 10f) + 2f,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val sheetContentHeight = screenHeight * 0.86f

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        OsmMapView(
            locations = locations,
            places = places,
            snapshots = snapshots,
            trips = remember(groupTrips, selectedTripId, selectedTripTrack) {
                groupTrips.map {
                    if (it.id == selectedTripId && selectedTripTrack.isNotEmpty())
                        it.copy(points = selectedTripTrack) else it
                }
            },
            followedUserId = followedUserId,
            activeTripPoints = activeTrip?.points ?: emptyList(),
            selectedTripId = selectedTripId,
            fitSelectedTripToken = fitTripToken,
            currentUserId = currentUserId,
            targetFocusPoint = targetMapFocus,
            focusToken = focusToken,
            followPoint = followPoint,
            onMapTap = { activeFullPanel = null },
            onUserPan = {
                if (followedUserId != null) followedUserId = null
            },
            onMemberSelected = { selectedMemberForSheet = it },
            onPlaceSelected = { selectedPlaceForSheet = it },
            onSnapshotClusterSelected = { selectedSnapshotClusterForGallery = it },
            onMapCenterChanged = { center -> currentMapCenter = center },
            modifier = Modifier.fillMaxSize()
        )

        // Sfumatura in alto
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            RadarTheme.palette.gradients.mapScrimTop,
                            RadarTheme.palette.gradients.mapScrimBottom
                        )
                    )
                )
        )

        // Header in alto
        MapTopBar(
            groupName = currentGroup?.name ?: stringResource(R.string.label_radar_fallback),
            joinCode = currentGroup?.joinCode,
            memberCount = activeMembers.size,
            onlineCount = locations.count { loc ->
                System.currentTimeMillis() - loc.timestamp < PRESENCE_ONLINE_MS &&
                    activeMembers.any { it.userId == loc.userId }
            },
            onSwitchGroup = onSwitchGroup,
            onOpenSettings = { openPanel(RadarPanel.SETTINGS) },
            onSos = { showSosConfirmDialog = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
        )

        // Controlli Mappa: Posizionati AL CENTRO A SINISTRA (CenterStart)
        MapActionRail(
            isFollowing = followedUserId != null,
            onToggleFollow = {
                if (followedUserId != null) {
                    followedUserId = null
                    Toast.makeText(context, strFollowOff, Toast.LENGTH_SHORT).show()
                } else {
                    val targetId = focusTargetUserId ?: currentUserId
                    val target = locations.find { it.userId == targetId }
                    if (target != null) {
                        followedUserId = targetId
                        focusMapOn(target.latitude, target.longitude, collapse = false)
                        val label = if (targetId == currentUserId) context.getString(R.string.label_you_follow) else (target.nickname ?: target.userName)
                        Toast.makeText(context, strFollowOn.format(label), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, strFollowTargetUnavailable, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onLocateSelf = {
                val myLoc = locations.find { it.userId == currentUserId }
                if (myLoc != null) {
                    focusTargetUserId = currentUserId
                    focusMapOn(myLoc.latitude, myLoc.longitude, collapse = false)
                } else {
                    Toast.makeText(context, strPositionUnavailable, Toast.LENGTH_SHORT).show()
                }
            },
            isRecording = activeTrip != null,
            onToggleTrip = {
                if (activeTrip != null) {
                    coroutineScope.launch {
                        repository.stopAndSaveTrip()
                        Toast.makeText(context, strTripSaved, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    repository.startTrip()
                }
            },
            onAddPlace = { showAddPlaceDialog = true },
            onTakeSnapshot = { showSnapshotSourceDialog = true },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = Spacing.md)
        )

        // Pill di registrazione viaggio: in basso a destra
        AnimatedVisibility(
            visible = activeTrip != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = Spacing.lg, bottom = 80.dp)
        ) {
            activeTrip?.let { trip ->
                var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(trip.startTime) {
                    while (true) {
                        nowMs = System.currentTimeMillis()
                        delay(1000)
                    }
                }
                val elapsedMs = nowMs - trip.startTime
                val elapsedMin = (elapsedMs / 60000).toInt()
                val elapsedSec = ((elapsedMs / 1000) % 60).toInt()
                val km = trip.distanceMeters / 1000.0

                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = Color(0xCC18181B),
                    border = BorderStroke(1.dp, Color(0x1F71717A)),
                    modifier = Modifier.padding(Spacing.xs)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                    ) {
                        RadarPulseAnimation(
                            color = Color(0xFFF43F5E),
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = "%02d:%02d  •  %.2f km".format(elapsedMin, elapsedSec, km),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Persone Live / Carosello Membri: In Basso a Sinistra (BottomStart) sopra la dock
        AnimatedVisibility(
            visible = activeFullPanel == null && locations.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = Spacing.md, bottom = 80.dp)
        ) {
            MemberCarousel(
                locations = locations,
                currentUserId = currentUserId,
                followedUserId = followedUserId,
                onMemberClick = { loc ->
                    focusTargetUserId = loc.userId
                    focusMapOn(loc.latitude, loc.longitude, collapse = false)
                    if (followedUserId != null) followedUserId = loc.userId
                },
                onMemberLongClick = { selectedMemberForSheet = it }
            )
        }

        // Dock Fluttuante in Basso al Centro (BottomCenter)
        FloatingDock(
            selectedPanel = activeFullPanel,
            chatCount = unreadChatCount,
            pendingCount = if (isOwnerOrAdmin) pendingMembers.size else 0,
            memberCount = activeMembers.size,
            placeCount = places.size,
            tripCount = groupTrips.size,
            onSelectPanel = { target ->
                if (activeFullPanel == target) activeFullPanel = null
                else openPanel(target)
            },
            onTakeSnapshot = { showSnapshotSourceDialog = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = Spacing.xs)
        )

        // Banner dell'inseguimento
        AnimatedVisibility(
            visible = followedUserId != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(
                    start = Spacing.lg,
                    top = if (locations.isNotEmpty()) 148.dp else 76.dp
                )
        ) {
            val bannerName = followedLocation?.let {
                if (!it.nickname.isNullOrBlank()) it.nickname!! else it.userName
            } ?: followedMember?.let {
                if (!it.nickname.isNullOrBlank()) it.nickname!! else it.displayName
            } ?: "…"
            val bannerPhoto = followedLocation?.photoBase64 ?: followedMember?.photoBase64
            GlassSurface(
                shape = RoundedCornerShape(Radius.pill),
                contentPadding = Spacing.xs
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    modifier = Modifier.padding(start = Spacing.xs, end = Spacing.xs)
                ) {
                    RadarAvatar(
                        name = bannerName,
                        photoBase64 = bannerPhoto,
                        size = Sizes.avatarSm,
                        ringColor = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        onClick = { followedUserId = null },
                        shape = RoundedCornerShape(Radius.pill),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("stop_following_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_stop),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(
                                horizontal = Spacing.sm,
                                vertical = Spacing.xs
                            )
                        )
                    }
                }
            }
        }
    }

    // SCHERMATE E PANNELLI FULL-SCREEN SEPARATI (quando activeFullPanel != null)
    activeFullPanel?.let { currentPanel ->
        Dialog(
            onDismissRequest = { activeFullPanel = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    // Intestazione con pulsante di chiusura/indietro
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { activeFullPanel = null }) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = stringResource(R.string.action_close),
                                tint = Color(0xFFF2F2F7)
                            )
                        }
                        Text(
                            text = stringResource(currentPanel.labelRes),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            ),
                            color = Color(0xFFF2F2F7),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        when (currentPanel) {
                            RadarPanel.MEMBERS -> MembersPanel(
                                members = activeMembers,
                                pendingMembers = pendingMembers,
                                locations = locations,
                                currentUserId = currentUserId,
                                isOwnerOrAdmin = isOwnerOrAdmin,
                                isLoading = members.isEmpty() && currentGroup != null,
                                onMemberClick = { loc ->
                                    selectedMemberForSheet = loc
                                    activeFullPanel = null
                                },
                                onFocusMember = { loc ->
                                    focusMapOn(loc.latitude, loc.longitude)
                                    activeFullPanel = null
                                },
                                onKickMember = { memberToKick = it },
                                onApprove = { memberId ->
                                    val gid = currentGroup?.id ?: return@MembersPanel
                                    coroutineScope.launch {
                                        val res = repository.approveJoinRequest(gid, memberId)
                                        Toast.makeText(
                                            context,
                                            if (res.isSuccess) strRequestApproved
                                            else "Errore: ${res.exceptionOrNull()?.message}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onReject = { memberId ->
                                    val gid = currentGroup?.id ?: return@MembersPanel
                                    coroutineScope.launch {
                                        val res = repository.rejectJoinRequest(gid, memberId)
                                        Toast.makeText(
                                            context,
                                            if (res.isSuccess) strRequestRejected
                                            else "Errore: ${res.exceptionOrNull()?.message}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            )

                            RadarPanel.CHAT -> ChatPanel(
                                messages = messages,
                                currentUserId = currentUserId,
                                groupId = currentGroup?.id ?: "",
                                repository = repository,
                                onImageClick = { fullScreenImageSource = it }
                            )

                            RadarPanel.PLACES -> PlacesPanel(
                                places = places,
                                alerts = geofenceAlerts,
                                onPlaceClick = {
                                    selectedPlaceForSheet = it
                                    activeFullPanel = null
                                },
                                onFocusPlace = { place ->
                                    focusMapOn(place.latitude, place.longitude)
                                    activeFullPanel = null
                                },
                                onAddPlaceClick = { showAddPlaceDialog = true },
                                onEditPlace = { placeToEdit = it },
                                onDeletePlace = { placeId ->
                                    coroutineScope.launch { repository.deletePlace(placeId) }
                                }
                            )

                            RadarPanel.TRIPS -> TripsPanel(
                                trips = groupTrips,
                                activeTrip = activeTrip,
                                currentUserId = currentUserId,
                                selectedTripId = selectedTripId,
                                onTripSelected = { tripId ->
                                    tripForDetail = groupTrips.find { it.id == tripId }
                                },
                                onDeleteTrip = { tripId ->
                                    coroutineScope.launch { repository.deleteTrip(tripId) }
                                },
                                onStartTrip = { repository.startTrip() },
                                onStopTrip = {
                                    coroutineScope.launch {
                                        repository.stopAndSaveTrip()
                                        Toast.makeText(context, strTripSaved, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )

                            RadarPanel.SETTINGS -> SettingsPanel(
                                currentUser = currentUser,
                                currentGroup = currentGroup,
                                currentUserId = currentUserId,
                                myMember = members.find { it.userId == currentUserId },
                                isOwnerOrAdmin = isOwnerOrAdmin,
                                activeMemberCount = activeMembers.size,
                                pendingMemberCount = pendingMembers.size,
                                trackingIntervalSec = trackingIntervalSec,
                                isTrackingEnabled = isTrackingEnabled,
                                isGlobalGhostMode = isGlobalGhostMode,
                                isPowerSavingMode = isPowerSavingMode,
                                isAutoTripEnabled = isAutoTripEnabled,
                                isAutoTripShared = isAutoTripShared,
                                isSimulationRunning = isSimulationRunning,
                                onEditProfileClick = { showEditProfileDialog = true },
                                onEditGroupClick = { showEditGroupDialog = true },
                                onSwitchGroup = onSwitchGroup,
                                onUpdateInterval = { sec ->
                                    repository.setTrackingFrequencySeconds(sec)
                                },
                                onTogglePowerSaving = { enabled ->
                                    repository.setPowerSavingMode(enabled)
                                    Toast.makeText(
                                        context,
                                        if (enabled) strBatterySaverOn else strGpsPrecisionRestored,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onToggleAutoTrip = { enabled ->
                                    repository.setAutoTripEnabled(enabled)
                                    Toast.makeText(
                                        context,
                                        if (enabled) strAutoTripOn else strAutoTripOff,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onToggleAutoTripShared = { repository.setAutoTripShared(it) },
                                onToggleTracking = { enabled ->
                                    repository.setBackgroundTrackingEnabled(enabled)
                                    Toast.makeText(
                                        context,
                                        if (enabled) strBgTrackingOn else strBgTrackingOff,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onToggleGlobalGhostMode = { enabled ->
                                    repository.setGlobalGhostMode(enabled)
                                    Toast.makeText(
                                        context,
                                        if (enabled) strGhostOn else strGhostOff,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onToggleGroupTracking = { enabled ->
                                    val gid = currentGroup?.id ?: return@SettingsPanel
                                    coroutineScope.launch {
                                        repository.updateMemberGroupTracking(gid, enabled)
                                    }
                                },
                                onToggleAccessPolicy = { requiresApproval ->
                                    val gid = currentGroup?.id ?: return@SettingsPanel
                                    coroutineScope.launch {
                                        repository.updateGroupAccessPolicy(gid, requiresApproval)
                                    }
                                },
                                onToggleSimulation = { isSimulationRunning = it },
                                onRequestLeaveGroup = { showLeaveDialog = true },
                                onRequestDeleteGroup = { showDeleteGroupDialog = true },
                                onLogout = {
                                    LocationTrackingService.stop(context)
                                    repository.signOut()
                                },
                                onSendFeedback = { text -> repository.sendFeedback(text) },
                                onFetchFeedback = { repository.fetchFeedback() },
                                onUpdateFeedbackStatus = { id, status -> repository.updateFeedbackStatus(id, status) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ======================= OVERLAY: FOGLI E DIALOG =======================

    selectedMemberForSheet?.let { loc ->
        MemberDetailSheet(
            location = loc,
            isSelf = loc.userId == currentUserId,
            onDismiss = { selectedMemberForSheet = null },
            onNavigateToChat = {
                selectedMemberForSheet = null
                openPanel(RadarPanel.CHAT)
            },
            onEditProfileClick = {
                selectedMemberForSheet = null
                showEditProfileDialog = true
            }
        )
    }

    selectedPlaceForSheet?.let { place ->
        PlaceDetailSheet(
            place = place,
            onDismiss = { selectedPlaceForSheet = null },
            onShowOnMap = {
                if (it.latitude != 0.0 && it.longitude != 0.0 && !it.latitude.isNaN() && !it.longitude.isNaN()) {
                    focusMapOn(it.latitude, it.longitude)
                } else {
                    Toast.makeText(context, strInvalidPlaceCoords, Toast.LENGTH_SHORT).show()
                }
            },
            onDeletePlace = { toDelete ->
                coroutineScope.launch {
                    repository.deletePlace(toDelete.id)
                    Toast.makeText(context, strPlaceDeleted.format(toDelete.name), Toast.LENGTH_SHORT).show()
                }
            },
            onEditPlace = { toEdit ->
                selectedPlaceForSheet = null
                placeToEdit = toEdit
            },
            onToggleGeofence = { target, enabled ->
                coroutineScope.launch {
                    val res = repository.updatePlace(target.copy(geofenceEnabled = enabled))
                    if (res.isSuccess) {
                        // Il foglio mostra la copia che gli e' stata passata: senza
                        // questo aggiornamento l'interruttore tornerebbe indietro.
                        selectedPlaceForSheet = res.getOrNull()
                        Toast.makeText(
                            context,
                            if (enabled) strPlaceAlertsOn.format(target.name)
                            else strPlaceAlertsOff.format(target.name),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Errore: ${res.exceptionOrNull()?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    placeToEdit?.let { editing ->
        AddPlaceDialog(
            initialLat = editing.latitude,
            initialLon = editing.longitude,
            existingPlace = editing,
            onDismiss = { placeToEdit = null },
            onPlaceAdded = { updated ->
                placeToEdit = null
                coroutineScope.launch {
                    val res = repository.updatePlace(updated)
                    Toast.makeText(
                        context,
                        if (res.isSuccess) strPlaceUpdated.format(updated.name)
                        else "Errore salvataggio: ${res.exceptionOrNull()?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    if (showEditProfileDialog && currentGroup != null && currentUser != null) {
        val myMember = members.find { it.userId == currentUserId } ?: GroupMember(
            userId = currentUserId,
            displayName = currentUser?.displayName ?: stringResource(R.string.label_user_name_fallback),
            role = "member"
        )
        EditGroupProfileDialog(
            currentMember = myMember,
            onDismiss = { showEditProfileDialog = false },
            onSaveProfile = { newDisplayName, newNickname, newPhotoBase64 ->
                coroutineScope.launch {
                    val res = repository.updateGroupMemberProfile(
                        groupId = currentGroup.id,
                        memberId = myMember.userId,
                        displayName = newDisplayName,
                        nickname = newNickname,
                        photoBase64 = newPhotoBase64
                    )
                    showEditProfileDialog = false
                    Toast.makeText(
                        context,
                        if (res.isSuccess) strProfileUpdated
                        else "Errore salvataggio: ${res.exceptionOrNull()?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    if (showEditGroupDialog && currentGroup != null) {
        EditGroupDialog(
            group = currentGroup,
            onDismiss = { showEditGroupDialog = false },
            onSave = { newName, newDescription, newPhotoBase64, newIsPublic ->
                coroutineScope.launch {
                    val res = repository.updateGroupInfo(
                        groupId = currentGroup.id,
                        name = newName,
                        description = newDescription,
                        photoBase64 = newPhotoBase64,
                        isPublic = newIsPublic
                    )
                    showEditGroupDialog = false
                    Toast.makeText(
                        context,
                        if (res.isSuccess) strGroupUpdated
                        else "Errore salvataggio: ${res.exceptionOrNull()?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    tripForDetail?.let { trip ->
        TripDetailDialog(
            trip = trip,
            isOnMap = selectedTripId == trip.id,
            onDismiss = { tripForDetail = null },
            onHideFromMap = {
                tripForDetail = null
                selectedTripId = null
                selectedTripTrack = emptyList()
            },
            onShowOnMap = {
                tripForDetail = null
                coroutineScope.launch {
                    // Un viaggio in corso porta gia' i punti con se': quelli
                    // conclusi hanno la traccia nel sottodocumento.
                    val track = if (trip.isLive) trip.points
                        else repository.loadTripTrack(trip.id)

                    if (track.isEmpty()) {
                        Toast.makeText(context, strTrackUnavailable, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    selectedTripTrack = track
                    selectedTripId = trip.id
                    collapseSheet()
                    // Inquadra l'INTERA traccia. Centrare sulla partenza a zoom
                    // fisso mostrava solo l'inizio del percorso.
                    fitTripToken++
                }
            }
        )
    }

    if (showAddPlaceDialog) {
        val myLoc = locations.find { it.userId == currentUserId } ?: locations.firstOrNull()
        val initialLat = currentMapCenter?.first ?: myLoc?.latitude ?: 41.9028
        val initialLon = currentMapCenter?.second ?: myLoc?.longitude ?: 12.4964
        AddPlaceDialog(
            initialLat = initialLat,
            initialLon = initialLon,
            onDismiss = { showAddPlaceDialog = false },
            onPlaceAdded = { place ->
                showAddPlaceDialog = false
                coroutineScope.launch {
                    repository.addPlace(place)
                    Toast.makeText(context, strPlaceAdded.format(place.name), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showSosConfirmDialog) {
        val sosGroupName = currentGroup?.name ?: stringResource(R.string.label_radar_fallback)
        ConfirmDialog(
            icon = Icons.Default.CrisisAlert,
            iconTint = RadarSemantic.Sos,
            title = stringResource(R.string.dialog_sos_title),
            message = stringResource(R.string.dialog_sos_body, sosGroupName),
            confirmLabel = stringResource(R.string.action_send_sos),
            onConfirm = {
                showSosConfirmDialog = false
                val gid = currentGroup?.id
                if (!gid.isNullOrBlank()) {
                    repository.sendSosAlert(gid)
                    Toast.makeText(context, strSosSent, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showSosConfirmDialog = false }
        )
    }

    memberToKick?.let { target ->
        ConfirmDialog(
            icon = Icons.Default.PersonRemove,
            iconTint = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.dialog_kick_title),
            message = stringResource(R.string.dialog_kick_body, target.displayName),
            confirmLabel = stringResource(R.string.action_kick),
            onConfirm = {
                memberToKick = null
                if (currentGroup != null) {
                    coroutineScope.launch {
                        repository.removeMemberFromGroup(currentGroup.id, target.userId)
                    }
                }
            },
            onDismiss = { memberToKick = null }
        )
    }

    if (showDeleteGroupDialog && currentGroup != null) {
        ConfirmDialog(
            icon = Icons.Default.DeleteForever,
            iconTint = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.dialog_delete_group_title),
            message = stringResource(R.string.dialog_delete_group_body, currentGroup.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                showDeleteGroupDialog = false
                coroutineScope.launch {
                    val res = repository.deleteGroup(currentGroup.id)
                    Toast.makeText(
                        context,
                        if (res.isSuccess) strGroupDeleted
                        else "Errore: ${res.exceptionOrNull()?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (res.isSuccess) onSwitchGroup()
                }
            },
            onDismiss = { showDeleteGroupDialog = false }
        )
    }

    if (showLeaveDialog && currentGroup != null) {
        ConfirmDialog(
            icon = Icons.Default.ExitToApp,
            iconTint = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.dialog_leave_title),
            message = stringResource(R.string.dialog_leave_body, currentGroup.name),
            confirmLabel = stringResource(R.string.action_leave),
            onConfirm = {
                showLeaveDialog = false
                coroutineScope.launch {
                    repository.leaveGroup(currentGroup.id)
                    onSwitchGroup()
                }
            },
            onDismiss = { showLeaveDialog = false }
        )
    }

    if (showSnapshotSourceDialog) {
        SnapshotSourceDialog(
            onCamera = {
                showSnapshotSourceDialog = false
                launchMapCameraSafe()
            },
            onGallery = {
                showSnapshotSourceDialog = false
                snapshotGalleryLauncher.launch("image/*")
            },
            onDismiss = { showSnapshotSourceDialog = false }
        )
    }

    fullScreenImageSource?.let { source ->
        FullScreenMediaViewer(
            imageSource = source,
            onDismiss = { fullScreenImageSource = null }
        )
    }

    selectedSnapshotClusterForGallery?.let { cluster ->
        SnapshotClusterGalleryDialog(
            snapshots = cluster.snapshots,
            currentUserId = currentUserId,
            onDelete = { snapshot ->
                coroutineScope.launch { repository.deletePlaceSnapshot(snapshot.id) }
            },
            onDismiss = { selectedSnapshotClusterForGallery = null }
        )
    }

    if (capturedSnapshotUri != null || capturedSnapshotBitmap != null) {
        val myLoc = locations.find { it.userId == currentUserId } ?: locations.firstOrNull()
        AddPlaceSnapshotDialog(
            imageUri = capturedSnapshotUri,
            bitmap = capturedSnapshotBitmap,
            latitude = myLoc?.latitude ?: 41.9028,
            longitude = myLoc?.longitude ?: 12.4964,
            repository = repository,
            onDismiss = {
                capturedSnapshotUri = null
                capturedSnapshotBitmap = null
            },
            onPublished = {
                capturedSnapshotUri = null
                capturedSnapshotBitmap = null
            }
        )
    }

    // Mostra overlay di caricamento mentre i dati del gruppo si caricano
    val isGroupDataLoading = currentGroup != null && currentUserId.isNotBlank() && members.isEmpty()
    if (isGroupDataLoading) {
        GroupLoadingOverlay()
    }

    if (showGpsDialog) {
        Dialog(onDismissRequest = { showGpsDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.xl),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(Sizes.avatarLg).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.LocationOff, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(Sizes.iconLg))
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Text(stringResource(R.string.dialog_gps_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        stringResource(R.string.dialog_gps_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    Button(
                        onClick = {
                            showGpsDialog = false
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.sm)
                    ) { Text(stringResource(R.string.action_enable_gps)) }
                    Spacer(Modifier.height(Spacing.sm))
                    TextButton(
                        onClick = { showGpsDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.action_not_now)) }
                }
            }
        }
    }
}

// ============================================================================
// SOVRAPPOSIZIONI SULLA MAPPA
// ============================================================================

@Composable
private fun MapTopBar(
    groupName: String,
    joinCode: String?,
    memberCount: Int,
    onlineCount: Int,
    onSwitchGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    onSos: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0x0A71717A),
        border = BorderStroke(1.dp, Color(0x1F71717A))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm)
        ) {
            RadarPulseCompact(modifier = Modifier.size(Sizes.avatarSm))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    ),
                    color = Color(0xFFF2F2F7),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34D399))
                    )
                    val subtitle = stringResource(R.string.map_topbar_subtitle, onlineCount, memberCount)
                    Text(
                        text = if (!joinCode.isNullOrBlank()) "$subtitle · $joinCode" else subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFA1A1AA),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onSwitchGroup,
                modifier = Modifier.testTag("switch_group_button")
            ) {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = stringResource(R.string.action_change_group),
                    tint = Color(0xFFF2F2F7)
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("open_settings_button")
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.tab_settings),
                    tint = Color(0xFFF2F2F7)
                )
            }

            // Pulsante SOS corallo con bagliore soffuso
            Surface(
                onClick = onSos,
                shape = CircleShape,
                color = Color(0x26F43F5E),
                border = BorderStroke(1.dp, Color(0x66F43F5E)),
                modifier = Modifier
                    .size(Sizes.fab)
                    .testTag("sos_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = stringResource(R.string.action_send_sos),
                        tint = Color(0xFFF43F5E),
                        modifier = Modifier.size(Sizes.iconLg)
                    )
                }
            }
        }
    }
}

@Composable
private fun MapActionRail(
    isFollowing: Boolean,
    onToggleFollow: () -> Unit,
    onLocateSelf: () -> Unit,
    isRecording: Boolean,
    onToggleTrip: () -> Unit,
    onAddPlace: () -> Unit,
    onTakeSnapshot: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        horizontalAlignment = Alignment.Start
    ) {
        RailButton(
            icon = if (isFollowing) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
            contentDescription = if (isFollowing) stringResource(R.string.action_follow_off) else stringResource(R.string.action_follow_on_label),
            onClick = onToggleFollow,
            container = if (isFollowing) Color(0xFF6366F1) else Color(0xCC18181B),
            content = if (isFollowing) Color.White else Color(0xFFF2F2F7),
            testTag = "follow_mode_fab"
        )
        RailButton(
            icon = Icons.Default.MyLocation,
            contentDescription = stringResource(R.string.action_center_my_location),
            onClick = onLocateSelf,
            testTag = "locate_self_fab"
        )
        RailButton(
            icon = if (isRecording) Icons.Default.Stop else Icons.Default.DirectionsCar,
            contentDescription = if (isRecording) stringResource(R.string.action_stop_trip) else stringResource(R.string.action_record_trip),
            onClick = onToggleTrip,
            container = if (isRecording) Color(0xFFF43F5E) else Color(0xCC18181B),
            content = Color.White,
            testTag = "trip_record_fab"
        )
        RailButton(
            icon = Icons.Default.AddLocationAlt,
            contentDescription = stringResource(R.string.action_add_place),
            onClick = onAddPlace,
            testTag = "add_place_fab"
        )
        RailButton(
            icon = Icons.Default.AddAPhoto,
            contentDescription = stringResource(R.string.action_take_snapshot),
            onClick = onTakeSnapshot,
            container = Color(0xFF6366F1),
            content = Color.White,
            testTag = "take_geo_snapshot_fab"
        )
    }
}

@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Color(0xCC18181B),
    content: Color = Color(0xFFF2F2F7),
    testTag: String? = null
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        border = BorderStroke(1.dp, Color(0x1F71717A)),
        modifier = modifier
            .size(48.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = content,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun MemberCarousel(
    locations: List<UserLocation>,
    currentUserId: String,
    followedUserId: String?,
    onMemberClick: (UserLocation) -> Unit,
    onMemberLongClick: (UserLocation) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        items(locations, key = { it.userId }) { loc ->
            val isSelf = loc.userId == currentUserId
            val isFollowed = loc.userId == followedUserId || (followedUserId == null && isSelf)
            val name = if (!loc.nickname.isNullOrBlank()) loc.nickname!! else loc.userName
            val isOnline = System.currentTimeMillis() - loc.timestamp < PRESENCE_ONLINE_MS

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.combinedClickable(
                    onClick = { onMemberClick(loc) },
                    onLongClick = { onMemberLongClick(loc) }
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    RadarAvatar(
                        name = loc.userName,
                        photoBase64 = loc.photoBase64,
                        size = if (isFollowed) 54.dp else 44.dp,
                        ringColor = if (isFollowed) Color(0xFF34D399) else Color(0x3371717A),
                        containerColor = Color(0xFF27272A),
                        contentColor = Color.White
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(13.dp)
                            .clip(CircleShape)
                            .background(Color.Black)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) Color(0xFF34D399) else Color(0xFFF43F5E))
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Testo e informazioni fluttuanti direttamente sullo sfondo della mappa (senza riquadro solido)
                Text(
                    text = if (isSelf) stringResource(R.string.label_you) else name,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isFollowed) FontWeight.Bold else FontWeight.Medium,
                        shadow = Shadow(
                            color = Color.Black,
                            offset = Offset(0f, 2f),
                            blurRadius = 8f
                        )
                    ),
                    color = Color(0xFFF2F2F7),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (isFollowed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isOnline) Color(0xFF34D399) else Color(0xFFF43F5E))
                        )
                        Text(
                            text = if (isOnline) "Online" else "Offline",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                shadow = Shadow(
                                    color = Color.Black,
                                    offset = Offset(0f, 2f),
                                    blurRadius = 8f
                                )
                            ),
                            color = if (isOnline) Color(0xFF34D399) else Color(0xFFF43F5E)
                        )
                        Text(
                            text = "· Batteria: ${loc.batteryLevel}%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                shadow = Shadow(
                                    color = Color.Black,
                                    offset = Offset(0f, 2f),
                                    blurRadius = 8f
                                )
                            ),
                            color = Color(0xFFF2F2F7)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// BARRA DI NAVIGAZIONE INFERIORE (FLOATING DOCK CON TASTO FOTOCAMERA AMBRA)
// ============================================================================

@Composable
private fun FloatingDock(
    selectedPanel: RadarPanel?,
    chatCount: Int,
    pendingCount: Int,
    memberCount: Int,
    placeCount: Int,
    tripCount: Int,
    onSelectPanel: (RadarPanel) -> Unit,
    onTakeSnapshot: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xEE121216),
        border = BorderStroke(1.dp, Color(0x1F71717A)),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = { onSelectPanel(RadarPanel.MEMBERS) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = "Home",
                    tint = if (selectedPanel == RadarPanel.MEMBERS) Color(0xFF6366F1) else Color(0xFFF2F2F7)
                )
            }

            IconButton(
                onClick = { onSelectPanel(RadarPanel.PLACES) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = "Places",
                    tint = if (selectedPanel == RadarPanel.PLACES) Color(0xFF6366F1) else Color(0xFFA1A1AA)
                )
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                IconButton(onClick = { onSelectPanel(RadarPanel.CHAT) }) {
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        contentDescription = "Chat",
                        tint = if (selectedPanel == RadarPanel.CHAT) Color(0xFF6366F1) else Color(0xFFA1A1AA)
                    )
                }
                if (chatCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-8).dp, y = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF43F5E))
                    )
                }
            }

            IconButton(
                onClick = { onSelectPanel(RadarPanel.TRIPS) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Route,
                    contentDescription = "Trips",
                    tint = if (selectedPanel == RadarPanel.TRIPS) Color(0xFF6366F1) else Color(0xFFA1A1AA)
                )
            }
        }
    }
}

// ============================================================================
// PANNELLO: MEMBRI
// ============================================================================

@Composable
private fun MembersPanel(
    members: List<GroupMember>,
    pendingMembers: List<GroupMember>,
    locations: List<UserLocation>,
    currentUserId: String,
    isOwnerOrAdmin: Boolean,
    isLoading: Boolean,
    onMemberClick: (UserLocation) -> Unit,
    onFocusMember: (UserLocation) -> Unit,
    onKickMember: (GroupMember) -> Unit,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    if (isLoading) {
        MemberListSkeleton(modifier = Modifier.padding(Spacing.lg))
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            top = Spacing.sm,
            bottom = Spacing.xxxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (isOwnerOrAdmin && pendingMembers.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(R.string.status_awaiting_approval),
                    subtitle = stringResource(R.string.pending_members_desc),
                    icon = Icons.Default.PendingActions
                )
            }
            items(pendingMembers, key = { "pending_${it.userId}" }) { pending ->
                PendingMemberRow(
                    member = pending,
                    onApprove = { onApprove(pending.userId) },
                    onReject = { onReject(pending.userId) }
                )
            }
            item { Spacer(Modifier.height(Spacing.sm)) }
        }

        if (members.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.empty_members_title),
                    description = stringResource(R.string.empty_members_body),
                    icon = Icons.Default.GroupAdd,
                    lottieAsset = "empty_members"
                )
            }
        } else {
            items(members, key = { it.userId }) { member ->
                val loc = locations.find { it.userId == member.userId }
                MemberRow(
                    member = member,
                    location = loc,
                    isSelf = member.userId == currentUserId,
                    canKick = isOwnerOrAdmin && member.userId != currentUserId,
                    onClick = { loc?.let(onMemberClick) },
                    onFocus = { loc?.let(onFocusMember) },
                    onKick = { onKickMember(member) }
                )
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: GroupMember,
    location: UserLocation?,
    isSelf: Boolean,
    canKick: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    onKick: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        onClick = onClick,
        enabled = location != null,
        shape = RoundedCornerShape(16.dp),
        color = Color(0x0A71717A),
        border = BorderStroke(
            if (isSelf) 1.5.dp else 1.dp,
            if (isSelf) Color(0xFF6366F1) else Color(0x1F71717A)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Box {
                RadarAvatar(
                    name = member.displayName,
                    photoBase64 = member.photoBase64,
                    size = Sizes.avatarMd,
                    containerColor = if (isSelf) Color(0xFF6366F1) else Color(0xFF27272A),
                    contentColor = Color.White
                )
                if (location != null) {
                    PresenceDot(
                        lastSeenMillis = location.timestamp,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = if (isSelf) stringResource(R.string.member_name_self, member.displayName) else member.displayName,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        color = Color(0xFFF2F2F7),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    when (member.role) {
                        "owner" -> RadarBadge(
                            text = stringResource(R.string.role_owner),
                            containerColor = Color(0x266366F1),
                            contentColor = Color(0xFF6366F1)
                        )
                        "admin" -> RadarBadge(
                            text = stringResource(R.string.role_admin),
                            containerColor = Color(0x2634D399),
                            contentColor = Color(0xFF34D399)
                        )
                    }
                }

                val subtitle = buildString {
                    if (!member.nickname.isNullOrBlank()) append("${member.nickname} · ")
                    if (location != null) {
                        append(location.currentPlaceName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.status_moving))
                        append(" · ${formatShortTime(location.timestamp, context)}")
                    } else {
                        append(context.getString(R.string.status_not_sharing))
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFA1A1AA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (location != null) {
                    Spacer(Modifier.height(Spacing.xxs))
                    BatteryBadge(level = location.batteryLevel, isCharging = location.isCharging)
                }
            }

            if (location != null) {
                IconButton(onClick = onFocus) {
                    Icon(
                        Icons.Default.NearMe,
                        contentDescription = stringResource(R.string.action_show_on_map),
                        tint = Color(0xFF6366F1),
                        modifier = Modifier.size(Sizes.iconMd)
                    )
                }
            }
            if (canKick) {
                IconButton(onClick = onKick) {
                    Icon(
                        Icons.Default.PersonRemove,
                        contentDescription = stringResource(R.string.action_remove_member),
                        tint = Color(0xFFF43F5E),
                        modifier = Modifier.size(Sizes.iconMd)
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingMemberRow(
    member: GroupMember,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            RadarAvatar(
                name = member.displayName,
                photoBase64 = member.photoBase64,
                size = Sizes.avatarMd,
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.label_access_request),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalIconButton(
                onClick = onApprove,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_approve))
            }
            FilledTonalIconButton(
                onClick = onReject,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_reject))
            }
        }
    }
}

// ============================================================================
// PANNELLO: CHAT
// ============================================================================

@Composable
private fun ChatPanel(
    messages: List<ChatMessage>,
    currentUserId: String,
    groupId: String,
    repository: FirebaseRepository,
    onImageClick: (Any) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val strNoPhoto = stringResource(R.string.toast_no_photo)
    val strCameraError = stringResource(R.string.toast_camera_error)
    val strCameraPermNeeded = stringResource(R.string.toast_camera_permission_needed)
    val strPhotoFileError = stringResource(R.string.toast_photo_file_error)

    var inputText by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var pendingChatCamera by remember { mutableStateOf(false) }
    var pendingChatCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun sendImage(uri: Uri, caption: String) {
        if (groupId.isBlank()) return
        isUploading = true
        coroutineScope.launch {
            val res = repository.compressImageToBase64(uri, maxDimension = 1280, quality = 85)
            isUploading = false
            val base64 = res.getOrNull()
            if (res.isSuccess && !base64.isNullOrBlank()) {
                repository.sendMessage(
                    groupId,
                    ChatMessage(text = caption, imageBase64 = base64, type = MessageType.IMAGE)
                )
            } else {
                Toast.makeText(context, "Errore elaborazione immagine", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val cameraPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { isSuccess ->
        val uri = pendingChatCameraUri
        if (isSuccess && uri != null) {
            sendImage(uri, "Foto scattata in chat")
        } else {
            Toast.makeText(context, strNoPhoto, Toast.LENGTH_SHORT).show()
        }
    }

    val chatCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && pendingChatCamera) {
            pendingChatCamera = false
            val uri = pendingChatCameraUri ?: ImageUtils.createTempImageUri(context)
            pendingChatCameraUri = uri
            if (uri != null) {
                runCatching { cameraPhotoLauncher.launch(uri) }.onFailure {
                    Toast.makeText(context, strCameraError.format(it.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        } else if (!isGranted) {
            pendingChatCamera = false
            Toast.makeText(context, strCameraPermNeeded, Toast.LENGTH_SHORT).show()
        }
    }

    fun launchChatCameraSafe() {
        val tempUri = ImageUtils.createTempImageUri(context)
        if (tempUri == null) {
            Toast.makeText(context, strPhotoFileError, Toast.LENGTH_SHORT).show()
            return
        }
        pendingChatCameraUri = tempUri
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            runCatching { cameraPhotoLauncher.launch(tempUri) }.onFailure {
                Toast.makeText(context, strCameraError.format(it.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingChatCamera = true
            chatCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) sendImage(uri, "Immagine condivisa") }

    fun sendText() {
        val trimmed = inputText.trim()
        if (trimmed.isNotBlank() && groupId.isNotBlank()) {
            repository.sendMessage(groupId, ChatMessage(text = trimmed, type = MessageType.TEXT))
            inputText = ""
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.size - 1) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                })
            }
    ) {
        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.chat_empty_title),
                    description = stringResource(R.string.chat_empty_body),
                    icon = Icons.Default.ChatBubbleOutline,
                    lottieAsset = "empty_chat"
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatBubble(
                        message = msg,
                        isMe = msg.senderId == currentUserId,
                        onImageClick = onImageClick
                    )
                }
            }
        }

        AnimatedVisibility(visible = isUploading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Surface(
            color = Color(0xEE121216),
            tonalElevation = Elevation.raised,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                IconButton(
                    onClick = { launchChatCameraSafe() },
                    modifier = Modifier.testTag("chat_camera_button")
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = stringResource(R.string.chat_take_photo_desc),
                        tint = Color(0xFF6366F1)
                    )
                }
                IconButton(
                    onClick = { photoPickerLauncher.launch("image/*") },
                    modifier = Modifier.testTag("attach_photo_button")
                ) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = stringResource(R.string.chat_attach_image_desc),
                        tint = Color(0xFF6366F1)
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text(stringResource(R.string.chat_input_placeholder), color = Color(0xFFA1A1AA)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field"),
                    shape = RoundedCornerShape(Radius.pill),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = Color(0x1F71717A),
                        focusedTextColor = Color(0xFFF2F2F7),
                        unfocusedTextColor = Color(0xFFF2F2F7)
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { sendText() })
                )

                val canSend = inputText.isNotBlank()
                Surface(
                    onClick = { sendText() },
                    enabled = canSend,
                    shape = CircleShape,
                    color = if (canSend) Color(0xFF6366F1) else Color(0x3371717A),
                    modifier = Modifier
                        .size(Sizes.fab)
                        .testTag("send_message_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = stringResource(R.string.chat_send_desc),
                            tint = Color.White,
                            modifier = Modifier.size(Sizes.iconMd)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    isMe: Boolean,
    onImageClick: (Any) -> Unit
) {
    when (message.type) {
        MessageType.GEOFENCE_ALERT -> {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = Color(0x266366F1)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = Color(0xFF818CF8),
                            modifier = Modifier.size(Sizes.iconSm)
                        )
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFF2F2F7)
                        )
                        Text(
                            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFA1A1AA)
                        )
                    }
                }
            }
            return
        }

        MessageType.SOS_ALERT -> {
            Surface(
                shape = RoundedCornerShape(Radius.md),
                color = Color(0x33F43F5E),
                border = BorderStroke(1.dp, Color(0xFFF43F5E)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Icon(
                        Icons.Default.CrisisAlert,
                        contentDescription = null,
                        tint = Color(0xFFF43F5E),
                        modifier = Modifier.size(Sizes.iconXl)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.chat_sos_alert_label, message.senderName),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(0xFFF43F5E)
                        )
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFF2F2F7)
                        )
                    }
                }
            }
            return
        }

        else -> Unit
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        if (!isMe) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF818CF8),
                modifier = Modifier.padding(start = Spacing.md, bottom = Spacing.xxs)
            )
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = Radius.md,
                topEnd = Radius.md,
                bottomStart = if (isMe) Radius.md else Radius.xs,
                bottomEnd = if (isMe) Radius.xs else Radius.md
            ),
            color = if (isMe) Color(0xFF6366F1) else Color(0xFF27272A),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(Spacing.sm)) {
                val bitmap = remember(message.imageBase64) {
                    ImageUtils.base64ToBitmap(message.imageBase64)
                }
                val imageSource = message.getImageSource()

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Immagine condivisa",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable { message.imageBase64?.let(onImageClick) },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(Spacing.xs))
                } else if (imageSource != null) {
                    AsyncImage(
                        model = imageSource,
                        contentDescription = "Immagine condivisa",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable { onImageClick(imageSource) },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }

                val hidePlaceholderCaption = message.text == "Immagine condivisa" ||
                    message.text == "Foto condivisa" ||
                    message.text == "Foto scattata in chat"

                if (message.text.isNotBlank() && !hidePlaceholderCaption) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF2F2F7)
                    )
                }

                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = Spacing.xxs)
                )
            }
        }
    }
}

// ============================================================================
// PANNELLO: LUOGHI
// ============================================================================

@Composable
private fun PlacesPanel(
    places: List<SavedPlace>,
    alerts: List<GeofenceEvent>,
    onPlaceClick: (SavedPlace) -> Unit,
    onFocusPlace: (SavedPlace) -> Unit,
    onAddPlaceClick: () -> Unit,
    onEditPlace: (SavedPlace) -> Unit,
    onDeletePlace: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            top = Spacing.sm,
            bottom = Spacing.xxxl
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item {
            SectionHeader(
                title = stringResource(R.string.section_safe_zones),
                subtitle = stringResource(R.string.section_safe_zones_desc),
                icon = Icons.Default.Security,
                action = {
                    FilledTonalButton(
                        onClick = onAddPlaceClick,
                        shape = RoundedCornerShape(Radius.sm),
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                        modifier = Modifier.testTag("add_place_tab_fab"),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF6366F1),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.action_add))
                    }
                }
            )
        }

        if (places.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.empty_places_title),
                    description = stringResource(R.string.empty_places_body),
                    icon = Icons.Default.PinDrop,
                    lottieAsset = "empty_places"
                )
            }
        } else {
            items(places, key = { it.id }) { place ->
                PlaceRow(
                    place = place,
                    onClick = { onPlaceClick(place) },
                    onFocus = { onFocusPlace(place) },
                    onEdit = { onEditPlace(place) },
                    onDelete = { onDeletePlace(place.id) }
                )
            }
        }

        if (alerts.isNotEmpty()) {
            item {
                Spacer(Modifier.height(Spacing.sm))
                SectionHeader(
                    title = stringResource(R.string.section_recent_activity),
                    icon = Icons.Default.History
                )
            }
            items(alerts.take(8), key = { it.id }) { alert ->
                GeofenceAlertRow(alert)
            }
        }
    }
}

@Composable
private fun PlaceRow(
    place: SavedPlace,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val accent = placeColor(place.category)
    val context = LocalContext.current

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color(0x0A71717A),
        border = BorderStroke(1.dp, Color(0x1F71717A)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("place_card_${place.id}")
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(Sizes.avatarMd)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = placeIcon(place.category),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(Sizes.iconLg)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFF2F2F7),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!place.geofenceEnabled) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = stringResource(R.string.content_desc_alerts_disabled),
                            tint = Color(0xFFA1A1AA),
                            modifier = Modifier.size(Sizes.iconSm)
                        )
                    }
                }
                Text(
                    text = if (place.geofenceEnabled)
                        stringResource(R.string.place_row_subtitle, stringResource(place.category.labelRes), place.radiusMeters.toInt())
                    else
                        stringResource(R.string.place_row_subtitle_silent, stringResource(place.category.labelRes), place.radiusMeters.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.content_desc_edit_place),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.iconMd)
                )
            }
            IconButton(onClick = onFocus) {
                Icon(
                    Icons.Default.NearMe,
                    contentDescription = stringResource(R.string.action_show_on_map),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Sizes.iconMd)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.content_desc_delete_place),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(Sizes.iconMd)
                )
            }
        }
    }
}

@Composable
private fun GeofenceAlertRow(alert: GeofenceEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            modifier = Modifier
                .size(Sizes.avatarSm)
                .clip(CircleShape)
                .background(
                    (if (alert.isInside) RadarSemantic.Online else RadarSemantic.Idle)
                        .copy(alpha = 0.18f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (alert.isInside) Icons.Default.Login else Icons.Default.Logout,
                contentDescription = null,
                tint = if (alert.isInside) RadarSemantic.Online else RadarSemantic.Idle,
                modifier = Modifier.size(Sizes.iconSm)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (alert.isInside) stringResource(R.string.msg_arrived_at, alert.userName, alert.placeName)
                       else stringResource(R.string.msg_left_place, alert.userName, alert.placeName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = SimpleDateFormat("HH:mm · dd MMM", Locale.getDefault()).format(Date(alert.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// PANNELLO: IMPOSTAZIONI
// ============================================================================

@Composable
private fun SettingsPanel(
    currentUser: UserData?,
    currentGroup: GroupData?,
    currentUserId: String,
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
    isSimulationRunning: Boolean,
    onEditProfileClick: () -> Unit,
    onEditGroupClick: () -> Unit,
    onSwitchGroup: () -> Unit,
    onUpdateInterval: (Int) -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onTogglePowerSaving: (Boolean) -> Unit,
    onToggleAutoTrip: (Boolean) -> Unit,
    onToggleAutoTripShared: (Boolean) -> Unit,
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

    fun applyInterval(raw: String, unit: TrackingTimeUnit) {
        val num = raw.toIntOrNull() ?: return
        onUpdateInterval((num * unit.multiplier).coerceIn(5, 86400))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
                Text(
                    text = stringResource(R.string.settings_effective_interval, formatInterval(effective, context)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.settings_trip_speed_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Viaggi ----
        item {
            SettingsCard {
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_section_trips),
                    subtitle = stringResource(R.string.settings_trips_subtitle),
                    icon = Icons.Default.Route
                )
                SettingsToggleRow(
                    title = stringResource(R.string.settings_auto_trip_title),
                    description = stringResource(R.string.settings_auto_trip_desc),
                    icon = Icons.Default.AutoMode,
                    checked = isAutoTripEnabled,
                    onCheckedChange = onToggleAutoTrip,
                    testTag = "auto_trip_switch"
                )
                if (isAutoTripEnabled) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_auto_trip_shared_title),
                        description = stringResource(R.string.settings_auto_trip_shared_desc),
                        icon = if (isAutoTripShared) Icons.Default.Group else Icons.Default.Lock,
                        checked = isAutoTripShared,
                        onCheckedChange = onToggleAutoTripShared,
                        testTag = "auto_trip_shared_switch"
                    )
                }
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
                            .background(MaterialTheme.colorScheme.primaryContainer),
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
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
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
                    OutlinedButton(
                        onClick = onSwitchGroup,
                        shape = RoundedCornerShape(Radius.sm),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = Spacing.sm)
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.action_change_short))
                    }
                    if (isOwnerOrAdmin && currentGroup != null) {
                        OutlinedButton(
                            onClick = onEditGroupClick,
                            shape = RoundedCornerShape(Radius.sm),
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
                    color = MaterialTheme.colorScheme.primaryContainer,
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
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = currentGroup?.joinCode ?: "——————",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
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
                Spacer(Modifier.height(Spacing.xs))
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

                Spacer(Modifier.height(Spacing.md))
                HairlineDivider()
                Spacer(Modifier.height(Spacing.md))

                SettingsSectionHeader(
                    title = stringResource(R.string.settings_language),
                    subtitle = stringResource(R.string.settings_language_subtitle),
                    icon = Icons.Default.Language
                )
                Spacer(Modifier.height(Spacing.xs))
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
                                    // La locale si applica in attachBaseContext, che
                                    // gira una volta per istanza di Activity: senza
                                    // recreate() il cambio si vedrebbe solo al
                                    // prossimo avvio dell'app.
                                    //
                                    // Non basta un cast: LocalContext puo' essere un
                                    // ContextWrapper (lo e' di sicuro qui, visto che
                                    // la locale stessa lo avvolge), quindi si risale
                                    // la catena fino all'Activity.
                                    context.findActivityOrNull()?.recreate()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
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

                OutlinedButton(
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Sizes.iconMd),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
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
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                    OutlinedButton(onClick = { checkResult = null }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(Radius.sm)) { Text(stringResource(R.string.action_later)) }
                                    Button(onClick = { checkResult = null; AppUpdater.downloadAndInstall(context, result.info.apkUrl) },
                                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(Radius.sm)) { Text(stringResource(R.string.action_update)) }
                                }
                            }
                        }
                    }
                    CheckResult.UpToDate -> Dialog(onDismissRequest = { checkResult = null }) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                            shape = RoundedCornerShape(Radius.xl),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(Sizes.iconLg))
                                }
                                Text(
                                    text = stringResource(R.string.up_to_date_body, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(onClick = { checkResult = null }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.sm)) { Text(stringResource(R.string.action_ok)) }
                            }
                        }
                    }
                    CheckResult.NetworkError -> Dialog(onDismissRequest = { checkResult = null }) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                            shape = RoundedCornerShape(Radius.xl),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                Button(onClick = { checkResult = null }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.sm)) { Text(stringResource(R.string.action_ok)) }
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
                        enabled = !feedbackSending && feedbackText.isNotBlank()
                    ) {
                        if (feedbackSending) {
                            CircularProgressIndicator(modifier = Modifier.size(Sizes.iconMd), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
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

                Spacer(Modifier.height(Spacing.md))

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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                    Text(stringResource(R.string.dev_area_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
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
                    Button(onClick = { tryUnlock() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.sm)) {
                        Text(stringResource(R.string.action_sign_in))
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                    Text(stringResource(R.string.dev_feedback_list_title), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(Spacing.md))
                    if (loading) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
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
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
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
                                                        tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(Sizes.iconMd))
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
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.sm)) {
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            content = content
        )
    }
}

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
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier
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

// ============================================================================
// DIALOG CONDIVISI
// ============================================================================

@Composable
private fun ConfirmDialog(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            shape = RoundedCornerShape(Radius.xl),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(Sizes.avatarLg)
                        .clip(CircleShape)
                        .background(iconTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(Sizes.iconLg))
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Radius.sm)
                    ) { Text(stringResource(R.string.action_cancel)) }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Radius.sm),
                        colors = ButtonDefaults.buttonColors(containerColor = iconTint)
                    ) { Text(confirmLabel) }
                }
            }
        }
    }
}

@Composable
private fun GroupLoadingOverlay() {
    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val infiniteTransition = rememberInfiniteTransition(label = "radar_loading")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing)
        ),
        label = "sweep"
    )

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor.copy(alpha = 0.93f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xl)
            ) {
                Canvas(modifier = Modifier.size(140.dp)) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val maxRadius = size.minDimension / 2

                    // Concentric rings
                    for (i in 1..3) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.15f),
                            radius = maxRadius * i / 3f,
                            center = center,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }

                    // Radar sweep pie slice (trailing fade)
                    for (i in 0 until 30) {
                        val alpha = (i / 30f) * 0.55f
                        val startA = sweepAngle - 90f - 3f * (30 - i)
                        drawArc(
                            color = primaryColor.copy(alpha = alpha),
                            startAngle = startA,
                            sweepAngle = 3f,
                            useCenter = true,
                            topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                            size = GeometrySize(maxRadius * 2, maxRadius * 2)
                        )
                    }

                    // Outer ring
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.4f),
                        radius = maxRadius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Center dot
                    drawCircle(color = primaryColor, radius = 5.dp.toPx(), center = center)

                    // Sweep tip line
                    val tipX = center.x + maxRadius * kotlin.math.cos(Math.toRadians((sweepAngle - 90.0))).toFloat()
                    val tipY = center.y + maxRadius * kotlin.math.sin(Math.toRadians((sweepAngle - 90.0))).toFloat()
                    drawLine(
                        color = primaryColor.copy(alpha = 0.8f),
                        start = center,
                        end = Offset(tipX, tipY),
                        strokeWidth = 2.dp.toPx()
                    )
                }
                Text(
                    text = stringResource(R.string.loading_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SnapshotSourceDialog(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.xl),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Box(
                modifier = Modifier
                    .size(Sizes.avatarLg)
                    .clip(CircleShape)
                    .background(RadarSemantic.Snapshot.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.AddAPhoto,
                    contentDescription = null,
                    tint = RadarSemantic.Snapshot,
                    modifier = Modifier.size(Sizes.iconLg)
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.snapshot_source_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = stringResource(R.string.snapshot_source_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.xs))
                FilledTonalButton(
                    onClick = onCamera,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(Sizes.iconMd))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.action_take_photo_now))
                }
                OutlinedButton(
                    onClick = onGallery,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(Sizes.iconMd))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.action_choose_gallery))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

// ============================================================================
// UTILITÀ
// ============================================================================

private fun placeColor(category: PlaceCategory): Color = when (category) {
    PlaceCategory.HOME -> RadarSemantic.PlaceHome
    PlaceCategory.WORK -> RadarSemantic.PlaceWork
    PlaceCategory.SCHOOL -> RadarSemantic.PlaceSchool
    PlaceCategory.GYM -> RadarSemantic.PlaceGym
    PlaceCategory.OTHER -> RadarSemantic.PlaceOther
}

private fun placeIcon(category: PlaceCategory) = when (category) {
    PlaceCategory.HOME -> Icons.Default.Home
    PlaceCategory.WORK -> Icons.Default.Work
    PlaceCategory.SCHOOL -> Icons.Default.School
    PlaceCategory.GYM -> Icons.Default.FitnessCenter
    PlaceCategory.OTHER -> Icons.Default.Place
}

private fun formatInterval(seconds: Int, context: android.content.Context): String = when {
    seconds <= 0 -> "—"
    seconds % 3600 == 0 -> context.getString(R.string.interval_hours, seconds / 3600)
    seconds % 60 == 0 -> context.getString(R.string.interval_minutes, seconds / 60)
    else -> context.getString(R.string.interval_seconds, seconds)
}

private fun formatShortTime(timestamp: Long, context: android.content.Context): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> context.getString(R.string.map_time_just_now)
        diff < 3_600_000 -> context.getString(R.string.time_short_minutes_ago, (diff / 60_000).toInt())
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

// ============================================================================
// PANNELLO: VIAGGI
// ============================================================================

@Composable
private fun TripsPanel(
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
                    color = Color(0x0A71717A),
                    border = BorderStroke(1.dp, Color(0xFFF43F5E))
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
                                color = Color(0xFFF43F5E),
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                stringResource(R.string.trip_recording),
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFFF43F5E)
                            )
                        }
                        Text(
                            "%02d:%02d  •  %.2f km  •  %d punti".format(
                                elapsedMin, elapsedSec, km, activeTrip.points.size
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA1A1AA)
                        )
                        Button(
                            onClick = onStopTrip,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF43F5E)),
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
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
                color = Color(0x0A71717A),
                border = BorderStroke(
                    if (isSelected) 1.5.dp else 1.dp,
                    if (isSelected) Color(0xFF6366F1) else Color(0x1F71717A)
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
                            trip.isLive -> Color(0xFFF43F5E)
                            isSelected -> Color(0xFF6366F1)
                            else -> Color(0xFFA1A1AA)
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
                                color = Color(0xFFF2F2F7)
                            )
                            TripBadge(
                                text = if (trip.isLive) stringResource(R.string.trip_badge_live) else trip.source.label(context).uppercase(),
                                color = when {
                                    trip.isLive -> Color(0xFFF43F5E)
                                    trip.source == TripSource.AUTO -> Color(0xFF34D399)
                                    else -> Color(0xFF6366F1)
                                }
                            )
                            if (trip.isPrivate) {
                                TripBadge(
                                    text = stringResource(R.string.trip_badge_private),
                                    color = Color(0xFFA1A1AA)
                                )
                            }
                        }
                        val route = listOfNotNull(trip.startPlaceName, trip.endPlaceName)
                        Text(
                            if (route.size == 2) "${route[0]} → ${route[1]}"
                            else dateFormat.format(java.util.Date(trip.startTime)),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA1A1AA),
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
private fun TripDetailDialog(
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                    Spacer(Modifier.width(Spacing.xs))
                    if (isOnMap) {
                        OutlinedButton(onClick = onHideFromMap) {
                            Icon(Icons.Default.LayersClear, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.action_remove_from_map))
                        }
                    } else {
                        Button(onClick = onShowOnMap) {
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
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

/**
 * Risale la catena dei ContextWrapper fino all'Activity.
 *
 * Serve per `recreate()` al cambio lingua: `LocalContext.current` qui è avvolto
 * almeno una volta (dal context localizzato), quindi un cast diretto ad Activity
 * fallirebbe e il cambio lingua sembrerebbe non funzionare.
 */
private fun android.content.Context.findActivityOrNull(): android.app.Activity? {
    var ctx: android.content.Context = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
