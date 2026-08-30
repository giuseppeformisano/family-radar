package com.example.repository

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import android.os.BatteryManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.example.geofence.GeofenceHelper
import com.example.R
import com.example.model.*
import com.example.model.Trip
import com.example.model.TripPoint
import com.example.model.TripSource
import com.example.model.ActiveTripState
import com.example.util.ImageUtils
import com.example.util.MotionTrigger
import com.example.util.VoiceUtils
import com.google.android.gms.location.*
import com.google.firebase.FirebaseException
import com.google.firebase.auth.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class FirebaseRepository private constructor(private val context: Context) {

    private val auth: FirebaseAuth? = try {
        FirebaseAuth.getInstance()
    } catch (e: Exception) {
        Log.w(TAG, "FirebaseAuth not initialized: ${e.message}")
        null
    }

    private val firestore: FirebaseFirestore? = try {
        FirebaseFirestore.getInstance()
    } catch (e: Exception) {
        Log.w(TAG, "FirebaseFirestore not initialized: ${e.message}")
        null
    }

    // Active Firestore listener registrations for cleanup
    private var locationsListener: ListenerRegistration? = null
    private var placesListener: ListenerRegistration? = null
    private var messagesListener: ListenerRegistration? = null
    private var membersListener: ListenerRegistration? = null
    private var eventsListener: ListenerRegistration? = null
    private var snapshotsListener: ListenerRegistration? = null
    private var tripsListener: ListenerRegistration? = null
    private var userDocListener: ListenerRegistration? = null
    // Auto-recupero listener di gruppo. Un errore recuperabile (quota esaurita,
    // rete assente) chiude lo stream Firestore, che non sempre riparte da solo
    // quando la quota si resetta: senza questo i membri restano "offline" finche'
    // l'app non viene riavviata a mano. La generazione invalida un reattach in
    // coda quando nel frattempo si e' cambiato gruppo o fatto cleanup.
    private var groupListenerGeneration = 0
    @Volatile private var reattachScheduled = false
    private var groupsCollectionListener: ListenerRegistration? = null
    private val memberStatusListeners = java.util.concurrent.ConcurrentHashMap<String, ListenerRegistration>()
    // Ultimo status visto per gruppo. Serve a distinguere una vera approvazione
    // (transizione PENDING -> ACTIVE, in cui e' giusto entrare da soli) dal primo
    // snapshot di un gruppo gia' ACTIVE dopo una nuova installazione: in quel caso
    // NON bisogna auto-selezionare, altrimenti con piu' gruppi i listener partono
    // in parallelo e si rincorrono selezionando ciascuno il proprio gruppo.
    private val memberStatusSeen = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val memberGroupsMap = java.util.concurrent.ConcurrentHashMap<String, GroupData>()
    private var lastObservedEventTimestamp: Long = System.currentTimeMillis()
    private var lastObservedMessageTimestamp: Long = System.currentTimeMillis()

    // Reactive states
    private val _currentUserState = MutableStateFlow<UserData?>(null)
    val currentUserState = _currentUserState.asStateFlow()

    private val _userGroupsState = MutableStateFlow<List<GroupData>>(emptyList())
    val userGroupsState = _userGroupsState.asStateFlow()

    private val _currentGroupLocations = MutableStateFlow<List<UserLocation>>(emptyList())
    val currentGroupLocations = _currentGroupLocations.asStateFlow()

    private val _currentGroupPlaces = MutableStateFlow<List<SavedPlace>>(emptyList())
    val currentGroupPlaces = _currentGroupPlaces.asStateFlow()

    private val _currentGroupSnapshots = MutableStateFlow<List<PlaceSnapshot>>(emptyList())
    val currentGroupSnapshots = _currentGroupSnapshots.asStateFlow()

    private val _currentGroupMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentGroupMessages = _currentGroupMessages.asStateFlow()

    // Paginazione chat a blocchi: si parte dagli ultimi CHAT_HISTORY_LIMIT e,
    // scorrendo in alto, si rialza il limite ri-agganciando il listener. hasMoreChat
    // dice se l'ultimo blocco era pieno (quindi c'e' probabilmente altra storia).
    private var chatLimit = CHAT_HISTORY_LIMIT
    @Volatile private var isLoadingMoreChat = false
    private val _hasMoreChat = MutableStateFlow(false)
    val hasMoreChat = _hasMoreChat.asStateFlow()

    // Ultima nota vocale ARRIVATA da un altro membro (fresca): la mappa la usa per
    // l'anello pulsante sul marker di chi parla e per l'autoplay di chi sta guardando.
    data class VoicePing(val userId: String, val messageId: String, val durationMs: Long, val timestamp: Long, val audioUrl: String? = null)
    private val _latestVoicePing = MutableStateFlow<VoicePing?>(null)
    val latestVoicePing = _latestVoicePing.asStateFlow()

    // Vero mentre la schermata radar e' in primo piano (ON_RESUME). Con autoplay
    // attivo, un vocale in arrivo viene riprodotto qui: la notifica col suo suono
    // ruberebbe il focus audio troncando la riproduzione, quindi la si sopprime.
    @Volatile private var radarForeground = false
    fun setRadarForeground(value: Boolean) { radarForeground = value }

    private val _currentGroupMembers = MutableStateFlow<List<GroupMember>>(emptyList())
    val currentGroupMembers = _currentGroupMembers.asStateFlow()

    private val _activeGeofenceAlerts = MutableStateFlow<List<GeofenceEvent>>(emptyList())
    val activeGeofenceAlerts = _activeGeofenceAlerts.asStateFlow()

    private val _groupTrips = MutableStateFlow<List<Trip>>(emptyList())
    val groupTrips = _groupTrips.asStateFlow()

    private val _activeTrip = MutableStateFlow<ActiveTripState?>(null)
    val activeTrip = _activeTrip.asStateFlow()

    private val settingsPrefs = context.getSharedPreferences("family_radar_settings_prefs", Context.MODE_PRIVATE)

    /**
     * Annulla la migrazione che aveva portato l'intervallo da 30 a 90 secondi.
     *
     * Quel cambio risparmiava batteria ma rendeva la mappa troppo pigra e
     * ingrossava il passo delle tracce dei viaggi: il default torna a 30.
     * Si riporta a 30 solo chi ha esattamente il 90 scritto dalla vecchia
     * migrazione automatica; chi ha scelto a mano un valore diverso — 90 incluso,
     * se lo ha impostato dopo — non viene toccato, perché la migrazione gira una
     * volta sola ed è marcata da una sua chiave.
     */
    private fun migrateTrackingIntervalDefault() {
        val autoMigratedTo90 = settingsPrefs.getBoolean("tracking_freq_migrated_v2", false)
        if (settingsPrefs.getBoolean("tracking_freq_restored_v3", false)) return

        val editor = settingsPrefs.edit().putBoolean("tracking_freq_restored_v3", true)
        if (autoMigratedTo90 && settingsPrefs.getInt("tracking_freq_sec", 30) == 90) {
            editor.putInt("tracking_freq_sec", DEFAULT_TRACKING_INTERVAL_SEC)
        }
        editor.apply()
    }

    // Tracking frequency in seconds, persisted
    private val _trackingFrequencySeconds = MutableStateFlow(
        run {
            migrateTrackingIntervalDefault()
            settingsPrefs.getInt("tracking_freq_sec", DEFAULT_TRACKING_INTERVAL_SEC)
        }
    )
    val trackingFrequencySeconds = _trackingFrequencySeconds.asStateFlow()

    // Background sticky notification tracking (enabled by default) persisted
    private val _isBackgroundTrackingEnabled = MutableStateFlow(settingsPrefs.getBoolean("bg_tracking_enabled", true))
    val isBackgroundTrackingEnabled = _isBackgroundTrackingEnabled.asStateFlow()

    // Global Ghost mode (default false) persisted
    private val _isGlobalGhostMode = MutableStateFlow(settingsPrefs.getBoolean("global_ghost_mode", false))
    val isGlobalGhostMode = _isGlobalGhostMode.asStateFlow()

    // Autoplay note vocali per chi guarda la mappa (default spento) persistito.
    private val _isVoiceAutoplayEnabled = MutableStateFlow(settingsPrefs.getBoolean("voice_autoplay", false))
    val isVoiceAutoplayEnabled = _isVoiceAutoplayEnabled.asStateFlow()

    fun setVoiceAutoplayEnabled(enabled: Boolean) {
        if (_isVoiceAutoplayEnabled.value == enabled) return
        _isVoiceAutoplayEnabled.value = enabled
        runCatching { settingsPrefs.edit().putBoolean("voice_autoplay", enabled).apply() }
    }

    // Risparmio energia (default false) persistito.
    //
    // Non spegne il tracciamento: cambia solo la *sorgente* della posizione.
    // Con PRIORITY_BALANCED_POWER_ACCURACY il sistema smette di accendere il
    // chip GPS e ricava la posizione da WiFi e celle telefoniche: precisione
    // ~100 m invece di ~5 m, ma consumo molto piu' basso e funziona anche al
    // chiuso. Per l'utente resta tutto uguale, continua a comparire sulla mappa.
    private val _isPowerSavingMode = MutableStateFlow(settingsPrefs.getBoolean("power_saving_mode", false))
    val isPowerSavingMode = _isPowerSavingMode.asStateFlow()

    // Rilevamento automatico dei viaggi (default spento) persistito.
    private val _isAutoTripEnabled = MutableStateFlow(settingsPrefs.getBoolean("auto_trip_enabled", false))
    val isAutoTripEnabled = _isAutoTripEnabled.asStateFlow()

    // Se i viaggi rilevati da soli sono visibili al gruppo o solo a chi li ha fatti.
    private val _isAutoTripShared = MutableStateFlow(settingsPrefs.getBoolean("auto_trip_shared", false))
    val isAutoTripShared = _isAutoTripShared.asStateFlow()

    fun setAutoTripEnabled(enabled: Boolean) {
        if (_isAutoTripEnabled.value == enabled) return
        _isAutoTripEnabled.value = enabled
        settingsPrefs.edit().putBoolean("auto_trip_enabled", enabled).apply()
        autoMovingSinceMillis = 0L
        autoStationarySinceMillis = 0L
        recentFixes.clear()
        if (enabled) {
            startMotionSensing()
        } else {
            stopMotionSensing()
        }
        // L'intervallo effettivo cambia: da fermi va limitato a un minuto,
        // altrimenti l'app non si accorge in tempo che sei partito.
        applyEffectiveTrackingInterval()
    }

    fun setAutoTripShared(shared: Boolean) {
        if (_isAutoTripShared.value == shared) return
        _isAutoTripShared.value = shared
        settingsPrefs.edit().putBoolean("auto_trip_shared", shared).apply()
    }

    /**
     * Precisione da chiedere a Play Services. Unico punto di verita': la usano
     * sia il tracciamento in-app silenzioso sia il servizio in foreground.
     */
    fun locationPriority(): Int =
        if (_isPowerSavingMode.value) Priority.PRIORITY_BALANCED_POWER_ACCURACY
        else Priority.PRIORITY_HIGH_ACCURACY

    fun setPowerSavingMode(enabled: Boolean) {
        if (_isPowerSavingMode.value == enabled) return
        _isPowerSavingMode.value = enabled
        settingsPrefs.edit().putBoolean("power_saving_mode", enabled).apply()

        // Prima il gate, poi i produttori. Passando ad alta precisione il primo
        // fix preciso puo' distare parecchio da quello approssimato scritto per
        // ultimo; passando a bassa precisione il raggio di errore si allarga di
        // colpo. In entrambi i casi il gate confronterebbe grandezze non
        // omogenee, quindi va azzerato prima che riprendano ad arrivare fix.
        resetLocationGate()

        // Entrambi i produttori vanno riagganciati con la nuova precisione,
        // altrimenti il cambio avrebbe effetto solo al riavvio dell'app.
        // Nessuna interruzione: startSilentLocationTracking stacca e riattacca,
        // e il servizio riemette la richiesta senza perdere il foreground.
        if (silentLocationCallback != null) {
            // Ripubblica gia' lui la posizione nota in coda all'aggancio.
            startSilentLocationTracking()
        } else {
            pushLastKnownLocationNow()
        }
        if (_isBackgroundTrackingEnabled.value) {
            com.example.service.LocationTrackingService.updatePowerMode(context)
        }
    }

    fun setTrackingFrequencySeconds(seconds: Int) {
        val clamped = seconds.coerceIn(5, 86400)
        _trackingFrequencySeconds.value = clamped
        settingsPrefs.edit().putInt("tracking_freq_sec", clamped).apply()
        // Passa dall'intervallo effettivo: se e' in corso un viaggio la sua
        // cadenza fitta ha la precedenza, e il nuovo valore entra in vigore
        // quando la registrazione finisce. Spingere qui il valore dell'utente
        // avrebbe azzoppato la traccia a meta' registrazione.
        applyEffectiveTrackingInterval()
    }

    fun setBackgroundTrackingEnabled(enabled: Boolean) {
        _isBackgroundTrackingEnabled.value = enabled
        settingsPrefs.edit().putBoolean("bg_tracking_enabled", enabled).apply()
        if (enabled) {
            com.example.service.LocationTrackingService.start(context, _trackingFrequencySeconds.value)
        } else {
            com.example.service.LocationTrackingService.stop(context)
        }
    }

    fun setGlobalGhostMode(enabled: Boolean) {
        _isGlobalGhostMode.value = enabled
        settingsPrefs.edit().putBoolean("global_ghost_mode", enabled).apply()

        val currentUser = _currentUserState.value
        val currentGroup = currentUser?.currentGroupId

        // Aggiornamento ottimistico del flow locale PRIMA di ripubblicare:
        // updateLocation legge isTrackingActive da qui, e la scrittura su
        // Firestore col rimbalzo del listener e' piu' lenta del fix che stiamo
        // per spingere. Senza questa riga il fix verrebbe scartato dal controllo
        // sul tracking di gruppo, ancora fermo al valore precedente.
        if (currentUser != null) {
            _currentGroupMembers.value = _currentGroupMembers.value.map {
                if (it.userId == currentUser.uid) it.copy(isTrackingActive = !enabled) else it
            }
        }

        // Spegnendo il ghost mode il documento di posizione e' stato cancellato,
        // ma il gate ricorda ancora l'ultimo fix inviato prima dell'accensione:
        // da fermi lo scarterebbe come "sotto soglia" e si resterebbe invisibili
        // fino all'heartbeat. Azzerare il gate e ripubblicare subito l'ultima
        // posizione nota fa ricomparire l'utente all'istante.
        if (!enabled) {
            resetLocationGate()
            pushLastKnownLocationNow()
        }

        if (currentUser != null && !currentGroup.isNullOrBlank() && firestore != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    firestore.collection("groups").document(currentGroup)
                        .collection("members").document(currentUser.uid)
                        .update("isTrackingActive", !enabled)
                        .await()
                    if (enabled) {
                        firestore.collection("groups").document(currentGroup)
                            .collection("locations").document(currentUser.uid)
                            .delete()
                            .await()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "setGlobalGhostMode update error: ${e.message}")
                }
            }
        }
    }

    suspend fun updateMemberGroupTracking(groupId: String, isTrackingActive: Boolean): Result<Unit> {
        val currentUser = _currentUserState.value ?: return Result.failure(Exception(str(R.string.err_not_authenticated)))
        return try {
            if (firestore != null) {
                firestore.collection("groups").document(groupId)
                    .collection("members").document(currentUser.uid)
                    .update("isTrackingActive", isTrackingActive)
                    .await()
                if (!isTrackingActive) {
                    firestore.collection("groups").document(groupId)
                        .collection("locations").document(currentUser.uid)
                        .delete()
                        .await()
                }
            }
            _currentGroupMembers.value = _currentGroupMembers.value.map {
                if (it.userId == currentUser.uid) it.copy(isTrackingActive = isTrackingActive) else it
            }

            // Stesso problema del ghost mode: disattivando si cancella il
            // documento di posizione, e riattivando da fermi il gate scarterebbe
            // il fix come "sotto soglia" lasciando il membro invisibile fino
            // all'heartbeat.
            if (isTrackingActive) {
                resetLocationGate()
                pushLastKnownLocationNow()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "updateMemberGroupTracking failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Aggiorna nome, descrizione e immagine del gruppo. Come per gli avatar dei
     * membri l'immagine viaggia in Base64 dentro il documento: Firebase Storage
     * non e' in uso e il limite vero e' il MB per documento di Firestore.
     */
    suspend fun updateGroupInfo(
        groupId: String,
        name: String,
        description: String,
        photoBase64: String?,
        isPublic: Boolean = false
    ): Result<Unit> {
        val cleanName = name.trim().ifBlank { "Gruppo" }
        val cleanDesc = description.trim()
        val cleanPhoto = photoBase64?.trim() ?: ""

        return try {
            if (firestore != null && groupId.isNotBlank()) {
                firestore.collection("groups").document(groupId)
                    .update(
                        mapOf(
                            "name" to cleanName,
                            "description" to cleanDesc,
                            "photoBase64" to cleanPhoto,
                            // nameLower va riscritto qui: i gruppi creati prima di
                            // questo campo non lo hanno e senza non sono cercabili.
                            "nameLower" to cleanName.lowercase(),
                            "isPublic" to isPublic
                        )
                    )
                    .await()
            }
            _userGroupsState.value = _userGroupsState.value.map {
                if (it.id == groupId) {
                    it.copy(
                        name = cleanName,
                        description = cleanDesc,
                        photoBase64 = cleanPhoto,
                        nameLower = cleanName.lowercase(),
                        isPublic = isPublic
                    )
                } else it
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "updateGroupInfo failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun updateGroupAccessPolicy(groupId: String, requiresApproval: Boolean): Result<Unit> {
        return try {
            if (firestore != null) {
                firestore.collection("groups").document(groupId)
                    .update("requiresApproval", requiresApproval)
                    .await()
            }
            _userGroupsState.value = _userGroupsState.value.map {
                if (it.id == groupId) it.copy(requiresApproval = requiresApproval) else it
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "updateGroupAccessPolicy failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ---------------------------------------------------------------------
    // SELEZIONE DEL GRUPPO
    //
    // Il pulsante "cambia gruppo" non funzionava per due motivi che si sommavano:
    //
    //  1. `clearCurrentGroupSelection` azzerava solo lo stato in memoria, mai il
    //     campo su Firestore. Al primo re-emit del documento users/{uid} — e ne
    //     arrivano di continuo, per token FCM, lastSeen, ecc. — il listener
    //     rimetteva dentro il gruppo appena abbandonato.
    //  2. Il listener calcolava `targetGroupId = lastApprovedGroupId ?: currentGroupId`.
    //     `lastApprovedGroupId` viene scritto all'approvazione e non veniva mai
    //     ripulito, quindi vinceva per sempre: qualunque altro gruppo scegliessi,
    //     venivi riportato all'ultimo in cui eri stato approvato.
    //
    // Ora `lastApprovedGroupId` è un segnale usa-e-getta (vale solo se non c'è
    // già una scelta esplicita) e [groupIdDismissedByUser] impedisce al listener
    // di riproporre il gruppo che l'utente ha appena lasciato, senza bloccare
    // l'ingresso automatico in un gruppo appena approvato.
    // ---------------------------------------------------------------------

    /** true mentre l'utente sta scegliendo un gruppo: la UI non deve auto-navigare. */
    private val _isChoosingGroup = MutableStateFlow(false)
    val isChoosingGroup = _isChoosingGroup.asStateFlow()

    @Volatile
    private var groupIdDismissedByUser: String? = null

    fun clearCurrentGroupSelection() {
        val current = _currentUserState.value
        val leavingGroupId = current?.currentGroupId

        groupIdDismissedByUser = leavingGroupId
        _isChoosingGroup.value = true

        if (current != null) {
            _currentUserState.value = current.copy(currentGroupId = null)
        }
        cleanupGroupListeners()

        // Senza questa scrittura la scelta non sopravvive né a un re-emit del
        // documento né a un riavvio dell'app.
        val uid = current?.uid
        if (firestore != null && !uid.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    firestore.collection("users").document(uid).update(
                        mapOf(
                            "currentGroupId" to null,
                            "lastApprovedGroupId" to null,
                            "lastUpdated" to System.currentTimeMillis()
                        )
                    ).await()
                } catch (e: Exception) {
                    Log.w(TAG, "clearCurrentGroupSelection: update fallita: ${e.message}")
                }
            }
        }
    }

    // Deep link navigation target from notifications
    private val _deepLinkTarget = MutableStateFlow<DeepLinkTarget?>(null)
    val deepLinkTarget = _deepLinkTarget.asStateFlow()

    fun setDeepLinkTarget(target: DeepLinkTarget?) {
        _deepLinkTarget.value = target
    }

    fun consumeDeepLinkTarget() {
        _deepLinkTarget.value = null
    }

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var silentLocationCallback: LocationCallback? = null

    init {
        // Check if there is an existing signed-in Firebase user
        val fbUser = auth?.currentUser
        if (fbUser != null) {
            val userData = UserData(
                uid = fbUser.uid,
                displayName = fbUser.displayName ?: fbUser.email?.substringBefore("@") ?: "Utente Radar",
                email = fbUser.email,
                photoUrl = fbUser.photoUrl?.toString(),
                isAnonymous = fbUser.isAnonymous,
                fcmToken = getStoredFcmToken()
            )
            _currentUserState.value = userData
            startUserRealtimeSync(userData.uid)
        } else {
            _currentUserState.value = null
            _userGroupsState.value = emptyList()
        }

        // Fetch current FCM token if available
        fetchAndSyncFcmToken()

        ensureMotionSensing()
    }

    /**
     * Registra quello che serve per capire come si sta muovendo l'utente.
     *
     * Il riconoscimento di attivita' parte sempre — serve all'icona sulla mappa
     * anche a viaggi automatici spenti — mentre il sensore hardware serve solo al
     * rilevamento dei viaggi e segue quell'impostazione.
     *
     * Va richiamata dopo che l'utente ha concesso ACTIVITY_RECOGNITION: al primo
     * tentativo il permesso non c'era e la registrazione era stata saltata.
     */
    fun ensureMotionSensing() {
        startActivityRecognition()
        if (_isAutoTripEnabled.value) startMotionSensing()
    }

    fun getStoredFcmToken(): String? {
        return try {
            val prefs = context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            prefs.getString("fcm_token", null)
        } catch (_: Exception) {
            null
        }
    }

    fun fetchAndSyncFcmToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    val stored = getStoredFcmToken()
                    if (token != stored) {
                        Log.d(TAG, "FCM token aggiornato, sincronizzo su Firestore")
                        updateFcmToken(token)
                    } else {
                        Log.d(TAG, "FCM token invariato, skip scrittura Firestore")
                    }
                }
            }.addOnFailureListener { e ->
                Log.w(TAG, "Failed to get FCM token: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseMessaging not available: ${e.message}")
        }
    }

    fun updateFcmToken(token: String) {
        try {
            val prefs = context.getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("fcm_token", token).apply()

            val currentUser = _currentUserState.value
            if (currentUser != null) {
                _currentUserState.value = currentUser.copy(fcmToken = token)
                if (firestore != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            firestore.collection("users").document(currentUser.uid)
                                .update("fcmToken", token)
                                .await()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update FCM token in Firestore: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating FCM token: ${e.message}")
        }
    }

    suspend fun sendFeedback(text: String): Result<Unit> {
        val user = _currentUserState.value ?: return Result.failure(Exception(str(R.string.err_not_authenticated)))
        return try {
            val entry = hashMapOf(
                "text" to text.trim(),
                "userId" to user.uid,
                "userName" to (user.displayName.ifBlank { "Utente" }),
                "timestamp" to System.currentTimeMillis(),
                "versionName" to com.example.BuildConfig.VERSION_NAME,
                "versionCode" to com.example.BuildConfig.VERSION_CODE,
                "status" to "pending"
            )
            firestore?.collection("feedback")?.add(entry)?.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "sendFeedback error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun fetchFeedback(): List<com.example.model.FeedbackEntry> {
        return try {
            firestore?.collection("feedback")
                ?.orderBy("timestamp", Query.Direction.DESCENDING)
                ?.limit(100)
                ?.get()
                ?.await()
                ?.documents
                ?.mapNotNull { doc ->
                    try {
                        com.example.model.FeedbackEntry(
                            id = doc.id,
                            text = doc.getString("text") ?: "",
                            userId = doc.getString("userId") ?: "",
                            userName = doc.getString("userName") ?: "Utente",
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            versionName = doc.getString("versionName") ?: "",
                            versionCode = doc.getLong("versionCode")?.toInt() ?: 0,
                            status = doc.getString("status") ?: "pending"
                        )
                    } catch (_: Exception) { null }
                }
                ?.filter { it.status != "done" && it.status != "discarded" }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchFeedback error: ${e.message}")
            emptyList()
        }
    }

    suspend fun updateFeedbackStatus(id: String, status: String) {
        try {
            firestore?.collection("feedback")?.document(id)
                ?.set(hashMapOf("status" to status), com.google.firebase.firestore.SetOptions.merge())?.await()
        } catch (e: Exception) {
            Log.w(TAG, "updateFeedbackStatus error: ${e.message}")
        }
    }

    fun setTrackingFrequency(seconds: Int) {
        _trackingFrequencySeconds.value = seconds
    }

    fun setBackgroundTracking(enabled: Boolean) {
        _isBackgroundTrackingEnabled.value = enabled
    }

    // ================== AUTHENTICATION ==================

    suspend fun signInWithGoogle(activityContext: Context): Result<UserData> {
        return try {
            val credentialManager = CredentialManager.create(activityContext)
            
            val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(GOOGLE_SERVER_CLIENT_ID)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogleOption)
                .build()

            val response = credentialManager.getCredential(activityContext, request)
            val credential = response.credential

            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken

                if (auth != null) {
                    val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                    val authResult = auth.signInWithCredential(authCredential).await()
                    val fbUser = authResult.user
                    val user = UserData(
                        uid = fbUser?.uid ?: UUID.randomUUID().toString(),
                        displayName = fbUser?.displayName ?: googleIdTokenCredential.displayName ?: "Utente Google",
                        email = fbUser?.email,
                        phoneNumber = fbUser?.phoneNumber,
                        photoUrl = fbUser?.photoUrl?.toString() ?: googleIdTokenCredential.profilePictureUri?.toString(),
                        isAnonymous = false
                    )
                    _currentUserState.value = user
                    syncUserWithFirestore(user)
                    loadUserGroupsFromFirestore(user.uid)
                    Result.success(user)
                } else {
                    val user = UserData(
                        uid = googleIdTokenCredential.id,
                        displayName = googleIdTokenCredential.displayName ?: "Utente Google",
                        email = googleIdTokenCredential.id,
                        photoUrl = googleIdTokenCredential.profilePictureUri?.toString(),
                        isAnonymous = false
                    )
                    _currentUserState.value = user
                    syncUserWithFirestore(user)
                    Result.success(user)
                }
            } else {
                Result.failure(IllegalStateException(str(R.string.err_google_credential_type)))
            }
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "Google Sign-In cancelled by user")
            Result.failure(Exception(str(R.string.err_google_cancelled)))
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google accounts available on this device: ${e.message}")
            Result.failure(Exception(str(R.string.err_google_no_account)))
        } catch (e: Exception) {
            Log.e(TAG, "signInWithGoogle failed: ${e.message}", e)
            val message = when {
                e.message?.contains("16:") == true -> "Configurazione Google Sign-In non completata. Verifica che l'account Google o l'impronta SHA-1 siano configurati nella console Firebase, oppure accedi con Telefono/Email."
                e.message?.contains("10:") == true -> "Errore di configurazione Google Play Services (Developer Error)."
                e.localizedMessage.isNullOrBlank() -> "Errore durante l'accesso Google: ${e.javaClass.simpleName}"
                else -> e.localizedMessage
            }
            Result.failure(Exception(message))
        }
    }

    fun sendPhoneVerificationCode(
        activity: Activity,
        phoneNumber: String,
        onCodeSent: (verificationId: String) -> Unit,
        onVerificationCompleted: (UserData) -> Unit,
        onVerificationFailed: (Exception) -> Unit
    ) {
        if (auth == null) {
            onVerificationFailed(IllegalStateException(str(R.string.err_auth_not_initialized)))
            return
        }

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val authResult = auth.signInWithCredential(credential).await()
                        val fbUser = authResult.user
                        val user = UserData(
                            uid = fbUser?.uid ?: UUID.randomUUID().toString(),
                            displayName = fbUser?.displayName ?: "Utente ($phoneNumber)",
                            phoneNumber = phoneNumber,
                            isAnonymous = false
                        )
                        _currentUserState.value = user
                        syncUserWithFirestore(user)
                        loadUserGroupsFromFirestore(user.uid)
                        onVerificationCompleted(user)
                    } catch (e: Exception) {
                        Log.e(TAG, "Auto-verification sign in failed: ${e.message}")
                        onVerificationFailed(e)
                    }
                }
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Log.e(TAG, "Phone verification failed: ${e.message}", e)
                onVerificationFailed(e)
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                Log.d(TAG, "Phone code sent. verificationId: $verificationId")
                onCodeSent(verificationId)
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    suspend fun verifyPhoneCodeAndSignIn(
        verificationId: String,
        smsCode: String,
        displayName: String = "",
        phoneNumber: String = ""
    ): Result<UserData> {
        return try {
            if (auth != null) {
                val credential = PhoneAuthProvider.getCredential(verificationId, smsCode)
                val authResult = auth.signInWithCredential(credential).await()
                val fbUser = authResult.user
                val finalName = displayName.ifBlank {
                    fbUser?.displayName ?: if (phoneNumber.isNotBlank()) "Utente ($phoneNumber)" else "Utente Telefono"
                }
                val user = UserData(
                    uid = fbUser?.uid ?: UUID.randomUUID().toString(),
                    displayName = finalName,
                    phoneNumber = fbUser?.phoneNumber ?: phoneNumber,
                    isAnonymous = false
                )
                _currentUserState.value = user
                syncUserWithFirestore(user)
                loadUserGroupsFromFirestore(user.uid)
                Result.success(user)
            } else {
                Result.failure(IllegalStateException(str(R.string.err_auth_not_initialized)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyPhoneCodeAndSignIn failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun signInWithEmail(email: String, pass: String): Result<UserData> {
        return try {
            if (auth != null) {
                val authResult = auth.signInWithEmailAndPassword(email, pass).await()
                val fbUser = authResult.user
                val user = UserData(
                    uid = fbUser?.uid ?: UUID.randomUUID().toString(),
                    displayName = fbUser?.displayName ?: email.substringBefore("@"),
                    email = email,
                    isAnonymous = false
                )
                _currentUserState.value = user
                syncUserWithFirestore(user)
                loadUserGroupsFromFirestore(user.uid)
                Result.success(user)
            } else {
                val user = UserData(
                    uid = "uid_${email.hashCode()}",
                    displayName = email.substringBefore("@"),
                    email = email,
                    isAnonymous = false
                )
                _currentUserState.value = user
                Result.success(user)
            }
        } catch (e: Exception) {
            Log.e(TAG, "signInWithEmail failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun signUpWithEmail(email: String, pass: String, displayName: String): Result<UserData> {
        return try {
            if (auth != null) {
                val authResult = auth.createUserWithEmailAndPassword(email, pass).await()
                val fbUser = authResult.user
                val finalName = displayName.ifBlank { email.substringBefore("@") }
                val user = UserData(
                    uid = fbUser?.uid ?: UUID.randomUUID().toString(),
                    displayName = finalName,
                    email = email,
                    isAnonymous = false
                )
                _currentUserState.value = user
                syncUserWithFirestore(user)
                loadUserGroupsFromFirestore(user.uid)
                Result.success(user)
            } else {
                val user = UserData(
                    uid = "uid_${email.hashCode()}",
                    displayName = displayName.ifBlank { email.substringBefore("@") },
                    email = email,
                    isAnonymous = false
                )
                _currentUserState.value = user
                Result.success(user)
            }
        } catch (e: Exception) {
            Log.e(TAG, "signUpWithEmail failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun signInAnonymously(nickname: String): Result<UserData> {
        return try {
            val validName = nickname.ifBlank { "Membro ${Random.nextInt(100, 999)}" }
            if (auth != null) {
                val authResult = auth.signInAnonymously().await()
                val user = UserData(
                    uid = authResult.user?.uid ?: UUID.randomUUID().toString(),
                    displayName = validName,
                    isAnonymous = true
                )
                _currentUserState.value = user
                syncUserWithFirestore(user)
                loadUserGroupsFromFirestore(user.uid)
                Result.success(user)
            } else {
                val user = UserData(
                    uid = "anon_${UUID.randomUUID().toString().take(8)}",
                    displayName = validName,
                    isAnonymous = true
                )
                _currentUserState.value = user
                Result.success(user)
            }
        } catch (e: Exception) {
            Log.e(TAG, "signInAnonymously failed: ${e.message}", e)
            val user = UserData(
                uid = "anon_${UUID.randomUUID().toString().take(8)}",
                displayName = nickname.ifBlank { "Ospite Radar" },
                isAnonymous = true
            )
            _currentUserState.value = user
            Result.success(user)
        }
    }

    fun signOut() {
        try {
            val currentGroup = _currentUserState.value?.currentGroupId
            if (!currentGroup.isNullOrBlank()) {
                unsubscribeFromGroupTopic(currentGroup)
            }
            cleanupUserRealtimeListeners()
            cleanupGroupListeners()
            auth?.signOut()
        } catch (e: Exception) {
            Log.e(TAG, "signOut failed: ${e.message}")
        }
        _currentUserState.value = null
        _userGroupsState.value = emptyList()
        _currentGroupLocations.value = emptyList()
        _currentGroupPlaces.value = emptyList()
        _currentGroupMessages.value = emptyList()
        _currentGroupMembers.value = emptyList()
        _currentGroupSnapshots.value = emptyList()
        _groupTrips.value = emptyList()
        _activeGeofenceAlerts.value = emptyList()
        _unreadChatCount.value = 0
        _activeTrip.value = null

        // Il repository e' un singleton di processo: senza questo azzeramento i
        // due segnali che governano la scelta del gruppo sopravvivono al
        // logout e avvelenano la sessione successiva.
        //
        // groupIdDismissedByUser e' il veto che impedisce di rientrare nel
        // gruppo appena lasciato. Se resta impostato, al nuovo accesso il
        // listener del documento utente scarta l'auto-selezione di QUEL gruppo,
        // currentGroupId non viene mai valorizzato, e l'app o entra in un
        // gruppo a caso o resta sulla lista senza saper dove andare.
        //
        // isChoosingGroup, se resta true, inchioda la UI sulla schermata di
        // scelta a ogni riavvio.
        groupIdDismissedByUser = null
        _isChoosingGroup.value = false
        resetLocationGate()
    }

    private suspend fun syncUserWithFirestore(user: UserData) {
        if (firestore == null) return
        try {
            val userMap = hashMapOf(
                "uid" to user.uid,
                "displayName" to user.displayName,
                "email" to (user.email ?: ""),
                "phoneNumber" to (user.phoneNumber ?: ""),
                "photoUrl" to (user.photoUrl ?: ""),
                "photoBase64" to (user.photoBase64 ?: ""),
                "fcmToken" to (user.fcmToken ?: getStoredFcmToken() ?: ""),
                "lastSeen" to System.currentTimeMillis(),
                "isAnonymous" to user.isAnonymous
            )
            firestore.collection("users").document(user.uid).set(userMap).await()
        } catch (e: Exception) {
            Log.w(TAG, "syncUserWithFirestore warning: ${e.message}")
        }
    }

    private fun cleanupUserRealtimeListeners() {
        userDocListener?.remove()
        userDocListener = null
        groupsCollectionListener?.remove()
        groupsCollectionListener = null
        memberStatusListeners.values.forEach { it.remove() }
        memberStatusListeners.clear()
        memberStatusSeen.clear()
        memberGroupsMap.clear()
    }

    // ================== GROUP MANAGEMENT & REAL-TIME REPO ==================

    /**
     * Continuous real-time listener on user profile, group directory, and membership documents.
     * Guarantees that when an admin approves a member, the member's device instantly intercepts
     * the change, unlocks the UI, and automatically subscribes to FCM topics in real-time.
     */
    fun startUserRealtimeSync(userId: String) {
        if (firestore == null || userId.isBlank()) return
        cleanupUserRealtimeListeners()

        // 1. Continuous listener on user document users/{userId}
        userDocListener = firestore.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                val lastApproved = snapshot.getString("lastApprovedGroupId")
                val currentGroupId = snapshot.getString("currentGroupId")

                // Ordine invertito rispetto a prima: la scelta esplicita dell'utente
                // ha la precedenza, `lastApprovedGroupId` interviene solo come
                // fallback quando non c'è nessun gruppo selezionato.
                val targetGroupId = currentGroupId?.takeIf { it.isNotBlank() }
                    ?: lastApproved?.takeIf { it.isNotBlank() }

                // Non riproporre il gruppo che l'utente ha appena abbandonato:
                // altrimenti il pulsante "cambia gruppo" rimbalza indietro subito.
                if (targetGroupId != null && targetGroupId == groupIdDismissedByUser) {
                    Log.d(TAG, "Auto-selezione ignorata per $targetGroupId: lasciato dall'utente")
                    return@addSnapshotListener
                }

                if (!targetGroupId.isNullOrBlank()) {
                    val existing = _userGroupsState.value.find { it.id == targetGroupId }
                    if (existing != null) {
                        if (_currentUserState.value?.currentGroupId != targetGroupId) {
                            selectGroup(targetGroupId)
                        }
                    } else {
                        // Retrieve group data and activate
                        firestore.collection("groups").document(targetGroupId).get()
                            .addOnSuccessListener { gDoc ->
                                if (gDoc.exists()) {
                                    val gData = GroupData(
                                        id = gDoc.getString("id") ?: gDoc.id,
                                        name = gDoc.getString("name") ?: "Gruppo",
                                        joinCode = gDoc.getString("joinCode") ?: "---",
                                        ownerId = gDoc.getString("ownerId") ?: "",
                                        description = gDoc.getString("description") ?: "",
                                        createdAt = gDoc.getLong("createdAt") ?: System.currentTimeMillis(),
                                        photoBase64 = gDoc.getString("photoBase64") ?: ""
                                    )
                                    val updated = (_userGroupsState.value + gData).distinctBy { it.id }
                                    _userGroupsState.value = updated
                                    selectGroup(targetGroupId)
                                }
                            }
                    }
                }
            }

        // 2. Continuous real-time listener on all groups in Firestore
        groupsCollectionListener = firestore.collection("groups")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    Log.w(TAG, "Groups collection listener error: ${error?.message}")
                    return@addSnapshotListener
                }

                val currentGroupDocs = snapshot.documents
                val updatedGroups = mutableListOf<GroupData>()

                for (doc in currentGroupDocs) {
                    val gId = doc.getString("id") ?: doc.id
                    val ownerId = doc.getString("ownerId") ?: ""
                    val reqApproval = doc.getBoolean("requiresApproval") ?: true
                    val existingInState = _userGroupsState.value.find { it.id == gId }
                    val currentStatus = if (ownerId == userId) "ACTIVE" else (existingInState?.userMembershipStatus ?: "PENDING")

                    val group = GroupData(
                        id = gId,
                        name = doc.getString("name") ?: "Gruppo",
                        joinCode = doc.getString("joinCode") ?: "---",
                        ownerId = ownerId,
                        description = doc.getString("description") ?: "",
                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                        requiresApproval = reqApproval,
                        photoBase64 = doc.getString("photoBase64") ?: "",
                        userMembershipStatus = currentStatus
                    )

                    memberGroupsMap[gId] = group

                    if (ownerId == userId) {
                        updatedGroups.add(group.copy(userMembershipStatus = "ACTIVE"))
                    } else {
                        // Il documento arriva fresco da Firestore: di quello che c'e'
                        // gia' in stato conserviamo solo lo stato di appartenenza e
                        // prendiamo da qui nome, descrizione e immagine. Senza questo
                        // ramo le modifiche del proprietario non raggiungevano mai chi
                        // non e' owner: attachMemberDocListener cattura groupData una
                        // volta sola e non si riattacca (vedi la guardia su
                        // memberStatusListeners), quindi ripubblicava per sempre la
                        // versione vista al primo giro.
                        if (existingInState != null) {
                            updatedGroups.add(
                                group.copy(userMembershipStatus = existingInState.userMembershipStatus)
                            )
                        }
                        attachMemberDocListener(gId, group, userId)
                    }
                }

                // Keep owner groups plus existing confirmed member groups that are still valid in Firestore.
                // updatedGroups viene prima nella concatenazione, quindi il distinctBy
                // tiene la copia fresca e questa lista copre solo i gruppi non ancora
                // ricostruiti sopra.
                val currentActiveMemberGroups = _userGroupsState.value.filter { g ->
                    g.ownerId != userId && currentGroupDocs.any { it.id == g.id }
                }
                val merged = (updatedGroups + currentActiveMemberGroups).distinctBy { it.id }
                _userGroupsState.value = merged

                val activeGroups = merged.filter { it.userMembershipStatus == "ACTIVE" }
                if (_currentUserState.value?.currentGroupId.isNullOrBlank() && activeGroups.isNotEmpty()) {
                    selectGroup(activeGroups.first().id)
                }
            }
    }

    /**
     * Attaches an individual real-time listener to groups/{groupId}/members/{userId}.
     * When the admin changes status from PENDING to ACTIVE, this listener fires instantly.
     */
    private fun attachMemberDocListener(groupId: String, groupData: GroupData, userId: String) {
        if (firestore == null || userId.isBlank() || groupId.isBlank()) return
        if (memberStatusListeners.containsKey(groupId)) return

        val reg = firestore.collection("groups").document(groupId)
            .collection("members").document(userId)
            .addSnapshotListener { memberDoc, error ->
                if (error != null) {
                    Log.w(TAG, "Member listener error for group $groupId: ${error.message}")
                    return@addSnapshotListener
                }

                if (memberDoc != null && memberDoc.exists()) {
                    val status = memberDoc.getString("status") ?: "ACTIVE"
                    // Una vera approvazione e' una transizione da uno stato precedente
                    // NON attivo (tipicamente PENDING) a ACTIVE. Al primo snapshot
                    // prev e' null: un gruppo gia' ACTIVE all'avvio non e' un'approvazione.
                    val prevStatus = memberStatusSeen.put(groupId, status)
                    val isFreshApproval = prevStatus != null &&
                        !prevStatus.equals("ACTIVE", ignoreCase = true) &&
                        status.equals("ACTIVE", ignoreCase = true)
                    // memberGroupsMap e' tenuto aggiornato dal listener sulla collection
                    // groups: leggendo da li' invece dal groupData catturato alla
                    // creazione di questo listener, una rinomina o un cambio di immagine
                    // fatti dal proprietario non vengono piu' sovrascritti col vecchio.
                    val freshGroup = memberGroupsMap[groupId] ?: groupData
                    val groupWithStatus = freshGroup.copy(userMembershipStatus = status)

                    val currentList = _userGroupsState.value.toMutableList()
                    val idx = currentList.indexOfFirst { it.id == groupId }
                    if (idx >= 0) {
                        currentList[idx] = groupWithStatus
                    } else {
                        currentList.add(groupWithStatus)
                    }
                    _userGroupsState.value = currentList

                    if (status.equals("ACTIVE", ignoreCase = true)) {
                        Log.d(TAG, "Real-time activation detected: user $userId is now ACTIVE in group $groupId")

                        // Mandatory immediate FCM topic subscription for the activated group
                        try {
                            FirebaseMessaging.getInstance().subscribeToTopic("group_$groupId")
                                .addOnSuccessListener { Log.d(TAG, "Subscribed to FCM topic group_$groupId") }
                            val safeTopic = "group_${groupId.replace("-", "_")}"
                            if (safeTopic != "group_$groupId") {
                                FirebaseMessaging.getInstance().subscribeToTopic(safeTopic)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "FCM subscription error: ${e.message}")
                        }

                        // Auto-entra SOLO in due casi sicuri:
                        //  - e' il gruppo gia' corrente (re-attivazione, idempotente);
                        //  - e' una vera approvazione appena avvenuta.
                        // Il vecchio ramo "currentGroupId vuoto -> entra" faceva sì che,
                        // dopo una nuova installazione con piu' gruppi, ogni listener
                        // entrasse nel proprio gruppo, causando il flip-flop. La scelta
                        // iniziale spetta a userDocListener (gruppo salvato) e a
                        // MainActivity (auto solo se il gruppo attivo e' uno solo).
                        val currentGid = _currentUserState.value?.currentGroupId
                        if (currentGid == groupId || (isFreshApproval && currentGid.isNullOrBlank())) {
                            selectGroup(groupId)
                        }
                    }
                } else {
                    // Document was deleted (rejected by admin or expelled)
                    val remaining = _userGroupsState.value.filterNot { it.id == groupId }
                    _userGroupsState.value = remaining
                    if (_currentUserState.value?.currentGroupId == groupId) {
                        val nextActive = remaining.firstOrNull { it.userMembershipStatus == "ACTIVE" }
                        if (nextActive != null) {
                            selectGroup(nextActive.id)
                        } else {
                            cleanupGroupListeners()
                            _currentUserState.value = _currentUserState.value?.copy(currentGroupId = null)
                        }
                    }
                }
            }
        memberStatusListeners[groupId] = reg
    }

    fun loadUserGroupsFromFirestore(userId: String) {
        startUserRealtimeSync(userId)
    }

    suspend fun createGroup(
        name: String,
        description: String = "",
        requiresApproval: Boolean = true,
        photoBase64: String = "",
        isPublic: Boolean = false
    ): Result<GroupData> {
        val currentUser = _currentUserState.value ?: return Result.failure(Exception(str(R.string.err_not_authenticated)))
        val groupId = "grp_${UUID.randomUUID().toString().take(8)}"
        val joinCode = generateJoinCode()

        val newGroup = GroupData(
            id = groupId,
            name = name.ifBlank { "Nuovo Gruppo Famiglia" },
            joinCode = joinCode,
            ownerId = currentUser.uid,
            description = description,
            createdAt = System.currentTimeMillis(),
            requiresApproval = requiresApproval,
            photoBase64 = photoBase64,
            userMembershipStatus = "ACTIVE"
        )

        try {
            if (firestore != null) {
                val groupMap = hashMapOf(
                    "id" to newGroup.id,
                    "name" to newGroup.name,
                    "joinCode" to newGroup.joinCode,
                    "ownerId" to newGroup.ownerId,
                    "description" to newGroup.description,
                    "createdAt" to newGroup.createdAt,
                    "requiresApproval" to requiresApproval,
                    "photoBase64" to newGroup.photoBase64,
                    "isPublic" to isPublic,
                    "nameLower" to name.lowercase(),
                    "memberCount" to 1
                )
                firestore.collection("groups").document(groupId).set(groupMap).await()

                // Add current user as owner with ACTIVE status
                val memberMap = hashMapOf(
                    "userId" to currentUser.uid,
                    "displayName" to currentUser.displayName,
                    "email" to (currentUser.email ?: ""),
                    "photoBase64" to (currentUser.photoBase64 ?: ""),
                    "role" to "owner",
                    "status" to "ACTIVE",
                    "joinedAt" to System.currentTimeMillis(),
                    "batteryLevel" to 100,
                    "isTrackingActive" to true,
                    "isOnline" to true
                )
                firestore.collection("groups").document(groupId)
                    .collection("members").document(currentUser.uid).set(memberMap).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "createGroup on Firestore error: ${e.message}")
        }

        val updatedGroups = (_userGroupsState.value + newGroup).distinctBy { it.id }
        _userGroupsState.value = updatedGroups
        selectGroup(groupId)
        return Result.success(newGroup)
    }

    /**
     * Join group with access policy check (Direct access vs Pending approval).
     */
    suspend fun joinGroupByCode(joinCode: String): Result<String> {
        val currentUser = _currentUserState.value ?: return Result.failure(Exception(str(R.string.err_not_authenticated)))
        val cleanCode = joinCode.trim().uppercase()

        try {
            if (firestore != null) {
                val snapshot = firestore.collection("groups")
                    .whereEqualTo("joinCode", cleanCode)
                    .limit(1)
                    .get()
                    .await()

                if (!snapshot.isEmpty) {
                    val doc = snapshot.documents[0]
                    val reqApproval = doc.getBoolean("requiresApproval") ?: true
                    val group = GroupData(
                        id = doc.getString("id") ?: doc.id,
                        name = doc.getString("name") ?: "Gruppo",
                        joinCode = doc.getString("joinCode") ?: cleanCode,
                        ownerId = doc.getString("ownerId") ?: "",
                        description = doc.getString("description") ?: "",
                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                        requiresApproval = reqApproval,
                        photoBase64 = doc.getString("photoBase64") ?: "",
                        userMembershipStatus = "ACTIVE"
                    )

                    val isOwner = group.ownerId == currentUser.uid
                    if (isOwner) {
                        val activeGroup = group.copy(userMembershipStatus = "ACTIVE")
                        _userGroupsState.value = (_userGroupsState.value + activeGroup).distinctBy { it.id }
                        selectGroup(group.id)
                        return Result.success("Accesso al tuo gruppo '${group.name}' confermato")
                    }

                    // Check if member record already exists
                    val memberDoc = doc.reference.collection("members").document(currentUser.uid).get().await()
                    if (memberDoc.exists()) {
                        val status = memberDoc.getString("status") ?: "ACTIVE"
                        val groupWithStatus = group.copy(userMembershipStatus = status)
                        _userGroupsState.value = (_userGroupsState.value + groupWithStatus).distinctBy { it.id }

                        if (status == "ACTIVE") {
                            selectGroup(group.id)
                            return Result.success("Accesso al gruppo '${group.name}' confermato")
                        } else {
                            attachMemberDocListener(group.id, group, currentUser.uid)
                            return Result.success("Richiesta inviata! In attesa di approvazione dell'amministratore di '${group.name}'.")
                        }
                    }

                    // New applicant
                    if (!reqApproval) {
                        // Direct instant access
                        val memberMap = hashMapOf(
                            "userId" to currentUser.uid,
                            "displayName" to currentUser.displayName,
                            "email" to (currentUser.email ?: ""),
                            "photoBase64" to (currentUser.photoBase64 ?: ""),
                            "role" to "member",
                            "status" to "ACTIVE",
                            "joinedAt" to System.currentTimeMillis(),
                            "batteryLevel" to 100,
                            "isTrackingActive" to true,
                            "isOnline" to true
                        )
                        firestore.collection("groups").document(group.id)
                            .collection("members").document(currentUser.uid).set(memberMap).await()

                        val activeGroup = group.copy(userMembershipStatus = "ACTIVE")
                        _userGroupsState.value = (_userGroupsState.value + activeGroup).distinctBy { it.id }
                        selectGroup(group.id)
                        return Result.success("Accesso immediato al gruppo '${group.name}' completato!")
                    } else {
                        // Approval required
                        val memberMap = hashMapOf(
                            "userId" to currentUser.uid,
                            "displayName" to currentUser.displayName,
                            "email" to (currentUser.email ?: ""),
                            "photoBase64" to (currentUser.photoBase64 ?: ""),
                            "role" to "member",
                            "status" to "PENDING",
                            "joinedAt" to System.currentTimeMillis(),
                            "batteryLevel" to 100,
                            "isTrackingActive" to true,
                            "isOnline" to true
                        )
                        firestore.collection("groups").document(group.id)
                            .collection("members").document(currentUser.uid).set(memberMap).await()

                        val pendingGroup = group.copy(userMembershipStatus = "PENDING")
                        _userGroupsState.value = (_userGroupsState.value + pendingGroup).distinctBy { it.id }

                        // Immediately attach real-time listener so the UI will unlock automatically as soon as admin approves
                        attachMemberDocListener(group.id, group, currentUser.uid)

                        // Send join_request event for Group Admin
                        val eventId = "req_${UUID.randomUUID().toString().take(8)}"
                        val eventMap = hashMapOf(
                            "id" to eventId,
                            "groupId" to group.id,
                            "type" to "join_request",
                            "userId" to currentUser.uid,
                            "userName" to currentUser.displayName,
                            "placeName" to group.name,
                            "message" to "${currentUser.displayName} ha richiesto di unirsi a ${group.name}",
                            "timestamp" to System.currentTimeMillis(),
                            "targetAdminId" to group.ownerId
                        )
                        firestore.collection("groups").document(group.id)
                            .collection("events").document(eventId).set(eventMap).await()

                        return Result.success("Richiesta inviata! In attesa di approvazione da parte dell'amministratore di '${group.name}'.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "joinGroupByCode firestore failed: ${e.message}")
            return Result.failure(e)
        }

        // Fallback: check local groups
        val existing = _userGroupsState.value.find { it.joinCode.equals(cleanCode, ignoreCase = true) }
        if (existing != null) {
            selectGroup(existing.id)
            return Result.success("Accesso al gruppo '${existing.name}' confermato")
        }

        return Result.failure(Exception(str(R.string.err_invalid_join_code)))
    }

    /** Ultimo errore di ricerca, esposto alla UI per diagnosticare index/regole. */
    private val _lastSearchError = MutableStateFlow<String?>(null)
    val lastSearchError = _lastSearchError.asStateFlow()

    /**
     * Cerca i gruppi pubblici per prefisso del nome.
     *
     * Deliberatamente NON usa un range su nameLower nella query: combinare
     * l'uguaglianza su isPublic con un range su un altro campo richiederebbe un
     * indice composito da creare a mano in Console. Qui si interroga solo
     * `isPublic == true` (indice a campo singolo, automatico) e si filtra il
     * prefisso lato client - per un'app familiare i gruppi pubblici sono pochi.
     */
    suspend fun searchGroupsByName(query: String): List<GroupData> {
        val q = query.lowercase().trim()
        if (firestore == null || q.isBlank()) return emptyList()
        return try {
            val snapshot = firestore.collection("groups")
                .whereEqualTo("isPublic", true)
                .limit(100)
                .get()
                .await()
            _lastSearchError.value = null
            snapshot.documents.mapNotNull { doc ->
                try {
                    val nameLower = (doc.getString("nameLower")
                        ?: doc.getString("name")?.lowercase() ?: "")
                    if (!nameLower.startsWith(q)) return@mapNotNull null
                    GroupData(
                        id = doc.getString("id") ?: doc.id,
                        name = doc.getString("name") ?: "",
                        joinCode = doc.getString("joinCode") ?: "",
                        ownerId = doc.getString("ownerId") ?: "",
                        description = doc.getString("description") ?: "",
                        createdAt = doc.getLong("createdAt") ?: 0L,
                        requiresApproval = doc.getBoolean("requiresApproval") ?: true,
                        photoBase64 = doc.getString("photoBase64") ?: "",
                        isPublic = true,
                        nameLower = nameLower,
                        memberCount = (doc.getLong("memberCount") ?: 0L).toInt()
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            // Tipicamente PERMISSION_DENIED (regole Firestore che vietano di
            // leggere gruppi di cui non sei membro). Lo si espone alla UI.
            Log.w(TAG, "searchGroupsByName failed: ${e.message}")
            _lastSearchError.value = e.message
            emptyList()
        }
    }

    /**
     * Fetch a group preview by its join code, without joining.
     */
    suspend fun getGroupPreviewByCode(code: String): GroupData? {
        return try {
            if (firestore == null) return null
            val cleanCode = code.trim().uppercase()
            val snapshot = firestore.collection("groups")
                .whereEqualTo("joinCode", cleanCode)
                .limit(1)
                .get()
                .await()
            val doc = snapshot.documents.firstOrNull() ?: return null
            GroupData(
                id = doc.getString("id") ?: doc.id,
                name = doc.getString("name") ?: "",
                joinCode = doc.getString("joinCode") ?: cleanCode,
                ownerId = doc.getString("ownerId") ?: "",
                description = doc.getString("description") ?: "",
                createdAt = doc.getLong("createdAt") ?: 0L,
                requiresApproval = doc.getBoolean("requiresApproval") ?: true,
                photoBase64 = doc.getString("photoBase64") ?: "",
                isPublic = doc.getBoolean("isPublic") ?: false,
                nameLower = doc.getString("nameLower") ?: "",
                memberCount = (doc.getLong("memberCount") ?: 0L).toInt()
            )
        } catch (e: Exception) {
            Log.w(TAG, "getGroupPreviewByCode failed: ${e.message}")
            null
        }
    }

    /**
     * Admin approves a pending join request. Sets status to "ACTIVE".
     * Also updates user document to guarantee real-time push/sync trigger.
     */
    suspend fun approveJoinRequest(groupId: String, memberId: String): Result<Unit> {
        return try {
            if (firestore != null) {
                // 1. Update member document to ACTIVE
                firestore.collection("groups").document(groupId)
                    .collection("members").document(memberId)
                    .update("status", "ACTIVE")
                    .await()

                // 2. Update user profile to ensure snapshot listener trigger
                firestore.collection("users").document(memberId).set(
                    hashMapOf(
                        "lastApprovedGroupId" to groupId,
                        "currentGroupId" to groupId,
                        "lastUpdated" to System.currentTimeMillis()
                    ),
                    com.google.firebase.firestore.SetOptions.merge()
                ).await()

                // 3. Post approved event in group
                val eventId = "appr_${UUID.randomUUID().toString().take(8)}"
                val eventMap = hashMapOf(
                    "id" to eventId,
                    "groupId" to groupId,
                    "type" to "member_approved",
                    "userId" to memberId,
                    "timestamp" to System.currentTimeMillis()
                )
                firestore.collection("groups").document(groupId)
                    .collection("events").document(eventId).set(eventMap).await()
            }
            _currentGroupMembers.value = _currentGroupMembers.value.map {
                if (it.userId == memberId) it.copy(status = "ACTIVE") else it
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "approveJoinRequest failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Admin rejects a pending join request. Deletes member record.
     */
    suspend fun rejectJoinRequest(groupId: String, memberId: String): Result<Unit> {
        return try {
            if (firestore != null) {
                firestore.collection("groups").document(groupId)
                    .collection("members").document(memberId)
                    .delete()
                    .await()
            }
            _currentGroupMembers.value = _currentGroupMembers.value.filterNot { it.userId == memberId }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "rejectJoinRequest failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Admin expels/removes a member from the group.
     */
    suspend fun removeMemberFromGroup(groupId: String, memberId: String): Result<Unit> {
        return try {
            if (firestore != null) {
                firestore.collection("groups").document(groupId)
                    .collection("members").document(memberId)
                    .delete()
                    .await()
                firestore.collection("groups").document(groupId)
                    .collection("locations").document(memberId)
                    .delete()
                    .await()
            }
            _currentGroupMembers.value = _currentGroupMembers.value.filterNot { it.userId == memberId }
            _currentGroupLocations.value = _currentGroupLocations.value.filterNot { it.userId == memberId }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "removeMemberFromGroup failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Elimina il gruppo e tutto ciò che contiene. Irreversibile.
     *
     * Firestore non cancella le sottocollezioni insieme al documento padre: se si
     * togliesse solo `groups/{id}`, membri, posizioni, messaggi, luoghi, eventi,
     * istantanee e viaggi resterebbero orfani e continuerebbero a occupare spazio
     * senza essere raggiungibili da nulla. Vanno svuotate una per una.
     */
    suspend fun deleteGroup(groupId: String): Result<Unit> {
        val user = _currentUserState.value ?: return Result.failure(Exception(str(R.string.err_not_authenticated)))
        val group = _userGroupsState.value.find { it.id == groupId }
        val myRole = _currentGroupMembers.value.find { it.userId == user.uid }?.role
        if (group != null && group.ownerId != user.uid && myRole !in listOf("owner", "admin")) {
            return Result.failure(Exception(str(R.string.err_only_admin_can_delete_group)))
        }

        return try {
            val db = firestore
            if (db != null) {
                val groupRef = db.collection("groups").document(groupId)

                for (name in listOf("members", "locations", "places", "messages", "events", "snapshots", "trips")) {
                    try {
                        val docs = groupRef.collection(name).get().await()
                        for (doc in docs.documents) {
                            // La traccia di un viaggio e' a sua volta in una
                            // sottocollezione del viaggio: va tolta prima.
                            if (name == "trips") {
                                try {
                                    doc.reference.collection("track").document("data").delete().await()
                                } catch (_: Exception) {}
                            }
                            doc.reference.delete().await()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "deleteGroup: pulizia '$name' fallita: ${e.message}")
                    }
                }

                groupRef.delete().await()
            }

            unsubscribeFromGroupTopic(groupId)

            val remaining = _userGroupsState.value.filterNot { it.id == groupId }
            _userGroupsState.value = remaining

            if (_currentUserState.value?.currentGroupId == groupId) {
                cleanupGroupListeners()
                _currentUserState.value = _currentUserState.value?.copy(currentGroupId = null)
                groupIdDismissedByUser = groupId
                // Persistita, altrimenti il documento utente si riemette e
                // riporta dentro un gruppo che non esiste piu'.
                try {
                    db?.collection("users")?.document(user.uid)?.update(
                        mapOf(
                            "currentGroupId" to null,
                            "lastApprovedGroupId" to null,
                            "lastUpdated" to System.currentTimeMillis()
                        )
                    )?.await()
                } catch (e: Exception) {
                    Log.w(TAG, "deleteGroup: reset gruppo corrente fallito: ${e.message}")
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "deleteGroup failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Member leaves group: deletes member and location records, unsubscribes from FCM topic.
     */
    suspend fun leaveGroup(groupId: String): Result<Unit> {
        val user = _currentUserState.value ?: return Result.failure(Exception(str(R.string.err_not_authenticated)))
        return try {
            if (firestore != null) {
                firestore.collection("groups").document(groupId)
                    .collection("members").document(user.uid)
                    .delete()
                    .await()
                firestore.collection("groups").document(groupId)
                    .collection("locations").document(user.uid)
                    .delete()
                    .await()
            }
            unsubscribeFromGroupTopic(groupId)

            val remainingGroups = _userGroupsState.value.filterNot { it.id == groupId }
            _userGroupsState.value = remainingGroups

            if (_currentUserState.value?.currentGroupId == groupId) {
                val nextGroup = remainingGroups.firstOrNull()
                if (nextGroup != null) {
                    selectGroup(nextGroup.id)
                } else {
                    cleanupGroupListeners()
                    _currentUserState.value = _currentUserState.value?.copy(currentGroupId = null)
                    _currentGroupLocations.value = emptyList()
                    _currentGroupPlaces.value = emptyList()
                    _currentGroupSnapshots.value = emptyList()
                    _currentGroupMessages.value = emptyList()
                    _currentGroupMembers.value = emptyList()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "leaveGroup failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun selectGroup(groupId: String) {
        if (groupId.isBlank()) return

        val previousGroupId = _currentUserState.value?.currentGroupId
        if (previousGroupId == groupId && _currentGroupMembers.value.isNotEmpty()) {
            // Già dentro e con i listener attivi: rifare tutto provocherebbe solo
            // un giro inutile di detach/attach e un lampeggio della UI.
            _isChoosingGroup.value = false
            return
        }

        if (!previousGroupId.isNullOrBlank() && previousGroupId != groupId) {
            unsubscribeFromGroupTopic(previousGroupId)
        }

        // Scelta esplicita: annulla sia il veto sul gruppo lasciato sia lo stato
        // "sto scegliendo".
        groupIdDismissedByUser = null
        _isChoosingGroup.value = false

        _currentUserState.value = _currentUserState.value?.copy(currentGroupId = groupId)

        // Stacca i listener del gruppo precedente prima di agganciare i nuovi:
        // listenToGroupData li ricrea tutti e sei, e senza cleanup resterebbero
        // in ascolto due gruppi insieme (notifiche doppie, membri mescolati).
        cleanupGroupListeners()
        subscribeToGroupTopic(groupId)
        listenToGroupData(groupId)

        // Il gate riconosce da solo il cambio di gruppo e lascia passare il fix
        // successivo, ma quel fix puo' arrivare anche dopo minuti se l'intervallo
        // di tracciamento e' lungo. Ripubblicando subito l'ultima posizione nota
        // si compare nel nuovo gruppo all'istante, senza doverlo forzare a mano.
        pushLastKnownLocationNow()

        // Persistenza, così la scelta regge al riavvio e ai re-emit del documento.
        val uid = _currentUserState.value?.uid
        if (firestore != null && !uid.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    firestore.collection("users").document(uid).set(
                        hashMapOf(
                            "currentGroupId" to groupId,
                            // Consumato: da qui in poi non deve più forzare nulla.
                            "lastApprovedGroupId" to null,
                            "lastUpdated" to System.currentTimeMillis()
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    ).await()
                } catch (e: Exception) {
                    Log.w(TAG, "selectGroup: persistenza fallita: ${e.message}")
                }
            }
        }
    }

    fun subscribeToGroupTopic(groupId: String) {
        if (groupId.isBlank()) return
        try {
            val primaryTopic = "group_$groupId"
            FirebaseMessaging.getInstance().subscribeToTopic(primaryTopic)
                .addOnSuccessListener {
                    Log.d(TAG, "Subscribed to FCM topic: $primaryTopic")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to subscribe to FCM topic $primaryTopic: ${e.message}")
                }

            val sanitizedTopic = "group_${groupId.replace("-", "_")}"
            if (sanitizedTopic != primaryTopic) {
                FirebaseMessaging.getInstance().subscribeToTopic(sanitizedTopic)
            }
        } catch (e: Exception) {
            Log.w(TAG, "subscribeToGroupTopic exception: ${e.message}")
        }
    }

    fun unsubscribeFromGroupTopic(groupId: String) {
        if (groupId.isBlank()) return
        try {
            val primaryTopic = "group_$groupId"
            FirebaseMessaging.getInstance().unsubscribeFromTopic(primaryTopic)
                .addOnSuccessListener {
                    Log.d(TAG, "Unsubscribed from FCM topic: $primaryTopic")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to unsubscribe from FCM topic $primaryTopic: ${e.message}")
                }

            val sanitizedTopic = "group_${groupId.replace("-", "_")}"
            if (sanitizedTopic != primaryTopic) {
                FirebaseMessaging.getInstance().unsubscribeFromTopic(sanitizedTopic)
            }
        } catch (e: Exception) {
            Log.w(TAG, "unsubscribeFromGroupTopic exception: ${e.message}")
        }
    }

    /**
     * Un errore del listener e' recuperabile riagganciandolo? PERMISSION_DENIED /
     * UNAUTHENTICATED / NOT_FOUND non lo sono (riprovare non cambia nulla); tutto
     * il resto — RESOURCE_EXHAUSTED (quota), UNAVAILABLE (rete), ABORTED, INTERNAL,
     * DEADLINE_EXCEEDED — lo e'.
     */
    private fun isRecoverableListenerError(e: Exception?): Boolean {
        val code = (e as? FirebaseFirestoreException)?.code ?: return true
        return when (code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            FirebaseFirestoreException.Code.UNAUTHENTICATED,
            FirebaseFirestoreException.Code.NOT_FOUND -> false
            else -> true
        }
    }

    /**
     * Riaggancia TUTTI i listener di gruppo dopo un errore recuperabile, una sola
     * volta (debounce via [reattachScheduled]) e solo se nel frattempo non si e'
     * cambiato gruppo (guardia sulla generazione). Ricrea l'intero set: e' la
     * stessa strada gia' collaudata di [listenToGroupData], senza duplicare la
     * logica di riattacco per ognuno dei sette listener.
     */
    private fun scheduleGroupListenersReattach(groupId: String, reason: String) {
        if (reattachScheduled) return
        reattachScheduled = true
        val genAtSchedule = groupListenerGeneration
        Log.w(TAG, "Listener di gruppo in errore ($reason) -> reattach tra ${LISTENER_REATTACH_DELAY_MS}ms")
        CoroutineScope(Dispatchers.IO).launch {
            delay(LISTENER_REATTACH_DELAY_MS)
            // Ancora sullo stesso gruppo e nessun cleanup/reattach nel frattempo?
            if (genAtSchedule == groupListenerGeneration &&
                _currentUserState.value?.currentGroupId == groupId
            ) {
                Log.w(TAG, "Riaggancio listener del gruppo $groupId")
                listenToGroupData(groupId)
            } else {
                reattachScheduled = false
            }
        }
    }

    private fun cleanupGroupListeners() {
        // Invalida qualsiasi reattach in coda: i listener stanno per essere
        // ricreati (o azzerati), non deve ripartire quello vecchio.
        groupListenerGeneration++
        reattachScheduled = false
        locationsListener?.remove()
        placesListener?.remove()
        messagesListener?.remove()
        membersListener?.remove()
        eventsListener?.remove()
        snapshotsListener?.remove()
        locationsListener = null
        placesListener = null
        messagesListener = null
        membersListener = null
        eventsListener = null
        snapshotsListener = null
        tripsListener?.remove()
        tripsListener = null

        // Staccare i listener non basta: i flow continuano a esporre i dati del
        // gruppo che si sta lasciando finche' i listener del nuovo gruppo non
        // emettono. Nel frattempo la UI del gruppo nuovo mostra membri, pill e
        // marker di quello vecchio -- e se il gruppo nuovo e' vuoto (appena
        // creato) alcune collection non emettono affatto, quindi i dati vecchi
        // resterebbero li' per sempre. Lo stato di gruppo va azzerato qui, tutto.
        _currentGroupLocations.value = emptyList()
        _currentGroupPlaces.value = emptyList()
        _currentGroupMessages.value = emptyList()
        _currentGroupMembers.value = emptyList()
        _currentGroupSnapshots.value = emptyList()
        _activeGeofenceAlerts.value = emptyList()
        _groupTrips.value = emptyList()
        // Anche il badge dei non letti e' per gruppo: senza reset mostrerebbe
        // il conteggio del gruppo precedente fino alla prima emissione.
        _unreadChatCount.value = 0
        // Paginazione chat: si riparte dal primo blocco al prossimo gruppo.
        chatLimit = CHAT_HISTORY_LIMIT
        isLoadingMoreChat = false
        _hasMoreChat.value = false
    }

    /**
     * (Ri)aggancia il listener dei messaggi con un dato [limit]. La query prende gli
     * ultimi [limit] messaggi (DESC) e li riporta in ordine cronologico. Per caricare
     * altra storia si richiama con un limite piu' alto ([loadMoreChat]): il listener
     * vecchio viene staccato e sostituito, mantenendo il realtime su tutta la finestra.
     */
    private fun listenToMessages(groupId: String, limit: Long) {
        val db = firestore ?: return
        messagesListener?.remove()
        messagesListener = db.collection("groups").document(groupId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    isLoadingMoreChat = false
                    if (isRecoverableListenerError(e)) scheduleGroupListenersReattach(groupId, "messages: ${e.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val currentUid = _currentUserState.value?.uid
                    val list = snapshot.documents.mapNotNull { doc ->
                        try {
                            val typeStr = doc.getString("type") ?: "TEXT"
                            val type = try { MessageType.valueOf(typeStr) } catch (ex: Exception) { MessageType.TEXT }
                            val imageBase64 = doc.getString("imageBase64")
                            val imageUrl = doc.getString("imageUrl")
                            val senderId = doc.getString("senderId") ?: ""
                            val senderName = doc.getString("senderName") ?: "Membro"
                            val text = doc.getString("text") ?: ""
                            val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()

                            val audioBase64 = doc.getString("audioBase64")
                            val audioUrl = doc.getString("audioUrl")
                            val msg = ChatMessage(
                                id = doc.getString("id") ?: doc.id,
                                senderId = senderId,
                                senderName = senderName,
                                senderPhoto = doc.getString("senderPhoto"),
                                text = text,
                                imageBase64 = if (!imageBase64.isNullOrBlank()) imageBase64 else null,
                                imageUrl = if (!imageUrl.isNullOrBlank()) imageUrl else null,
                                timestamp = timestamp,
                                type = type,
                                latitude = doc.getDouble("latitude"),
                                longitude = doc.getDouble("longitude"),
                                audioBase64 = if (!audioBase64.isNullOrBlank()) audioBase64 else null,
                                audioUrl = if (!audioUrl.isNullOrBlank()) audioUrl else null,
                                audioDurationMs = doc.getLong("audioDurationMs") ?: 0L,
                                placeName = doc.getString("placeName")?.ifBlank { null }
                            )

                            // Check if this is a new message from another member
                            if (timestamp > lastObservedMessageTimestamp && senderId.isNotBlank() && senderId != currentUid) {
                                // Nota vocale fresca da un altro: segnala per anello/autoplay
                                // sulla mappa. L'audio NON e' nel messaggio: si scarichera'
                                // dal doc separato voiceNotes/{id} solo al momento del play.
                                if (type == MessageType.VOICE) {
                                    _latestVoicePing.value = VoicePing(senderId, msg.id, msg.audioDurationMs, timestamp, msg.audioUrl)
                                }
                                when (type) {
                                    // TYPE 3: SOS Alert Message
                                    MessageType.SOS_ALERT -> {
                                        showLocalNotification(
                                            title = str(R.string.notif_sos_title),
                                            body = str(R.string.notif_sos_immediate_body, senderName),
                                            isHighPriority = true,
                                            notificationId = 999,
                                            destination = "ALERT",
                                            groupId = groupId,
                                            latitude = doc.getDouble("latitude"),
                                            longitude = doc.getDouble("longitude"),
                                            senderId = senderId
                                        )
                                    }
                                    // TYPE 2: Normal Group Chat Message
                                    MessageType.TEXT, MessageType.IMAGE, MessageType.LOCATION_SHARE, MessageType.VOICE -> {
                                        // Vocale + radar in primo piano + autoplay: si sta gia'
                                        // riproducendo sulla mappa, la notifica troncherebbe l'audio.
                                        val suppress = type == MessageType.VOICE &&
                                            radarForeground && _isVoiceAutoplayEnabled.value
                                        if (!suppress) {
                                            val bodyText = when (type) {
                                                MessageType.IMAGE -> "Ha inviato un'immagine"
                                                MessageType.LOCATION_SHARE -> "Ha condiviso la posizione"
                                                MessageType.VOICE -> "Ha inviato un vocale"
                                                else -> text
                                            }
                                            showLocalNotification(
                                                title = senderName,
                                                body = bodyText,
                                                isHighPriority = false,
                                                notificationId = (timestamp % 100000).toInt(),
                                                destination = "CHAT",
                                                groupId = groupId,
                                                senderId = senderId
                                            )
                                        }
                                    }
                                    else -> {
                                        // GEOFENCE_ALERT handled by events listener
                                    }
                                }
                            }
                            msg
                        } catch (ex: Exception) {
                            null
                        }
                    }
                    if (list.isNotEmpty()) {
                        val maxTime = list.maxOf { it.timestamp }
                        if (maxTime > lastObservedMessageTimestamp) {
                            lastObservedMessageTimestamp = maxTime
                        }
                    }
                    // Blocco pieno = con ogni probabilita' c'e' altra storia da caricare.
                    _hasMoreChat.value = list.size.toLong() >= limit
                    isLoadingMoreChat = false
                    // La query e' DESCENDING: la UI vuole i messaggi dal piu'
                    // vecchio al piu' recente, altrimenti la chat appare capovolta.
                    val chronological = list.sortedBy { it.timestamp }
                    _currentGroupMessages.value = chronological
                    recomputeUnreadChat(groupId, chronological)
                }
            }
    }

    /**
     * Carica il blocco di messaggi precedente rialzando il limite e riagganciando
     * il listener. Protetto da [isLoadingMoreChat] per non accodare richieste e da
     * [hasMoreChat] per non chiedere oltre la fine della cronologia.
     */
    fun loadMoreChat() {
        if (isLoadingMoreChat || !_hasMoreChat.value) return
        val groupId = _currentUserState.value?.currentGroupId ?: return
        isLoadingMoreChat = true
        chatLimit += CHAT_PAGE_SIZE
        listenToMessages(groupId, chatLimit)
    }

    private fun listenToGroupData(groupId: String) {
        if (firestore == null) return
        cleanupGroupListeners()

        val joinTime = System.currentTimeMillis()
        lastObservedEventTimestamp = joinTime
        lastObservedMessageTimestamp = joinTime

        try {
            // 1. Real-time locations listener
            locationsListener = firestore.collection("groups").document(groupId)
                .collection("locations")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        Log.w(TAG, "Listen locations failed: ${e.message}")
                        if (isRecoverableListenerError(e)) scheduleGroupListenersReattach(groupId, "locations: ${e.message}")
                        return@addSnapshotListener
                    }
                    // Niente guardia su isEmpty: una collection vuota e' a tutti
                    // gli effetti un dato ("qui non c'e' nessuno"). Ignorandola,
                    // entrando in un gruppo appena creato le posizioni del gruppo
                    // precedente non venivano mai sovrascritte e restavano sulla
                    // mappa. Gli altri listener assegnano gia' incondizionatamente.
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                val lat = doc.getDouble("latitude") ?: 0.0
                                val lon = doc.getDouble("longitude") ?: 0.0
                                UserLocation(
                                    userId = doc.getString("userId") ?: doc.id,
                                    userName = doc.getString("userName") ?: "Membro",
                                    nickname = doc.getString("nickname"),
                                    photoBase64 = doc.getString("photoBase64"),
                                    latitude = lat,
                                    longitude = lon,
                                    accuracy = (doc.getDouble("accuracy") ?: 0.0).toFloat(),
                                    speed = (doc.getDouble("speed") ?: 0.0).toFloat(),
                                    bearing = (doc.getDouble("bearing") ?: 0.0).toFloat(),
                                    altitude = doc.getDouble("altitude") ?: 0.0,
                                    batteryLevel = (doc.getLong("batteryLevel") ?: 100L).toInt(),
                                    timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                                    isOnline = doc.getBoolean("isOnline") ?: true,
                                    currentPlaceName = doc.getString("currentPlaceName"),
                                    activityType = doc.getString("activityType") ?: ""
                                )
                            } catch (ex: Exception) {
                                null
                            }
                        }
                        _currentGroupLocations.value = list
                    }
                }

            // 2. Real-time geofence places listener
            placesListener = firestore.collection("groups").document(groupId)
                .collection("places")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        if (isRecoverableListenerError(e)) scheduleGroupListenersReattach(groupId, "places: ${e.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                val catStr = doc.getString("category") ?: "HOME"
                                val category = try { PlaceCategory.valueOf(catStr) } catch (ex: Exception) { PlaceCategory.HOME }
                                val lat = doc.getDouble("latitude") ?: 0.0
                                val lon = doc.getDouble("longitude") ?: 0.0
                                if (lat == 0.0 && lon == 0.0) return@mapNotNull null
                                SavedPlace(
                                    id = doc.getString("id") ?: doc.id,
                                    name = doc.getString("name") ?: "Luogo",
                                    category = category,
                                    latitude = lat,
                                    longitude = lon,
                                    radiusMeters = doc.getDouble("radiusMeters") ?: 100.0,
                                    createdBy = doc.getString("createdBy") ?: "",
                                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                                    // I documenti creati prima di questo campo non
                                    // lo hanno: per loro il geofence resta attivo.
                                    geofenceEnabled = doc.getBoolean("geofenceEnabled") ?: true
                                )
                            } catch (ex: Exception) {
                                null
                            }
                        }
                        // distinctBy: un documento legacy con un campo `id` che
                        // collide con un altro basta a far crashare la LazyColumn.
                        _currentGroupPlaces.value = list.distinctBy { it.id }
                    }
                }

            // 3. Chat messages listener — paginato a blocchi (vedi listenToMessages).
            chatLimit = CHAT_HISTORY_LIMIT
            _hasMoreChat.value = false
            listenToMessages(groupId, chatLimit)

            // 4. Real-time geofence & group events listener (TYPE 1: Places Entry/Exit & TYPE 3: SOS & Admin Join Requests)
            eventsListener = firestore.collection("groups").document(groupId)
                .collection("events")
                .whereGreaterThan("timestamp", joinTime)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Listen group events error: ${error.message}")
                        if (isRecoverableListenerError(error)) scheduleGroupListenersReattach(groupId, "events: ${error.message}")
                        return@addSnapshotListener
                    }
                    val currentUid = _currentUserState.value?.uid
                    snapshot?.documentChanges?.forEach { change ->
                        if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            val data = change.document.data
                            val senderId = data["userId"] as? String
                            val timestamp = (data["timestamp"] as? Long) ?: System.currentTimeMillis()
                            if (timestamp > lastObservedEventTimestamp) {
                                lastObservedEventTimestamp = timestamp
                            }

                            val type = data["type"] as? String ?: "geofence_entry"
                            val userName = data["userName"] as? String ?: "Un membro"
                            val placeName = data["placeName"] as? String ?: "un luogo"
                            val customMsg = data["message"] as? String
                            val eventLat = (data["latitude"] as? Double)
                            val eventLon = (data["longitude"] as? Double)

                            // Handle join request specifically for admin
                            if (type == "join_request") {
                                val targetAdminId = data["targetAdminId"] as? String
                                val activeGroup = _userGroupsState.value.find { it.id == groupId }
                                val isAdmin = activeGroup?.ownerId == currentUid || targetAdminId == currentUid
                                if (isAdmin && senderId != currentUid) {
                                    showLocalNotification(
                                        title = str(R.string.notif_join_request_title),
                                        body = customMsg ?: str(R.string.notif_join_request_body, userName),
                                        isHighPriority = false,
                                        notificationId = (timestamp % 100000).toInt(),
                                        destination = "MEMBERS",
                                        groupId = groupId,
                                        senderId = senderId
                                    )
                                }
                            } else if (!senderId.isNullOrBlank() && senderId != currentUid) {
                                // Only notify other members (exclude self)
                                when (type) {
                                    // TYPE 1: Geofence Entry
                                    "geofence_entry" -> {
                                        showLocalNotification(
                                            title = str(R.string.notif_place_entry_title),
                                            body = customMsg ?: str(R.string.msg_arrived_at, userName, placeName),
                                            isHighPriority = false,
                                            notificationId = (timestamp % 100000).toInt(),
                                            destination = "MAP",
                                            groupId = groupId,
                                            latitude = eventLat,
                                            longitude = eventLon,
                                            senderId = senderId
                                        )
                                    }
                                    // TYPE 1: Geofence Exit
                                    "geofence_exit" -> {
                                        showLocalNotification(
                                            title = str(R.string.notif_place_exit_title),
                                            body = customMsg ?: str(R.string.msg_left_place, userName, placeName),
                                            isHighPriority = false,
                                            notificationId = (timestamp % 100000).toInt(),
                                            destination = "MAP",
                                            groupId = groupId,
                                            latitude = eventLat,
                                            longitude = eventLon,
                                            senderId = senderId
                                        )
                                    }
                                    // TYPE 3: SOS Alert Event
                                    "sos_alert" -> {
                                        showLocalNotification(
                                            title = str(R.string.notif_sos_emergency_title),
                                            body = customMsg ?: str(R.string.notif_sos_emergency_body, userName),
                                            isHighPriority = true,
                                            notificationId = 999,
                                            destination = "ALERT",
                                            groupId = groupId,
                                            latitude = eventLat,
                                            longitude = eventLon,
                                            senderId = senderId
                                        )
                                    }
                                    "trip_start" -> {
                                        showLocalNotification(
                                            title = str(R.string.notif_trip_start_title),
                                            body = str(R.string.notif_trip_start_body, userName),
                                            isHighPriority = false,
                                            notificationId = (timestamp % 100000).toInt(),
                                            destination = "MAP",
                                            groupId = groupId,
                                            senderId = senderId
                                        )
                                    }
                                    "trip_end" -> {
                                        showLocalNotification(
                                            title = str(R.string.notif_trip_end_title),
                                            body = str(R.string.notif_trip_end_body, userName),
                                            isHighPriority = false,
                                            notificationId = (timestamp % 100000).toInt(),
                                            destination = "MAP",
                                            groupId = groupId,
                                            senderId = senderId
                                        )
                                    }
                                    "movement" -> {
                                        val kindText = movementKindText(data["activityKind"] as? String)
                                        val body = if (kindText != null) {
                                            str(R.string.notif_movement_body, userName, kindText)
                                        } else {
                                            str(R.string.notif_movement_body_generic, userName)
                                        }
                                        showLocalNotification(
                                            title = str(R.string.notif_movement_title),
                                            body = body,
                                            isHighPriority = false,
                                            notificationId = (timestamp % 100000).toInt(),
                                            destination = "MAP",
                                            groupId = groupId,
                                            senderId = senderId
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

            // 5. Real-time members listener
            membersListener = firestore.collection("groups").document(groupId)
                .collection("members")
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        if (isRecoverableListenerError(e)) scheduleGroupListenersReattach(groupId, "members: ${e.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                GroupMember(
                                    userId = doc.getString("userId") ?: doc.id,
                                    displayName = doc.getString("displayName") ?: "Membro",
                                    nickname = doc.getString("nickname"),
                                    email = doc.getString("email"),
                                    photoUrl = doc.getString("photoUrl"),
                                    photoBase64 = doc.getString("photoBase64"),
                                    role = doc.getString("role") ?: "member",
                                    status = doc.getString("status") ?: "ACTIVE",
                                    joinedAt = doc.getLong("joinedAt") ?: System.currentTimeMillis(),
                                    batteryLevel = (doc.getLong("batteryLevel") ?: 100L).toInt(),
                                    isTrackingActive = doc.getBoolean("isTrackingActive") ?: true,
                                    isOnline = doc.getBoolean("isOnline") ?: true
                                )
                            } catch (ex: Exception) {
                                null
                            }
                        }
                        _currentGroupMembers.value = list
                    }
                }

            // 6. Real-time geolocated snapshots listener
            snapshotsListener = firestore.collection("groups").document(groupId)
                .collection("snapshots")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        Log.w(TAG, "Listen snapshots failed: ${e.message}")
                        if (isRecoverableListenerError(e)) scheduleGroupListenersReattach(groupId, "snapshots: ${e.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                val lat = doc.getDouble("latitude") ?: 0.0
                                val lon = doc.getDouble("longitude") ?: 0.0
                                val photoBase64 = doc.getString("photoBase64") ?: ""
                                val photoUrl = doc.getString("photoUrl")?.takeIf { it.isNotBlank() }
                                // Accetta snapshot con URL o con Base64; scarta solo se entrambi mancano.
                                if (photoUrl == null && photoBase64.isBlank()) return@mapNotNull null
                                if (lat == 0.0 && lon == 0.0) return@mapNotNull null
                                PlaceSnapshot(
                                    id = doc.getString("id") ?: doc.id,
                                    groupId = doc.getString("groupId") ?: groupId,
                                    userId = doc.getString("userId") ?: "",
                                    userName = doc.getString("userName") ?: "Membro",
                                    userPhotoBase64 = doc.getString("userPhotoBase64"),
                                    photoBase64 = photoBase64,
                                    photoUrl = photoUrl,
                                    latitude = lat,
                                    longitude = lon,
                                    timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                                    caption = doc.getString("caption") ?: ""
                                )
                            } catch (ex: Exception) {
                                null
                            }
                        }
                        _currentGroupSnapshots.value = list
                    }
                }

            // 7. Real-time trips listener
            tripsListener = firestore.collection("groups").document(groupId)
                .collection("trips")
                .orderBy("startTime", Query.Direction.DESCENDING)
                .limit(50)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) {
                        if (isRecoverableListenerError(e)) scheduleGroupListenersReattach(groupId, "trips: ${e.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val myUid = _currentUserState.value?.uid
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                val isLive = doc.getBoolean("isLive") ?: false
                                val ownerId = doc.getString("userId") ?: ""

                                // Un viaggio privato lo vede solo chi l'ha fatto.
                                val isPrivate = doc.getBoolean("isPrivate") ?: false
                                if (isPrivate && ownerId != myUid) return@mapNotNull null

                                // I punti viaggiano nel documento SOLO finche' il
                                // viaggio e' in diretta, perche' gli altri devono
                                // vederlo avanzare. Una volta concluso stanno nel
                                // sottodocumento e si leggono all'apertura.
                                val points = if (isLive) {
                                    @Suppress("UNCHECKED_CAST")
                                    val rawPoints = doc.get("points") as? List<Map<String, Any>> ?: emptyList()
                                    rawPoints.mapNotNull { p ->
                                        val lat = (p["latitude"] as? Double) ?: return@mapNotNull null
                                        val lon = (p["longitude"] as? Double) ?: return@mapNotNull null
                                        TripPoint(lat, lon, (p["timestamp"] as? Long) ?: 0L)
                                    }
                                } else emptyList()

                                @Suppress("UNCHECKED_CAST")
                                val liveTrackRaw = doc.get("liveTrack") as? List<Map<String, Any>> ?: emptyList()
                                val liveTrack = liveTrackRaw.mapNotNull { map ->
                                    val lt = (map["latitude"] as? Number)?.toDouble() ?: return@mapNotNull null
                                    val ln = (map["longitude"] as? Number)?.toDouble() ?: return@mapNotNull null
                                    val ts = (map["timestamp"] as? Long) ?: 0L
                                    TripPoint(lt, ln, ts)
                                }

                                Trip(
                                    id = doc.id,
                                    groupId = groupId,
                                    userId = ownerId,
                                    userName = doc.getString("userName") ?: "Membro",
                                    startTime = doc.getLong("startTime") ?: 0L,
                                    endTime = doc.getLong("endTime") ?: 0L,
                                    durationMs = doc.getLong("durationMs") ?: 0L,
                                    distanceMeters = doc.getDouble("distanceMeters") ?: 0.0,
                                    pointCount = (doc.getLong("pointCount") ?: 0L).toInt(),
                                    source = TripSource.fromRaw(doc.getString("source")),
                                    maxSpeedMs = (doc.getDouble("maxSpeedMs") ?: 0.0).toFloat(),
                                    movingMs = doc.getLong("movingMs") ?: 0L,
                                    startPlaceName = doc.getString("startPlaceName")?.ifBlank { null },
                                    endPlaceName = doc.getString("endPlaceName")?.ifBlank { null },
                                    isLive = isLive,
                                    isPrivate = isPrivate,
                                    activityKind = doc.getString("activityKind") ?: "",
                                    points = points,
                                    liveTrack = liveTrack
                                )
                            } catch (ex: Exception) { null }
                        }
                        _groupTrips.value = list
                    }
                }

        } catch (e: Exception) {
            Log.w(TAG, "Error attaching Firestore listeners: ${e.message}")
        }
    }

    // ================== SILENT IN-APP LOCATION TRACKING ==================

    fun startSilentLocationTracking() {
        try {
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) return

            if (fusedLocationClient == null) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            }

            stopSilentLocationTracking()

            val intervalMs = effectiveTrackingIntervalMs().coerceAtLeast(200L)
            val request = LocationRequest.Builder(locationPriority(), intervalMs).apply {
                setMinUpdateIntervalMillis(intervalMs / 2)
                setWaitForAccurateLocation(false)
                setMaxUpdateDelayMillis(intervalMs)
                setMinUpdateDistanceMeters(0f)
            }.build()

            silentLocationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc: Location? = result.lastLocation
                    if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                        val (battery, isCharging) = getBatteryStatus()
                        val uLoc = UserLocation(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracy = loc.accuracy,
                            speed = if (loc.hasSpeed()) loc.speed else 0.0f,
                            bearing = if (loc.hasBearing()) loc.bearing else 0.0f,
                            altitude = if (loc.hasAltitude()) loc.altitude else 0.0,
                            batteryLevel = battery,
                            isCharging = isCharging,
                            timestamp = System.currentTimeMillis(),
                            isOnline = true
                        )
                        CoroutineScope(Dispatchers.IO).launch {
                            updateLocation(uLoc)
                        }
                    }
                }
            }

            fusedLocationClient?.requestLocationUpdates(
                request,
                silentLocationCallback!!,
                Looper.getMainLooper()
            )

            // Il primo fix reale puo' tardare parecchio: a GPS freddo sono
            // decine di secondi, e con un intervallo lungo si aspetta comunque
            // il primo tick. Play Services ha pero' una posizione in cache:
            // pubblicandola subito si compare sulla mappa all'apertura invece
            // di restare invisibili finche' non arriva il primo fix.
            pushLastKnownLocationNow()

            Log.d(TAG, "Silent in-app location tracking active (no notification)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start silent location updates: ${e.message}")
        }
    }

    /**
     * Azzera il gate anti-drift: il fix successivo verra' trattato come "primo
     * fix" e scritto senza filtri.
     *
     * Serve ogni volta che la posizione viene RIMOSSA da Firestore pur restando
     * il dispositivo fermo (uscita dal ghost mode, riattivazione del tracking di
     * gruppo). Senza questo azzeramento il gate confronta il nuovo fix con
     * l'ultimo inviato *prima* dello spegnimento: da fermi lo spostamento e'
     * sotto i 18 m e il fix viene scartato, quindi il documento cancellato non
     * viene mai riscritto e si resta invisibili sulla mappa fino all'heartbeat
     * dei 5 minuti.
     */
    private fun resetLocationGate() {
        lastSentLatitude = null
        lastSentLongitude = null
        lastSentAtMillis = 0L
        lastSentBatteryLevel = null
        lastSentGroupId = null
    }

    private var inAppPresenceJob: kotlinx.coroutines.Job? = null
    private var isAppInForeground: Boolean = false

    fun setAppForeground(isInForeground: Boolean) {
        isAppInForeground = isInForeground
        if (isInForeground) {
            startInAppPresenceLoop()
            pushLastKnownLocationNow()
        } else {
            stopInAppPresenceLoop()
        }
    }

    private fun startInAppPresenceLoop() {
        inAppPresenceJob?.cancel()
        inAppPresenceJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isAppInForeground) {
                try {
                    val user = _currentUserState.value
                    val currentGroup = user?.currentGroupId ?: _userGroupsState.value.firstOrNull()?.id
                    if (user != null && !currentGroup.isNullOrBlank()) {
                        val silentFor = System.currentTimeMillis() - lastSentAtMillis
                        if (silentFor >= 60_000L) {
                            pushLastKnownLocationNow()
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "In-app presence loop tick failed: ${t.message}")
                }
                delay(30_000L)
            }
        }
    }

    private fun stopInAppPresenceLoop() {
        inAppPresenceJob?.cancel()
        inAppPresenceJob = null
    }

    /**
     * Ripubblica subito l'ultima posizione nota, senza aspettare il prossimo
     * tick del tracking (che con intervalli lunghi puo' essere parecchi secondi).
     * Va usata dopo [resetLocationGate], altrimenti il gate scarta comunque il fix.
     */
    fun pushLastKnownLocationNow() {
        try {
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val (battery, isCharging) = getBatteryStatus()
            val user = _currentUserState.value ?: return

            if (fusedLocationClient == null && (hasFine || hasCoarse)) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            }

            if (hasFine || hasCoarse) {
                fusedLocationClient?.lastLocation?.addOnSuccessListener { loc ->
                    if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                        val uLoc = UserLocation(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracy = loc.accuracy,
                            speed = if (loc.hasSpeed()) loc.speed else 0.0f,
                            bearing = if (loc.hasBearing()) loc.bearing else 0.0f,
                            altitude = if (loc.hasAltitude()) loc.altitude else 0.0,
                            batteryLevel = battery,
                            isCharging = isCharging,
                            timestamp = System.currentTimeMillis(),
                            isOnline = true
                        )
                        CoroutineScope(Dispatchers.IO).launch {
                            updateLocation(uLoc)
                        }
                    } else {
                        val prevLoc = _currentGroupLocations.value.find { it.userId == user.uid }
                        if (prevLoc != null && prevLoc.latitude != 0.0 && prevLoc.longitude != 0.0) {
                            val uLoc = prevLoc.copy(
                                batteryLevel = battery,
                                isCharging = isCharging,
                                timestamp = System.currentTimeMillis(),
                                isOnline = true
                            )
                            CoroutineScope(Dispatchers.IO).launch {
                                updateLocation(uLoc)
                            }
                        }
                    }
                }?.addOnFailureListener {
                    val prevLoc = _currentGroupLocations.value.find { it.userId == user.uid }
                    if (prevLoc != null && prevLoc.latitude != 0.0 && prevLoc.longitude != 0.0) {
                        val uLoc = prevLoc.copy(
                            batteryLevel = battery,
                            isCharging = isCharging,
                            timestamp = System.currentTimeMillis(),
                            isOnline = true
                        )
                        CoroutineScope(Dispatchers.IO).launch {
                            updateLocation(uLoc)
                        }
                    }
                }
            } else {
                val prevLoc = _currentGroupLocations.value.find { it.userId == user.uid }
                if (prevLoc != null && prevLoc.latitude != 0.0 && prevLoc.longitude != 0.0) {
                    val uLoc = prevLoc.copy(
                        batteryLevel = battery,
                        isCharging = isCharging,
                        timestamp = System.currentTimeMillis(),
                        isOnline = true
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        updateLocation(uLoc)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushLastKnownLocationNow failed: ${e.message}")
        }
    }

    fun stopSilentLocationTracking() {
        try {
            silentLocationCallback?.let {
                fusedLocationClient?.removeLocationUpdates(it)
            }
            silentLocationCallback = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping silent location tracking: ${e.message}")
        }
    }

    private fun getBatteryStatus(): Pair<Int, Boolean> {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, filter)
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 100
            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            Pair(batteryPct, isCharging)
        } catch (e: Exception) {
            Pair(100, false)
        }
    }

    // ================== LOCATION UPDATES ==================

    // ---------------------------------------------------------------------
    // FILTRO JITTER / ANTI-DRIFT
    //
    // Il GPS continua a produrre fix leggermente diversi anche a telefono fermo
    // sul tavolo. Scriverli tutti su Firestore significa marker che vibrano sulla
    // mappa, batteria sprecata e quota di scritture bruciata. Qui decidiamo cosa
    // vale la pena trasmettere.
    // ---------------------------------------------------------------------

    private var lastSentLatitude: Double? = null
    private var lastSentLongitude: Double? = null
    private var lastSentAtMillis: Long = 0L
    private var lastSentBatteryLevel: Int? = null
    private var lastWrittenLocUserName: String? = null
    private var lastWrittenLocNickname: String? = null
    private var lastWrittenLocPhoto: String? = null

    /**
     * Gruppo in cui e' finita l'ultima scrittura. Senza questo il gate ragiona
     * solo su "quanto mi sono spostato da quando ho trasmesso", ignorando che la
     * destinazione e' cambiata: entrando in un altro gruppo, da fermi, ogni fix
     * verrebbe scartato e nel nuovo gruppo non esisterebbe alcun documento di
     * posizione. E' il motivo per cui al primo accesso serviva forzare a mano.
     */
    private var lastSentGroupId: String? = null

    /** Esito della valutazione, tenuto esplicito per poterlo loggare in chiaro. */
    private data class LocationGate(
        val shouldSend: Boolean,
        val reason: String,
        val isHeartbeat: Boolean = false
    )

    private fun evaluateLocationGate(location: UserLocation, targetGroupId: String): LocationGate {
        val prevLat = lastSentLatitude
        val prevLon = lastSentLongitude
        if (prevLat == null || prevLon == null) {
            return LocationGate(true, "primo fix", isHeartbeat = true)
        }

        // Destinazione cambiata: nel nuovo gruppo il documento non esiste ancora,
        // quindi va scritto a prescindere da quanto ci si e' spostati.
        if (lastSentGroupId != targetGroupId) {
            return LocationGate(true, "primo fix nel gruppo", isHeartbeat = true)
        }

        val elapsed = System.currentTimeMillis() - lastSentAtMillis
        val heartbeatThreshold = if (isAppInForeground) 60_000L else HEARTBEAT_INTERVAL_MS
        if (elapsed >= heartbeatThreshold) {
            // Heartbeat: anche da fermi bisogna rinfrescare stato online, orario
            // e livello batteria, altrimenti agli altri risultiamo scomparsi.
            return LocationGate(true, if (isAppInForeground) "in-app heartbeat" else "heartbeat", isHeartbeat = true)
        }

        // Durante un viaggio attivo, la velocità supera quasi sempre la soglia
        // e causerebbe una scrittura su locations/{uid} ogni 500 ms. Limitiamo
        // a una scrittura ogni TRIP_LOCATION_WRITE_INTERVAL_MS (30 s): gli altri
        // membri vedono comunque la posizione aggiornata di frequente tramite
        // la traccia live (flushLiveTrip), non dal documento locations.
        if (_activeTrip.value != null && elapsed < TRIP_LOCATION_WRITE_INTERVAL_MS) {
            return LocationGate(false, "trip throttle: ultimo invio ${elapsed / 1000}s fa")
        }

        if (location.speed > MOVING_SPEED_THRESHOLD_MS) {
            return LocationGate(true, "in movimento (${"%.1f".format(location.speed)} m/s)")
        }

        val distance = GeofenceHelper.calculateDistanceMeters(
            prevLat, prevLon, location.latitude, location.longitude
        )

        if (distance < MIN_DISPLACEMENT_METERS) {
            return LocationGate(false, "spostamento ${distance.toInt()}m sotto soglia")
        }

        // Se il raggio di incertezza del fix è più ampio dello spostamento stesso,
        // quello "spostamento" può benissimo essere solo rumore del sensore.
        if (location.accuracy > 0f && distance <= location.accuracy) {
            return LocationGate(false, "spostamento ${distance.toInt()}m entro l'errore ${location.accuracy.toInt()}m")
        }

        return LocationGate(true, "spostamento ${distance.toInt()}m")
    }

    suspend fun updateLocation(location: UserLocation) {
        val user = _currentUserState.value ?: return
        val currentGroup = user.currentGroupId ?: _userGroupsState.value.firstOrNull()?.id ?: return

        // If Global Ghost Mode is enabled, do not upload location
        if (_isGlobalGhostMode.value) {
            return
        }

        // If user disabled tracking specifically for this group, do not upload location
        val myMember = _currentGroupMembers.value.find { it.userId == user.uid }
        if (myMember != null && !myMember.isTrackingActive) {
            return
        }

        // Il viaggio registra su OGNI fix, prima del gate. Il gate decide cosa
        // vale la pena scrivere su Firestore, non cosa vale la pena tracciare: i
        // punti del viaggio stanno in memoria e non costano scritture. Stando
        // dopo il gate, la traccia ereditava le sue soglie e finiva ridotta a
        // pochissimi punti -- su una strada dritta, a due.
        // Il filtro dei 15 m dentro recordTripPoint basta a togliere il rumore.
        if (_activeTrip.value != null) {
            recordTripPoint(location)
        } else {
            // Fuori dal viaggio i fix finiscono nel buffer: se fra poco parte un
            // rilevamento automatico, sono loro il tratto iniziale da recuperare.
            rememberFix(location)
        }

        // Va valutato DOPO la registrazione del punto: se decide di avviare un
        // viaggio qui, il fix corrente e' gia' stato usato per quello in corso e
        // non si perde nulla; se decide di chiuderlo, la traccia e' completa.
        evaluateAutoTrip(location)

        // Notifica "in movimento" agli altri membri: indipendente dall'auto-trip,
        // gira su ogni fix e si basa su Activity Recognition + spostamento netto.
        evaluateMovementNotification(location)

        // La valutazione geofence gira su OGNI fix, anche su quelli che non
        // trasmettiamo: un ingresso o un'uscita da un luogo non va perso solo
        // perché lo spostamento era piccolo.
        val gate = evaluateLocationGate(location, currentGroup)

        // Due ricerche distinte, di proposito:
        //  - per gli AVVISI contano solo i luoghi con geofence attivo
        //  - per l'etichetta "dove sei" contano tutti, perché un luogo con gli
        //    avvisi spenti resta comunque un posto che ha un nome
        val allPlaces = _currentGroupPlaces.value
        val placeForLabel = GeofenceHelper.findCurrentPlace(location, allPlaces)

        // Gli AVVISI di ingresso/uscita si valutano solo su fix affidabili e con
        // isteresi: un fix impreciso o un salto del GPS mentre si sta fermi dentro
        // un luogo non deve produrre una raffica di "entra"/"esci". La sola etichetta
        // "dove sei" (placeForLabel) resta senza filtro perché non genera notifiche.
        if (location.accuracy <= GEOFENCE_MAX_ACCURACY_METERS) {
            val placeForAlert = resolveGeofencePlaceWithHysteresis(
                location, allPlaces.filter { it.geofenceEnabled }
            )
            checkGeofenceAlert(user.displayName, placeForAlert)
        }

        if (!gate.shouldSend) {
            Log.v(TAG, "Fix ignorato: ${gate.reason}")
            return
        }
        Log.d(TAG, "Fix trasmesso: ${gate.reason}")

        lastSentLatitude = location.latitude
        lastSentLongitude = location.longitude
        lastSentAtMillis = System.currentTimeMillis()
        lastSentGroupId = currentGroup

        // Compute current place
        val matchedPlace = placeForLabel
        val enrichedLocation = location.copy(
            userId = user.uid,
            userName = user.displayName,
            photoBase64 = user.photoBase64 ?: location.photoBase64,
            currentPlaceName = matchedPlace?.name
        )

        // Update local list
        val currentList = _currentGroupLocations.value.toMutableList()
        val index = currentList.indexOfFirst { it.userId == user.uid }
        if (index >= 0) {
            currentList[index] = enrichedLocation
        } else {
            currentList.add(enrichedLocation)
        }
        _currentGroupLocations.value = currentList

        // Update Firestore
        try {
            if (firestore != null) {
                val profileChanged = enrichedLocation.userName != lastWrittenLocUserName ||
                    (enrichedLocation.nickname ?: "") != (lastWrittenLocNickname ?: "") ||
                    (enrichedLocation.photoBase64 ?: "") != (lastWrittenLocPhoto ?: "")

                val locMap = hashMapOf<String, Any?>(
                    "userId" to enrichedLocation.userId,
                    "latitude" to enrichedLocation.latitude,
                    "longitude" to enrichedLocation.longitude,
                    "accuracy" to enrichedLocation.accuracy,
                    "speed" to enrichedLocation.speed,
                    "bearing" to enrichedLocation.bearing,
                    "altitude" to enrichedLocation.altitude,
                    "batteryLevel" to enrichedLocation.batteryLevel,
                    "timestamp" to enrichedLocation.timestamp,
                    "isOnline" to true,
                    "currentPlaceName" to (enrichedLocation.currentPlaceName ?: ""),
                    "activityType" to _currentActivityKind
                )

                if (profileChanged || gate.isHeartbeat) {
                    locMap["userName"] = enrichedLocation.userName
                    locMap["nickname"] = enrichedLocation.nickname ?: ""
                    locMap["photoBase64"] = enrichedLocation.photoBase64 ?: ""
                    lastWrittenLocUserName = enrichedLocation.userName
                    lastWrittenLocNickname = enrichedLocation.nickname
                    lastWrittenLocPhoto = enrichedLocation.photoBase64
                }

                firestore.collection("groups").document(currentGroup)
                    .collection("locations").document(user.uid)
                    .set(locMap, com.google.firebase.firestore.SetOptions.merge()).await()

                // La batteria vive anche in members/{uid} perche' la lista membri la
                // mostra senza leggere le posizioni. Aggiornarla a ogni fix pero'
                // raddoppiava le scritture per nulla: cambia di un punto ogni diversi
                // minuti. Si scrive solo a variazione significativa o sull'heartbeat.
                val previousBattery = lastSentBatteryLevel
                val batteryChanged = previousBattery == null ||
                    kotlin.math.abs(previousBattery - enrichedLocation.batteryLevel) >= BATTERY_WRITE_DELTA
                if (batteryChanged || gate.isHeartbeat) {
                    lastSentBatteryLevel = enrichedLocation.batteryLevel
                    firestore.collection("groups").document(currentGroup)
                        .collection("members").document(user.uid)
                        .update("batteryLevel", enrichedLocation.batteryLevel)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateLocation firestore error: ${e.message}")
        }
    }

    private var lastNotifiedPlaceId: String? = null
    private var lastNotifiedPlaceName: String? = null
    private var geofenceExitMissCount = 0

    /**
     * Trova il luogo "corrente" ai fini degli avvisi applicando l'isteresi:
     *  - per ENTRARE serve stare entro il raggio pieno;
     *  - per USCIRE dal luogo in cui si e' gia' dentro serve superare
     *    raggio + [GEOFENCE_EXIT_MARGIN_METERS].
     * Cosi' un jitter di pochi metri sul bordo non alterna ingresso/uscita.
     */
    private fun resolveGeofencePlaceWithHysteresis(
        location: UserLocation,
        places: List<SavedPlace>
    ): SavedPlace? {
        // Se siamo gia' dentro un luogo, ci restiamo finche' non usciamo davvero.
        val current = places.find { it.id == lastNotifiedPlaceId }
        if (current != null) {
            val d = GeofenceHelper.calculateDistanceMeters(
                location.latitude, location.longitude, current.latitude, current.longitude
            )
            if (d <= current.radiusMeters + GEOFENCE_EXIT_MARGIN_METERS) return current
        }
        // Altrimenti si entra in un nuovo luogo solo col raggio pieno.
        return places.firstOrNull { GeofenceHelper.isInsidePlace(location, it) }
    }

    private fun checkGeofenceAlert(userName: String, place: SavedPlace?) {
        val user = _currentUserState.value ?: return
        val groupId = user.currentGroupId ?: _userGroupsState.value.firstOrNull()?.id ?: return

        if (place != null) {
            geofenceExitMissCount = 0
            if (lastNotifiedPlaceId != place.id) {
                lastNotifiedPlaceId = place.id
                lastNotifiedPlaceName = place.name
                val eventId = "evt_${UUID.randomUUID().toString().take(8)}"
                val event = GeofenceEvent(
                    id = eventId,
                    placeName = place.name,
                    userName = userName,
                    isInside = true,
                    timestamp = System.currentTimeMillis()
                )
                _activeGeofenceAlerts.value = listOf(event) + _activeGeofenceAlerts.value.take(9)

                // 1. Record event in Firestore groups/{groupId}/events collection for Cloud Functions / push triggers
                try {
                    if (firestore != null) {
                        val eventMap = hashMapOf(
                            "id" to eventId,
                            "groupId" to groupId,
                            "type" to "geofence_entry",
                            "userId" to user.uid,
                            "userName" to userName,
                            "placeId" to place.id,
                            "placeName" to place.name,
                            "message" to "$userName è arrivato a ${place.name}",
                            "timestamp" to System.currentTimeMillis()
                        )
                        firestore.collection("groups").document(groupId)
                            .collection("events").document(eventId).set(eventMap)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write geofence_entry event to Firestore: ${e.message}")
                }

                // 2. Also send system message to chat in Firestore
                val sysMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    senderId = user.uid,
                    senderName = "Radar Alert",
                    text = str(R.string.msg_arrived_at, userName, place.name),
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.GEOFENCE_ALERT
                )
                sendMessage(groupId, sysMsg)
            }
        } else {
            if (lastNotifiedPlaceId != null) {
                geofenceExitMissCount++
                if (geofenceExitMissCount < 3) return
                geofenceExitMissCount = 0
                val previousPlaceName = lastNotifiedPlaceName ?: "un luogo sicuro"
                val previousPlaceId = lastNotifiedPlaceId ?: ""
                val exitEventId = "evt_${UUID.randomUUID().toString().take(8)}"
                val event = GeofenceEvent(
                    id = exitEventId,
                    placeName = previousPlaceName,
                    userName = userName,
                    isInside = false,
                    timestamp = System.currentTimeMillis()
                )
                _activeGeofenceAlerts.value = listOf(event) + _activeGeofenceAlerts.value.take(9)

                // Record exit event in Firestore groups/{groupId}/events
                try {
                    if (firestore != null) {
                        val eventMap = hashMapOf(
                            "id" to exitEventId,
                            "groupId" to groupId,
                            "type" to "geofence_exit",
                            "userId" to user.uid,
                            "userName" to userName,
                            "placeId" to previousPlaceId,
                            "placeName" to previousPlaceName,
                            "message" to "$userName ha lasciato $previousPlaceName",
                            "timestamp" to System.currentTimeMillis()
                        )
                        firestore.collection("groups").document(groupId)
                            .collection("events").document(exitEventId).set(eventMap)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write geofence_exit event to Firestore: ${e.message}")
                }

                // System message in chat
                val sysMsg = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    senderId = user.uid,
                    senderName = "Radar Alert",
                    text = str(R.string.msg_left_place, userName, previousPlaceName),
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.GEOFENCE_ALERT
                )
                sendMessage(groupId, sysMsg)

                lastNotifiedPlaceId = null
                lastNotifiedPlaceName = null
            }
        }
    }

    // ================== PLACES / GEOFENCE ==================

    suspend fun addPlace(place: SavedPlace): Result<SavedPlace> {
        val user = _currentUserState.value ?: return Result.failure(Exception(str(R.string.err_not_authenticated)))
        val currentGroup = user.currentGroupId ?: _userGroupsState.value.firstOrNull()?.id
            ?: return Result.failure(Exception(str(R.string.err_no_group_selected)))

        val newPlace = place.copy(
            id = if (place.id.isBlank()) "plc_${UUID.randomUUID().toString().take(8)}" else place.id,
            createdBy = user.uid
        )

        try {
            if (firestore != null) {
                val map = hashMapOf(
                    "id" to newPlace.id,
                    "name" to newPlace.name,
                    "category" to newPlace.category.name,
                    "latitude" to newPlace.latitude,
                    "longitude" to newPlace.longitude,
                    "radiusMeters" to newPlace.radiusMeters,
                    "createdBy" to newPlace.createdBy,
                    "createdAt" to newPlace.createdAt,
                    "geofenceEnabled" to newPlace.geofenceEnabled
                )
                firestore.collection("groups").document(currentGroup)
                    .collection("places").document(newPlace.id).set(map).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "addPlace firestore failed: ${e.message}")
        }

        // Aggiornamento ottimistico IDEMPOTENTE: il listener su `places` puo'
        // aver gia' consegnato lo stesso documento appena scritto. Un append
        // cieco lo duplicherebbe nella lista, e due elementi con la stessa key
        // fanno crashare la LazyColumn del pannello Luoghi
        // (IllegalArgumentException: Key "plc_..." was already used).
        _currentGroupPlaces.value =
            _currentGroupPlaces.value.filterNot { it.id == newPlace.id } + newPlace
        return Result.success(newPlace)
    }

    /**
     * Aggiorna un luogo esistente: nome, categoria, coordinate, raggio e
     * attivazione del geofence.
     *
     * Non usa `set()` ma `update()` sui soli campi modificabili, così `createdBy`
     * e `createdAt` restano quelli originali anche se chi modifica non è chi ha
     * creato il luogo.
     */
    suspend fun updatePlace(place: SavedPlace): Result<SavedPlace> {
        val user = _currentUserState.value ?: return Result.failure(Exception(str(R.string.err_not_authenticated)))
        val currentGroup = user.currentGroupId ?: _userGroupsState.value.firstOrNull()?.id
            ?: return Result.failure(Exception(str(R.string.err_no_group_selected)))
        if (place.id.isBlank()) return Result.failure(Exception(str(R.string.err_place_without_id)))

        // Se si spegne il geofence del luogo in cui ci si trova adesso, la
        // valutazione successiva non troverebbe più un luogo attivo e sparerebbe
        // un evento di uscita che l'utente non ha compiuto. Si dimentica il luogo
        // in silenzio.
        if (!place.geofenceEnabled && lastNotifiedPlaceId == place.id) {
            lastNotifiedPlaceId = null
            lastNotifiedPlaceName = null
        }

        try {
            if (firestore != null) {
                firestore.collection("groups").document(currentGroup)
                    .collection("places").document(place.id)
                    .update(
                        mapOf(
                            "name" to place.name,
                            "category" to place.category.name,
                            "latitude" to place.latitude,
                            "longitude" to place.longitude,
                            "radiusMeters" to place.radiusMeters,
                            "geofenceEnabled" to place.geofenceEnabled
                        )
                    ).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "updatePlace firestore failed: ${e.message}")
            return Result.failure(e)
        }

        // Aggiornamento ottimistico: il listener consegnerà comunque la versione
        // dal server, ma così la UI non aspetta il giro di rete.
        _currentGroupPlaces.value = _currentGroupPlaces.value.map {
            if (it.id == place.id) place else it
        }
        return Result.success(place)
    }

    suspend fun deletePlace(placeId: String): Result<Unit> {
        val currentGroup = _currentUserState.value?.currentGroupId ?: _userGroupsState.value.firstOrNull()?.id ?: ""
        try {
            if (firestore != null && currentGroup.isNotBlank()) {
                firestore.collection("groups").document(currentGroup)
                    .collection("places").document(placeId).delete().await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "deletePlace firestore failed: ${e.message}")
        }
        _currentGroupPlaces.value = _currentGroupPlaces.value.filter { it.id != placeId }
        return Result.success(Unit)
    }

    // ================== GROUP MEMBER CUSTOM PROFILE ==================

    /**
     * Profilo unico centralizzato + soprannome per-gruppo.
     *
     * Nome e foto sono globali: si scrivono su `users/{uid}` e si propagano al
     * documento membro e alla posizione di OGNI gruppo di cui l'utente fa parte,
     * cosi' appari uguale ovunque e subito. Il [nickname] invece e' l'unico dato
     * per-gruppo: si scrive solo su `members/{uid}` (e sulla location) del
     * [groupId] corrente.
     *
     * Il parametro [memberId] deve essere l'utente stesso: non si modifica il
     * profilo altrui. Se non lo e', si aggiorna solo il gruppo corrente (difesa).
     */
    suspend fun updateGroupMemberProfile(
        groupId: String,
        memberId: String,
        displayName: String,
        nickname: String?,
        photoBase64: String?
    ): Result<Unit> {
        val cleanName = displayName.trim().ifBlank { "Membro" }
        val cleanNick = nickname?.trim()?.ifBlank { null }
        val cleanPhoto = photoBase64?.trim()?.ifBlank { null }

        val currentUser = _currentUserState.value
        val isSelf = currentUser != null && currentUser.uid == memberId

        try {
            if (firestore != null && groupId.isNotBlank() && memberId.isNotBlank()) {
                if (isSelf) {
                    val nameUnchanged = cleanName == currentUser!!.displayName
                    val photoUnchanged = cleanPhoto == currentUser.photoBase64

                    if (nameUnchanged && photoUnchanged) {
                        // Solo il nickname è cambiato: aggiorna solo il gruppo corrente
                        val memberMap = hashMapOf<String, Any?>("nickname" to cleanNick)
                        val locMap = hashMapOf<String, Any?>("nickname" to cleanNick)
                        firestore.collection("groups").document(groupId)
                            .collection("members").document(memberId)
                            .set(memberMap, com.google.firebase.firestore.SetOptions.merge())
                        firestore.collection("groups").document(groupId)
                            .collection("locations").document(memberId)
                            .set(locMap, com.google.firebase.firestore.SetOptions.merge())
                    } else {
                        // Nome o foto cambiati: propaga a tutti i gruppi
                        _currentUserState.value = currentUser.copy(displayName = cleanName, photoBase64 = cleanPhoto)
                        firestore.collection("users").document(memberId)
                            .set(hashMapOf("displayName" to cleanName, "photoBase64" to cleanPhoto), com.google.firebase.firestore.SetOptions.merge())

                        val allGroupIds = (_userGroupsState.value.map { it.id } + groupId).distinct()
                        for (gid in allGroupIds) {
                            val memberMap = hashMapOf<String, Any?>(
                                "displayName" to cleanName,
                                "photoBase64" to cleanPhoto
                            )
                            val locMap2 = hashMapOf<String, Any?>(
                                "userName" to cleanName,
                                "photoBase64" to cleanPhoto
                            )
                            if (gid == groupId) {
                                memberMap["nickname"] = cleanNick
                                locMap2["nickname"] = cleanNick
                            }
                            firestore.collection("groups").document(gid)
                                .collection("members").document(memberId)
                                .set(memberMap, com.google.firebase.firestore.SetOptions.merge())
                            firestore.collection("groups").document(gid)
                                .collection("locations").document(memberId)
                                .set(locMap2, com.google.firebase.firestore.SetOptions.merge())
                        }
                    }
                } else {
                    // Non e' il proprio profilo: si tocca solo il gruppo indicato.
                    firestore.collection("groups").document(groupId)
                        .collection("members").document(memberId).set(
                            hashMapOf<String, Any?>(
                                "displayName" to cleanName,
                                "nickname" to cleanNick,
                                "photoBase64" to cleanPhoto
                            ),
                            com.google.firebase.firestore.SetOptions.merge()
                        ).await()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateGroupMemberProfile error: ${e.message}")
            return Result.failure(e)
        }

        // Update local members state (gruppo corrente)
        val updatedMembers = _currentGroupMembers.value.map { m ->
            if (m.userId == memberId) {
                m.copy(displayName = cleanName, nickname = cleanNick, photoBase64 = cleanPhoto)
            } else m
        }
        _currentGroupMembers.value = updatedMembers

        return Result.success(Unit)
    }

    // ================== CLOUDINARY UPLOAD ==================

    private val httpClient by lazy { OkHttpClient() }
    private val renderBaseUrl = "https://cross-notify-hub.onrender.com"
    private val renderApiKey = "cross-notify-secret-key-2026"

    /**
     * Carica un file su Cloudinary via il backend Render e ritorna l'URL pubblico,
     * o null in caso di errore. Da chiamare sempre su Dispatchers.IO.
     */
    private suspend fun uploadFileToCloudinary(
        base64: String,
        mimeType: String,
        filename: String,
        appName: String = "family-radar"
    ): String? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("appName", appName)
                put("fileBase64", base64)
                put("mimeType", mimeType)
                put("filename", filename)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$renderBaseUrl/upload-file")
                .addHeader("x-api-key", renderApiKey)
                .post(body)
                .build()
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            if (response.isSuccessful && responseBody != null) {
                JSONObject(responseBody).optString("url").takeIf { it.isNotBlank() }
            } else {
                Log.w(TAG, "uploadFileToCloudinary HTTP ${response.code}: $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "uploadFileToCloudinary fallita: ${e.message}")
            null
        }
    }

    /**
     * Scarica un file da URL e lo scrive in cache locale. Ritorna il path locale o null.
     */
    private suspend fun downloadToCache(messageId: String, url: String, extension: String = "m4a"): String? =
        withContext(Dispatchers.IO) {
            try {
                val file = File(context.cacheDir, "voice_$messageId.$extension")
                if (file.exists() && file.length() > 0) return@withContext file.absolutePath
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    response.body?.bytes()?.let { bytes ->
                        file.writeBytes(bytes)
                        file.absolutePath
                    }
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "downloadToCache fallita per $messageId: ${e.message}")
                null
            }
        }

    /** Carica un'immagine JPEG (Base64) su Cloudinary e ritorna l'URL pubblico o null. */
    suspend fun uploadImageToCloudinary(base64: String, filename: String = "img_${System.currentTimeMillis()}.jpg"): String? =
        uploadFileToCloudinary(base64, "image/jpeg", filename)

    // ================== MESSAGES & CHAT (BASE64 ON FIRESTORE) ==================

    fun sendMessage(groupId: String, message: ChatMessage) {
        val user = _currentUserState.value
        val msg = if (message.id.isBlank()) {
            message.copy(
                id = "msg_${UUID.randomUUID().toString().take(8)}",
                senderId = user?.uid ?: "anon",
                senderName = user?.displayName ?: "Utente",
                timestamp = System.currentTimeMillis()
            )
        } else message

        // Idempotente come addPlace: il listener sui messaggi puo' riconsegnare
        // lo stesso documento, e una key duplicata fa crashare la LazyColumn.
        _currentGroupMessages.value =
            _currentGroupMessages.value.filterNot { it.id == msg.id } + msg

        try {
            if (firestore != null) {
                val map = hashMapOf(
                    "id" to msg.id,
                    "senderId" to msg.senderId,
                    "senderName" to msg.senderName,
                    "senderPhoto" to (msg.senderPhoto ?: ""),
                    "text" to msg.text,
                    "imageBase64" to (msg.imageBase64 ?: ""),
                    "imageUrl" to (msg.imageUrl ?: ""),
                    "timestamp" to msg.timestamp,
                    "type" to msg.type.name,
                    "latitude" to (msg.latitude ?: 0.0),
                    "longitude" to (msg.longitude ?: 0.0),
                    "snapshotId" to msg.snapshotId,
                    "audioBase64" to (msg.audioBase64 ?: ""),
                    "audioUrl" to (msg.audioUrl ?: ""),
                    "audioDurationMs" to msg.audioDurationMs,
                    "placeName" to (msg.placeName ?: "")
                )
                firestore.collection("groups").document(groupId)
                    .collection("messages").document(msg.id).set(map)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage firestore failed: ${e.message}")
        }
    }

    /**
     * Invia una nota vocale come messaggio di chat (type VOICE). L'audio e' Base64,
     * lat/lon sono il punto in cui e' stata registrata; placeName l'eventuale luogo.
     */
    suspend fun sendVoiceMessage(
        groupId: String,
        audioBase64: String,
        durationMs: Long,
        latitude: Double?,
        longitude: Double?,
        placeName: String?
    ) {
        val id = "msg_${UUID.randomUUID().toString().take(8)}"
        // Carica l'audio su Cloudinary; in caso di errore fallback sulla vecchia
        // voiceNotes collection cosi' il messaggio viene comunque inviato.
        val audioUrl = uploadFileToCloudinary(audioBase64, "audio/mp4", "$id.m4a")
        if (audioUrl != null) {
            // Cache locale per il mittente: evita di riscaricarlo al primo play.
            runCatching { VoiceUtils.writeCacheFromBase64(context, id, audioBase64) }
        } else {
            // Fallback: salva in Firestore come prima (retrocompatibilita').
            try {
                firestore?.collection("groups")?.document(groupId)
                    ?.collection("voiceNotes")?.document(id)
                    ?.set(hashMapOf("audioBase64" to audioBase64))
            } catch (e: Exception) {
                Log.w(TAG, "scrittura voiceNote fallback fallita: ${e.message}")
            }
            runCatching { VoiceUtils.writeCacheFromBase64(context, id, audioBase64) }
        }
        sendMessage(
            groupId,
            ChatMessage(
                id = id,
                type = MessageType.VOICE,
                audioUrl = audioUrl,
                audioDurationMs = durationMs,
                latitude = latitude,
                longitude = longitude,
                placeName = placeName
            )
        )
    }

    /**
     * Path locale dell'audio di una nota vocale, scaricandolo UNA sola volta dal doc
     * separato `voiceNotes/{messageId}` e mettendolo in cache. Ritorna null se assente.
     */
    suspend fun getVoiceNotePath(groupId: String, messageId: String, audioUrl: String? = null): String? {
        VoiceUtils.cachedPath(context, messageId)?.let { return it }
        // Nuovo percorso: scarica da Cloudinary URL.
        if (!audioUrl.isNullOrBlank()) {
            return downloadToCache(messageId, audioUrl)
        }
        // Fallback per vecchi messaggi: leggi da voiceNotes Firestore.
        return try {
            val doc = firestore?.collection("groups")?.document(groupId)
                ?.collection("voiceNotes")?.document(messageId)?.get()?.await()
            val b64 = doc?.getString("audioBase64")
            if (b64.isNullOrBlank()) null else VoiceUtils.writeCacheFromBase64(context, messageId, b64)
        } catch (e: Exception) {
            Log.w(TAG, "getVoiceNotePath fallita: ${e.message}")
            null
        }
    }

    /**
     * Compresses an image URI (from camera or gallery) and converts to Base64 JPEG string
     * with high resolution and fidelity for direct Firestore storage.
     */
    suspend fun compressImageToBase64(uri: Uri, maxDimension: Int = 1280, quality: Int = 85): Result<String> {
        return try {
            val base64 = ImageUtils.uriToBase64(context, uri, maxDimension = maxDimension, quality = quality)
            if (!base64.isNullOrBlank()) {
                Result.success(base64)
            } else {
                Result.failure(Exception(str(R.string.err_image_processing)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "compressImageToBase64 error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Compresses a direct Bitmap to Base64 JPEG string with high resolution and fidelity.
     */
    fun compressBitmapToBase64(bitmap: Bitmap, maxDimension: Int = 1280, quality: Int = 85): Result<String> {
        return try {
            val base64 = ImageUtils.bitmapToBase64(bitmap, maxDimension = maxDimension, quality = quality)
            if (!base64.isNullOrBlank()) {
                Result.success(base64)
            } else {
                Result.failure(Exception(str(R.string.err_snapshot_processing)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "compressBitmapToBase64 error: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ================== PLACE SNAPSHOTS (GEOREFERENCED PHOTOS) ==================

    suspend fun addPlaceSnapshot(snapshot: PlaceSnapshot): Result<PlaceSnapshot> {
        val user = _currentUserState.value ?: return Result.failure(Exception(str(R.string.err_not_authenticated)))
        val currentGroup = user.currentGroupId ?: _userGroupsState.value.firstOrNull()?.id
            ?: return Result.failure(Exception(str(R.string.err_no_group_selected)))

        val snapshotId = if (snapshot.id.isBlank()) "snp_${UUID.randomUUID().toString().take(8)}" else snapshot.id
        // Carica foto snapshot su Cloudinary; fallback a Base64 se fallisce.
        val photoUrl = uploadFileToCloudinary(snapshot.photoBase64, "image/jpeg", "$snapshotId.jpg")
        val newSnapshot = snapshot.copy(
            id = snapshotId,
            groupId = currentGroup,
            userId = user.uid,
            userName = user.displayName,
            userPhotoBase64 = user.photoBase64,
            photoUrl = photoUrl,
            timestamp = System.currentTimeMillis()
        )

        try {
            if (firestore != null) {
                val map = hashMapOf(
                    "id" to newSnapshot.id,
                    "groupId" to newSnapshot.groupId,
                    "userId" to newSnapshot.userId,
                    "userName" to newSnapshot.userName,
                    "userPhotoBase64" to (newSnapshot.userPhotoBase64 ?: ""),
                    "photoBase64" to if (photoUrl != null) "" else newSnapshot.photoBase64,
                    "photoUrl" to (photoUrl ?: ""),
                    "latitude" to newSnapshot.latitude,
                    "longitude" to newSnapshot.longitude,
                    "timestamp" to newSnapshot.timestamp,
                    "caption" to newSnapshot.caption
                )
                firestore.collection("groups").document(currentGroup)
                    .collection("snapshots").document(newSnapshot.id).set(map).await()

                // Also notify group members with a feed message in chat
                val snapMsg = ChatMessage(
                    id = "msg_${UUID.randomUUID().toString().take(8)}",
                    senderId = user.uid,
                    senderName = user.displayName,
                    senderPhoto = user.photoBase64,
                    text = if (newSnapshot.caption.isNotBlank()) str(R.string.msg_new_snapshot_caption, newSnapshot.caption)
                        else str(R.string.msg_new_snapshot),
                    imageBase64 = if (photoUrl != null) null else newSnapshot.photoBase64,
                    imageUrl = photoUrl,
                    timestamp = newSnapshot.timestamp,
                    type = MessageType.IMAGE,
                    latitude = newSnapshot.latitude,
                    longitude = newSnapshot.longitude,
                    snapshotId = newSnapshot.id
                )
                sendMessage(currentGroup, snapMsg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "addPlaceSnapshot firestore failed: ${e.message}")
        }

        _currentGroupSnapshots.value = listOf(newSnapshot) + _currentGroupSnapshots.value.filterNot { it.id == newSnapshot.id }
        return Result.success(newSnapshot)
    }

    suspend fun deletePlaceSnapshot(snapshotId: String): Result<Unit> {
        val currentGroup = _currentUserState.value?.currentGroupId ?: _userGroupsState.value.firstOrNull()?.id ?: ""
        try {
            if (firestore != null && currentGroup.isNotBlank()) {
                firestore.collection("groups").document(currentGroup)
                    .collection("snapshots").document(snapshotId).delete().await()

                // Delete associated chat messages that reference this snapshot
                val messageDocs = firestore.collection("groups").document(currentGroup)
                    .collection("messages")
                    .whereEqualTo("snapshotId", snapshotId)
                    .get().await()
                for (doc in messageDocs.documents) {
                    runCatching { doc.reference.delete().await() }
                }
                // Also remove them from the local StateFlow
                val deletedIds = messageDocs.documents.map { it.id }.toSet()
                if (deletedIds.isNotEmpty()) {
                    _currentGroupMessages.value = _currentGroupMessages.value.filterNot { it.id in deletedIds }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "deletePlaceSnapshot firestore failed: ${e.message}")
        }
        _currentGroupSnapshots.value = _currentGroupSnapshots.value.filterNot { it.id == snapshotId }
        return Result.success(Unit)
    }

    /**
     * Sends an emergency SOS alert to the group.
     * Records both a high-priority chat message and a real-time event in Firestore.
     */
    fun sendSosAlert(groupId: String) {
        val user = _currentUserState.value ?: return
        val eventId = "sos_${UUID.randomUUID().toString().take(8)}"
        val timestamp = System.currentTimeMillis()

        // 1. Send SOS message in chat
        val sosMsg = ChatMessage(
            id = eventId,
            senderId = user.uid,
            senderName = user.displayName,
            text = str(R.string.msg_sos_sent),
            timestamp = timestamp,
            type = MessageType.SOS_ALERT
        )
        sendMessage(groupId, sosMsg)

        // 2. Record SOS event in groups/{groupId}/events
        try {
            if (firestore != null) {
                val eventMap = hashMapOf(
                    "id" to eventId,
                    "groupId" to groupId,
                    "type" to "sos_alert",
                    "userId" to user.uid,
                    "userName" to user.displayName,
                    "placeName" to "Posizione attuale",
                    "message" to "${user.displayName} ha inviato una richiesta di soccorso",
                    "timestamp" to timestamp
                )
                firestore.collection("groups").document(groupId)
                    .collection("events").document(eventId).set(eventMap)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendSosAlert firestore error: ${e.message}")
        }
    }

    /**
     * Unico imbuto per le notifiche generate dai listener Firestore.
     *
     * Il corpo vero sta in [com.example.notification.RadarNotifier]: qui si decide
     * solo quale forma dare all'avviso in base alla destinazione. Le notifiche di
     * chat vengono impilate per gruppo, quelle di luogo e SOS escono come banner.
     *
     * Il parametro `notificationId` non serve più — gli ID li assegna il notifier,
     * che deve poterli ritrovare per cancellarli — ma resta nella firma per non
     * toccare le decine di chiamate esistenti.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun showLocalNotification(
        title: String,
        body: String,
        isHighPriority: Boolean = false,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt(),
        destination: String = "MAP",
        groupId: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        senderId: String? = null
    ) {
        try {
            val groupName = groupId?.let { gid ->
                _userGroupsState.value.find { it.id == gid }?.name
            }

            when {
                destination.equals("CHAT", ignoreCase = true) && !groupId.isNullOrBlank() -> {
                    com.example.notification.RadarNotifier.notifyChatMessage(
                        context = context,
                        groupId = groupId,
                        groupName = groupName,
                        senderName = title,
                        body = body,
                        timestamp = System.currentTimeMillis(),
                        senderId = senderId
                    )
                }

                isHighPriority || destination.equals("ALERT", ignoreCase = true) -> {
                    com.example.notification.RadarNotifier.notifySos(
                        context = context,
                        groupId = groupId,
                        title = title,
                        body = body,
                        latitude = latitude,
                        longitude = longitude,
                        senderId = senderId
                    )
                }

                destination.equals("MAP", ignoreCase = true) -> {
                    com.example.notification.RadarNotifier.notifyPlaceEvent(
                        context = context,
                        groupId = groupId,
                        title = title,
                        body = body,
                        latitude = latitude,
                        longitude = longitude,
                        senderId = senderId
                    )
                }

                else -> {
                    com.example.notification.RadarNotifier.notifyGeneric(
                        context = context,
                        destination = destination,
                        title = title,
                        body = body,
                        groupId = groupId,
                        senderId = senderId
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error showing local notification: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------
    // MESSAGGI NON LETTI
    //
    // Prima il badge mostrava `messages.size`, cioè il totale storico della chat:
    // non era un conteggio di non letti, cresceva e basta. Ora si confronta il
    // timestamp dei messaggi con l'ultima apertura della scheda Chat.
    // ---------------------------------------------------------------------

    private val _unreadChatCount = MutableStateFlow(0)
    val unreadChatCount = _unreadChatCount.asStateFlow()

    private fun lastReadKey(groupId: String) = "chat_last_read_$groupId"

    /** Da chiamare quando l'utente apre la chat: azzera badge e notifiche in status bar. */
    fun markChatRead(groupId: String) {
        if (groupId.isBlank()) return
        settingsPrefs.edit().putLong(lastReadKey(groupId), System.currentTimeMillis()).apply()
        _unreadChatCount.value = 0
        com.example.notification.RadarNotifier.clearChatNotifications(context, groupId)
    }

    private fun recomputeUnreadChat(groupId: String, messages: List<ChatMessage>) {
        val myUid = _currentUserState.value?.uid
        val lastRead = settingsPrefs.getLong(lastReadKey(groupId), 0L)
        _unreadChatCount.value = messages.count {
            it.timestamp > lastRead && it.senderId != myUid
        }
    }

    /**
     * Stringa localizzata secondo la scelta in Impostazioni.
     *
     * Il repository tiene l'application context, che ignora la lingua scelta
     * nell'app: leggere da `context.getString` darebbe la lingua del dispositivo.
     * Nome corto perche' compare decine di volte.
     */
    private fun str(resId: Int, vararg args: Any): String =
        com.example.ui.theme.LanguagePreferences.localizedContext(context)
            .getString(resId, *args)

    private fun generateJoinCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    // ================== TRIP RECORDING ==================

    /**
     * Intervallo di campionamento realmente in uso: quello ad altissima frequenza (500ms) del viaggio se
     * ce n'e' uno in registrazione, altrimenti quello scelto dall'utente.
     */
    private fun effectiveTrackingIntervalMs(): Long = when {
        _activeTrip.value != null -> TRIP_TRACKING_INTERVAL_MS
        // Col rilevamento automatico attivo non si puo' aspettare l'intervallo
        // dell'utente: l'app si accorge della partenza solo quando guarda dove
        // sei, quindi con 10 minuti perderebbe l'inizio del tragitto — o un giro
        // breve per intero. Da fermi si guarda al massimo ogni minuto.
        _isAutoTripEnabled.value -> minOf(_trackingFrequencySeconds.value, AUTO_TRIP_MAX_IDLE_SEC) * 1000L
        else -> _trackingFrequencySeconds.value * 1000L
    }

    private fun effectiveTrackingIntervalSec(): Int = (effectiveTrackingIntervalMs() / 1000L).toInt().coerceAtLeast(1)

    /**
     * Riallinea i due produttori di posizione all'intervallo effettivo. Va
     * chiamata all'avvio e alla fine di un viaggio: senza, la registrazione
     * continuerebbe a ricevere fix alla cadenza del radar (90 secondi di
     * default) e la traccia resterebbe fatta di due o tre punti.
     */
    private fun applyEffectiveTrackingInterval() {
        if (silentLocationCallback != null) {
            startSilentLocationTracking()
        }
        if (_isBackgroundTrackingEnabled.value) {
            com.example.service.LocationTrackingService.updateIntervalMs(
                context, effectiveTrackingIntervalMs()
            )
        }
    }

    /**
     * Avvia una registrazione.
     *
     * Un viaggio MANUALE crea subito il documento su Firestore con `isLive`:
     * gli altri membri lo vedono avanzare in tempo reale. Uno AUTOMATICO no,
     * perché non è una scelta esplicita di chi guida e non ha senso annunciarla.
     */
    fun startTrip(source: TripSource = TripSource.MANUAL) {
        if (_activeTrip.value != null) return

        val now = System.currentTimeMillis()
        val myLocation = _currentGroupLocations.value.find { it.userId == _currentUserState.value?.uid }

        // Un viaggio automatico parte per definizione a spostamento gia' iniziato:
        // i fix tenuti da [rememberFix] sono il tratto percorso mentre l'app stava
        // ancora decidendo, e senza recuperarli la traccia comincerebbe a meta'
        // strada. Il manuale invece parte quando lo dice l'utente: li' il presente
        // e' l'inizio giusto.
        val backfill = if (source == TripSource.AUTO) backfilledStartPoints() else emptyList()
        val first = backfill.firstOrNull()
        val last = backfill.lastOrNull()

        // Distanza e tempo in movimento del tratto recuperato: senza questo la
        // traccia comparirebbe sulla mappa ma i chilometri di quel pezzo non
        // sarebbero contati, e la media risulterebbe piu' bassa del vero.
        var backfillDistance = 0.0
        var backfillMovingMs = 0L
        for (i in 1 until backfill.size) {
            val a = backfill[i - 1]
            val b = backfill[i]
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                a.latitude, a.longitude, b.latitude, b.longitude, results
            )
            backfillDistance += results[0].toDouble()
            val gapSec = (b.timestamp - a.timestamp) / 1000.0
            if (gapSec in 0.0..TRIP_MOVING_GAP_MAX_SEC) {
                backfillMovingMs += (b.timestamp - a.timestamp).coerceAtLeast(0L)
            }
        }

        _activeTrip.value = ActiveTripState(
            startTime = first?.timestamp ?: now,
            source = source,
            points = backfill,
            lastLat = last?.latitude ?: 0.0,
            lastLon = last?.longitude ?: 0.0,
            distanceMeters = backfillDistance,
            movingMs = backfillMovingMs,
            lastFixAt = last?.timestamp ?: now,
            startPlaceName = myLocation?.currentPlaceName
        )
        if (backfill.isNotEmpty()) {
            Log.d(TAG, "Viaggio avviato recuperando ${backfill.size} punti gia' percorsi")
        }
        recentFixes.clear()
        // Il viaggio impone da se' la cadenza fitta: l'infittimento del sensore
        // ha finito il suo compito.
        isFixRateBoosted = false
        applyEffectiveTrackingInterval()

        if (source == TripSource.MANUAL) {
            CoroutineScope(Dispatchers.IO).launch { createLiveTripDocument() }
        }
    }

    /** Crea il documento del viaggio in diretta e ne memorizza l'id. */
    private suspend fun createLiveTripDocument() {
        val trip = _activeTrip.value ?: return
        val user = _currentUserState.value ?: return
        val groupId = user.currentGroupId ?: return
        val db = firestore ?: return

        try {
            val data = hashMapOf(
                "userId" to user.uid,
                "userName" to user.displayName,
                "groupId" to groupId,
                "startTime" to trip.startTime,
                "endTime" to 0L,
                "durationMs" to 0L,
                "distanceMeters" to 0.0,
                "pointCount" to 0,
                "source" to trip.source.name,
                "isLive" to true,
                "isPrivate" to false,
                "startPlaceName" to (trip.startPlaceName ?: ""),
                "points" to emptyList<Map<String, Any>>()
            )
            val ref = db.collection("groups").document(groupId)
                .collection("trips").add(data).await()
            // Atomico: qui si scrive da un thread IO mentre i fix di posizione
            // aggiornano lo stesso stato dal main looper.
            _activeTrip.update { it?.copy(liveTripId = ref.id) }

            // Notifica gli altri membri che un viaggio è iniziato.
            try {
                val eventId = "trip_${UUID.randomUUID().toString().take(8)}"
                val eventMap = hashMapOf(
                    "id" to eventId,
                    "groupId" to groupId,
                    "type" to "trip_start",
                    "userId" to user.uid,
                    "userName" to user.displayName,
                    // Long come TUTTI gli altri eventi: il listener filtra con
                    // whereGreaterThan("timestamp", joinTime) dove joinTime e' un
                    // Long, e Firestore esclude dai filtri di disuguaglianza i campi
                    // di tipo diverso. Con serverTimestamp() (tipo Timestamp) gli
                    // eventi viaggio venivano scartati dalla query e non notificati.
                    "timestamp" to System.currentTimeMillis()
                )
                db.collection("groups").document(groupId)
                    .collection("events").document(eventId).set(eventMap)
            } catch (e: Exception) {
                Log.w(TAG, "trip_start event fallita: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "createLiveTripDocument fallita: ${e.message}")
        }
    }

    /**
     * Aggiunge un punto alla registrazione in corso.
     *
     * Tutta la modifica passa da `update {}`, che è una lettura-modifica-scrittura
     * atomica. Con l'assegnazione diretta c'era una corsa reale: i fix arrivano
     * sul main looper mentre [createLiveTripDocument] scrive `liveTripId` da un
     * thread IO. Se un fix leggeva lo stato prima e lo riscriveva dopo, l'id del
     * documento in diretta veniva perso per sempre — niente più aggiornamenti
     * agli altri membri, e allo stop il documento originale restava orfano con
     * `isLive = true`, cioè un viaggio "in corso" per sempre nell'elenco di tutti.
     */
    private fun recordTripPoint(location: UserLocation) {
        // Un fix con raggio d'incertezza enorme e' quello che fa "saltare" la
        // traccia in punti dove non si e' mai stati: capita al chiuso, nelle
        // gallerie e quando il GPS ripiega sulle celle telefoniche.
        if (location.accuracy > TRIP_MAX_ACCURACY_METERS) return

        val lat = location.latitude
        val lon = location.longitude
        val now = System.currentTimeMillis()
        var flushDue = false

        _activeTrip.update { current ->
            // Il lambda puo' essere rieseguito se un altro thread scrive nel
            // frattempo: la decisione va ricalcolata a ogni tentativo.
            flushDue = false
            if (current == null) return@update null

            if (current.lastLat == 0.0 && current.lastLon == 0.0) {
                val tp = TripPoint(lat, lon, now)
                return@update current.copy(
                    points = current.points + tp,
                    lastLat = lat,
                    lastLon = lon,
                    lastFixAt = now
                )
            }

            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                current.lastLat, current.lastLon, lat, lon, results
            )
            val distFromLast = results[0].toDouble()
            if (distFromLast < 15.0) return@update current

            // Secondo filtro sugli spostamenti assurdi: se per coprire quella
            // distanza servirebbe una velocita' impossibile, il fix e' sbagliato,
            // non e' un movimento. Senza, un singolo fix ballerino piazza un picco
            // nella traccia e gonfia i chilometri totali.
            val elapsedSec = (now - current.lastFixAt) / 1000.0
            if (elapsedSec > 0.5 && distFromLast / elapsedSec > TRIP_MAX_SPEED_MS) return@update current

            // Il tempo fra due punti validi e' tempo in movimento. La differenza
            // con la durata totale e' il tempo fermo: semafori, code, soste.
            val movingDelta = if (elapsedSec in 0.0..TRIP_MOVING_GAP_MAX_SEC) {
                (now - current.lastFixAt).coerceAtLeast(0L)
            } else 0L

            val tp = TripPoint(lat, lon, now)
            val next = current.copy(
                points = current.points + tp,
                lastLat = lat,
                lastLon = lon,
                distanceMeters = current.distanceMeters + distFromLast,
                maxSpeedMs = maxOf(current.maxSpeedMs, location.speed),
                movingMs = current.movingMs + movingDelta,
                lastFixAt = now
            )
            // Aggiornamento della diretta: non a ogni punto, sarebbero centinaia
            // di scritture all'ora. Ogni 15 secondi si riversa la traccia RDP
            // semplificata: gli altri la vedono avanzare a un costo sostenibile.
            if (next.liveTripId != null && now - next.lastLiveWriteAt >= TRIP_LIVE_FLUSH_MS) {
                flushDue = true
                next.copy(lastLiveWriteAt = now)
            } else {
                next
            }
        }

        if (flushDue) {
            CoroutineScope(Dispatchers.IO).launch { flushLiveTrip() }
        }
    }

    /** Riversa sul documento in diretta i punti accumulati finora. */
    private suspend fun flushLiveTrip() {
        val trip = _activeTrip.value ?: return
        val tripId = trip.liveTripId ?: return
        val groupId = _currentUserState.value?.currentGroupId ?: return
        val db = firestore ?: return

        try {
            val simplified = rdpSimplify(trip.points, TRIP_RDP_EPSILON_METERS)
            db.collection("groups").document(groupId)
                .collection("trips").document(tripId)
                .update(
                    mapOf(
                        "distanceMeters" to trip.distanceMeters,
                        "durationMs" to (System.currentTimeMillis() - trip.startTime),
                        "pointCount" to simplified.size,
                        "points" to simplified.map {
                            hashMapOf(
                                "latitude" to it.latitude,
                                "longitude" to it.longitude,
                                "timestamp" to it.timestamp
                            )
                        }
                    )
                ).await()
        } catch (e: Exception) {
            Log.w(TAG, "flushLiveTrip fallita: ${e.message}")
        }
    }

    suspend fun stopAndSaveTrip(): Result<Unit> {
        val trip = _activeTrip.value ?: return Result.failure(Exception(str(R.string.err_no_active_trip)))

        // La registrazione va chiusa e la cadenza ripristinata SUBITO, prima di
        // qualunque controllo che possa uscire con un errore: altrimenti un
        // salvataggio fallito lascerebbe il campionamento fitto attivo a tempo
        // indeterminato, con il GPS a 5 secondi che divora la batteria.
        _activeTrip.value = null
        applyEffectiveTrackingInterval()

        val user = _currentUserState.value ?: return Result.failure(Exception(str(R.string.err_not_authenticated)))
        val groupId = user.currentGroupId ?: return Result.failure(Exception(str(R.string.err_no_group_selected)))
        val db = firestore ?: return Result.failure(Exception(str(R.string.err_firestore_unavailable)))

        // Troppo corto per essere un viaggio: se era condiviso in diretta va
        // comunque rimosso, altrimenti resta un documento "in corso" per sempre.
        if (trip.points.size < 2) {
            trip.liveTripId?.let { id ->
                try {
                    db.collection("groups").document(groupId)
                        .collection("trips").document(id).delete().await()
                } catch (_: Exception) {}
            }
            return Result.success(Unit)
        }

        val simplified = rdpSimplify(trip.points, TRIP_RDP_EPSILON_METERS)
        val endTime = System.currentTimeMillis()
        val endPlace = GeofenceHelper.findCurrentPlace(
            UserLocation(latitude = trip.lastLat, longitude = trip.lastLon),
            _currentGroupPlaces.value
        )?.name

        val isPrivate = trip.source == TripSource.AUTO && !_isAutoTripShared.value

        return try {
            val meta = hashMapOf(
                "userId" to user.uid,
                "userName" to user.displayName,
                "groupId" to groupId,
                "startTime" to trip.startTime,
                "endTime" to endTime,
                "durationMs" to (endTime - trip.startTime),
                "distanceMeters" to trip.distanceMeters,
                "pointCount" to simplified.size,
                "source" to trip.source.name,
                "maxSpeedMs" to trip.maxSpeedMs.toDouble(),
                "movingMs" to trip.movingMs,
                "startPlaceName" to (trip.startPlaceName ?: ""),
                "endPlaceName" to (endPlace ?: ""),
                "isLive" to false,
                "isPrivate" to isPrivate,
                "activityKind" to trip.activityKind,
                // La traccia esce dal documento dell'elenco e va nel
                // sottodocumento: cinquanta viaggi con i punti dentro
                // significherebbero scaricarli tutti a ogni apertura del gruppo.
                "points" to emptyList<Map<String, Any>>()
            )

            val tripRef = if (trip.liveTripId != null) {
                val ref = db.collection("groups").document(groupId)
                    .collection("trips").document(trip.liveTripId)
                ref.set(meta).await()
                ref
            } else {
                db.collection("groups").document(groupId)
                    .collection("trips").add(meta).await()
            }

            tripRef.collection("track").document("data").set(
                mapOf(
                    "points" to simplified.map {
                        hashMapOf(
                            "latitude" to it.latitude,
                            "longitude" to it.longitude,
                            "timestamp" to it.timestamp
                        )
                    }
                )
            ).await()

            // Notifica gli altri membri che il viaggio e' terminato (solo se condiviso).
            if (!isPrivate) {
                try {
                    val eventId = db.collection("groups").document(groupId)
                        .collection("events").document().id
                    val eventMap = hashMapOf(
                        "id" to eventId,
                        "groupId" to groupId,
                        "type" to "trip_end",
                        "userId" to user.uid,
                        "userName" to user.displayName,
                        // Long come TUTTI gli altri eventi: il listener filtra con
                    // whereGreaterThan("timestamp", joinTime) dove joinTime e' un
                    // Long, e Firestore esclude dai filtri di disuguaglianza i campi
                    // di tipo diverso. Con serverTimestamp() (tipo Timestamp) gli
                    // eventi viaggio venivano scartati dalla query e non notificati.
                    "timestamp" to System.currentTimeMillis()
                    )
                    db.collection("groups").document(groupId)
                        .collection("events").document(eventId).set(eventMap)
                } catch (e: Exception) {
                    Log.w(TAG, "trip_end event fallita: ${e.message}")
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "stopAndSaveTrip failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Legge la traccia di un viaggio, su richiesta. L'elenco porta solo i
     * metadati: i punti si pagano solo quando si apre quel viaggio.
     */
    suspend fun loadTripTrack(tripId: String): List<TripPoint> {
        val groupId = _currentUserState.value?.currentGroupId ?: return emptyList()
        val db = firestore ?: return emptyList()
        return try {
            val doc = db.collection("groups").document(groupId)
                .collection("trips").document(tripId)
                .collection("track").document("data").get().await()

            @Suppress("UNCHECKED_CAST")
            val raw = doc.get("points") as? List<Map<String, Any>> ?: emptyList()
            raw.mapNotNull { p ->
                val lat = p["latitude"] as? Double ?: return@mapNotNull null
                val lon = p["longitude"] as? Double ?: return@mapNotNull null
                TripPoint(lat, lon, (p["timestamp"] as? Long) ?: 0L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadTripTrack fallita: ${e.message}")
            emptyList()
        }
    }

    suspend fun deleteTrip(tripId: String): Result<Unit> {
        val user = _currentUserState.value ?: return Result.failure(Exception(str(R.string.err_not_authenticated)))
        val groupId = user.currentGroupId ?: return Result.failure(Exception(str(R.string.err_no_group_selected)))
        return try {
            val tripRef = firestore?.collection("groups")?.document(groupId)
                ?.collection("trips")?.document(tripId)
            // Il sottodocumento della traccia non se ne va da solo: Firestore non
            // cancella le sottocollezioni insieme al documento padre.
            try { tripRef?.collection("track")?.document("data")?.delete()?.await() } catch (_: Exception) {}
            tripRef?.delete()?.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- Rilevamento automatico ----

    private var autoMovingSinceMillis: Long = 0L
    private var autoStationarySinceMillis: Long = 0L

    // ---------------------------------------------------------------------
    // RILEVAMENTO AUTOMATICO: TRE SEGNALI, UN SOLO PUNTO DI DECISIONE
    //
    // Il polling da solo arrivava sempre tardi. L'app guarda la posizione a
    // intervalli, quindi fra la partenza vera e il primo fix in movimento si
    // perde fino a un intervallo intero; e la soglia dei 90 secondi puo' essere
    // verificata solo al fix successivo, che ne aggiunge un altro. Con un
    // minuto di cadenza il viaggio partiva dopo tre.
    //
    // Ora concorrono tre segnali, e il primo che basta fa partire il viaggio:
    //
    //  1. TYPE_SIGNIFICANT_MOTION ([MotionTrigger]) — il sensor hub sveglia il
    //     processo appena il telefono si muove davvero. Non dice come ti stai
    //     muovendo, ma permette di infittire subito i fix invece di aspettare
    //     il prossimo giro del polling.
    //  2. Activity Recognition ([onActivityTransition]) — Android conferma
    //     IN_VEHICLE / ON_BICYCLE / RUNNING. E' un classificatore e ci mette
    //     qualche decina di secondi, ma quando parla non sono falsi positivi.
    //  3. Distanza percorsa ([AUTO_TRIP_START_DISTANCE_M]) — se ti sei
    //     allontanato tanto dal punto in cui eri fermo, la conferma temporale
    //     non serve piu': ti sei spostato e basta.
    //
    // E in ogni caso il viaggio non comincia da dove sei quando scatta la
    // soglia, ma dai fix tenuti in [recentFixes]: vedi [backfilledStartPoints].
    // ---------------------------------------------------------------------

    /** Ultimi fix osservati, per ricostruire il tratto percorso prima dell'avvio. */
    private val recentFixes = ArrayDeque<TripPoint>()

    /** Ultimo stato riportato dall'Activity Recognition, null se non si sa. */
    private var lastKnownActivityIsTravel: Boolean? = null

    private var motionTrigger: MotionTrigger? = null
    private var activityTransitionPendingIntent: android.app.PendingIntent? = null

    /** Da dove eri fermo l'ultima volta: serve alla scorciatoia sulla distanza. */
    private var stationaryAnchorLat: Double = 0.0
    private var stationaryAnchorLon: Double = 0.0

    // --- Notifica "in movimento" (indipendente dall'auto-trip) ---
    /** Ultimo punto in cui Android ci dava fermi/a piedi: ancora per la notifica. */
    private var movementAnchorLat: Double = 0.0
    private var movementAnchorLon: Double = 0.0
    /** Quando e' partita l'ultima notifica di movimento: per il cooldown. */
    private var lastMovementNotifyAt: Long =
        settingsPrefs.getLong("movement_notify_at", 0L)

    /** Vero mentre i fix sono infittiti dopo uno scatto del sensore di movimento. */
    private var isFixRateBoosted: Boolean = false
    private var fixBoostStartedAt: Long = 0L

    /**
     * Tiene in memoria gli ultimi fix, scartando quelli piu' vecchi di
     * [AUTO_TRIP_BACKFILL_WINDOW_MS]. Costa nulla — sono coordinate in RAM, non
     * scritture — ed e' quello che permette a un viaggio di cominciare da dove
     * e' cominciato davvero invece che da dove eri quando l'app se n'e' accorta.
     */
    private fun rememberFix(location: UserLocation) {
        if (!_isAutoTripEnabled.value) return
        if (location.accuracy > TRIP_MAX_ACCURACY_METERS) return

        val now = System.currentTimeMillis()
        recentFixes.addLast(TripPoint(location.latitude, location.longitude, now))

        while (recentFixes.isNotEmpty() &&
            now - recentFixes.first().timestamp > AUTO_TRIP_BACKFILL_WINDOW_MS
        ) {
            recentFixes.removeFirst()
        }
        while (recentFixes.size > AUTO_TRIP_BACKFILL_MAX_POINTS) {
            recentFixes.removeFirst()
        }
    }

    /**
     * I punti bufferizzati da usare come inizio del viaggio: si risale indietro
     * finche' i fix restano vicini nel tempo, cioe' finche' fanno parte dello
     * stesso spostamento.
     */
    private fun backfilledStartPoints(): List<TripPoint> {
        if (recentFixes.isEmpty()) return emptyList()
        val ordered = recentFixes.toList()
        val cutoff = System.currentTimeMillis() - AUTO_TRIP_BACKFILL_WINDOW_MS
        return ordered.filter { it.timestamp >= cutoff }
    }

    /**
     * Arma il sensore hardware di movimento. Serve solo al rilevamento automatico
     * dei viaggi, quindi si accende con quello. Facoltativo: se il dispositivo non
     * ha TYPE_SIGNIFICANT_MOTION si resta sul controllo a intervalli.
     */
    private fun startMotionSensing() {
        try {
            val trigger = motionTrigger ?: MotionTrigger(context).also { motionTrigger = it }
            if (trigger.isAvailable) {
                trigger.start { onSignificantMotion() }
            } else {
                Log.d(TAG, "TYPE_SIGNIFICANT_MOTION non disponibile: resto sul polling")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sensore movimento non avviato: ${e.message}")
        }
    }

    /**
     * Registra le transizioni di attivita'. Indipendente dal rilevamento
     * automatico dei viaggi, perche' serve anche solo a mostrare sulla mappa come
     * si sta muovendo ciascun membro: gira sul sensor hub e costa quasi nulla.
     */
    private fun startActivityRecognition() {
        if (activityTransitionPendingIntent != null) return
        if (!hasActivityRecognitionPermission()) {
            Log.d(TAG, "Permesso ACTIVITY_RECOGNITION assente: nessun riconoscimento attivita'")
            return
        }
        try {
            // Il metodo del builder si chiama setActivityTransition, non
            // setActivityTransitionType: quest'ultimo non esiste nell'API.
            val activityTypes: List<Int> = listOf(
                DetectedActivity.IN_VEHICLE,
                DetectedActivity.ON_BICYCLE,
                DetectedActivity.RUNNING,
                DetectedActivity.WALKING,
                DetectedActivity.STILL
            )
            val transitionTypes: List<Int> = listOf(
                ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                ActivityTransition.ACTIVITY_TRANSITION_EXIT
            )
            val transitions = ArrayList<ActivityTransition>()
            for (activityType in activityTypes) {
                for (transitionType in transitionTypes) {
                    transitions.add(
                        ActivityTransition.Builder()
                            .setActivityType(activityType)
                            .setActivityTransition(transitionType)
                            .build()
                    )
                }
            }

            val intent = Intent(context, com.example.service.ActivityTransitionReceiver::class.java)
                .setAction(com.example.service.ActivityTransitionReceiver.ACTION_TRANSITION)
            // Da API 31 il PendingIntent va dichiarato mutabile: e' il sistema a
            // riempirlo con il risultato della transizione.
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = android.app.PendingIntent.getBroadcast(context, 0, intent, flags)
            activityTransitionPendingIntent = pending

            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending)
                .addOnSuccessListener { Log.d(TAG, "Activity Recognition registrata") }
                .addOnFailureListener { Log.w(TAG, "Activity Recognition fallita: ${it.message}") }
        } catch (e: Exception) {
            Log.w(TAG, "Activity Recognition non registrata: ${e.message}")
        }
    }

    /**
     * Disarma solo il sensore hardware. Il riconoscimento di attivita' resta
     * registrato: serve all'icona sulla mappa anche con i viaggi automatici spenti.
     */
    private fun stopMotionSensing() {
        try {
            motionTrigger?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Stop sensore movimento fallito: ${e.message}")
        }
    }

    fun hasActivityRecognitionPermission(): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    } catch (e: Exception) {
        false
    }

    /**
     * Il sensor hub ha svegliato il processo. Non sappiamo ancora se e' un
     * viaggio, quindi non si avvia niente: si accende il GPS fitto, cosi' i fix
     * successivi arrivano subito e finiscono in [recentFixes]. Se poi si rivela
     * un falso allarme, [applyEffectiveTrackingInterval] rimette la cadenza
     * normale alla chiusura del viaggio o al prossimo giro.
     */
    private fun onSignificantMotion() {
        if (!_isAutoTripEnabled.value) return
        if (_activeTrip.value != null) return
        try {
            isFixRateBoosted = true
            fixBoostStartedAt = System.currentTimeMillis()
            if (silentLocationCallback != null) startSilentLocationTracking()
            if (_isBackgroundTrackingEnabled.value) {
                com.example.service.LocationTrackingService.updateIntervalMs(
                    context, TRIP_TRACKING_INTERVAL_MS
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Infittimento fix dopo movimento fallito: ${e.message}")
        }
    }

    /**
     * Riporta la cadenza dei fix a quella normale dopo un infittimento che non ha
     * prodotto un viaggio. Senza questo, un singolo falso allarme del sensore
     * — il telefono spostato sul tavolo — lascerebbe l'app a chiedere la posizione
     * ogni cinque secondi per sempre.
     */
    private fun endFixRateBoostIfIdle() {
        if (!isFixRateBoosted) return
        if (_activeTrip.value != null) return
        if (System.currentTimeMillis() - fixBoostStartedAt < FIX_BOOST_MAX_MS) return
        isFixRateBoosted = false
        Log.d(TAG, "Nessun viaggio dopo il movimento: cadenza fix ripristinata")
        applyEffectiveTrackingInterval()
    }

    /**
     * Android ha riconosciuto un cambio di attivita'. Un ENTER in uno stato di
     * spostamento e' la conferma piu' affidabile che abbiamo e fa partire il
     * viaggio senza attendere la soglia temporale; un ENTER in STILL chiude la
     * finestra e riabilita la chiusura per immobilita'.
     *
     * Chiamata dal broadcast receiver, quindi puo' arrivare col processo appena
     * risvegliato: niente assunzioni su cosa e' inizializzato.
     */
    fun onActivityTransition(activityType: Int, isEnter: Boolean) {
        // Il modo di spostarsi va registrato sempre, anche a viaggi automatici
        // spenti: e' quello che la mappa mostra accanto a ciascun membro.
        if (isEnter) {
            _currentActivityKind = when (activityType) {
                DetectedActivity.IN_VEHICLE -> ActivityKind.VEHICLE
                DetectedActivity.ON_BICYCLE -> ActivityKind.BICYCLE
                DetectedActivity.RUNNING -> ActivityKind.RUNNING
                DetectedActivity.WALKING -> ActivityKind.WALKING
                DetectedActivity.STILL -> ActivityKind.STILL
                else -> _currentActivityKind
            }
            // La posizione mostrata deve aggiornarsi subito, senza attendere il
            // prossimo fix: altrimenti l'icona resterebbe indietro di un giro.
            CoroutineScope(Dispatchers.IO).launch { pushActivityKindNow() }

            // Si annota sul viaggio in corso, cosi' il dettaglio puo' dire come
            // ci si e' spostati. Solo i modi di VIAGGIO: una sosta a piedi in
            // mezzo a un tragitto in auto non deve riscrivere l'etichetta.
            val kind = _currentActivityKind
            if (kind == ActivityKind.VEHICLE || kind == ActivityKind.BICYCLE ||
                kind == ActivityKind.RUNNING || kind == ActivityKind.WALKING
            ) {
                _activeTrip.update { it?.copy(activityKind = kind) }
            }
        }

        if (!_isAutoTripEnabled.value) return

        val isTravel = activityType == DetectedActivity.IN_VEHICLE ||
            activityType == DetectedActivity.ON_BICYCLE ||
            activityType == DetectedActivity.RUNNING

        if (activityType == DetectedActivity.STILL && isEnter) {
            lastKnownActivityIsTravel = false
            return
        }

        if (!isTravel || !isEnter) return
        lastKnownActivityIsTravel = true

        if (_activeTrip.value != null) {
            // Gia' in viaggio: la conferma serve solo a non farlo chiudere.
            autoStationarySinceMillis = 0L
            return
        }

        // La conferma di sistema non fa piu' partire da sola: da fermi Android
        // puo' dire IN_VEHICLE per errore (sei su un mezzo fermo, o il telefono
        // e' in un veicolo parcheggiato) e aprirebbe un viaggio fantasma. Si parte
        // solo se ci si e' gia' allontanati dall'ancora di uno spostamento vero.
        val lastFix = recentFixes.lastOrNull()
        val displacementFromAnchor = if (lastFix != null) {
            distanceFromStationaryAnchor(UserLocation(latitude = lastFix.latitude, longitude = lastFix.longitude))
        } else 0.0
        if (displacementFromAnchor < AUTO_TRIP_CONFIRM_MIN_DISPLACEMENT_M) {
            // Ci si fida della conferma per non chiudere, ma per aprire si aspetta
            // che la distanza dall'ancora superi la soglia (via evaluateAutoTrip).
            autoStationarySinceMillis = 0L
            return
        }

        Log.d(TAG, "Viaggio automatico avviato su conferma di sistema (${displacementFromAnchor.toInt()}m dall'ancora)")
        autoMovingSinceMillis = 0L
        autoStationarySinceMillis = 0L
        startTrip(TripSource.AUTO)
    }

    /** Come si sta muovendo questo dispositivo, secondo Android. */
    private var _currentActivityKind: String = ""

    /**
     * Scrive solo il campo dell'attivita' su locations/{uid}. E' un aggiornamento
     * minuscolo e va fatto fuori dal ciclo dei fix, perche' una transizione puo'
     * arrivare quando l'utente e' fermo e nessun fix nuovo e' in arrivo.
     */
    private suspend fun pushActivityKindNow() {
        val user = _currentUserState.value ?: return
        val groupId = user.currentGroupId ?: return
        val db = firestore ?: return
        if (_isGlobalGhostMode.value) return
        val myMember = _currentGroupMembers.value.find { it.userId == user.uid }
        if (myMember != null && !myMember.isTrackingActive) return

        try {
            db.collection("groups").document(groupId)
                .collection("locations").document(user.uid)
                .update("activityType", _currentActivityKind)
                .await()
        } catch (e: Exception) {
            // Il documento potrebbe non esistere ancora: lo creera' il primo fix.
            Log.v(TAG, "Attivita' non scritta: ${e.message}")
        }
    }

    /**
     * Decide da sola quando comincia e quando finisce uno spostamento.
     *
     * Parte quando uno dei tre segnali basta (vedi il blocco di commento sopra),
     * così una camminata di pochi passi verso la finestra non diventa un viaggio.
     * Chiude dopo [AUTO_TRIP_STOP_MS] da fermo, perché una sosta breve — semaforo,
     * benzina — fa parte dello stesso tragitto e spezzarlo in due sarebbe peggio.
     */
    private fun evaluateAutoTrip(location: UserLocation) {
        if (!_isAutoTripEnabled.value) return

        // Un fix impreciso non puo' decidere se sei partito. In casa il GPS
        // sbanda di decine di metri e la velocita' che ne deriva e' rumore, non
        // movimento: senza questo filtro bastava camminare per casa perche' i
        // fix riportassero a ripetizione una velocita' sopra soglia.
        if (location.accuracy > TRIP_MAX_ACCURACY_METERS) return

        val now = System.currentTimeMillis()
        val moving = location.speed > AUTO_TRIP_START_SPEED_MS
        val active = _activeTrip.value

        if (active == null) {
            if (!moving) {
                autoMovingSinceMillis = 0L
                // Da fermo questo e' il punto da cui misurare l'allontanamento.
                stationaryAnchorLat = location.latitude
                stationaryAnchorLon = location.longitude
                endFixRateBoostIfIdle()
                return
            }
            if (autoMovingSinceMillis == 0L) {
                autoMovingSinceMillis = now
                if (stationaryAnchorLat == 0.0 && stationaryAnchorLon == 0.0) {
                    stationaryAnchorLat = location.latitude
                    stationaryAnchorLon = location.longitude
                }
            }

            val displacement = distanceFromStationaryAnchor(location)
            // Lo spostamento conta come reale solo se supera nettamente
            // l'incertezza del fix: un GPS che sbanda con 40 m di accuratezza puo'
            // riportare un'ancora "lontana" 150 m senza che ci si sia mossi. Chiedere
            // displacement > 3x accuracy scarta questi salti in casa.
            val displacementIsReal = displacement > location.accuracy * 3f
            val movedEnough = displacement >= AUTO_TRIP_START_DISTANCE_M && displacementIsReal
            // Lo spostamento NETTO dal punto in cui si era fermi e' cio' che
            // distingue un viaggio dall'andirivieni: camminando per casa la
            // velocita' istantanea puo' superare la soglia quanto vuole, ma non
            // ci si allontana mai. Senza questa condizione bastavano 90 secondi
            // di movimento sul posto per far partire un viaggio.
            val movedLongEnough = now - autoMovingSinceMillis >= AUTO_TRIP_START_MS &&
                displacement >= AUTO_TRIP_MIN_NET_DISPLACEMENT_M && displacementIsReal

            if (movedEnough || movedLongEnough) {
                autoMovingSinceMillis = 0L
                autoStationarySinceMillis = 0L
                Log.d(
                    TAG,
                    "Viaggio automatico avviato (" +
                        (if (movedEnough) "distanza" else "durata") +
                        ", ${displacement.toInt()}m dall'ancora)"
                )
                startTrip(TripSource.AUTO)
            }
            return
        }

        // Un viaggio manuale lo chiude chi l'ha aperto, non l'automatismo.
        if (active.source != TripSource.AUTO) return

        if (moving) {
            autoStationarySinceMillis = 0L
            return
        }

        // Se il sistema dice ancora che sei in viaggio, la velocita' a zero e'
        // probabilmente una sosta o un fix scadente: non si chiude.
        if (lastKnownActivityIsTravel == true) {
            autoStationarySinceMillis = 0L
            return
        }

        if (autoStationarySinceMillis == 0L) autoStationarySinceMillis = now
        if (now - autoStationarySinceMillis >= AUTO_TRIP_STOP_MS) {
            autoStationarySinceMillis = 0L
            Log.d(TAG, "Viaggio automatico concluso")
            CoroutineScope(Dispatchers.IO).launch { stopAndSaveTrip() }
        }
    }

    private fun distanceFromStationaryAnchor(location: UserLocation): Double {
        if (stationaryAnchorLat == 0.0 && stationaryAnchorLon == 0.0) return 0.0
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            stationaryAnchorLat, stationaryAnchorLon,
            location.latitude, location.longitude,
            results
        )
        return results[0].toDouble()
    }

    /**
     * Decide se avvisare gli altri che questo membro si sta muovendo.
     *
     * Del tutto indipendente dall'auto-trip: gira su OGNI fix, anche a viaggi
     * automatici spenti, perche' l'Activity Recognition e' sempre attiva (serve
     * gia' all'icona sulla mappa). La notifica scatta quando due condizioni sono
     * vere insieme:
     *   1. Android classifica il movimento come VIAGGIO (auto / bici / corsa);
     *   2. ci si e' allontanati di [MOVEMENT_NOTIFY_DISTANCE_M] dall'ultimo punto
     *      in cui si era fermi.
     * Un cooldown per-persona ([MOVEMENT_NOTIFY_COOLDOWN_MS]) evita di ri-avvisare
     * a ogni ripartenza dopo una sosta breve (spesa, benzina).
     */
    private fun evaluateMovementNotification(location: UserLocation) {
        if (location.accuracy > TRIP_MAX_ACCURACY_METERS) return

        val kind = _currentActivityKind
        val isTravel = kind == ActivityKind.VEHICLE ||
            kind == ActivityKind.BICYCLE ||
            kind == ActivityKind.RUNNING

        // Fermo o a piedi: qui si ancora il punto di partenza del prossimo viaggio.
        if (!isTravel) {
            movementAnchorLat = location.latitude
            movementAnchorLon = location.longitude
            return
        }

        // Primo fix "in viaggio" senza un'ancora: la si fissa adesso e si aspetta
        // il fix successivo per misurare l'allontanamento.
        if (movementAnchorLat == 0.0 && movementAnchorLon == 0.0) {
            movementAnchorLat = location.latitude
            movementAnchorLon = location.longitude
            return
        }

        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            movementAnchorLat, movementAnchorLon,
            location.latitude, location.longitude,
            results
        )
        val displacement = results[0].toDouble()
        // Scarta i salti del GPS: lo spostamento deve superare l'incertezza del fix.
        if (displacement < MOVEMENT_NOTIFY_DISTANCE_M) return
        if (displacement < location.accuracy * 3f) return

        val now = System.currentTimeMillis()
        if (now - lastMovementNotifyAt < MOVEMENT_NOTIFY_COOLDOWN_MS) return

        lastMovementNotifyAt = now
        settingsPrefs.edit().putLong("movement_notify_at", now).apply()
        // Riparte da qui: perche' scatti di nuovo serve una nuova sosta seguita da
        // un nuovo allontanamento, non basta continuare lo stesso viaggio.
        movementAnchorLat = location.latitude
        movementAnchorLon = location.longitude

        val notifiedKind = kind
        CoroutineScope(Dispatchers.IO).launch { emitMovementEvent(notifiedKind) }
    }

    /** Scrive l'evento "movement" cosi' che gli altri membri ricevano la notifica. */
    private suspend fun emitMovementEvent(activityKind: String) {
        val user = _currentUserState.value ?: return
        val groupId = user.currentGroupId ?: return
        val db = firestore ?: return
        if (_isGlobalGhostMode.value) return
        val myMember = _currentGroupMembers.value.find { it.userId == user.uid }
        if (myMember != null && !myMember.isTrackingActive) return

        try {
            val eventId = "move_${UUID.randomUUID().toString().take(8)}"
            val eventMap = hashMapOf(
                "id" to eventId,
                "groupId" to groupId,
                "type" to "movement",
                "userId" to user.uid,
                "userName" to user.displayName,
                "activityKind" to activityKind,
                // Long come tutti gli altri eventi: il listener filtra per timestamp
                // e Firestore scarta dai filtri di disuguaglianza i tipi diversi.
                "timestamp" to System.currentTimeMillis()
            )
            db.collection("groups").document(groupId)
                .collection("events").document(eventId).set(eventMap).await()
        } catch (e: Exception) {
            Log.w(TAG, "evento movement fallito: ${e.message}")
        }
    }

    /** Traduce il tipo di attivita' nella frase mostrata in notifica, null se ignoto. */
    private fun movementKindText(kind: String?): String? = when (kind) {
        ActivityKind.VEHICLE -> str(R.string.activity_kind_vehicle)
        ActivityKind.BICYCLE -> str(R.string.activity_kind_bicycle)
        ActivityKind.RUNNING -> str(R.string.activity_kind_running)
        ActivityKind.WALKING -> str(R.string.activity_kind_walking)
        else -> null
    }

    private fun rdpSimplify(points: List<TripPoint>, epsilon: Double): List<TripPoint> {
        if (points.size < 3) return points
        val first = points.first()
        val last = points.last()
        var maxDist = 0.0
        var maxIdx = 0
        for (i in 1 until points.size - 1) {
            val d = crossTrackDistance(points[i], first, last)
            if (d > maxDist) { maxDist = d; maxIdx = i }
        }
        return if (maxDist > epsilon) {
            val left = rdpSimplify(points.subList(0, maxIdx + 1), epsilon)
            val right = rdpSimplify(points.subList(maxIdx, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(first, last)
        }
    }

    /**
     * Distanza perpendicolare del punto dal SEGMENTO start-end, in metri.
     *
     * La versione precedente ricavava i due rilevamenti dalla stessa chiamata a
     * distanceBetween, quindi la loro differenza era sempre zero e la funzione
     * restituiva sempre 0: maxDist non superava mai epsilon e rdpSimplify
     * finiva sempre nel ramo listOf(first, last). Ogni viaggio veniva salvato
     * con due soli punti, qualunque percorso fosse stato fatto.
     *
     * Qui si proietta su un piano locale in metri: su tragitti di pochi
     * chilometri l'errore e' trascurabile, e la formula e' molto piu' semplice
     * e robusta della trigonometria sferica.
     */
    private fun crossTrackDistance(point: TripPoint, start: TripPoint, end: TripPoint): Double {
        val latRefRad = Math.toRadians((start.latitude + end.latitude) / 2.0)
        val metersPerDegLat = 111_132.0
        val metersPerDegLon = 111_320.0 * kotlin.math.cos(latRefRad)

        val ax = start.longitude * metersPerDegLon
        val ay = start.latitude * metersPerDegLat
        val bx = end.longitude * metersPerDegLon
        val by = end.latitude * metersPerDegLat
        val px = point.longitude * metersPerDegLon
        val py = point.latitude * metersPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) return kotlin.math.hypot(px - ax, py - ay)

        // t limitato a [0,1]: la distanza e' dal segmento, non dalla retta
        // infinita che lo contiene.
        val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return kotlin.math.hypot(px - cx, py - cy)
    }

    companion object {
        /** Sotto questa distanza dall'ultimo fix trasmesso non si scrive su Firestore. */
        const val MIN_DISPLACEMENT_METERS = 18f

        /** Oltre questa velocità si trasmette sempre: ~5,4 km/h, si è chiaramente in moto. */
        const val MOVING_SPEED_THRESHOLD_MS = 1.5f

        /** Aggiornamento forzato anche da fermi, per tenere vivi stato online e batteria. */
        const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L

        /** Attesa prima di riagganciare i listener di gruppo dopo un errore recuperabile. */
        const val LISTENER_REATTACH_DELAY_MS = 8_000L

        /**
         * Intervallo GPS di partenza: 30 secondi.
         *
         * Era stato portato a 90 per risparmiare batteria, ma a quel passo la
         * mappa risultava pigra e le tracce dei viaggi troppo grossolane. Il
         * risparmio vero lo fanno comunque il filtro anti-jitter (da fermi non si
         * scrive) e la precisione adattiva del servizio, non la rarefazione dei fix.
         */
        const val DEFAULT_TRACKING_INTERVAL_SEC = 30

        /**
         * Cadenza dei fix mentre un viaggio e' in registrazione (500 ms = 2 fix/sec).
         *
         * Durante un viaggio si campiona ad altissima frequenza (500ms) per ottenere
         * tracce curve perfette e ultra-fluide, e si torna all'intervallo standard
         * dell'utente appena si ferma la registrazione.
         */
        const val TRIP_TRACKING_INTERVAL_MS = 500L
        const val TRIP_TRACKING_INTERVAL_SEC = 1

        /** Oltre questo raggio d'incertezza il fix non entra nella traccia. */
        const val TRIP_MAX_ACCURACY_METERS = 50f

        /**
         * Oltre questo raggio d'incertezza il fix non genera avvisi geofence:
         * un fix impreciso non puo' decidere in modo affidabile se sei entrato o
         * uscito da un luogo, ed e' la causa principale delle raffiche di notifiche.
         */
        const val GEOFENCE_MAX_ACCURACY_METERS = 40f

        /**
         * Isteresi: una volta dentro un luogo si "esce" solo superando
         * raggio + questo margine. Assorbe i salti del GPS mentre si sta fermi
         * sul bordo di un luogo salvato.
         */
        const val GEOFENCE_EXIT_MARGIN_METERS = 50.0

        /** ~200 km/h: oltre, non e' movimento ma un errore del sensore. */
        const val TRIP_MAX_SPEED_MS = 55.0

        /**
         * Soglia di semplificazione della traccia. Dieci metri, sullo schermo di
         * un telefono, sono meno di un pixel a qualunque zoom realistico: toglie
         * ridondanza, non forma.
         */
        const val TRIP_RDP_EPSILON_METERS = 10.0

        /** Ogni quanto la diretta (traccia RDP) viene riversata su Firestore. */
        const val TRIP_LIVE_FLUSH_MS = 60_000L

        /** Durante un viaggio, scrive locations/{uid} al massimo ogni 30 s. */
        const val TRIP_LOCATION_WRITE_INTERVAL_MS = 30_000L

        /**
         * Oltre questo intervallo fra due punti non si conta tempo in movimento:
         * il buco e' dovuto al GPS perso, non a un tratto percorso.
         */
        const val TRIP_MOVING_GAP_MAX_SEC = 120.0

        /**
         * Movimento continuo necessario prima che parta un viaggio automatico.
         * E' la via piu' lenta delle tre: di norma arrivano prima la conferma di
         * sistema o la soglia di distanza.
         */
        const val AUTO_TRIP_START_MS = 150_000L

        /**
         * Allontanamento dal punto in cui si era fermi che da' per certo lo
         * spostamento, senza attendere [AUTO_TRIP_START_MS]. Sopra i due isolati
         * non e' piu' un giro per casa.
         */
        const val AUTO_TRIP_START_DISTANCE_M = 400.0

        /**
         * Allontanamento minimo dall'ancora richiesto perche' la conferma di
         * sistema (Android dice IN_VEHICLE/bici/corsa) faccia partire un viaggio.
         * Senza, bastava un falso "in vehicle" da fermi — o un tratto di pochi
         * metri — per aprire un viaggio. Con 100 m la conferma resta reattiva ma
         * solo dopo uno spostamento vero.
         */
        const val AUTO_TRIP_CONFIRM_MIN_DISPLACEMENT_M = 100.0

        /**
         * Velocita' oltre la quale si comincia a contare il tempo di movimento
         * per l'avvio automatico. Piu' alta di [MOVING_SPEED_THRESHOLD_MS], che
         * vale 1,5 m/s cioe' una camminata svelta: per il gate delle scritture
         * va bene, per decidere che e' cominciato un viaggio no. 2,8 m/s sono
         * circa 10 km/h, sopra il passo di chiunque cammini.
         */
        const val AUTO_TRIP_START_SPEED_MS = 3.5f

        /**
         * Allontanamento netto dal punto in cui si era fermi, richiesto perche'
         * la sola durata del movimento faccia partire un viaggio.
         *
         * E' la condizione che distingue un viaggio dall'andirivieni per casa:
         * camminando avanti e indietro la velocita' istantanea puo' superare la
         * soglia quanto vuole, ma la distanza dal punto di partenza resta
         * prossima a zero.
         */
        const val AUTO_TRIP_MIN_NET_DISPLACEMENT_M = 250.0

        /**
         * Quanto indietro si guarda per ricostruire l'inizio di un viaggio
         * rilevato da solo. Cinque minuti coprono il caso peggiore del polling
         * senza rischiare di incollare al viaggio uno spostamento precedente.
         */
        const val AUTO_TRIP_BACKFILL_WINDOW_MS = 5 * 60_000L

        /** Tetto al buffer dei fix recenti, per non far crescere la memoria. */
        const val AUTO_TRIP_BACKFILL_MAX_POINTS = 120

        /**
         * Quanto si tengono i fix infittiti dopo uno scatto del sensore di
         * movimento senza che sia partito un viaggio. Oltre, era un falso allarme
         * e la cadenza torna quella normale.
         */
        const val FIX_BOOST_MAX_MS = 3 * 60_000L

        /** Immobilita' necessaria prima che un viaggio automatico si chiuda. */
        const val AUTO_TRIP_STOP_MS = 3 * 60_000L

        /**
         * Spostamento netto (dall'ultimo punto in cui si era fermi) oltre il quale
         * si notifica agli altri che questo membro e' in movimento. Indipendente
         * dall'auto-trip: si basa solo su Activity Recognition + questo spostamento.
         */
        const val MOVEMENT_NOTIFY_DISTANCE_M = 300.0

        /**
         * Silenzio minimo fra due notifiche "in movimento" della stessa persona.
         * Copre soste intermedie (spesa, benzina) senza ri-notificare a ogni
         * ripartenza. Persistito in SharedPreferences per sopravvivere ai riavvii.
         */
        const val MOVEMENT_NOTIFY_COOLDOWN_MS = 30 * 60_000L

        /** Attesa massima fra due fix quando il rilevamento automatico e' attivo. */
        const val AUTO_TRIP_MAX_IDLE_SEC = 60

        /** Variazione minima di batteria che giustifica una scrittura su members/{uid}. */
        const val BATTERY_WRITE_DELTA = 5

        /** Primo blocco di messaggi caricati aprendo la chat. */
        const val CHAT_HISTORY_LIMIT = 50L

        /** Quanti messaggi in piu' si caricano a ogni scroll verso l'alto. */
        const val CHAT_PAGE_SIZE = 50L

        private const val TAG = "FirebaseRepository"
        const val GOOGLE_SERVER_CLIENT_ID = "357992636648-6k4uqv3pe5bbqke0ocjo1amlhlps5m8n.apps.googleusercontent.com"

        @Volatile
        private var instance: FirebaseRepository? = null

        fun getInstance(context: Context): FirebaseRepository {
            return instance ?: synchronized(this) {
                instance ?: FirebaseRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
