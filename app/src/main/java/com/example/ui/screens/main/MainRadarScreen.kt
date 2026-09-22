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
    val isHighPrecisionMovement by repository.isHighPrecisionMovement.collectAsState()
    val filterQ by repository.filterQ.collectAsState()
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
    data class FullScreenRequest(val source: Any, val sender: String? = null, val timestamp: Long? = null)
    var fullScreenRequest by remember { mutableStateOf<FullScreenRequest?>(null) }
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
    var followCam by remember { mutableStateOf(false) }
    // Quando si smette di seguire qualcuno (per qualsiasi motivo), torna al 2D.
    LaunchedEffect(followedUserId) { if (followedUserId == null) followCam = false }
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

    // Heatmap posizioni storiche del membro selezionato.
    var heatmapPoints by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var heatmapFitToken by remember { mutableIntStateOf(0) }

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

    // Segnala al repository quando il radar e' in primo piano: serve a sopprimere
    // la notifica del vocale mentre e' in autoplay sulla mappa (evita il taglio audio).
    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppForeground by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { repository.setRadarForeground(true); isAppForeground = true }
                Lifecycle.Event.ON_PAUSE -> { repository.setRadarForeground(false); isAppForeground = false }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            repository.setRadarForeground(false)
        }
    }

    // --- Nota vocale (push-to-talk, in basso a destra sulla mappa) ---
    var isRecordingVoice by remember { mutableStateOf(false) }
    val voiceRecording = remember { mutableStateOf<VoiceUtils.Recording?>(null) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(context, "Permesso microfono negato", Toast.LENGTH_SHORT).show()
    }
    fun startVoiceRecording(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return false
        }
        if (currentGroup?.id.isNullOrBlank()) return false
        val rec = VoiceUtils.startRecording(context) ?: run {
            Toast.makeText(context, "Microfono non disponibile", Toast.LENGTH_SHORT).show()
            return false
        }
        voiceRecording.value = rec
        isRecordingVoice = true
        return true
    }
    fun stopAndSendVoice() {
        val rec = voiceRecording.value ?: return
        voiceRecording.value = null
        isRecordingVoice = false
        val durationMs = rec.stop()
        val gid = currentGroup?.id
        // Sotto ~mezzo secondo e' un tocco involontario: si scarta.
        if (durationMs == null || durationMs < 500 || gid == null) {
            runCatching { rec.file.delete() }
            return
        }
        coroutineScope.launch {
            val b64 = withContext(Dispatchers.IO) { VoiceUtils.fileToBase64(rec.file) }
            runCatching { rec.file.delete() }
            if (b64 != null) {
                val myLoc = locations.find { it.userId == currentUserId }
                repository.sendVoiceMessage(gid, b64, durationMs, myLoc?.latitude, myLoc?.longitude, myLoc?.currentPlaceName)
            }
        }
    }

    // Riproduce una nota vocale una volta: scarica l'audio dal doc separato
    // (una sola volta, poi cache) e lo suona. Fire-and-forget.
    fun playVoiceNoteById(groupId: String?, messageId: String, audioUrl: String? = null) {
        val gid = groupId ?: run {
            android.util.Log.w("VoicePlay", "groupId nullo per $messageId")
            return
        }
        val resolvedUrl = audioUrl ?: repository.currentGroupMessages.value.find { it.id == messageId }?.audioUrl
        android.util.Log.d("VoicePlay", "play $messageId url=$resolvedUrl")
        coroutineScope.launch {
            val path = repository.getVoiceNotePath(gid, messageId, resolvedUrl)
            if (path == null) {
                android.util.Log.w("VoicePlay", "path nullo per $messageId (url=$resolvedUrl)")
                Toast.makeText(context, "Vocale: download fallito (url=${if (resolvedUrl.isNullOrBlank()) "assente" else "ok"})", Toast.LENGTH_LONG).show()
                return@launch
            }
            android.util.Log.d("VoicePlay", "path risolto $path")
            try {
                // Chiede il focus audio: se in quel momento parte un suono di
                // notifica, viene abbassato/messo in pausa invece di troncare il
                // vocale in riproduzione.
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                val audioAttrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(audioAttrs)
                            .build()
                        audioManager?.requestAudioFocus(req)
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager?.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    }
                }
                MediaPlayer().apply {
                    setAudioAttributes(audioAttrs)
                    setDataSource(path)
                    prepare()
                    setOnCompletionListener { runCatching { it.release() } }
                    setOnErrorListener { mp, what, extra ->
                        android.util.Log.e("VoicePlay", "MediaPlayer errore what=$what extra=$extra")
                        Toast.makeText(context, "Vocale: player errore $what/$extra", Toast.LENGTH_LONG).show()
                        runCatching { mp.release() }
                        true
                    }
                    start()
                    android.util.Log.d("VoicePlay", "start() ok per $messageId")
                }
            } catch (e: Exception) {
                android.util.Log.e("VoicePlay", "riproduzione fallita $messageId: ${e.message}", e)
                Toast.makeText(context, "Vocale: riproduzione fallita ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Anello pulsante sul marker di chi ha appena inviato un vocale + autoplay
    // (se attivo in Impostazioni) per chi sta guardando la mappa. L'anello resta
    // acceso per la durata del vocale (min 4s), cosi' si vede finche' si ascolta.
    val latestVoicePing by repository.latestVoicePing.collectAsState()
    val voiceAutoplay by repository.isVoiceAutoplayEnabled.collectAsState()
    var speakingUserId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(latestVoicePing) {
        val ping = latestVoicePing ?: return@LaunchedEffect
        if (ping.userId == currentUserId) return@LaunchedEffect
        if (System.currentTimeMillis() - ping.timestamp > 15_000) return@LaunchedEffect
        speakingUserId = ping.userId
        if (voiceAutoplay) playVoiceNoteById(currentGroup?.id, ping.messageId, ping.audioUrl)
        delay(maxOf(4_000L, ping.durationMs + 1_200L))
        speakingUserId = null
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
                val sid = target.senderId
                if (sid != null) {
                    // Attiva il follow sul membro: gestisce anche il caso in cui le
                    // posizioni non siano ancora caricate (il follow le aspetta e
                    // centra la mappa non appena arrivano).
                    followedUserId = sid
                    focusTargetUserId = sid
                    // Centra subito se la posizione e' gia' disponibile.
                    val memberLoc = locations.find { it.userId == sid }
                    if (memberLoc != null) {
                        focusMapOn(memberLoc.latitude, memberLoc.longitude, collapse = false)
                    } else if (target.latitude != null && target.longitude != null &&
                        !target.latitude.isNaN() && !target.longitude.isNaN()) {
                        focusMapOn(target.latitude, target.longitude, collapse = false)
                    }
                } else {
                    // Nessun membro specifico: centra sulle coordinate del payload.
                    if (target.latitude != null && target.longitude != null &&
                        !target.latitude.isNaN() && !target.longitude.isNaN()) {
                        focusMapOn(target.latitude, target.longitude, collapse = false)
                    }
                }
            }
        }
        repository.consumeDeepLinkTarget()
    }

    // Avere la chat aperta e in primo piano equivale a leggerla: non serve rientrare
    // dall'icona. Rifà mark-read a OGNI nuovo messaggio (chiave sull'ultimo id) e al
    // ritorno in primo piano, cosi' chi ha la chat davanti risulta subito "ha letto".
    LaunchedEffect(activeFullPanel, currentGroup?.id, messages.lastOrNull()?.id, isAppForeground) {
        val gid = currentGroup?.id
        if (activeFullPanel == RadarPanel.CHAT && isAppForeground && !gid.isNullOrBlank()) {
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
    // locations aggiornato in tempo reale dentro la coroutine: senza rememberUpdatedState
    // la variabile catturata al lancio dell'effetto resterebbe congelata alla prima
    // composizione, e la simulazione partirebbe dalla posizione "vecchia" del membro.
    val currentLocations by rememberUpdatedState(locations)
    val simCurrentUid by rememberUpdatedState(currentUser?.uid)

    // Simulazione percorso realistico in auto: waypoint che formano un loop con curve,
    // velocita' ~40 km/h (11 m/s), bearing calcolato dal segmento corrente, update ogni 1s.
    // Muove il PRIMO membro non-self presente nel gruppo.
    LaunchedEffect(isSimulationRunning) {
        if (!isSimulationRunning) {
            repository.clearSimRecentPoints()
            return@LaunchedEffect
        }

        val speedMs = 11.0  // ~40 km/h

        // Loop: da dove si trova ora, sceglie una destinazione casuale a ~1-2 km,
        // chiede a OSRM il PERCORSO SU STRADA e ci fa camminare sopra il pallino.
        while (isSimulationRunning) {
            val target = currentLocations.firstOrNull { it.userId != simCurrentUid } ?: break
            val originLat = target.latitude
            val originLon = target.longitude

            // Destinazione casuale ~1-2 km in una direzione qualsiasi.
            val bearingRad = Random.nextDouble(0.0, 2 * Math.PI)
            val distKm = Random.nextDouble(1.0, 2.0)
            val dLat = (distKm / 111.0) * Math.cos(bearingRad)
            val dLon = (distKm / (111.0 * Math.cos(Math.toRadians(originLat)))) * Math.sin(bearingRad)
            val destLat = originLat + dLat
            val destLon = originLon + dLon

            // Percorso su strada (fallback: linea retta se OSRM non risponde).
            val roadPath = com.example.util.RoadMatcher.route(originLat, originLon, destLat, destLon)
                ?: listOf(originLat to originLon, destLat to destLon)

            for (segIdx in 0 until roadPath.size - 1) {
                if (!isSimulationRunning) break
                val (aLat, aLon) = roadPath[segIdx]
                val (bLat, bLon) = roadPath[segIdx + 1]

                val results = FloatArray(1)
                android.location.Location.distanceBetween(aLat, aLon, bLat, bLon, results)
                val segLen = results[0].toDouble().coerceAtLeast(1.0)

                val y = Math.sin(Math.toRadians(bLon - aLon)) * Math.cos(Math.toRadians(bLat))
                val x = Math.cos(Math.toRadians(aLat)) * Math.sin(Math.toRadians(bLat)) -
                        Math.sin(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) *
                        Math.cos(Math.toRadians(bLon - aLon))
                val bearing = ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()

                var t = 0.0
                while (t < 1.0 && isSimulationRunning) {
                    val curLat = aLat + (bLat - aLat) * t
                    val curLon = aLon + (bLon - aLon) * t
                    repository.simulateMemberLocation(
                        target.copy(
                            latitude = curLat,
                            longitude = curLon,
                            speed = speedMs.toFloat() + (Random.nextFloat() - 0.5f) * 2f,
                            bearing = bearing,
                            accuracy = 8f,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                    delay(1000L)
                    t += speedMs / segLen
                }
            }
            // Arrivato a destinazione: riparte con un nuovo tragitto su strada.
        }
    }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val sheetContentHeight = screenHeight * 0.86f

    val useNewMap by ThemePreferences.useNewMapFlow.collectAsState()
    val mapStyle by ThemePreferences.mapStyleFlow.collectAsState()
    val terrainEnabled by ThemePreferences.terrainFlow.collectAsState()
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      if (useNewMap) {
        com.example.ui.components.MapLibreMapView(
            locations = locations,
            currentUserId = currentUserId,
            styleUrl = mapStyle.url,
            places = places,
            snapshots = snapshots,
            targetFocusPoint = targetMapFocus,
            focusToken = focusToken,
            followedUserId = followedUserId,
            followCam = followCam,
            onFollowCamChange = { followCam = it },
            terrainEnabled = terrainEnabled,
            speakingUserId = speakingUserId,
            heatmapPoints = heatmapPoints,
            heatmapFitToken = heatmapFitToken,
            onPlaceSelected = { selectedPlaceForSheet = it },
            onSnapshotClusterSelected = { selectedSnapshotClusterForGallery = it },
            onMemberSelected = { loc ->
                val ping = repository.latestVoicePing.value
                if (ping != null && ping.userId == loc.userId &&
                    System.currentTimeMillis() - ping.timestamp < 30_000
                ) {
                    playVoiceNoteById(currentGroup?.id, ping.messageId)
                } else {
                    selectedMemberForSheet = loc
                }
            },
            modifier = Modifier.fillMaxSize()
        )
      } else {
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
            speakingUserId = speakingUserId,
            activeTripPoints = activeTrip?.points ?: emptyList(),
            selectedTripId = selectedTripId,
            fitSelectedTripToken = fitTripToken,
            currentUserId = currentUserId,
            targetFocusPoint = targetMapFocus,
            focusToken = focusToken,
            followPoint = followPoint,
            onMapTap = {
                activeFullPanel = null
                if (heatmapPoints.isNotEmpty()) heatmapPoints = emptyList()
            },
            onUserPan = {
                if (followedUserId != null) followedUserId = null
                if (followCam) followCam = false
            },
            onMemberSelected = { loc ->
                // Se quel membro ha appena mandato un vocale (anello attivo), un tap
                // sul suo marker lo riproduce/riascolta; altrimenti apre il dettaglio.
                val ping = repository.latestVoicePing.value
                if (ping != null && ping.userId == loc.userId &&
                    System.currentTimeMillis() - ping.timestamp < 30_000
                ) {
                    playVoiceNoteById(currentGroup?.id, ping.messageId)
                } else {
                    selectedMemberForSheet = loc
                }
            },
            onPlaceSelected = { selectedPlaceForSheet = it },
            onSnapshotClusterSelected = { selectedSnapshotClusterForGallery = it },
            onMapCenterChanged = { center -> currentMapCenter = center },
            heatmapPoints = heatmapPoints,
            heatmapFitToken = heatmapFitToken,
            modifier = Modifier.fillMaxSize()
        )
      }

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

        // Header in alto con Cover Flow dei Membri integrato
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                modifier = Modifier.padding(horizontal = Spacing.lg)
            )

            // Cover Flow Membri Prospettico Infinito: Posizionato subito sotto il TopBar del gruppo
            AnimatedVisibility(
                visible = activeFullPanel == null && locations.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                PerspectiveMemberCoverFlow(
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
        }

        // Controlli Mappa: Posizionati AL CENTRO A SINISTRA (CenterStart)
        MapActionRail(
            isFollowing = followedUserId != null,
            onToggleFollow = {
                if (followedUserId != null) {
                    followedUserId = null
                    followCam = false
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
                .padding(end = Spacing.lg, bottom = 150.dp)
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
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.padding(Spacing.xs)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                    ) {
                        RadarPulseAnimation(
                            color = RadarSemantic.Sos,
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

        // Push-to-talk vocale: in basso a destra, raggiungibile col pollice destro.
        // Stessa estetica dei RailButton della mappa. Tieni premuto per registrare
        // (max VoiceUtils.MAX_DURATION_MS), rilascia per inviare. Mentre registri il
        // pulsante "respira" lentamente ed emette un alone rosso, ben visibile.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = Spacing.lg, bottom = 80.dp)
                .size(56.dp),
            contentAlignment = Alignment.Center
        ) {
            val recTransition = rememberInfiniteTransition(label = "recPulse")
            val pulseScale by recTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.22f,
                animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "recPulseScale"
            )
            val haloAlpha by recTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "recHaloAlpha"
            )

            if (isRecordingVoice) {
                // Alone che si espande e sfuma: rende la registrazione evidente.
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .graphicsLayer {
                            scaleX = pulseScale + 0.4f
                            scaleY = pulseScale + 0.4f
                            alpha = haloAlpha
                        }
                        .clip(CircleShape)
                        .background(RadarSemantic.Sos)
                )
            }

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer {
                        val s = if (isRecordingVoice) pulseScale else 1f
                        scaleX = s
                        scaleY = s
                    }
                    .clip(CircleShape)
                    .background(if (isRecordingVoice) RadarSemantic.Sos else MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                val started = startVoiceRecording()
                                if (started) {
                                    // Cap a MAX_DURATION_MS: si ferma al rilascio o allo scadere.
                                    withTimeoutOrNull(VoiceUtils.MAX_DURATION_MS) { tryAwaitRelease() }
                                    stopAndSendVoice()
                                }
                            }
                        )
                    }
                    .testTag("voice_ptt_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Nota vocale",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp)
                )
            }
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


    // SCHERMATE E PANNELLI FULL-SCREEN SEPARATI (quando activeFullPanel != null)
    activeFullPanel?.let { currentPanel ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
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
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = stringResource(currentPanel.labelRes),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
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
                                    focusTargetUserId = loc.userId
                                    if (followedUserId != null) {
                                        followedUserId = loc.userId
                                    }
                                    focusMapOn(loc.latitude, loc.longitude, collapse = true)
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
                                onImageClick = { src, sender, ts -> fullScreenRequest = FullScreenRequest(src, sender, ts) }
                            )

                            RadarPanel.PLACES -> PlacesPanel(
                                places = places,
                                alerts = geofenceAlerts,
                                onPlaceClick = { place ->
                                    selectedPlaceForSheet = place
                                    followedUserId = null
                                    focusMapOn(place.latitude, place.longitude, collapse = true)
                                    activeFullPanel = null
                                },
                                onFocusPlace = { place ->
                                    followedUserId = null
                                    focusMapOn(place.latitude, place.longitude, collapse = true)
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
                                memberLocations = locations,
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
                                isHighPrecisionMovement = isHighPrecisionMovement,
                                onToggleHighPrecisionMovement = { repository.setHighPrecisionMovement(it) },
                                filterQ = filterQ,
                                onSetFilterQ = { repository.setFilterQ(it) },
                                isSimulationRunning = isSimulationRunning,
                                isVoiceAutoplayEnabled = voiceAutoplay,
                                onToggleVoiceAutoplay = { repository.setVoiceAutoplayEnabled(it) },
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
            },
            onShowHeatmap = {
                val gid = currentGroup?.id ?: return@MemberDetailSheet
                val targetUserId = loc.userId
                selectedMemberForSheet = null
                coroutineScope.launch {
                    heatmapPoints = repository.fetchLocationHistory(gid, targetUserId)
                    heatmapFitToken++
                    collapseSheet()
                }
            }
        )
    }

    selectedPlaceForSheet?.let { place ->
        PlaceDetailSheet(
            place = place,
            onDismiss = { selectedPlaceForSheet = null },
            onShowOnMap = {
                if (it.latitude != 0.0 && it.longitude != 0.0 && !it.latitude.isNaN() && !it.longitude.isNaN()) {
                    followedUserId = null
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
                    followedUserId = null
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

    fullScreenRequest?.let { req ->
        FullScreenMediaViewer(
            imageSource = req.source,
            authorName = req.sender,
            timestamp = req.timestamp,
            onDismiss = { fullScreenRequest = null }
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

    // Overlay di caricamento dati gruppo, robusto contro il caso "due gruppi".
    //
    // Si mostra SOLO quando il gruppo visualizzato e' davvero quello selezionato
    // dall'utente: con piu' gruppi, `currentGroup` ha un fallback su
    // `userGroups.firstOrNull()` che oscilla mentre la lista si riordina agli emit
    // di Firestore, e puntava a un gruppo i cui membri non erano ancora caricati.
    //
    // Il timeout di sicurezza e' agganciato al booleano di caricamento, non
    // all'id del gruppo: cosi' l'oscillazione del gruppo corrente non lo resetta
    // di continuo lasciando lo spinner appeso all'infinito.
    val hasRealSelection = !currentUser?.currentGroupId.isNullOrBlank() &&
        currentGroup?.id == currentUser?.currentGroupId
    val rawGroupLoading = hasRealSelection && currentUserId.isNotBlank() && members.isEmpty()
    var showLoadingOverlay by remember { mutableStateOf(false) }
    LaunchedEffect(rawGroupLoading) {
        if (rawGroupLoading) {
            showLoadingOverlay = true
            delay(8000)
            showLoadingOverlay = false
        } else {
            showLoadingOverlay = false
        }
    }
    if (showLoadingOverlay) {
        GroupLoadingOverlay()
    }

    if (showGpsDialog) {
        Dialog(onDismissRequest = { showGpsDialog = false }) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.xl),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
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
                    Text(stringResource(R.string.dialog_gps_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
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
                        shape = RoundedCornerShape(Radius.sm),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) { Text(stringResource(R.string.action_enable_gps)) }
                    Spacer(Modifier.height(Spacing.sm))
                    TextButton(
                        onClick = { showGpsDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
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
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(RadarSemantic.Online)
                    )
                    val subtitle = stringResource(R.string.map_topbar_subtitle, onlineCount, memberCount)
                    Text(
                        text = if (!joinCode.isNullOrBlank()) "$subtitle · $joinCode" else subtitle,
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
                    contentDescription = stringResource(R.string.action_change_group),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("open_settings_button")
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.tab_settings),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            // Pulsante SOS corallo con bagliore soffuso
            Surface(
                onClick = onSos,
                shape = CircleShape,
                color = RadarSemantic.Sos.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, RadarSemantic.Sos.copy(alpha = 0.4f)),
                modifier = Modifier
                    .size(Sizes.fab)
                    .testTag("sos_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = stringResource(R.string.action_send_sos),
                        tint = RadarSemantic.Sos,
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
            container = if (isFollowing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            content = if (isFollowing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
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
            container = if (isRecording) RadarSemantic.Sos else MaterialTheme.colorScheme.surface,
            content = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface,
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
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
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
    container: Color = MaterialTheme.colorScheme.surface,
    content: Color = MaterialTheme.colorScheme.onSurface,
    testTag: String? = null
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = container,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

private data class MemberPresence(
    val label: String,
    val color: Color
)

private fun getMemberPresence(timestamp: Long): MemberPresence {
    val elapsed = System.currentTimeMillis() - timestamp
    return when {
        elapsed < PRESENCE_ONLINE_MS -> MemberPresence("Online", RadarSemantic.Online)
        elapsed < PRESENCE_IDLE_MS -> MemberPresence("Inattivo", RadarSemantic.BatteryMid)
        else -> MemberPresence("Offline", RadarSemantic.Offline)
    }
}

@Composable
private fun PerspectiveMemberCoverFlow(
    locations: List<UserLocation>,
    currentUserId: String,
    followedUserId: String?,
    onMemberClick: (UserLocation) -> Unit,
    onMemberLongClick: (UserLocation) -> Unit,
    modifier: Modifier = Modifier
) {
    if (locations.isEmpty()) return

    val listCount = locations.size
    val loopMultiplier = 1000
    val totalCount = listCount * loopMultiplier
    // Di default l'utente selezionato sono IO: la carousel parte centrata sul mio
    // avatar, non sul primo della lista.
    val myIndex = locations.indexOfFirst { it.userId == currentUserId }.coerceAtLeast(0)
    val initialCenter = (totalCount / 2) - ((totalCount / 2) % listCount) + myIndex

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialCenter)
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val coroutineScope = rememberCoroutineScope()

    // Calcola l'indice dell'elemento più vicino al centro visibile
    val centerIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) initialCenter
            else {
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                visibleItems.minByOrNull { item ->
                    val itemCenter = item.offset + item.size / 2
                    kotlin.math.abs(itemCenter - viewportCenter)
                }?.index ?: initialCenter
            }
        }
    }

    val activeMemberLoc = locations[centerIndex % listCount]

    var hasUserScrolled by remember { mutableStateOf(false) }

    // Quando lo scatto/scorrimento avviato dall'utente si ferma, sincronizza la mappa sull'utente centrato
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            hasUserScrolled = true
        } else if (hasUserScrolled) {
            hasUserScrolled = false
            onMemberClick(activeMemberLoc)
        }
    }

    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    // Slot logico compatto. La scala la applica graphicsLayer al draw, quindi la
    // dimensione misurata resta fissa: nessun relayout durante lo scorrimento.
    val itemSlot = 56.dp
    val overlap = (-4).dp                       // sovrapposizione a filo
    val horizontalPadding = ((screenWidth - itemSlot) / 2).coerceAtLeast(16.dp)
    // Passo centro-a-centro (slot + overlap) in px: normalizza la distanza dal
    // centro viewport in una frazione 0..1 per interpolare scala/opacita'.
    val slotPx = with(density) { (itemSlot + overlap).toPx() }

    // Avatar DECODIFICATI UNA VOLTA SOLA per membro (chiave = foto, non l'intero
    // location che cambia a ogni battito/timestamp). Durante lo scroll si disegna
    // un ImageBitmap gia' pronto: niente decodifiche async che fanno saltare frame.
    val photoSignature = remember(locations) {
        locations.joinToString(",") { "${it.userId}:${it.photoBase64?.length ?: 0}" }
    }
    val avatarBitmaps: Map<String, ImageBitmap?> = remember(photoSignature) {
        locations.associate { it.userId to ImageUtils.base64ToBitmap(it.photoBase64)?.asImageBitmap() }
    }

    // Frazione 0..1 di distanza dell'item dal centro viewport, letta al DRAW dallo
    // scroll. Usata identica da scala/alpha e da glow/anello: tutto in draw-phase,
    // così durante lo scorrimento non si ricompone nulla (movimento a refresh pieno).
    val centerFractionOf: (Int) -> Float = { idx ->
        val info = listState.layoutInfo
        val ii = info.visibleItemsInfo.firstOrNull { it.index == idx }
        if (ii != null && slotPx > 0f) {
            val vc = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val ic = ii.offset + ii.size / 2f
            (kotlin.math.abs(ic - vc) / slotPx).coerceIn(0f, 1f)
        } else 1f
    }

    val sage = RadarSemantic.Online
    val followRingColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Overlap Strip (Magnetic Piling): avatar sovrapposti, snap-to-center,
        // Hero centrale che emerge. Scala/alpha/glow tutti continui, letti dallo scroll.
        LazyRow(
            state = listState,
            flingBehavior = snapFlingBehavior,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(overlap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(count = totalCount, key = { it }) { index ->
                val actualIndex = index % listCount
                val loc = locations[actualIndex]
                // Solo per lo z-order (discreto, cambia di rado): il centro sopra tutti.
                val distance = kotlin.math.abs(index - centerIndex)
                val bmp = avatarBitmaps[loc.userId]
                val initial = loc.userName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .zIndex(-distance.toFloat())
                        .size(itemSlot)
                        // Trasformazione CONTINUA al draw: niente state mutato per
                        // frazione di pixel, zero jitter di ricomposizione.
                        .graphicsLayer {
                            val t = centerFractionOf(index)
                            val s = 1.0f + (0.62f - 1.0f) * t  
                            scaleX = s
                            scaleY = s
                            alpha = 1f + (0.5f - 1f) * t          // centro 1.0 → laterale 0.5
                        }
                        // Anello verde salvia sull'elemento attivo, intensita'
                        // continua = (1 - t). RIENTRATO dentro il bordo: non disegna
                        // nulla oltre lo slot, così la LazyRow non lo ritaglia a quadrato.
                        .drawBehind {
                            val a = (1f - centerFractionOf(index)).coerceIn(0f, 1f)
                            if (a > 0.01f) {
                                val stroke = 2.dp.toPx()
                                drawCircle(
                                    color = sage,
                                    radius = size.minDimension / 2f - stroke / 2f,
                                    center = Offset(size.width / 2f, size.height / 2f),
                                    style = Stroke(width = stroke),
                                    alpha = a
                                )
                            }
                            // Anello blu primario per la persona seguita.
                            if (loc.userId == followedUserId) {
                                val stroke = 3.dp.toPx()
                                drawCircle(
                                    color = followRingColor,
                                    radius = size.minDimension / 2f - stroke / 2f,
                                    center = Offset(size.width / 2f, size.height / 2f),
                                    style = Stroke(width = stroke)
                                )
                            }
                        }
                        .combinedClickable(
                            onClick = {
                                coroutineScope.launch { listState.animateScrollToItem(index) }
                                onMemberClick(loc)
                            },
                            onLongClick = { onMemberLongClick(loc) }
                        )
                ) {
                    // Avatar: bitmap pre-decodificato o iniziale come fallback a basso costo.
                    // Stesso colore/trasparenza dei pulsanti mappa (RailButton): fondo
                    // 0xCC18181B + bordo 0x1F71717A, per un look uniforme sulla mappa.
                    Box(
                        modifier = Modifier
                            .size(itemSlot)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = loc.userName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = initial,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Badge iconcina in basso a destra per stato di movimento (auto, bici, corsa, camminata)
                    val movementIcon = remember(loc.activityType, loc.speed, loc.timestamp) {
                        val isRecent = (System.currentTimeMillis() - loc.timestamp) < PRESENCE_ONLINE_MS
                        if (!isRecent) null
                        else {
                            val speedKmH = loc.speed * 3.6f
                            when (loc.activityType) {
                                ActivityKind.VEHICLE -> Icons.Default.DirectionsCar
                                ActivityKind.BICYCLE -> Icons.Default.DirectionsBike
                                ActivityKind.RUNNING -> Icons.Default.DirectionsRun
                                ActivityKind.WALKING -> Icons.Default.DirectionsWalk
                                ActivityKind.STILL -> if (speedKmH >= 15f) Icons.Default.DirectionsCar else null
                                else -> {
                                    when {
                                        speedKmH >= 25f -> Icons.Default.DirectionsCar
                                        speedKmH >= 8f -> Icons.Default.DirectionsBike
                                        speedKmH >= 2.5f -> Icons.Default.DirectionsWalk
                                        else -> null
                                    }
                                }
                            }
                        }
                    }

                    if (movementIcon != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.5.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = movementIcon,
                                contentDescription = null,
                                tint = RadarSemantic.Online,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(2.dp))

        // --- Telemetria borderless: solo per il membro centrato (nessun box) ---
        val activeName = if (!activeMemberLoc.nickname.isNullOrBlank()) activeMemberLoc.nickname!!
            else if (activeMemberLoc.userId == currentUserId) stringResource(R.string.label_you)
            else activeMemberLoc.userName
        val isSelf = activeMemberLoc.userId == currentUserId
        val activePresence = if (isSelf) {
            MemberPresence("Online", RadarSemantic.Online)
        } else {
            getMemberPresence(activeMemberLoc.timestamp)
        }
        val movingKmH = (activeMemberLoc.speed * 3.6f).toInt()
        val activityDescription = when (activeMemberLoc.activityType) {
            ActivityKind.VEHICLE -> if (movingKmH > 0) "In auto ($movingKmH km/h)" else "In auto"
            ActivityKind.BICYCLE -> if (movingKmH > 0) "In bici ($movingKmH km/h)" else "In bici"
            ActivityKind.RUNNING -> if (movingKmH > 0) "Corsa ($movingKmH km/h)" else "Corsa"
            ActivityKind.WALKING -> if (movingKmH > 0) "A piedi ($movingKmH km/h)" else "A piedi"
            else -> if (movingKmH > 2) "In movimento ($movingKmH km/h)" else null
        }
        val statusLabel = activityDescription ?: activePresence.label
        val statusColor = if (activityDescription != null) RadarSemantic.Online else activePresence.color
        val relativeTimeStr = if (isSelf) "ora" else formatRelativeShort(activeMemberLoc.timestamp)
        val textShadow = Shadow(color = Color.Black, offset = Offset(0f, 1.5f), blurRadius = 6f)

        // Riga 1 — Nome (SemiBold, #F2F2F7, 15sp).
        Text(
            text = activeName,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                shadow = textShadow
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(2.dp))

        // Riga 2 — micro-linea "Stato • tempo • batteria".
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, shadow = textShadow
                ),
                color = statusColor
            )
            Text("•", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, shadow = textShadow), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = relativeTimeStr,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, shadow = textShadow),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("•", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, shadow = textShadow), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "${activeMemberLoc.batteryLevel}%",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, shadow = textShadow
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Tempo trascorso in forma ultra compatta: "ora", "2min", "3h", "5g". */
private fun formatRelativeShort(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> "ora"
        diff < 3_600_000L -> "${diff / 60_000L}min"
        diff < 86_400_000L -> "${diff / 3_600_000L}h"
        else -> "${diff / 86_400_000L}g"
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
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    tint = if (selectedPanel == RadarPanel.MEMBERS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = { onSelectPanel(RadarPanel.PLACES) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = "Places",
                    tint = if (selectedPanel == RadarPanel.PLACES) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // Box stretto attorno all'IconButton: così il pallino si ancora
                // all'angolo dell'ICONA, non della cella larga, e resta sovrapposto
                // in alto a destra sull'icona. Compare/sparisce con i non letti.
                Box(contentAlignment = Alignment.Center) {
                    IconButton(onClick = { onSelectPanel(RadarPanel.CHAT) }) {
                        Icon(
                            Icons.Default.ChatBubbleOutline,
                            contentDescription = "Chat",
                            tint = if (selectedPanel == RadarPanel.CHAT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (chatCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = (-9).dp, y = 9.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(RadarSemantic.Sos)
                                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        )
                    }
                }
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
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            if (isSelf) 1.5.dp else 1.dp,
            if (isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
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
                    containerColor = if (isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isSelf) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
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
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    when (member.role) {
                        "owner" -> RadarBadge(
                            text = stringResource(R.string.role_owner),
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                        "admin" -> RadarBadge(
                            text = stringResource(R.string.role_admin),
                            containerColor = RadarSemantic.Online.copy(alpha = 0.15f),
                            contentColor = RadarSemantic.Online
                        )
                    }
                    if (member.appVersion.isNotBlank()) {
                        Text(
                            text = "v${member.appVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
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
                        contentDescription = stringResource(R.string.action_show_on_map),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Sizes.iconMd)
                    )
                }
            }
            if (canKick) {
                IconButton(onClick = onKick) {
                    Icon(
                        Icons.Default.PersonRemove,
                        contentDescription = stringResource(R.string.action_remove_member),
                        tint = RadarSemantic.Sos,
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
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
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
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    if (!place.geofenceEnabled) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = stringResource(R.string.content_desc_alerts_disabled),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
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
                        shape = RoundedCornerShape(Radius.sm),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
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
        containerColor = MaterialTheme.colorScheme.background,
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
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(Sizes.iconMd))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.action_take_photo_now))
                }
                OutlinedButton(
                    onClick = onGallery,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(Sizes.iconMd))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.action_choose_gallery))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) { Text(stringResource(R.string.action_cancel)) }
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

internal fun formatInterval(seconds: Int, context: android.content.Context): String = when {
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


/**
 * Risale la catena dei ContextWrapper fino all'Activity.
 *
 * Serve per `recreate()` al cambio lingua: `LocalContext.current` qui è avvolto
 * almeno una volta (dal context localizzato), quindi un cast diretto ad Activity
 * fallirebbe e il cambio lingua sembrerebbe non funzionare.
 */
internal fun android.content.Context.findActivityOrNull(): android.app.Activity? {
    var ctx: android.content.Context = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
