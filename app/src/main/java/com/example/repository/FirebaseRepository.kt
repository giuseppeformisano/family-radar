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
import com.example.model.*
import com.example.model.Trip
import com.example.model.TripPoint
import com.example.model.ActiveTripState
import com.example.util.ImageUtils
import com.google.android.gms.location.*
import com.google.firebase.FirebaseException
import com.google.firebase.auth.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
    private var groupsCollectionListener: ListenerRegistration? = null
    private val memberStatusListeners = java.util.concurrent.ConcurrentHashMap<String, ListenerRegistration>()
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
     * Porta il default da 30 a [DEFAULT_TRACKING_INTERVAL_SEC] secondi.
     *
     * Cambiare la costante non basterebbe: chi ha gia' usato l'app ha il 30
     * scritto nelle preferenze e se lo terrebbe per sempre. La migrazione tocca
     * solo chi non ha mai personalizzato il valore, cioe' chi ha esattamente il
     * vecchio default; qualsiasi altra scelta esplicita viene rispettata.
     */
    private fun migrateTrackingIntervalDefault() {
        if (settingsPrefs.getBoolean("tracking_freq_migrated_v2", false)) return
        val stored = settingsPrefs.getInt("tracking_freq_sec", DEFAULT_TRACKING_INTERVAL_SEC)
        val editor = settingsPrefs.edit().putBoolean("tracking_freq_migrated_v2", true)
        if (stored == 30) {
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

    // Risparmio energia (default false) persistito.
    //
    // Non spegne il tracciamento: cambia solo la *sorgente* della posizione.
    // Con PRIORITY_BALANCED_POWER_ACCURACY il sistema smette di accendere il
    // chip GPS e ricava la posizione da WiFi e celle telefoniche: precisione
    // ~100 m invece di ~5 m, ma consumo molto piu' basso e funziona anche al
    // chiuso. Per l'utente resta tutto uguale, continua a comparire sulla mappa.
    private val _isPowerSavingMode = MutableStateFlow(settingsPrefs.getBoolean("power_saving_mode", false))
    val isPowerSavingMode = _isPowerSavingMode.asStateFlow()

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

        // Entrambi i produttori vanno riagganciati con la nuova precisione,
        // altrimenti il cambio avrebbe effetto solo al riavvio dell'app.
        // Nessuna interruzione: startSilentLocationTracking stacca e riattacca,
        // e il servizio riemette la richiesta senza perdere il foreground.
        if (silentLocationCallback != null) {
            startSilentLocationTracking()
        }
        if (_isBackgroundTrackingEnabled.value) {
            com.example.service.LocationTrackingService.updatePowerMode(context)
        }

        // Passando ad alta precisione il primo fix preciso puo' distare parecchio
        // da quello approssimato scritto per ultimo; passando a bassa precisione
        // il raggio di errore si allarga di colpo. In entrambi i casi il gate
        // confronterebbe grandezze non omogenee, quindi lo si azzera e si
        // ripubblica subito: l'utente non deve accorgersi del cambio.
        resetLocationGate()
        pushLastKnownLocationNow()
    }

    fun setTrackingFrequencySeconds(seconds: Int) {
        val clamped = seconds.coerceIn(5, 86400)
        _trackingFrequencySeconds.value = clamped
        settingsPrefs.edit().putInt("tracking_freq_sec", clamped).apply()
        if (_isBackgroundTrackingEnabled.value) {
            com.example.service.LocationTrackingService.updateInterval(context, clamped)
        }
        if (silentLocationCallback != null) {
            startSilentLocationTracking()
        }
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
        val currentUser = _currentUserState.value ?: return Result.failure(Exception("Utente non loggato"))
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
                    Log.d(TAG, "Fetched FCM token: $token")
                    updateFcmToken(token)
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
                Result.failure(IllegalStateException("Tipo di credenziale Google non riconosciuto"))
            }
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "Google Sign-In cancelled by user")
            Result.failure(Exception("Accesso Google annullato"))
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No Google accounts available on this device: ${e.message}")
            Result.failure(Exception("Nessun account Google trovato sul dispositivo/emulatore. Aggiungi un account Google nelle impostazioni di Android o usa l'accesso con Numero di Telefono o Email."))
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
            onVerificationFailed(IllegalStateException("FirebaseAuth non inizializzato"))
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
                Result.failure(IllegalStateException("FirebaseAuth non inizializzato"))
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
                                        createdAt = gDoc.getLong("createdAt") ?: System.currentTimeMillis()
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
                        userMembershipStatus = currentStatus
                    )

                    memberGroupsMap[gId] = group

                    if (ownerId == userId) {
                        updatedGroups.add(group.copy(userMembershipStatus = "ACTIVE"))
                    } else {
                        attachMemberDocListener(gId, group, userId)
                    }
                }

                // Keep owner groups plus existing confirmed member groups that are still valid in Firestore
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
                    val groupWithStatus = groupData.copy(userMembershipStatus = status)

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

                        // Auto-select group to immediately transition UI to Main Radar Screen
                        if (_currentUserState.value?.currentGroupId.isNullOrBlank() || _currentUserState.value?.currentGroupId == groupId) {
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
        requiresApproval: Boolean = true
    ): Result<GroupData> {
        val currentUser = _currentUserState.value ?: return Result.failure(Exception("Utente non loggato"))
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
                    "requiresApproval" to requiresApproval
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
        val currentUser = _currentUserState.value ?: return Result.failure(Exception("Utente non loggato"))
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

        return Result.failure(Exception("Codice invito non valido o gruppo inesistente"))
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
     * Member leaves group: deletes member and location records, unsubscribes from FCM topic.
     */
    suspend fun leaveGroup(groupId: String): Result<Unit> {
        val user = _currentUserState.value ?: return Result.failure(Exception("Utente non loggato"))
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

    private fun cleanupGroupListeners() {
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
                                    altitude = doc.getDouble("altitude") ?: 0.0,
                                    batteryLevel = (doc.getLong("batteryLevel") ?: 100L).toInt(),
                                    timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                                    isOnline = doc.getBoolean("isOnline") ?: true,
                                    currentPlaceName = doc.getString("currentPlaceName")
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
                    if (e != null) return@addSnapshotListener
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
                                    createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
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

            // 3. Real-time chat messages listener (Notifies for Type 2: Chat & Type 3: SOS)
            //
            // Limitato agli ultimi CHAT_HISTORY_LIMIT messaggi. Le immagini sono
            // Base64 dentro i documenti, fino a 1 MB l'una: senza limite, aprire un
            // gruppo con cronologia lunga significava scaricarla tutta a ogni
            // riconnessione del listener. Si ordina DESCENDING per prendere i piu'
            // recenti e si riporta la lista in ordine cronologico piu' sotto.
            messagesListener = firestore.collection("groups").document(groupId)
                .collection("messages")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(CHAT_HISTORY_LIMIT)
                .addSnapshotListener { snapshot, e ->
                    if (e != null) return@addSnapshotListener
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
                                    longitude = doc.getDouble("longitude")
                                )

                                // Check if this is a new message from another member
                                if (timestamp > lastObservedMessageTimestamp && senderId.isNotBlank() && senderId != currentUid) {
                                    when (type) {
                                        // TYPE 3: SOS Alert Message
                                        MessageType.SOS_ALERT -> {
                                            showLocalNotification(
                                                title = "Allerta SOS",
                                                body = "Richiesta di soccorso immediata inviata da $senderName",
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
                                        MessageType.TEXT, MessageType.IMAGE, MessageType.LOCATION_SHARE -> {
                                            val bodyText = when (type) {
                                                MessageType.IMAGE -> "Ha inviato un'immagine"
                                                MessageType.LOCATION_SHARE -> "Ha condiviso la posizione"
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
                        // La query e' DESCENDING: la UI vuole i messaggi dal piu'
                        // vecchio al piu' recente, altrimenti la chat appare capovolta.
                        val chronological = list.sortedBy { it.timestamp }
                        _currentGroupMessages.value = chronological
                        recomputeUnreadChat(groupId, chronological)
                    }
                }

            // 4. Real-time geofence & group events listener (TYPE 1: Places Entry/Exit & TYPE 3: SOS & Admin Join Requests)
            eventsListener = firestore.collection("groups").document(groupId)
                .collection("events")
                .whereGreaterThan("timestamp", joinTime)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Listen group events error: ${error.message}")
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
                                        title = "Nuova richiesta di adesione",
                                        body = customMsg ?: "$userName ha richiesto di entrare nel gruppo",
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
                                            title = "Arrivo a destinazione",
                                            body = customMsg ?: "$userName è arrivato a $placeName",
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
                                            title = "Partenza registrata",
                                            body = customMsg ?: "$userName ha lasciato $placeName",
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
                                            title = "Allerta di emergenza SOS",
                                            body = customMsg ?: "$userName ha inviato una richiesta di soccorso",
                                            isHighPriority = true,
                                            notificationId = 999,
                                            destination = "ALERT",
                                            groupId = groupId,
                                            latitude = eventLat,
                                            longitude = eventLon,
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
                    if (e != null) return@addSnapshotListener
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
                        return@addSnapshotListener
                    }
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                val lat = doc.getDouble("latitude") ?: 0.0
                                val lon = doc.getDouble("longitude") ?: 0.0
                                val photoBase64 = doc.getString("photoBase64") ?: ""
                                if (photoBase64.isBlank() || (lat == 0.0 && lon == 0.0)) return@mapNotNull null
                                PlaceSnapshot(
                                    id = doc.getString("id") ?: doc.id,
                                    groupId = doc.getString("groupId") ?: groupId,
                                    userId = doc.getString("userId") ?: "",
                                    userName = doc.getString("userName") ?: "Membro",
                                    userPhotoBase64 = doc.getString("userPhotoBase64"),
                                    photoBase64 = photoBase64,
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
                    if (e != null) return@addSnapshotListener
                    if (snapshot != null) {
                        val list = snapshot.documents.mapNotNull { doc ->
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val rawPoints = doc.get("points") as? List<Map<String, Any>> ?: emptyList()
                                val points = rawPoints.mapNotNull { p ->
                                    val lat = (p["latitude"] as? Double) ?: return@mapNotNull null
                                    val lon = (p["longitude"] as? Double) ?: return@mapNotNull null
                                    val ts = (p["timestamp"] as? Long) ?: 0L
                                    TripPoint(lat, lon, ts)
                                }
                                Trip(
                                    id = doc.id,
                                    groupId = groupId,
                                    userId = doc.getString("userId") ?: "",
                                    userName = doc.getString("userName") ?: "Membro",
                                    startTime = doc.getLong("startTime") ?: 0L,
                                    endTime = doc.getLong("endTime") ?: 0L,
                                    durationMs = doc.getLong("durationMs") ?: 0L,
                                    distanceMeters = doc.getDouble("distanceMeters") ?: 0.0,
                                    points = points
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

            val interval = (_trackingFrequencySeconds.value * 1000L).coerceAtLeast(5000L)
            val request = LocationRequest.Builder(locationPriority(), interval).apply {
                setMinUpdateIntervalMillis(interval / 2)
                setWaitForAccurateLocation(false)
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
    }

    /**
     * Ripubblica subito l'ultima posizione nota, senza aspettare il prossimo
     * tick del tracking (che con intervalli lunghi puo' essere parecchi secondi).
     * Va usata dopo [resetLocationGate], altrimenti il gate scarta comunque il fix.
     */
    private fun pushLastKnownLocationNow() {
        try {
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) return

            if (fusedLocationClient == null) {
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            }

            fusedLocationClient?.lastLocation?.addOnSuccessListener { loc ->
                if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                    val (battery, isCharging) = getBatteryStatus()
                    val uLoc = UserLocation(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = loc.accuracy,
                        speed = if (loc.hasSpeed()) loc.speed else 0.0f,
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

    /** Esito della valutazione, tenuto esplicito per poterlo loggare in chiaro. */
    private data class LocationGate(
        val shouldSend: Boolean,
        val reason: String,
        val isHeartbeat: Boolean = false
    )

    private fun evaluateLocationGate(location: UserLocation): LocationGate {
        val prevLat = lastSentLatitude
        val prevLon = lastSentLongitude
        if (prevLat == null || prevLon == null) {
            return LocationGate(true, "primo fix", isHeartbeat = true)
        }

        val elapsed = System.currentTimeMillis() - lastSentAtMillis
        if (elapsed >= HEARTBEAT_INTERVAL_MS) {
            // Heartbeat: anche da fermi bisogna rinfrescare stato online, orario
            // e livello batteria, altrimenti agli altri risultiamo scomparsi.
            return LocationGate(true, "heartbeat", isHeartbeat = true)
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

        // La valutazione geofence gira su OGNI fix, anche su quelli che non
        // trasmettiamo: un ingresso o un'uscita da un luogo non va perso solo
        // perché lo spostamento era piccolo.
        val gate = evaluateLocationGate(location)
        val placeForGeofence = GeofenceHelper.findCurrentPlace(location, _currentGroupPlaces.value)
        checkGeofenceAlert(user.displayName, placeForGeofence)

        if (!gate.shouldSend) {
            Log.v(TAG, "Fix ignorato: ${gate.reason}")
            return
        }
        Log.d(TAG, "Fix trasmesso: ${gate.reason}")

        // Registra punto se viaggio attivo
        if (_activeTrip.value != null) {
            recordTripPoint(location.latitude, location.longitude)
        }

        lastSentLatitude = location.latitude
        lastSentLongitude = location.longitude
        lastSentAtMillis = System.currentTimeMillis()

        // Compute current place
        val matchedPlace = placeForGeofence
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
                val locMap = hashMapOf(
                    "userId" to enrichedLocation.userId,
                    "userName" to enrichedLocation.userName,
                    "nickname" to (enrichedLocation.nickname ?: ""),
                    "photoBase64" to (enrichedLocation.photoBase64 ?: ""),
                    "latitude" to enrichedLocation.latitude,
                    "longitude" to enrichedLocation.longitude,
                    "accuracy" to enrichedLocation.accuracy,
                    "speed" to enrichedLocation.speed,
                    "altitude" to enrichedLocation.altitude,
                    "batteryLevel" to enrichedLocation.batteryLevel,
                    "timestamp" to enrichedLocation.timestamp,
                    "isOnline" to true,
                    "currentPlaceName" to (enrichedLocation.currentPlaceName ?: "")
                )
                firestore.collection("groups").document(currentGroup)
                    .collection("locations").document(user.uid).set(locMap).await()

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

    private fun checkGeofenceAlert(userName: String, place: SavedPlace?) {
        val user = _currentUserState.value ?: return
        val groupId = user.currentGroupId ?: _userGroupsState.value.firstOrNull()?.id ?: return

        if (place != null) {
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
                    text = "$userName è arrivato a ${place.name}",
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.GEOFENCE_ALERT
                )
                sendMessage(groupId, sysMsg)
            }
        } else {
            if (lastNotifiedPlaceId != null) {
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
                    text = "$userName ha lasciato $previousPlaceName",
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
        val user = _currentUserState.value ?: return Result.failure(Exception("No user"))
        val currentGroup = user.currentGroupId ?: _userGroupsState.value.firstOrNull()?.id
            ?: return Result.failure(Exception("Nessun gruppo selezionato"))

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
                    "createdAt" to newPlace.createdAt
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

        try {
            if (firestore != null && groupId.isNotBlank() && memberId.isNotBlank()) {
                val updateMap = hashMapOf<String, Any?>(
                    "displayName" to cleanName,
                    "nickname" to cleanNick,
                    "photoBase64" to cleanPhoto
                )
                firestore.collection("groups").document(groupId)
                    .collection("members").document(memberId)
                    .set(updateMap, com.google.firebase.firestore.SetOptions.merge())
                    .await()

                // If updating self, also update user's profile and current location entry
                val currentUser = _currentUserState.value
                if (currentUser != null && currentUser.uid == memberId) {
                    val updatedUser = currentUser.copy(
                        displayName = cleanName,
                        photoBase64 = cleanPhoto
                    )
                    _currentUserState.value = updatedUser

                    firestore.collection("users").document(memberId).set(
                        hashMapOf(
                            "displayName" to cleanName,
                            "photoBase64" to cleanPhoto
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )

                    firestore.collection("groups").document(groupId)
                        .collection("locations").document(memberId).set(
                            hashMapOf(
                                "userName" to cleanName,
                                "nickname" to cleanNick,
                                "photoBase64" to cleanPhoto
                            ),
                            com.google.firebase.firestore.SetOptions.merge()
                        )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateGroupMemberProfile error: ${e.message}")
            return Result.failure(e)
        }

        // Update local members state
        val updatedMembers = _currentGroupMembers.value.map { m ->
            if (m.userId == memberId) {
                m.copy(displayName = cleanName, nickname = cleanNick, photoBase64 = cleanPhoto)
            } else m
        }
        _currentGroupMembers.value = updatedMembers

        return Result.success(Unit)
    }

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
                    "longitude" to (msg.longitude ?: 0.0)
                )
                firestore.collection("groups").document(groupId)
                    .collection("messages").document(msg.id).set(map)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage firestore failed: ${e.message}")
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
                Result.failure(Exception("Impossibile elaborare l'immagine"))
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
                Result.failure(Exception("Impossibile elaborare lo scatto fotografico"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "compressBitmapToBase64 error: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ================== PLACE SNAPSHOTS (GEOREFERENCED PHOTOS) ==================

    suspend fun addPlaceSnapshot(snapshot: PlaceSnapshot): Result<PlaceSnapshot> {
        val user = _currentUserState.value ?: return Result.failure(Exception("Utente non autenticato"))
        val currentGroup = user.currentGroupId ?: _userGroupsState.value.firstOrNull()?.id
            ?: return Result.failure(Exception("Nessun gruppo selezionato"))

        val newSnapshot = snapshot.copy(
            id = if (snapshot.id.isBlank()) "snp_${UUID.randomUUID().toString().take(8)}" else snapshot.id,
            groupId = currentGroup,
            userId = user.uid,
            userName = user.displayName,
            userPhotoBase64 = user.photoBase64,
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
                    "photoBase64" to newSnapshot.photoBase64,
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
                    text = if (newSnapshot.caption.isNotBlank()) "Nuova istantanea: ${newSnapshot.caption}" else "Ha pubblicato una nuova istantanea geolocalizzata",
                    imageBase64 = newSnapshot.photoBase64,
                    timestamp = newSnapshot.timestamp,
                    type = MessageType.IMAGE,
                    latitude = newSnapshot.latitude,
                    longitude = newSnapshot.longitude
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
            text = "Richiesta di assistenza immediata inviata",
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

    private fun generateJoinCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    // ================== TRIP RECORDING ==================

    fun startTrip() {
        _activeTrip.value = ActiveTripState(startTime = System.currentTimeMillis())
    }

    private fun recordTripPoint(lat: Double, lon: Double) {
        val current = _activeTrip.value ?: return
        val lastLat = current.lastLat
        val lastLon = current.lastLon

        if (lastLat == 0.0 && lastLon == 0.0) {
            _activeTrip.value = current.copy(
                points = current.points + TripPoint(lat, lon, System.currentTimeMillis()),
                lastLat = lat,
                lastLon = lon
            )
            return
        }

        val results = FloatArray(1)
        android.location.Location.distanceBetween(lastLat, lastLon, lat, lon, results)
        val distFromLast = results[0].toDouble()

        if (distFromLast < 15.0) return

        _activeTrip.value = current.copy(
            points = current.points + TripPoint(lat, lon, System.currentTimeMillis()),
            lastLat = lat,
            lastLon = lon,
            distanceMeters = current.distanceMeters + distFromLast
        )
    }

    suspend fun stopAndSaveTrip(): Result<Unit> {
        val trip = _activeTrip.value ?: return Result.failure(Exception("Nessun viaggio attivo"))
        val user = _currentUserState.value ?: return Result.failure(Exception("Utente non loggato"))
        val groupId = user.currentGroupId ?: return Result.failure(Exception("Nessun gruppo"))

        _activeTrip.value = null

        if (trip.points.size < 2) return Result.success(Unit)

        val simplified = rdpSimplify(trip.points, epsilon = 10.0)
        val endTime = System.currentTimeMillis()

        return try {
            val pointMaps = simplified.map { p ->
                hashMapOf("latitude" to p.latitude, "longitude" to p.longitude, "timestamp" to p.timestamp)
            }
            val data = hashMapOf(
                "userId" to user.uid,
                "userName" to user.displayName,
                "groupId" to groupId,
                "startTime" to trip.startTime,
                "endTime" to endTime,
                "durationMs" to (endTime - trip.startTime),
                "distanceMeters" to trip.distanceMeters,
                "points" to pointMaps
            )
            firestore?.collection("groups")?.document(groupId)
                ?.collection("trips")?.add(data)?.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "stopAndSaveTrip failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteTrip(tripId: String): Result<Unit> {
        val user = _currentUserState.value ?: return Result.failure(Exception("Utente non loggato"))
        val groupId = user.currentGroupId ?: return Result.failure(Exception("Nessun gruppo"))
        return try {
            firestore?.collection("groups")?.document(groupId)
                ?.collection("trips")?.document(tripId)?.delete()?.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
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

    private fun crossTrackDistance(point: TripPoint, start: TripPoint, end: TripPoint): Double {
        val r = FloatArray(2)
        android.location.Location.distanceBetween(start.latitude, start.longitude, end.latitude, end.longitude, r)
        val lineLen = r[0].toDouble()
        if (lineLen < 0.001) {
            android.location.Location.distanceBetween(point.latitude, point.longitude, start.latitude, start.longitude, r)
            return r[0].toDouble()
        }
        android.location.Location.distanceBetween(start.latitude, start.longitude, point.latitude, point.longitude, r)
        val d = r[0].toDouble()
        val brg12 = r[1].toDouble()
        android.location.Location.distanceBetween(start.latitude, start.longitude, point.latitude, point.longitude, r)
        val brg13 = r[1].toDouble()
        return kotlin.math.abs(d * kotlin.math.sin(Math.toRadians(brg13 - brg12)))
    }

    companion object {
        /** Sotto questa distanza dall'ultimo fix trasmesso non si scrive su Firestore. */
        const val MIN_DISPLACEMENT_METERS = 18f

        /** Oltre questa velocità si trasmette sempre: ~5,4 km/h, si è chiaramente in moto. */
        const val MOVING_SPEED_THRESHOLD_MS = 1.5f

        /** Aggiornamento forzato anche da fermi, per tenere vivi stato online e batteria. */
        const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L

        /**
         * Intervallo GPS di partenza. Novanta secondi: con il filtro anti-jitter
         * la posizione a schermo resta reattiva quando ci si muove, ma da fermi
         * non si scrive nulla e il chip GPS lavora molto meno.
         */
        const val DEFAULT_TRACKING_INTERVAL_SEC = 90

        /** Variazione minima di batteria che giustifica una scrittura su members/{uid}. */
        const val BATTERY_WRITE_DELTA = 5

        /** Messaggi caricati dalla chat. Oltre, la cronologia costa piu' di quanto valga. */
        const val CHAT_HISTORY_LIMIT = 50L

        private const val TAG = "FirebaseRepository"
        const val GOOGLE_SERVER_CLIENT_ID = "782024869586-as3i6548kt6l7t8nst4a5pr2ntfkca9v.apps.googleusercontent.com"

        @Volatile
        private var instance: FirebaseRepository? = null

        fun getInstance(context: Context): FirebaseRepository {
            return instance ?: synchronized(this) {
                instance ?: FirebaseRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
