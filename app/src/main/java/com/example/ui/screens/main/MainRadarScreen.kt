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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.model.*
import com.example.repository.FirebaseRepository
import com.example.service.LocationTrackingService
import com.example.ui.components.*
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.ThemePreferences
import com.example.util.ImageUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
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
    val locations by repository.currentGroupLocations.collectAsState()
    val places by repository.currentGroupPlaces.collectAsState()
    val snapshots by repository.currentGroupSnapshots.collectAsState()
    val messages by repository.currentGroupMessages.collectAsState()
    val members by repository.currentGroupMembers.collectAsState()
    val geofenceAlerts by repository.activeGeofenceAlerts.collectAsState()
    val trackingIntervalSec by repository.trackingFrequencySeconds.collectAsState()
    val isTrackingEnabled by repository.isBackgroundTrackingEnabled.collectAsState()
    val isGlobalGhostMode by repository.isGlobalGhostMode.collectAsState()
    val deepLinkTarget by repository.deepLinkTarget.collectAsState()

    val currentGroup = userGroups.find { it.id == currentUser?.currentGroupId } ?: userGroups.firstOrNull()
    val currentUserId = currentUser?.uid ?: ""
    val isOwnerOrAdmin = currentGroup?.ownerId == currentUserId || members.find { it.userId == currentUserId }?.role in listOf("owner", "admin")
    val pendingMembers = remember(members) { members.filter { it.status == "PENDING" } }
    val activeMembers = remember(members) { members.filter { it.status == "ACTIVE" } }

    var selectedTab by remember { mutableStateOf(0) }
    var selectedMemberForSheet by remember { mutableStateOf<UserLocation?>(null) }
    var selectedPlaceForSheet by remember { mutableStateOf<SavedPlace?>(null) }
    var showAddPlaceDialog by remember { mutableStateOf(false) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var memberToKick by remember { mutableStateOf<GroupMember?>(null) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var targetMapFocus by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var fullScreenImageSource by remember { mutableStateOf<Any?>(null) }
    var selectedSnapshotClusterForGallery by remember { mutableStateOf<PlaceSnapshotCluster?>(null) }
    var capturedSnapshotUri by remember { mutableStateOf<Uri?>(null) }
    var capturedSnapshotBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var pendingMapCameraUri by remember { mutableStateOf<Uri?>(null) }
    var showSnapshotSourceDialog by remember { mutableStateOf(false) }
    var pendingMapCameraAction by remember { mutableStateOf(false) }

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
    ) { uri ->
        if (uri != null) {
            capturedSnapshotUri = uri
        }
    }

    val mapCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (pendingMapCameraAction) {
                pendingMapCameraAction = false
                val uri = pendingMapCameraUri ?: ImageUtils.createTempImageUri(context)
                pendingMapCameraUri = uri
                if (uri != null) {
                    try {
                        takeSnapshotLauncher.launch(uri)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Impossibile aprire fotocamera: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
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
            try {
                takeSnapshotLauncher.launch(tempUri)
            } catch (e: Exception) {
                Toast.makeText(context, "Impossibile aprire fotocamera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingMapCameraAction = true
            mapCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Observe Deep Link Navigation
    LaunchedEffect(deepLinkTarget) {
        val target = deepLinkTarget ?: return@LaunchedEffect
        if (!target.groupId.isNullOrBlank() && target.groupId != currentGroup?.id) {
            repository.selectGroup(target.groupId)
        }
        when (target.destination.uppercase()) {
            "CHAT" -> selectedTab = 1
            "ALERT" -> selectedTab = 2
            "MAP" -> {
                selectedTab = 0
                if (target.latitude != null && target.longitude != null && !target.latitude.isNaN() && !target.longitude.isNaN()) {
                    targetMapFocus = Pair(target.latitude, target.longitude)
                }
            }
            "MEMBERS", "SETTINGS" -> selectedTab = 3
        }
        repository.consumeDeepLinkTarget()
    }

    // Start background tracking service on launch if enabled
    LaunchedEffect(isTrackingEnabled, trackingIntervalSec) {
        if (isTrackingEnabled) {
            LocationTrackingService.start(context, trackingIntervalSec)
        } else {
            LocationTrackingService.stop(context)
        }
    }

    // Auto simulated movement for demo/emulator interaction
    var isSimulationRunning by remember { mutableStateOf(false) }
    LaunchedEffect(isSimulationRunning) {
        while (isSimulationRunning) {
            delay(4000)
            val updated = locations.map { loc ->
                if (loc.userId != currentUser?.uid) {
                    val deltaLat = (Random.nextDouble() - 0.5) * 0.0008
                    val deltaLon = (Random.nextDouble() - 0.5) * 0.0008
                    loc.copy(
                        latitude = loc.latitude + deltaLat,
                        longitude = loc.longitude + deltaLon,
                        speed = (Random.nextFloat() * 10f) + 2f,
                        timestamp = System.currentTimeMillis()
                    )
                } else loc
            }
            updated.forEach { repository.updateLocation(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            RadarPulseAnimation(
                                modifier = Modifier.size(36.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = currentGroup?.name ?: "Radar Gruppo",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Codice: ${currentGroup?.joinCode ?: "---"} • ${members.size} Membri",
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    // Compact SOS / Emergency Alert Button
                    IconButton(
                        onClick = {
                            val gid = currentGroup?.id
                            if (!gid.isNullOrBlank()) {
                                repository.sendSosAlert(gid)
                                Toast.makeText(context, "Allerta SOS inviata al gruppo", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("sos_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Invia SOS",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    // Simulation movement play/pause toggle
                    IconButton(
                        onClick = {
                            isSimulationRunning = !isSimulationRunning
                            Toast.makeText(
                                context,
                                if (isSimulationRunning) "Simulazione movimento GPS avviata" else "Simulazione arrestata",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.testTag("simulation_toggle_button")
                    ) {
                        Icon(
                            if (isSimulationRunning) Icons.Default.DirectionsRun else Icons.Default.PlayCircle,
                            contentDescription = "Simula Movimento",
                            tint = if (isSimulationRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Switch group button (navigates to GroupSelectScreen)
                    IconButton(
                        onClick = onSwitchGroup,
                        modifier = Modifier.testTag("switch_group_button")
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Cambia Gruppo")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Radar, contentDescription = "Radar") },
                    label = { Text("Radar", maxLines = 1, fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_radar_tab")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        BadgedBox(badge = {
                            if (messages.isNotEmpty()) {
                                Badge { Text("${messages.size.coerceAtMost(99)}", maxLines = 1) }
                            }
                        }) {
                            Icon(Icons.Default.Chat, contentDescription = "Chat")
                        }
                    },
                    label = { Text("Chat", maxLines = 1, fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_chat_tab")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Place, contentDescription = "Luoghi") },
                    label = { Text("Luoghi", maxLines = 1, fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_places_tab")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = {
                        BadgedBox(badge = {
                            if (isOwnerOrAdmin && pendingMembers.isNotEmpty()) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.tertiary,
                                    contentColor = MaterialTheme.colorScheme.onTertiary
                                ) {
                                    Text("${pendingMembers.size}", maxLines = 1)
                                }
                            }
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Impostazioni")
                        }
                    },
                    label = { Text("Impostazioni", maxLines = 1, fontSize = 11.sp) },
                    modifier = Modifier.testTag("nav_members_tab")
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> RadarMapTab(
                    locations = locations,
                    places = places,
                    snapshots = snapshots,
                    currentUserId = currentUser?.uid ?: "",
                    targetFocusPoint = targetMapFocus,
                    onMemberClick = { selectedMemberForSheet = it },
                    onPlaceClick = { place ->
                        try {
                            selectedPlaceForSheet = place
                        } catch (e: Exception) {
                            Toast.makeText(context, "Errore selezione luogo: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSnapshotClusterClick = { cluster ->
                        selectedSnapshotClusterForGallery = cluster
                    },
                    onAddPlaceClick = { showAddPlaceDialog = true },
                    onTakeSnapshotClick = {
                        showSnapshotSourceDialog = true
                    }
                )
                1 -> GroupChatTab(
                    messages = messages,
                    currentUserId = currentUser?.uid ?: "",
                    groupId = currentGroup?.id ?: "",
                    repository = repository,
                    onImageClick = { fullScreenImageSource = it }
                )
                2 -> PlacesGeofenceTab(
                    places = places,
                    alerts = geofenceAlerts,
                    onPlaceClick = { place ->
                        try {
                            selectedPlaceForSheet = place
                        } catch (e: Exception) {
                            Toast.makeText(context, "Errore selezione luogo: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAddPlaceClick = { showAddPlaceDialog = true },
                    onDeletePlace = { placeId ->
                        coroutineScope.launch { repository.deletePlace(placeId) }
                    }
                )
                3 -> MembersSettingsTab(
                    members = members,
                    locations = locations,
                    currentGroup = currentGroup,
                    currentUser = currentUser,
                    trackingIntervalSec = trackingIntervalSec,
                    isTrackingEnabled = isTrackingEnabled,
                    isGlobalGhostMode = isGlobalGhostMode,
                    repository = repository,
                    onSwitchGroup = onSwitchGroup,
                    onEditProfileClick = { showEditProfileDialog = true },
                    onRequestKickMember = { mem -> memberToKick = mem },
                    onRequestLeaveGroup = { showLeaveDialog = true },
                    onUpdateInterval = { sec ->
                        repository.setTrackingFrequencySeconds(sec)
                        val formatted = when {
                            sec % 3600 == 0 -> "${sec / 3600} ore"
                            sec % 60 == 0 -> "${sec / 60} minuti"
                            else -> "$sec secondi"
                        }
                        Toast.makeText(context, "Frequenza GPS aggiornata: ogni $formatted", Toast.LENGTH_SHORT).show()
                    },
                    onToggleTracking = { enabled ->
                        repository.setBackgroundTrackingEnabled(enabled)
                        Toast.makeText(context, if (enabled) "Tracciamento in background attivato" else "Tracciamento in background disattivato", Toast.LENGTH_SHORT).show()
                    },
                    onToggleGlobalGhostMode = { enabled ->
                        repository.setGlobalGhostMode(enabled)
                        Toast.makeText(context, if (enabled) "Modalità Fantasma attivata (Sei invisibile)" else "Modalità Fantasma disattivata (Posizione visibile)", Toast.LENGTH_SHORT).show()
                    },
                    onToggleGroupTracking = { enabled ->
                        val gid = currentGroup?.id ?: return@MembersSettingsTab
                        coroutineScope.launch {
                            repository.updateMemberGroupTracking(gid, enabled)
                            Toast.makeText(context, if (enabled) "Posizione condivisa nel gruppo" else "Posizione nascosta in questo gruppo", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onToggleAccessPolicy = { reqApproval ->
                        val gid = currentGroup?.id ?: return@MembersSettingsTab
                        coroutineScope.launch {
                            repository.updateGroupAccessPolicy(gid, reqApproval)
                            Toast.makeText(context, if (reqApproval) "Approvazione admin richiesta per i nuovi membri" else "Accesso diretto con codice invito abilitato", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onApproveJoinRequest = { memberId ->
                        val gid = currentGroup?.id ?: return@MembersSettingsTab
                        coroutineScope.launch {
                            val res = repository.approveJoinRequest(gid, memberId)
                            if (res.isSuccess) {
                                Toast.makeText(context, "Richiesta approvata con successo!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Errore approvazione: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onRejectJoinRequest = { memberId ->
                        val gid = currentGroup?.id ?: return@MembersSettingsTab
                        coroutineScope.launch {
                            val res = repository.rejectJoinRequest(gid, memberId)
                            if (res.isSuccess) {
                                Toast.makeText(context, "Richiesta rifiutata", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Errore: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onMemberClick = { memLoc ->
                        selectedMemberForSheet = memLoc
                    },
                    onLogout = {
                        LocationTrackingService.stop(context)
                        repository.signOut()
                    }
                )
            }

            // Member Detail Bottom Sheet
            selectedMemberForSheet?.let { loc ->
                MemberDetailSheet(
                    location = loc,
                    isSelf = loc.userId == currentUser?.uid,
                    onDismiss = { selectedMemberForSheet = null },
                    onNavigateToChat = {
                        selectedMemberForSheet = null
                        selectedTab = 1
                    },
                    onEditProfileClick = {
                        selectedMemberForSheet = null
                        showEditProfileDialog = true
                    }
                )
            }

            // Place Detail Bottom Sheet
            selectedPlaceForSheet?.let { place ->
                PlaceDetailSheet(
                    place = place,
                    onDismiss = { selectedPlaceForSheet = null },
                    onShowOnMap = {
                        try {
                            if (it.latitude != 0.0 && it.longitude != 0.0 && !it.latitude.isNaN() && !it.longitude.isNaN()) {
                                targetMapFocus = Pair(it.latitude, it.longitude)
                                selectedTab = 0
                            } else {
                                Toast.makeText(context, "Coordinate del luogo non valide", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Errore visualizzazione mappa: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDeletePlace = { toDelete ->
                        coroutineScope.launch {
                            repository.deletePlace(toDelete.id)
                            Toast.makeText(context, "Luogo '${toDelete.name}' eliminato", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // Edit Group Profile Dialog
            if (showEditProfileDialog && currentGroup != null && currentUser != null) {
                val myMember = members.find { it.userId == currentUser?.uid } ?: GroupMember(
                    userId = currentUser?.uid ?: "",
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
                            if (res.isSuccess) {
                                Toast.makeText(context, "Profilo aggiornato", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Errore salvataggio: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }

            // Add Place Dialog
            if (showAddPlaceDialog) {
                val myLoc = locations.find { it.userId == currentUser?.uid } ?: locations.firstOrNull()
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

            // Kick Member Confirmation Dialog (M3 Style)
            memberToKick?.let { targetMember ->
                AlertDialog(
                    onDismissRequest = { memberToKick = null },
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
                                Icons.Default.PersonRemove,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    title = {
                        Text(
                            "Espelli Membro",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )
                    },
                    text = {
                        Text(
                            "Vuoi davvero rimuovere '${targetMember.displayName}' dal gruppo? L'utente non avrà più accesso ai dati della mappa, posizioni e messaggi.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val target = targetMember
                                memberToKick = null
                                if (currentGroup != null) {
                                    coroutineScope.launch {
                                        repository.removeMemberFromGroup(currentGroup.id, target.userId)
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Espelli")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { memberToKick = null },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Annulla")
                        }
                    }
                )
            }

            // Leave Group Confirmation Dialog (M3 Style)
            if (showLeaveDialog && currentGroup != null) {
                AlertDialog(
                    onDismissRequest = { showLeaveDialog = false },
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
                                Icons.Default.ExitToApp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    title = {
                        Text(
                            "Abbandona Gruppo",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )
                    },
                    text = {
                        Text(
                            "Sei sicuro di voler abbandonare il gruppo '${currentGroup.name}'? La tua posizione non sarà più condivisa e non riceverai più notifiche.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showLeaveDialog = false
                                coroutineScope.launch {
                                    repository.leaveGroup(currentGroup.id)
                                    onSwitchGroup()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Abbandona")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { showLeaveDialog = false },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Annulla")
                        }
                    }
                )
            }

            // Snapshot Source Selection Dialog (Camera vs Gallery)
            if (showSnapshotSourceDialog) {
                AlertDialog(
                    onDismissRequest = { showSnapshotSourceDialog = false },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    icon = {
                        Icon(
                            Icons.Default.AddAPhoto,
                            contentDescription = null,
                            tint = Color(0xFFEA580C),
                            modifier = Modifier.size(32.dp)
                        )
                    },
                    title = {
                        Text(
                            "Nuova Istantanea Luogo",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "Scegli come acquisire la foto da geolocalizzare sulla mappa:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            FilledTonalButton(
                                onClick = {
                                    showSnapshotSourceDialog = false
                                    launchMapCameraSafe()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scatta Foto Live")
                            }

                            OutlinedButton(
                                onClick = {
                                    showSnapshotSourceDialog = false
                                    snapshotGalleryLauncher.launch("image/*")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scegli da Galleria")
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(
                            onClick = { showSnapshotSourceDialog = false },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Annulla")
                        }
                    }
                )
            }

            // Fullscreen Image Viewer
            fullScreenImageSource?.let { source ->
                FullScreenMediaViewer(
                    imageSource = source,
                    onDismiss = { fullScreenImageSource = null }
                )
            }

            // Snapshot Cluster Gallery Viewer
            selectedSnapshotClusterForGallery?.let { cluster ->
                SnapshotClusterGalleryDialog(
                    snapshots = cluster.snapshots,
                    onDismiss = { selectedSnapshotClusterForGallery = null }
                )
            }

            // Add Place Snapshot Dialog
            if (capturedSnapshotUri != null || capturedSnapshotBitmap != null) {
                val myLoc = locations.find { it.userId == currentUser?.uid } ?: locations.firstOrNull()
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
    }
}

// ================== TAB 0: RADAR & MAP ==================

@Composable
private fun RadarMapTab(
    locations: List<UserLocation>,
    places: List<SavedPlace>,
    snapshots: List<PlaceSnapshot> = emptyList(),
    currentUserId: String,
    targetFocusPoint: Pair<Double, Double>? = null,
    onMemberClick: (UserLocation) -> Unit,
    onPlaceClick: (SavedPlace) -> Unit,
    onSnapshotClusterClick: (PlaceSnapshotCluster) -> Unit = {},
    onAddPlaceClick: () -> Unit,
    onTakeSnapshotClick: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Live OSM Map View with 60 FPS layer switching & Expandable Layers Button
        OsmMapView(
            locations = locations,
            places = places,
            snapshots = snapshots,
            currentUserId = currentUserId,
            targetFocusPoint = targetFocusPoint,
            onMemberSelected = onMemberClick,
            onPlaceSelected = onPlaceClick,
            onSnapshotClusterSelected = onSnapshotClusterClick
        )

        // Top Floating Member Carousel
        if (locations.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 0.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(locations, key = { it.userId }) { userLoc ->
                    val isSelf = userLoc.userId == currentUserId
                    val timeStr = formatShortTime(userLoc.timestamp)
                    val avatarBitmap = remember(userLoc.photoBase64) {
                        ImageUtils.base64ToBitmap(userLoc.photoBase64)
                    }
                    val effectiveName = if (!userLoc.nickname.isNullOrBlank()) userLoc.nickname else userLoc.userName

                    Card(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onMemberClick(userLoc) }
                            .shadow(4.dp, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelf) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                if (avatarBitmap != null) {
                                    Image(
                                        bitmap = avatarBitmap.asImageBitmap(),
                                        contentDescription = "Avatar",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Text(
                                        text = userLoc.userName.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            color = if (isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = if (isSelf) "Tu ($effectiveName)" else effectiveName,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${userLoc.batteryLevel}% • $timeStr",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (userLoc.batteryLevel <= 20) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Left Compact Actions (Add Place mini-FAB & Take Snapshot mini-FAB right next to each other)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Add Place Mini-FAB (Compact icon only)
            SmallFloatingActionButton(
                onClick = onAddPlaceClick,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape,
                modifier = Modifier
                    .size(44.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), CircleShape)
                    .testTag("add_place_fab")
            ) {
                Icon(
                    Icons.Default.AddLocationAlt,
                    contentDescription = "Aggiungi Luogo",
                    modifier = Modifier.size(22.dp)
                )
            }

            // Take Snapshot Mini-FAB (Placed immediately to the right of Add Place, compact icon only)
            SmallFloatingActionButton(
                onClick = onTakeSnapshotClick,
                containerColor = Color(0xFFEA580C),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier
                    .size(44.dp)
                    .border(1.dp, Color(0xFFC2410C).copy(alpha = 0.4f), CircleShape)
                    .testTag("take_geo_snapshot_fab")
            ) {
                Icon(
                    Icons.Default.AddAPhoto,
                    contentDescription = "Scatta Istantanea",
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ================== TAB 1: GROUP CHAT & SOCIAL ==================

@Composable
private fun GroupChatTab(
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

    val cameraPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { isSuccess ->
        val uri = pendingChatCameraUri
        if (isSuccess && uri != null && groupId.isNotBlank()) {
            isUploading = true
            coroutineScope.launch {
                val compressRes = repository.compressImageToBase64(uri, maxDimension = 1280, quality = 85)
                isUploading = false
                if (compressRes.isSuccess) {
                    val base64 = compressRes.getOrNull()
                    if (!base64.isNullOrBlank()) {
                        val msg = ChatMessage(
                            text = "Foto scattata in chat",
                            imageBase64 = base64,
                            type = MessageType.IMAGE
                        )
                        repository.sendMessage(groupId, msg)
                    }
                } else {
                    Toast.makeText(context, "Errore elaborazione foto", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(context, "Nessuna foto acquisita", Toast.LENGTH_SHORT).show()
        }
    }

    val chatCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (pendingChatCamera) {
                pendingChatCamera = false
                val uri = pendingChatCameraUri ?: ImageUtils.createTempImageUri(context)
                pendingChatCameraUri = uri
                if (uri != null) {
                    try {
                        cameraPhotoLauncher.launch(uri)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Impossibile avviare fotocamera: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            pendingChatCamera = false
            Toast.makeText(context, "Permesso fotocamera necessario per scattare foto live", Toast.LENGTH_SHORT).show()
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
            try {
                cameraPhotoLauncher.launch(tempUri)
            } catch (e: Exception) {
                Toast.makeText(context, "Impossibile avviare fotocamera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingChatCamera = true
            chatCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && groupId.isNotBlank()) {
            isUploading = true
            coroutineScope.launch {
                val compressRes = repository.compressImageToBase64(uri)
                isUploading = false
                if (compressRes.isSuccess) {
                    val base64 = compressRes.getOrNull()
                    if (!base64.isNullOrBlank()) {
                        val msg = ChatMessage(
                            text = "Immagine condivisa",
                            imageBase64 = base64,
                            type = MessageType.IMAGE
                        )
                        repository.sendMessage(groupId, msg)
                    }
                } else {
                    Toast.makeText(context, "Errore caricamento immagine", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                )
            }
    ) {
        // Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                val isMe = msg.senderId == currentUserId
                ChatMessageBubble(
                    message = msg,
                    isMe = isMe,
                    onImageClick = onImageClick,
                    onDismissKeyboard = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                )
            }
        }

        if (isUploading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Input Field & Buttons
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Live Camera Button
                IconButton(
                    onClick = { launchChatCameraSafe() },
                    modifier = Modifier.testTag("chat_camera_button")
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = "Scatta Foto Live",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Photo Attachment Button
                IconButton(
                    onClick = { photoPickerLauncher.launch("image/*") },
                    modifier = Modifier.testTag("attach_photo_button")
                ) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = "Allega Immagine",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Text Input
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Scrivi un messaggio...") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field"),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank()) {
                            val msg = ChatMessage(text = inputText.trim(), type = MessageType.TEXT)
                            repository.sendMessage(groupId, msg)
                            inputText = ""
                        }
                    })
                )

                // Send Button
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            val msg = ChatMessage(text = inputText.trim(), type = MessageType.TEXT)
                            repository.sendMessage(groupId, msg)
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .testTag("send_message_button")
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Invia",
                        tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    isMe: Boolean,
    onImageClick: (Any) -> Unit,
    onDismissKeyboard: () -> Unit = {}
) {
    if (message.type == MessageType.GEOFENCE_ALERT) {
        // Geofence System Pill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismissKeyboard() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSecondaryContainer)
                    )
                }
            }
        }
        return
    }

    if (message.type == MessageType.SOS_ALERT) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismissKeyboard() }
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.CrisisAlert, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
                Column {
                    Text(
                        text = "Allerta SOS: ${message.senderName}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    )
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        return
    }

    // Normal Message Bubble
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismissKeyboard() },
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        if (!isMe) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
            )
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMe) 16.dp else 4.dp,
                bottomEnd = if (isMe) 4.dp else 16.dp
            ),
            color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                val base64Bitmap = remember(message.imageBase64) {
                    ImageUtils.base64ToBitmap(message.imageBase64)
                }
                val imageSource = message.getImageSource()

                if (base64Bitmap != null) {
                    Image(
                        bitmap = base64Bitmap.asImageBitmap(),
                        contentDescription = "Immagine condivisa",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { message.imageBase64?.let { onImageClick(it) } },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                } else if (imageSource != null) {
                    AsyncImage(
                        model = imageSource,
                        contentDescription = "Immagine condivisa",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageClick(imageSource) },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                if (message.text.isNotBlank() && message.text != "Immagine condivisa" && message.text != "Foto condivisa") {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    ),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

// ================== TAB 2: PLACES & GEOFENCE ==================

@Composable
private fun PlacesGeofenceTab(
    places: List<SavedPlace>,
    alerts: List<GeofenceEvent>,
    onPlaceClick: (SavedPlace) -> Unit,
    onAddPlaceClick: () -> Unit,
    onDeletePlace: (String) -> Unit
) {
    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddPlaceClick,
                icon = { Icon(Icons.Default.AddLocation, contentDescription = null) },
                text = { Text("Aggiungi Luogo") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("add_place_tab_fab")
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Geofence Activity Summary Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = Color.White)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Zone Sicure del Gruppo",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                "Ricevi notifiche automatiche all'arrivo o partenza dei membri.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                }
            }

            // Places List
            item {
                Text(
                    "Luoghi Salvati (${places.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (places.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.PinDrop, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Nessun luogo sicuro impostato", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text(
                                "Aggiungi Casa, Scuola, Lavoro o Palestra per ricevere avvisi automatici quando un membro entra o esce.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(places, key = { it.id }) { place ->
                    PlaceItemCard(
                        place = place,
                        onClick = { onPlaceClick(place) },
                        onDelete = { onDeletePlace(place.id) }
                    )
                }
            }

            // Recent Alerts
            if (alerts.isNotEmpty()) {
                item {
                    Text(
                        "Attività Recente Geofence",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }

                items(alerts.take(5), key = { it.id }) { alert ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                if (alert.isInside) Icons.Default.Login else Icons.Default.Logout,
                                contentDescription = null,
                                tint = if (alert.isInside) Color(0xFF22C55E) else Color(0xFFEAB308),
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${alert.userName} ${if (alert.isInside) "è arrivato a" else "è partito da"} ${alert.placeName}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    SimpleDateFormat("HH:mm - dd MMM", Locale.getDefault()).format(Date(alert.timestamp)),
                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceItemCard(
    place: SavedPlace,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("place_card_${place.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        when (place.category) {
                            PlaceCategory.HOME -> Color(0xFF648AC8).copy(alpha = 0.2f)
                            PlaceCategory.WORK -> Color(0xFF6A948D).copy(alpha = 0.2f)
                            PlaceCategory.SCHOOL -> Color(0xFFD97706).copy(alpha = 0.2f)
                            PlaceCategory.GYM -> Color(0xFFDC2626).copy(alpha = 0.2f)
                            PlaceCategory.OTHER -> Color(0xFF64748B).copy(alpha = 0.2f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when (place.category) {
                        PlaceCategory.HOME -> Icons.Default.Home
                        PlaceCategory.WORK -> Icons.Default.Work
                        PlaceCategory.SCHOOL -> Icons.Default.School
                        PlaceCategory.GYM -> Icons.Default.FitnessCenter
                        PlaceCategory.OTHER -> Icons.Default.Place
                    },
                    contentDescription = null,
                    tint = when (place.category) {
                        PlaceCategory.HOME -> Color(0xFF648AC8)
                        PlaceCategory.WORK -> Color(0xFF6A948D)
                        PlaceCategory.SCHOOL -> Color(0xFFD97706)
                        PlaceCategory.GYM -> Color(0xFFDC2626)
                        PlaceCategory.OTHER -> Color(0xFF64748B)
                    },
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Categoria: ${place.category.label} • Raggio: ${place.radiusMeters.toInt()}m",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Elimina Luogo", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ================== TAB 3: USER & GROUP SETTINGS ==================

enum class TrackingTimeUnit(val label: String, val multiplier: Int) {
    SECONDS("Secondi", 1),
    MINUTES("Minuti", 60),
    HOURS("Ore", 3600)
}

@Composable
private fun MembersSettingsTab(
    members: List<GroupMember>,
    locations: List<UserLocation>,
    currentGroup: GroupData?,
    currentUser: UserData?,
    trackingIntervalSec: Int,
    isTrackingEnabled: Boolean,
    isGlobalGhostMode: Boolean,
    repository: FirebaseRepository,
    onSwitchGroup: () -> Unit,
    onEditProfileClick: () -> Unit,
    onRequestKickMember: (GroupMember) -> Unit,
    onRequestLeaveGroup: () -> Unit,
    onUpdateInterval: (Int) -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onToggleGlobalGhostMode: (Boolean) -> Unit,
    onToggleGroupTracking: (Boolean) -> Unit,
    onToggleAccessPolicy: (Boolean) -> Unit,
    onApproveJoinRequest: (String) -> Unit,
    onRejectJoinRequest: (String) -> Unit,
    onMemberClick: (UserLocation) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val currentThemeMode by ThemePreferences.themeModeFlow.collectAsState()

    val currentUserId = currentUser?.uid ?: ""
    val myMember = members.find { it.userId == currentUserId }
    val myRole = myMember?.role ?: "member"
    val isOwnerOrAdmin = myRole == "owner" || myRole == "admin" || currentGroup?.ownerId == currentUserId

    val pendingMembers = remember(members) { members.filter { it.status == "PENDING" } }
    val activeMembers = remember(members) { members.filter { it.status == "ACTIVE" } }

    // Dynamic Interval State (Textfield + Unit Selector)
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

    fun applyIntervalChange(rawText: String, unit: TrackingTimeUnit) {
        val num = rawText.toIntOrNull() ?: 1
        val calculatedSeconds = (num * unit.multiplier).coerceIn(5, 86400)
        onUpdateInterval(calculatedSeconds)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {

        // ================= SECTION 0: PENDING APPROVAL REQUESTS (ADMIN ONLY) =================
        if (isOwnerOrAdmin && pendingMembers.isNotEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.PendingActions,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                "Richieste di Accesso in Sospeso (${pendingMembers.size})",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            )
                        }

                        Text(
                            "Questi utenti hanno inserito il codice invito e sono in attesa della tua approvazione per accedere alla mappa e ai messaggi.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f))
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            pendingMembers.forEach { pendingUser ->
                                val avatarBitmap = remember(pendingUser.photoBase64) {
                                    ImageUtils.base64ToBitmap(pendingUser.photoBase64)
                                }
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.tertiary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (avatarBitmap != null) {
                                                Image(
                                                    bitmap = avatarBitmap.asImageBitmap(),
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Text(
                                                    pendingUser.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
                                                    color = MaterialTheme.colorScheme.onTertiary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                pendingUser.displayName,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "In attesa di approvazione",
                                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.tertiary)
                                            )
                                        }

                                        // Action buttons: Approve & Reject
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            FilledTonalIconButton(
                                                onClick = { onApproveJoinRequest(pendingUser.userId) },
                                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = "Approva")
                                            }

                                            FilledTonalIconButton(
                                                onClick = { onRejectJoinRequest(pendingUser.userId) },
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
                            }
                        }
                    }
                }
            }
        }

        // ================= SECTION 1: IMPOSTAZIONI UTENTE GLOBALI =================
        item {
            Text(
                "Impostazioni Utente",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            )
        }

        // Profile Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val avatarBitmap = remember(currentUser?.photoBase64) {
                            ImageUtils.base64ToBitmap(currentUser?.photoBase64)
                        }
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarBitmap != null) {
                                Image(
                                    bitmap = avatarBitmap.asImageBitmap(),
                                    contentDescription = "Foto Profilo",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(
                                    text = currentUser?.displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentUser?.displayName ?: "Utente Radar",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!currentUser?.email.isNullOrBlank()) {
                                Text(
                                    text = currentUser?.email ?: "",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = onEditProfileClick,
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Modifica")
                        }
                    }
                }
            }
        }

        // Global Ghost Mode & Theme Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Global Ghost Mode Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    if (isGlobalGhostMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = if (isGlobalGhostMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    "Modalità Fantasma Globale",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                            Text(
                                "Nasconde la tua posizione e ti rende invisibile su tutti i gruppi contemporaneamente",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                        Switch(
                            checked = isGlobalGhostMode,
                            onCheckedChange = onToggleGlobalGhostMode,
                            modifier = Modifier.testTag("global_ghost_mode_switch")
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // App Theme Selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Tema Applicazione", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                ThemeMode.SYSTEM to "Sistema",
                                ThemeMode.LIGHT to "Chiaro",
                                ThemeMode.DARK to "Scuro"
                            ).forEach { (mode, label) ->
                                val selected = currentThemeMode == mode
                                FilterChip(
                                    selected = selected,
                                    onClick = { ThemePreferences.setThemeMode(context, mode) },
                                    label = { Text(label, fontSize = 12.sp) },
                                    leadingIcon = {
                                        Icon(
                                            when (mode) {
                                                ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                                ThemeMode.LIGHT -> Icons.Default.LightMode
                                                ThemeMode.DARK -> Icons.Default.DarkMode
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ================= SECTION 2: TRACCIAMENTO GPS GLOBALE =================
        item {
            Text(
                "Tracciamento GPS & Frequenza",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Background Tracking Switch (Default enabled)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Tracciamento in Background", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                            Text(
                                "Mantiene attiva la posizione e il radar tramite servizio in background persistente",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                        Switch(
                            checked = isTrackingEnabled,
                            onCheckedChange = onToggleTracking,
                            modifier = Modifier.testTag("tracking_switch")
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Dynamic Frequency Input (Numeric TextField + Unit Selector)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Intervallo Aggiornamento Posizione GPS",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = intervalText,
                                onValueChange = { input ->
                                    val filtered = input.filter { it.isDigit() }.take(5)
                                    intervalText = filtered
                                    if (filtered.isNotBlank()) {
                                        applyIntervalChange(filtered, intervalUnit)
                                    }
                                },
                                label = { Text("Valore") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(0.45f)
                                    .testTag("interval_input_field"),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            // Unit Selector (Segmented Chips)
                            Row(
                                modifier = Modifier.weight(0.55f),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    TrackingTimeUnit.SECONDS to "Sec",
                                    TrackingTimeUnit.MINUTES to "Min",
                                    TrackingTimeUnit.HOURS to "Ore"
                                ).forEach { (unit, label) ->
                                    val isSel = intervalUnit == unit
                                    FilterChip(
                                        selected = isSel,
                                        onClick = {
                                            intervalUnit = unit
                                            if (intervalText.isNotBlank()) {
                                                applyIntervalChange(intervalText, unit)
                                            }
                                        },
                                        label = { Text(label, fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }

                        val calculated = (intervalText.toIntOrNull() ?: 1) * intervalUnit.multiplier
                        Text(
                            text = "Intervallo effettivo: ogni $calculated secondi",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }

        // ================= SECTION 3: IMPOSTAZIONI GRUPPO ATTUALE =================
        item {
            Text(
                "Impostazioni Gruppo: ${currentGroup?.name ?: ""}",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Group Info & Invite Code Banner
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = currentGroup?.name ?: "Gruppo Famiglia",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "${activeMembers.size} Membri Attivi" + if (pendingMembers.isNotEmpty()) " • ${pendingMembers.size} in attesa" else "",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }

                        OutlinedButton(
                            onClick = onSwitchGroup,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cambia")
                        }
                    }

                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // Join Code Banner
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Codice Invito:", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onPrimaryContainer))
                            Text(
                                currentGroup?.joinCode ?: "------",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            )
                        }
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Codice Invito", currentGroup?.joinCode ?: "")
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Codice invito copiato!", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copia")
                        }
                    }
                }
            }
        }

        // Per-Group Settings Card (Sharing in this group & Admin Access Policy)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Per-Group Location Sharing Toggle (for current user)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Condividi Posizione in Questo Gruppo",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                "Se disattivato, la tua posizione non sarà visibile solo ai membri di questo gruppo",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                        Switch(
                            checked = myMember?.isTrackingActive ?: true,
                            onCheckedChange = onToggleGroupTracking,
                            modifier = Modifier.testTag("group_tracking_switch")
                        )
                    }

                    // Admin Access Policy Toggle (Visible only to Admin/Owner)
                    if (isOwnerOrAdmin && currentGroup != null) {
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.AdminPanelSettings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        "Richiedi Approvazione Nuovi Membri",
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                                Text(
                                    if (currentGroup.requiresApproval)
                                        "I nuovi utenti che inseriscono il codice invito devono essere approvati da te prima di accedere."
                                    else
                                        "Chiunque abbia il codice invito accede direttamente al gruppo senza approvazione.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                            Switch(
                                checked = currentGroup.requiresApproval,
                                onCheckedChange = onToggleAccessPolicy,
                                modifier = Modifier.testTag("access_policy_switch")
                            )
                        }
                    }
                }
            }
        }

        // Group Members List
        item {
            Text(
                "Gestione Membri del Gruppo (${activeMembers.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        items(activeMembers, key = { it.userId }) { member ->
            val isMe = member.userId == currentUserId
            val loc = locations.find { it.userId == member.userId }
            val avatarBitmap = remember(member.photoBase64) { ImageUtils.base64ToBitmap(member.photoBase64) }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isMe) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    else MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = loc != null) { loc?.let { onMemberClick(it) } }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                member.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                member.displayName,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (member.role == "owner") {
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.primary) {
                                    Text("PROPRIETARIO", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                            } else if (member.role == "admin") {
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondary) {
                                    Text("ADMIN", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                            }
                        }
                        if (!member.nickname.isNullOrBlank()) {
                            Text(
                                "Soprannome: ${member.nickname}",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                        if (loc != null) {
                            Text(
                                "Batteria: ${loc.batteryLevel}% • Aggiornato: ${formatShortTime(loc.timestamp)}",
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }

                    // Kick Button if Admin/Owner and not kicking self
                    if (isOwnerOrAdmin && !isMe) {
                        IconButton(
                            onClick = { onRequestKickMember(member) }
                        ) {
                            Icon(Icons.Default.PersonRemove, contentDescription = "Rimuovi Membro", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        // Leave Group Button
        item {
            OutlinedButton(
                onClick = onRequestLeaveGroup,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("leave_group_button")
            ) {
                Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Abbandona Questo Gruppo")
            }
        }

        // Sign Out Button
        item {
            Button(
                onClick = onLogout,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("logout_app_button")
            ) {
                Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Disconnetti Account")
            }
        }
    }
}

// ================== HELPER FUNCTIONS ==================

private fun formatShortTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "Adesso"
        diff < 3600_000 -> "${diff / 60_000}m fa"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
