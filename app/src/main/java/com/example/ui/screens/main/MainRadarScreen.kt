@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.example.ui.screens.main

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
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
private enum class RadarPanel(val label: String) {
    MEMBERS("Membri"),
    CHAT("Chat"),
    PLACES("Luoghi"),
    TRIPS("Viaggi"),
    SETTINGS("Impostazioni")
}

enum class TrackingTimeUnit(val label: String, val multiplier: Int) {
    SECONDS("Secondi", 1),
    MINUTES("Minuti", 60),
    HOURS("Ore", 3600)
}

@Composable
fun MainRadarScreen(
    repository: FirebaseRepository,
    onSwitchGroup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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
        rawLocations.map { loc ->
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
    val isOwnerOrAdmin = currentGroup?.ownerId == currentUserId ||
        members.find { it.userId == currentUserId }?.role in listOf("owner", "admin")
    val pendingMembers = remember(members) { members.filter { it.status == "PENDING" } }
    val activeMembers = remember(members) { members.filter { it.status == "ACTIVE" } }

    // --- Stato del bottom sheet ---
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val isSheetExpanded = sheetState.currentValue == SheetValue.Expanded
    var panel by remember { mutableStateOf(RadarPanel.MEMBERS) }

    fun openPanel(target: RadarPanel) {
        panel = target
        coroutineScope.launch { sheetState.expand() }
    }

    fun collapseSheet() {
        coroutineScope.launch { sheetState.partialExpand() }
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
            Toast.makeText(context, "Nessuna foto acquisita", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "Impossibile aprire fotocamera: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (!isGranted) {
            pendingMapCameraAction = false
            Toast.makeText(context, "Permesso fotocamera non concesso", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchMapCameraSafe() {
        val tempUri = ImageUtils.createTempImageUri(context)
        if (tempUri == null) {
            Toast.makeText(context, "Impossibile creare il file per la foto", Toast.LENGTH_SHORT).show()
            return
        }
        pendingMapCameraUri = tempUri
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            runCatching { takeSnapshotLauncher.launch(tempUri) }.onFailure {
                Toast.makeText(context, "Impossibile aprire fotocamera: ${it.message}", Toast.LENGTH_SHORT).show()
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
            "CHAT" -> { panel = RadarPanel.CHAT; sheetState.expand() }
            "ALERT" -> { panel = RadarPanel.MEMBERS; sheetState.expand() }
            // MEMBERS e SETTINGS sono destinazioni diverse e vanno tenute
            // separate: le notifiche join_request e low_battery hanno
            // destination MEMBERS, e le azioni Approva/Rifiuta vivono nel
            // pannello Membri. Mandandole a SETTINGS l'admin apriva un pannello
            // dove la richiesta non e' nemmeno mostrata.
            "MEMBERS" -> { panel = RadarPanel.MEMBERS; sheetState.expand() }
            "SETTINGS" -> { panel = RadarPanel.SETTINGS; sheetState.expand() }
            "MAP" -> {
                sheetState.partialExpand()
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
    // Dipende anche da messages.size perche' i messaggi che arrivano a pannello
    // gia' aperto sono letti anch'essi.
    LaunchedEffect(panel, currentGroup?.id, messages.size) {
        val gid = currentGroup?.id
        if (panel == RadarPanel.CHAT && !gid.isNullOrBlank() && isSheetExpanded) {
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

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = Sizes.sheetPeek,
        sheetShape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetShadowElevation = Elevation.overlay,
        sheetDragHandle = { SheetHandle(expanded = isSheetExpanded) },
        containerColor = MaterialTheme.colorScheme.background,
        sheetContent = {
            Column(modifier = Modifier
                .fillMaxWidth()
                .height(sheetContentHeight)
            ) {
                PanelSelector(
                    selected = panel,
                    chatCount = unreadChatCount,
                    pendingCount = if (isOwnerOrAdmin) pendingMembers.size else 0,
                    memberCount = activeMembers.size,
                    placeCount = places.size,
                    tripCount = groupTrips.size,
                    onSelect = { openPanel(it) }
                )

                Box(modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                ) {
                    AnimatedContent(
                        targetState = panel,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "panel_switch"
                    ) { current ->
                        when (current) {
                            RadarPanel.MEMBERS -> MembersPanel(
                                members = activeMembers,
                                pendingMembers = pendingMembers,
                                locations = locations,
                                currentUserId = currentUserId,
                                isOwnerOrAdmin = isOwnerOrAdmin,
                                isLoading = members.isEmpty() && currentGroup != null,
                                onMemberClick = { loc ->
                                    selectedMemberForSheet = loc
                                    collapseSheet()
                                },
                                onFocusMember = { loc -> focusMapOn(loc.latitude, loc.longitude) },
                                onKickMember = { memberToKick = it },
                                onApprove = { memberId ->
                                    val gid = currentGroup?.id ?: return@MembersPanel
                                    coroutineScope.launch {
                                        val res = repository.approveJoinRequest(gid, memberId)
                                        Toast.makeText(
                                            context,
                                            if (res.isSuccess) "Richiesta approvata"
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
                                            if (res.isSuccess) "Richiesta rifiutata"
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
                                    collapseSheet()
                                },
                                onFocusPlace = { place -> focusMapOn(place.latitude, place.longitude) },
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
                                // Il tap apre la scheda di dettaglio; e' da li'
                                // che si sceglie se portare la traccia in mappa.
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
                                        Toast.makeText(context, "Viaggio salvato", Toast.LENGTH_SHORT).show()
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
                                        if (enabled) "Risparmio energia attivo: posizione da WiFi e rete dati"
                                        else "Precisione GPS ripristinata",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onToggleAutoTrip = { enabled ->
                                    repository.setAutoTripEnabled(enabled)
                                    Toast.makeText(
                                        context,
                                        if (enabled) "L'app registrerà i viaggi da sola"
                                        else "Rilevamento automatico disattivato",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onToggleAutoTripShared = { repository.setAutoTripShared(it) },
                                onToggleTracking = { enabled ->
                                    repository.setBackgroundTrackingEnabled(enabled)
                                    Toast.makeText(
                                        context,
                                        if (enabled) "Tracciamento in background attivato"
                                        else "Tracciamento in background disattivato",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onToggleGlobalGhostMode = { enabled ->
                                    repository.setGlobalGhostMode(enabled)
                                    Toast.makeText(
                                        context,
                                        if (enabled) "Modalità Fantasma attiva: sei invisibile"
                                        else "Modalità Fantasma disattivata",
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
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            OsmMapView(
                locations = locations,
                places = places,
                snapshots = snapshots,
                // I viaggi conclusi arrivano senza punti: la traccia di quello
                // aperto viene letta a parte e reinnestata qui, così la mappa
                // riceve solo cio' che deve davvero disegnare.
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
                onMapTap = { if (isSheetExpanded) collapseSheet() },
                onUserPan = {
                    // Trascinare la mappa e' il modo naturale per dire "lasciami
                    // guardare dove voglio": spegne l'inseguimento.
                    if (followedUserId != null) followedUserId = null
                },
                onMemberSelected = { selectedMemberForSheet = it },
                onPlaceSelected = { selectedPlaceForSheet = it },
                onSnapshotClusterSelected = { selectedSnapshotClusterForGallery = it },
                modifier = Modifier.fillMaxSize()
            )

            // Sfumatura in alto: rende leggibile la barra sopra qualunque tipo di mappa.
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

            MapTopBar(
                groupName = currentGroup?.name ?: "Radar",
                joinCode = currentGroup?.joinCode,
                memberCount = activeMembers.size,
                // Stessa soglia del PresenceDot: con due valori separati il
                // conteggio in intestazione poteva contraddire i pallini sotto.
                // Solo i membri ACTIVE contano come "online": un membro PENDING
                // puo' avere una posizione nella collection ma non e' ancora nel
                // gruppo, e mostrarlo come online contraddirebbe il conteggio membri.
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

            MapActionRail(
                isFollowing = followedUserId != null,
                onToggleFollow = {
                    if (followedUserId != null) {
                        followedUserId = null
                        Toast.makeText(context, "Inseguimento disattivato", Toast.LENGTH_SHORT).show()
                    } else {
                        val targetId = focusTargetUserId ?: currentUserId
                        val target = locations.find { it.userId == targetId }
                        if (target != null) {
                            followedUserId = targetId
                            focusMapOn(target.latitude, target.longitude, collapse = false)
                            val label = if (targetId == currentUserId) "te" else (target.nickname ?: target.userName)
                            Toast.makeText(context, "Inseguimento attivo su $label", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Posizione del bersaglio non disponibile", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onLocateSelf = {
                    val myLoc = locations.find { it.userId == currentUserId }
                    if (myLoc != null) {
                        focusTargetUserId = currentUserId
                        focusMapOn(myLoc.latitude, myLoc.longitude, collapse = false)
                    } else {
                        Toast.makeText(context, "Posizione non ancora disponibile", Toast.LENGTH_SHORT).show()
                    }
                },
                isRecording = activeTrip != null,
                onToggleTrip = {
                    if (activeTrip != null) {
                        coroutineScope.launch {
                            repository.stopAndSaveTrip()
                            Toast.makeText(context, "Viaggio salvato", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        repository.startTrip()
                    }
                },
                onAddPlace = { showAddPlaceDialog = true },
                onTakeSnapshot = { showSnapshotSourceDialog = true },
                // A sinistra e in basso: la colonna a destra e' gia' occupata dai
                // controlli interni della mappa (layer, zoom, inquadra gruppo).
                // Il padding inferiore tiene i pulsanti sopra il bottom sheet.
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = Spacing.lg, bottom = Sizes.sheetPeek + Spacing.lg)
            )

            // Pill di registrazione: in basso a destra, sopra il bottom sheet.
            // Solo pallino rosso pulsante + tempo + km; lo stop si fa dal pulsante
            // rosso nel rail a sinistra, per non avere due comandi identici.
            AnimatedVisibility(
                visible = activeTrip != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = Spacing.lg, bottom = Sizes.sheetPeek + Spacing.lg)
            ) {
                activeTrip?.let { trip ->
                    // Il tempo trascorso deve derivare da uno *stato* letto in
                    // composizione, altrimenti resta congelato al primo valore:
                    // un contatore che incrementa senza essere letto non provoca
                    // ricomposizione.
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

                    GlassSurface(
                        shape = RoundedCornerShape(Radius.pill),
                        contentPadding = Spacing.xs
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                        ) {
                            RadarPulseAnimation(
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "%02d:%02d  •  %.2f km".format(elapsedMin, elapsedSec, km),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Carosello membri: visibile solo con il pannello chiuso, così a sheet
            // aperto lo schermo non mostra due volte la stessa informazione.
            AnimatedVisibility(
                visible = !isSheetExpanded && locations.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 76.dp)
            ) {
                MemberCarousel(
                    locations = locations,
                    currentUserId = currentUserId,
                    followedUserId = followedUserId,
                    onMemberClick = { loc ->
                        // Tap sul carosello = centra subito su quel membro con zoom
                        // ravvicinato. Il dettaglio resta raggiungibile dalla lista
                        // nel pannello Membri.
                        focusTargetUserId = loc.userId
                        focusMapOn(loc.latitude, loc.longitude, collapse = false)
                        // Se stavamo inseguendo qualcun altro, il bersaglio passa a lui.
                        if (followedUserId != null) followedUserId = loc.userId
                    },
                    onMemberLongClick = { selectedMemberForSheet = it }
                )
            }

            // Banner dell'inseguimento. Sostituisce l'etichetta minuscola che
            // stava nella barra laterale: li' diceva che stavi seguendo qualcuno
            // ma si perdeva fra i pulsanti, e soprattutto non era ovvio come
            // uscirne — bisognava sapere che quel pulsante faceva da interruttore.
            // Qui c'e' la faccia di chi stai seguendo e un modo esplicito per
            // smettere.
            AnimatedVisibility(
                visible = followedUserId != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (!isSheetExpanded && locations.isNotEmpty()) 148.dp else 76.dp)
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
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.padding(start = Spacing.xs, end = Spacing.sm)
                    ) {
                        RadarAvatar(
                            name = bannerName,
                            photoBase64 = bannerPhoto,
                            size = Sizes.avatarSm,
                            ringColor = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Stai seguendo",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = bannerName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            onClick = { followedUserId = null },
                            shape = RoundedCornerShape(Radius.pill),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("stop_following_button")
                        ) {
                            Text(
                                text = "Smetti",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(
                                    horizontal = Spacing.md,
                                    vertical = Spacing.xs
                                )
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
                    Toast.makeText(context, "Coordinate del luogo non valide", Toast.LENGTH_SHORT).show()
                }
            },
            onDeletePlace = { toDelete ->
                coroutineScope.launch {
                    repository.deletePlace(toDelete.id)
                    Toast.makeText(context, "Luogo '${toDelete.name}' eliminato", Toast.LENGTH_SHORT).show()
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
                            if (enabled) "Avvisi attivati per '${target.name}'"
                            else "Avvisi disattivati per '${target.name}'",
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
                        if (res.isSuccess) "Luogo '${updated.name}' aggiornato"
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
            displayName = currentUser?.displayName ?: "Utente",
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
                        if (res.isSuccess) "Profilo aggiornato"
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
            onSave = { newName, newDescription, newPhotoBase64 ->
                coroutineScope.launch {
                    val res = repository.updateGroupInfo(
                        groupId = currentGroup.id,
                        name = newName,
                        description = newDescription,
                        photoBase64 = newPhotoBase64
                    )
                    showEditGroupDialog = false
                    Toast.makeText(
                        context,
                        if (res.isSuccess) "Gruppo aggiornato"
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
                        Toast.makeText(context, "Traccia non disponibile", Toast.LENGTH_SHORT).show()
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
        AddPlaceDialog(
            initialLat = myLoc?.latitude ?: 41.9028,
            initialLon = myLoc?.longitude ?: 12.4964,
            onDismiss = { showAddPlaceDialog = false },
            onPlaceAdded = { place ->
                showAddPlaceDialog = false
                coroutineScope.launch {
                    repository.addPlace(place)
                    Toast.makeText(context, "Luogo '${place.name}' aggiunto", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showSosConfirmDialog) {
        ConfirmDialog(
            icon = Icons.Default.CrisisAlert,
            iconTint = RadarSemantic.Sos,
            title = "Invia allerta SOS",
            message = "Tutti i membri di ${currentGroup?.name ?: "questo gruppo"} riceveranno " +
                "una notifica di emergenza con la tua posizione attuale.",
            confirmLabel = "Invia SOS",
            onConfirm = {
                showSosConfirmDialog = false
                val gid = currentGroup?.id
                if (!gid.isNullOrBlank()) {
                    repository.sendSosAlert(gid)
                    Toast.makeText(context, "Allerta SOS inviata al gruppo", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showSosConfirmDialog = false }
        )
    }

    memberToKick?.let { target ->
        ConfirmDialog(
            icon = Icons.Default.PersonRemove,
            iconTint = MaterialTheme.colorScheme.error,
            title = "Espelli membro",
            message = "Vuoi rimuovere '${target.displayName}' dal gruppo? " +
                "Non avrà più accesso a mappa, posizioni e messaggi.",
            confirmLabel = "Espelli",
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
            title = "Elimina gruppo",
            // Il messaggio elenca cosa sparisce: "sei sicuro?" non dice a
            // nessuno che sta per perdere anche chat, luoghi e viaggi.
            message = "Eliminare '${currentGroup.name}' per tutti i membri?\n\n" +
                "Spariscono definitivamente messaggi, luoghi, istantanee e viaggi " +
                "del gruppo. L'operazione non si può annullare.",
            confirmLabel = "Elimina",
            onConfirm = {
                showDeleteGroupDialog = false
                coroutineScope.launch {
                    val res = repository.deleteGroup(currentGroup.id)
                    Toast.makeText(
                        context,
                        if (res.isSuccess) "Gruppo eliminato"
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
            title = "Abbandona gruppo",
            message = "Vuoi uscire da '${currentGroup.name}'? La tua posizione non sarà " +
                "più condivisa e non riceverai più notifiche.",
            confirmLabel = "Abbandona",
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
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        contentPadding = Spacing.sm
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            RadarPulseCompact(modifier = Modifier.size(Sizes.avatarSm))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(RadarSemantic.Online)
                    )
                    Text(
                        text = "$onlineCount online · $memberCount membri" +
                            if (!joinCode.isNullOrBlank()) " · $joinCode" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    contentDescription = "Cambia gruppo",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Le impostazioni stanno qui e non fra le schede del foglio: le altre
            // sezioni mostrano dati del gruppo che vivono sulla mappa, questa e'
            // configurazione. Tenendola fra le schede costringeva a cinque voci
            // in scorrimento orizzontale, con l'ultima fuori schermo.
            //
            // Nessun pallino di notifica qui: le richieste di adesione si
            // approvano nel pannello Membri, ed e' li' che sta il loro badge.
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("open_settings_button")
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Impostazioni",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // SOS: unico elemento sempre a piena saturazione, per essere trovato al volo.
            Surface(
                onClick = onSos,
                shape = CircleShape,
                color = RadarSemantic.Sos,
                modifier = Modifier
                    .size(Sizes.fab)
                    .testTag("sos_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Invia SOS",
                        tint = Color.White,
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
        // L'etichetta di stato e' salita in un banner sopra la mappa: qui era
        // minuscola e in mezzo agli altri pulsanti, quindi non si notava.
        RailButton(
            icon = if (isFollowing) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
            contentDescription = if (isFollowing) "Disattiva inseguimento" else "Attiva inseguimento",
            onClick = onToggleFollow,
            container = if (isFollowing) MaterialTheme.colorScheme.primary
            else RadarTheme.palette.gradients.glassTint,
            content = if (isFollowing) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            testTag = "follow_mode_fab"
        )
        RailButton(
            icon = Icons.Default.MyLocation,
            contentDescription = "Centra sulla mia posizione",
            onClick = onLocateSelf,
            testTag = "locate_self_fab"
        )
        RailButton(
            icon = if (isRecording) Icons.Default.Stop else Icons.Default.DirectionsCar,
            contentDescription = if (isRecording) "Stop viaggio" else "Registra viaggio",
            onClick = onToggleTrip,
            container = if (isRecording) MaterialTheme.colorScheme.error else RadarTheme.palette.gradients.glassTint,
            content = if (isRecording) Color.White else MaterialTheme.colorScheme.onSurface,
            testTag = "trip_record_fab"
        )
        RailButton(
            icon = Icons.Default.AddLocationAlt,
            contentDescription = "Aggiungi luogo",
            onClick = onAddPlace,
            testTag = "add_place_fab"
        )
        RailButton(
            icon = Icons.Default.AddAPhoto,
            contentDescription = "Scatta istantanea",
            onClick = onTakeSnapshot,
            container = RadarSemantic.Snapshot,
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
    container: Color = RadarTheme.palette.gradients.glassTint,
    content: Color = MaterialTheme.colorScheme.onSurface,
    testTag: String? = null
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        shadowElevation = Elevation.floating,
        modifier = modifier
            .size(Sizes.fab)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = content,
                modifier = Modifier.size(Sizes.iconLg)
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
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        items(locations, key = { it.userId }) { loc ->
            val isSelf = loc.userId == currentUserId
            val isFollowed = loc.userId == followedUserId
            val name = if (!loc.nickname.isNullOrBlank()) loc.nickname!! else loc.userName

            GlassSurface(
                shape = RoundedCornerShape(Radius.pill),
                contentPadding = Spacing.xs,
                modifier = Modifier.combinedClickable(
                    onClick = { onMemberClick(loc) },
                    onLongClick = { onMemberLongClick(loc) }
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.padding(end = Spacing.sm)
                ) {
                    Box {
                        RadarAvatar(
                            name = loc.userName,
                            photoBase64 = loc.photoBase64,
                            size = Sizes.avatarSm,
                            ringColor = if (isFollowed) MaterialTheme.colorScheme.primary else null,
                            containerColor = if (isSelf) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (isSelf) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        PresenceDot(
                            lastSeenMillis = loc.timestamp,
                            modifier = Modifier.align(Alignment.BottomEnd)
                        )
                    }
                    Column {
                        Text(
                            text = if (isSelf) "Tu" else name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        BatteryBadge(level = loc.batteryLevel, isCharging = loc.isCharging)
                    }
                }
            }
        }
    }
}

// ============================================================================
// SELETTORE DI PANNELLO
// ============================================================================

/**
 * Barra di navigazione del foglio.
 *
 * Erano cinque PillChip in scorrimento orizzontale: su un telefono stretto
 * l'ultima restava fuori schermo, e una destinazione che si scopre scorrendo
 * non è una destinazione. In più la chip, in Material 3, è il componente che
 * filtra una lista — non quello che cambia sezione.
 *
 * Ora sono quattro voci a larghezza uguale che entrano su qualsiasi schermo,
 * con la pastiglia dell'indicatore attivo dietro l'icona: la stessa grammatica
 * della NavigationBar, ma costruita sui token del progetto invece che sui
 * default di Material, così segue la palette anche in Material You.
 * Impostazioni è salita nella barra sopra la mappa.
 */
@Composable
private fun PanelSelector(
    selected: RadarPanel,
    chatCount: Int,
    pendingCount: Int,
    memberCount: Int,
    placeCount: Int,
    tripCount: Int,
    onSelect: (RadarPanel) -> Unit,
    modifier: Modifier = Modifier
) {
    val entries = listOf(
        PanelEntry(RadarPanel.MEMBERS, Icons.Default.Group, memberCount, pendingCount, "nav_members_tab"),
        PanelEntry(RadarPanel.CHAT, Icons.Default.Chat, null, chatCount.coerceAtMost(99), "nav_chat_tab"),
        PanelEntry(RadarPanel.PLACES, Icons.Default.Place, placeCount, 0, "nav_places_tab"),
        PanelEntry(RadarPanel.TRIPS, Icons.Default.Route, tripCount.takeIf { it > 0 }, 0, "nav_trips_tab")
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
    ) {
        entries.forEach { entry ->
            PanelSelectorItem(
                entry = entry,
                isSelected = selected == entry.panel,
                onClick = { onSelect(entry.panel) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private data class PanelEntry(
    val panel: RadarPanel,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** Conteggio mostrato accanto all'etichetta, se ha senso per quella sezione. */
    val count: Int?,
    /** Notifiche non lette o richieste in attesa: pallino rosso, non conteggio neutro. */
    val badge: Int,
    val testTag: String
)

@Composable
private fun PanelSelectorItem(
    entry: PanelEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Il colore va animato: cambiando scheda l'indicatore deve scorrere, non
    // saltare. Nessun clip e nessun layer sopra la mappa, siamo dentro il foglio.
    val indicatorColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
        else Color.Transparent,
        animationSpec = tween(220),
        label = "panel_indicator"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "panel_content"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.md))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs)
            .testTag(entry.testTag),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 30.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(indicatorColor)
            )
            Icon(
                imageVector = entry.icon,
                contentDescription = entry.panel.label,
                tint = contentColor,
                modifier = Modifier.size(Sizes.iconSm)
            )
            if (entry.badge > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-2).dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (entry.count != null) "${entry.panel.label} ${entry.count}" else entry.panel.label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
                    title = "In attesa di approvazione",
                    subtitle = "Hanno inserito il codice invito e aspettano il tuo via libera",
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
                    title = "Nessun membro attivo",
                    description = "Condividi il codice invito del gruppo per far entrare " +
                        "familiari e amici nel radar.",
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
    Surface(
        onClick = onClick,
        enabled = location != null,
        shape = RoundedCornerShape(Radius.md),
        color = if (isSelf) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
                    size = Sizes.avatarMd
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
                        text = if (isSelf) "${member.displayName} (tu)" else member.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    when (member.role) {
                        "owner" -> RadarBadge("Proprietario")
                        "admin" -> RadarBadge(
                            text = "Admin",
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        )
                    }
                }

                val subtitle = buildString {
                    if (!member.nickname.isNullOrBlank()) append("${member.nickname} · ")
                    if (location != null) {
                        append(location.currentPlaceName?.takeIf { it.isNotBlank() } ?: "In movimento")
                        append(" · ${formatShortTime(location.timestamp)}")
                    } else {
                        append("Posizione non condivisa")
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        contentDescription = "Mostra sulla mappa",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Sizes.iconMd)
                    )
                }
            }
            if (canKick) {
                IconButton(onClick = onKick) {
                    Icon(
                        Icons.Default.PersonRemove,
                        contentDescription = "Rimuovi membro",
                        tint = MaterialTheme.colorScheme.error,
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
                    text = "Richiesta di accesso",
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
                Icon(Icons.Default.Check, contentDescription = "Approva")
            }
            FilledTonalIconButton(
                onClick = onReject,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = "Rifiuta")
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
            Toast.makeText(context, "Nessuna foto acquisita", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "Impossibile avviare fotocamera: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (!isGranted) {
            pendingChatCamera = false
            Toast.makeText(context, "Permesso fotocamera necessario", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchChatCameraSafe() {
        val tempUri = ImageUtils.createTempImageUri(context)
        if (tempUri == null) {
            Toast.makeText(context, "Impossibile creare il file per la foto", Toast.LENGTH_SHORT).show()
            return
        }
        pendingChatCameraUri = tempUri
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            runCatching { cameraPhotoLauncher.launch(tempUri) }.onFailure {
                Toast.makeText(context, "Impossibile avviare fotocamera: ${it.message}", Toast.LENGTH_SHORT).show()
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
                    title = "Ancora nessun messaggio",
                    description = "Scrivi al gruppo, condividi una foto o la tua posizione.",
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
            color = MaterialTheme.colorScheme.surface,
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
                        contentDescription = "Scatta foto",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = { photoPickerLauncher.launch("image/*") },
                    modifier = Modifier.testTag("attach_photo_button")
                ) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = "Allega immagine",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Scrivi un messaggio…") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field"),
                    shape = RoundedCornerShape(Radius.pill),
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
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
                    color = if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(Sizes.fab)
                        .testTag("send_message_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Invia",
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(Sizes.iconSm)
                        )
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            return
        }

        MessageType.SOS_ALERT -> {
            Surface(
                shape = RoundedCornerShape(Radius.md),
                color = MaterialTheme.colorScheme.errorContainer,
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
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(Sizes.iconXl)
                    )
                    Column {
                        Text(
                            text = "Allerta SOS · ${message.senderName}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
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
                color = MaterialTheme.colorScheme.primary,
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
            color = if (isMe) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
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
                        color = if (isMe) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface
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
                title = "Zone sicure",
                subtitle = "Avvisi automatici quando un membro arriva o si allontana",
                icon = Icons.Default.Security,
                action = {
                    FilledTonalButton(
                        onClick = onAddPlaceClick,
                        shape = RoundedCornerShape(Radius.sm),
                        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                        modifier = Modifier.testTag("add_place_tab_fab")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Aggiungi")
                    }
                }
            )
        }

        if (places.isEmpty()) {
            item {
                EmptyState(
                    title = "Nessuna zona impostata",
                    description = "Aggiungi Casa, Scuola, Lavoro o Palestra per ricevere " +
                        "avvisi quando qualcuno entra o esce.",
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
                    title = "Attività recente",
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

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
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
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // Un luogo senza avvisi resta in elenco identico agli altri:
                    // senza questo segno non si capirebbe perche' non notifica.
                    if (!place.geofenceEnabled) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = "Avvisi disattivati",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Sizes.iconSm)
                        )
                    }
                }
                Text(
                    text = "${place.category.label} · raggio ${place.radiusMeters.toInt()} m" +
                        if (!place.geofenceEnabled) " · avvisi spenti" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Modifica luogo",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Sizes.iconMd)
                )
            }
            IconButton(onClick = onFocus) {
                Icon(
                    Icons.Default.NearMe,
                    contentDescription = "Mostra sulla mappa",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Sizes.iconMd)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Elimina luogo",
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
                text = "${alert.userName} ${if (alert.isInside) "è arrivato a" else "è uscito da"} ${alert.placeName}",
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
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val currentThemeMode by ThemePreferences.themeModeFlow.collectAsState()

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
                SettingsSectionHeader(title = "Profilo", icon = Icons.Default.Person)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    RadarAvatar(
                        name = currentUser?.displayName ?: "Utente",
                        photoBase64 = currentUser?.photoBase64,
                        size = Sizes.avatarLg
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentUser?.displayName ?: "Utente Radar",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val secondary = currentUser?.email?.takeIf { it.isNotBlank() }
                            ?: currentUser?.phoneNumber?.takeIf { it.isNotBlank() }
                            ?: "Account anonimo"
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
                        Text("Modifica")
                    }
                }
            }
        }

        // ---- Privacy ----
        item {
            SettingsCard {
                SettingsSectionHeader(
                    title = "Privacy",
                    subtitle = "Chi vede la tua posizione",
                    icon = if (isGlobalGhostMode) Icons.Default.VisibilityOff else Icons.Default.Visibility
                )
                SettingsToggleRow(
                    title = "Modalità fantasma",
                    description = "Nessuno ti vede, in tutti i gruppi",
                    icon = if (isGlobalGhostMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    iconTint = if (isGlobalGhostMode) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    checked = isGlobalGhostMode,
                    onCheckedChange = onToggleGlobalGhostMode,
                    testTag = "global_ghost_mode_switch"
                )
                SettingsToggleRow(
                    title = "Condividi in questo gruppo",
                    description = "Se lo spegni, sparisci solo da ${currentGroup?.name ?: "questo gruppo"}",
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
                    title = "Tracciamento",
                    subtitle = "Come viene rilevata la posizione",
                    icon = Icons.Default.GpsFixed
                )
                SettingsToggleRow(
                    title = "Tracciamento in background",
                    description = "Continua a funzionare anche ad app chiusa",
                    icon = Icons.Default.GpsFixed,
                    checked = isTrackingEnabled,
                    onCheckedChange = onToggleTracking,
                    testTag = "tracking_switch"
                )
                SettingsToggleRow(
                    title = "Risparmio energia",
                    description = "Meno batteria, posizione meno precisa (circa 100 metri)",
                    icon = Icons.Default.BatterySaver,
                    iconTint = if (isPowerSavingMode) RadarSemantic.BatteryOk
                    else MaterialTheme.colorScheme.primary,
                    checked = isPowerSavingMode,
                    onCheckedChange = onTogglePowerSaving,
                    testTag = "power_saving_switch"
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "Frequenza aggiornamento",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Ogni quanto viene rilevata la tua posizione",
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
                        label = { Text("Valore") },
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
                            label = unit.label.take(3),
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
                    text = "Aggiornamento ogni ${formatInterval(effective)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "Durante un viaggio si passa sempre a 5 secondi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Viaggi ----
        item {
            SettingsCard {
                SettingsSectionHeader(
                    title = "Viaggi",
                    subtitle = "Registrazione dei tuoi spostamenti",
                    icon = Icons.Default.Route
                )
                SettingsToggleRow(
                    title = "Rileva i viaggi da solo",
                    description = "Registra quando ti muovi, senza premere niente. Consuma più batteria",
                    icon = Icons.Default.AutoMode,
                    checked = isAutoTripEnabled,
                    onCheckedChange = onToggleAutoTrip,
                    testTag = "auto_trip_switch"
                )
                if (isAutoTripEnabled) {
                    SettingsToggleRow(
                        title = "Condividi i viaggi automatici",
                        description = "Se lo spegni, li vedi solo tu",
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
                            text = "GRUPPO ATTIVO",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(Spacing.xxs))
                        Text(
                            text = currentGroup?.name ?: "Nessun gruppo",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(Spacing.xxs))
                        Text(
                            text = "$activeMemberCount membri attivi" +
                                if (pendingMemberCount > 0) " · $pendingMemberCount in attesa" else "",
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
                        Text("Cambia")
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
                            Text("Modifica")
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
                            Text("Abbandona")
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
                            Text("Elimina")
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
                                text = "Codice invito",
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
                                    ClipData.newPlainText("Codice invito", currentGroup?.joinCode ?: "")
                                )
                                Toast.makeText(context, "Codice copiato", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(Radius.sm)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text("Copia")
                        }
                    }
                }

                if (isOwnerOrAdmin && currentGroup != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    SettingsToggleRow(
                        title = "Approvazione nuovi membri",
                        description = if (currentGroup.requiresApproval)
                            "Devi approvare tu chi entra col codice"
                        else
                            "Chi ha il codice entra subito",
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
                    title = "Aspetto",
                    subtitle = "Tema dell'applicazione",
                    icon = Icons.Default.Palette
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    listOf(
                        Triple(ThemeMode.SYSTEM, "Sistema", Icons.Default.BrightnessAuto),
                        Triple(ThemeMode.LIGHT, "Chiaro", Icons.Default.LightMode),
                        Triple(ThemeMode.DARK, "Scuro", Icons.Default.DarkMode)
                    ).forEach { (mode, label, icon) ->
                        PillChip(
                            label = label,
                            icon = icon,
                            selected = currentThemeMode == mode,
                            onClick = { ThemePreferences.setThemeMode(context, mode) },
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
                    title = "Account",
                    subtitle = "Disconnessione da questo dispositivo",
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
                    Text("Disconnetti account")
                }
            }
        }

        // ---- App ----
        item {
            SettingsCard {
                SettingsSectionHeader(title = "App", icon = Icons.Default.Info)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Versione",
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
                    Text(if (checking) "Controllo in corso..." else "Controlla aggiornamenti")
                }

                when (val result = checkResult) {
                    is CheckResult.Available -> AlertDialog(
                        onDismissRequest = { checkResult = null },
                        title = { Text("Aggiornamento disponibile") },
                        text = { Text("È disponibile la versione ${result.info.versionName}. Scaricala e installala ora.") },
                        confirmButton = {
                            Button(onClick = {
                                checkResult = null
                                AppUpdater.downloadAndInstall(context, result.info.apkUrl)
                            }) { Text("Aggiorna") }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { checkResult = null }) { Text("Dopo") }
                        }
                    )
                    CheckResult.UpToDate -> AlertDialog(
                        onDismissRequest = { checkResult = null },
                        title = { Text("Sei aggiornato") },
                        text = { Text("Stai usando la versione ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), che è l'ultima disponibile.") },
                        confirmButton = {
                            Button(onClick = { checkResult = null }) { Text("OK") }
                        }
                    )
                    CheckResult.NetworkError -> AlertDialog(
                        onDismissRequest = { checkResult = null },
                        title = { Text("Impossibile verificare") },
                        text = { Text("Controlla la connessione e riprova.") },
                        confirmButton = {
                            Button(onClick = { checkResult = null }) { Text("OK") }
                        }
                    )
                    null -> Unit
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
                    title = "Sviluppo",
                    subtitle = "Strumenti di prova",
                    icon = Icons.Default.Code
                )
                SettingsToggleRow(
                    title = "Simula movimento",
                    description = "Muove membri finti sulla mappa. Solo per prove",
                    icon = if (isSimulationRunning) Icons.Default.DirectionsRun else Icons.Default.PlayCircle,
                    checked = isSimulationRunning,
                    onCheckedChange = onToggleSimulation,
                    testTag = "simulation_toggle_button"
                )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.xl),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Box(
                modifier = Modifier
                    .size(Sizes.avatarLg)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(Sizes.iconLg))
            }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.buttonColors(containerColor = iconTint)
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(Radius.sm)) {
                Text("Annulla")
            }
        }
    )
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
                text = "Nuova istantanea",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = "La foto verrà agganciata alla tua posizione attuale sulla mappa.",
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
                    Text("Scatta ora")
                }
                OutlinedButton(
                    onClick = onGallery,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(Sizes.iconMd))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Scegli dalla galleria")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
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

private fun formatInterval(seconds: Int): String = when {
    seconds <= 0 -> "—"
    seconds % 3600 == 0 -> "${seconds / 3600} ore"
    seconds % 60 == 0 -> "${seconds / 60} minuti"
    else -> "$seconds secondi"
}

private fun formatShortTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "adesso"
        diff < 3_600_000 -> "${diff / 60_000} min fa"
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

                GlassSurface(shape = RoundedCornerShape(Radius.lg)) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            RadarPulseAnimation(
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                "Registrazione in corso",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.error
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
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text("Termina e salva")
                        }
                    }
                }
            }
        } else {
            item {
                Button(
                    onClick = onStartTrip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Inizia viaggio")
                }
            }
        }

        if (trips.isEmpty() && activeTrip == null) {
            item {
                EmptyState(
                    icon = Icons.Default.Route,
                    title = "Nessun viaggio",
                    description = "I viaggi registrati appariranno qui"
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
                shape = RoundedCornerShape(Radius.lg),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
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
                            trip.isLive -> MaterialTheme.colorScheme.error
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
                            // Distinguere a colpo d'occhio cosa ha premuto una
                            // persona da cosa ha dedotto l'app.
                            TripBadge(
                                text = if (trip.isLive) "IN CORSO" else trip.source.label.uppercase(),
                                color = when {
                                    trip.isLive -> MaterialTheme.colorScheme.error
                                    trip.source == TripSource.AUTO -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                            if (trip.isPrivate) {
                                TripBadge(
                                    text = "PRIVATO",
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
                                (trip.activityLabel?.let { "  •  $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isMine && !trip.isLive) {
                        IconButton(onClick = { onDeleteTrip(trip.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Elimina viaggio",
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
                // Header: icon + title + close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            if (trip.source == TripSource.AUTO) Icons.Default.AutoMode else Icons.Default.Route,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = listOfNotNull(trip.startPlaceName, trip.endPlaceName)
                                    .takeIf { it.size == 2 }?.joinToString(" → ")
                                    ?: "Viaggio di ${trip.userName}",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = dateFormat.format(Date(trip.startTime)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Chiudi")
                    }
                }

                // Stat tiles
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TripStatTile(
                        label = "Distanza",
                        value = "%.1f".format(trip.distanceMeters / 1000.0),
                        unit = "km",
                        modifier = Modifier.weight(1f)
                    )
                    TripStatTile(
                        label = "Durata",
                        value = "${trip.durationMs / 60000}",
                        unit = "min",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TripStatTile(
                        label = "Media",
                        value = "${(trip.averageSpeedMs * 3.6f).toInt()}",
                        unit = "km/h",
                        modifier = Modifier.weight(1f)
                    )
                    TripStatTile(
                        label = "Massima",
                        value = "${(trip.maxSpeedMs * 3.6f).toInt()}",
                        unit = "km/h",
                        modifier = Modifier.weight(1f)
                    )
                }

                HairlineDivider()

                TripDetailRow("Partenza", timeFormat.format(Date(trip.startTime)))
                if (trip.endTime > 0) {
                    TripDetailRow("Arrivo", timeFormat.format(Date(trip.endTime)))
                }
                if (trip.stoppedMs > 60_000) {
                    TripDetailRow("Tempo fermo", "${trip.stoppedMs / 60000} min")
                }
                trip.activityLabel?.let { TripDetailRow("Spostamento", it) }
                TripDetailRow("Registrazione", trip.source.label)
                TripDetailRow("Da", trip.userName)

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Chiudi") }
                    Spacer(Modifier.width(Spacing.xs))
                    if (isOnMap) {
                        OutlinedButton(onClick = onHideFromMap) {
                            Icon(Icons.Default.LayersClear, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text("Togli dalla mappa")
                        }
                    } else {
                        Button(onClick = onShowOnMap) {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(Sizes.iconSm))
                            Spacer(Modifier.width(Spacing.xs))
                            Text("Mostra sulla mappa")
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
