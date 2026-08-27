package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.example.ui.theme.Radius
import com.example.ui.theme.Sizes
import androidx.core.content.ContextCompat
import com.example.model.DeepLinkTarget
import com.example.repository.FirebaseRepository
import com.example.ui.screens.auth.AuthScreen
import com.example.ui.screens.groups.GroupSelectScreen
import com.example.ui.screens.main.MainRadarScreen
import com.example.ui.theme.LanguagePreferences
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemePreferences
import com.example.ui.theme.RadarDark
import androidx.compose.ui.graphics.Color
import com.example.util.AppUpdater
import com.example.util.CheckResult
import com.example.util.UpdateInfo

enum class AppScreen {
    AUTH,
    GROUP_SELECT,
    MAIN_RADAR
}

/** Tentativi del controllo aggiornamenti all'avvio, prima di rinunciare per questa sessione. */
private const val UPDATE_CHECK_ATTEMPTS = 3

/** Attesa fra i tentativi, moltiplicata per il numero del tentativo. */
private const val UPDATE_CHECK_RETRY_DELAY_MS = 5_000L

class MainActivity : ComponentActivity() {

    private lateinit var repository: FirebaseRepository

    /**
     * Applica la lingua scelta prima che venga creata qualsiasi risorsa.
     *
     * È il punto giusto perché da qui la locale vale per tutto l'albero Compose e
     * per ogni `getString` fatto dall'Activity, senza dover fornire a mano
     * `LocalContext`. Gira una volta per istanza: cambiare lingua richiede
     * `recreate()`, che è quello che fa il selettore in Impostazioni.
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LanguagePreferences.localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemePreferences.init(this)
        LanguagePreferences.init(this)
        repository = FirebaseRepository.getInstance(this)

        handleIntent(intent)

        setContent {
            val currentThemeMode by ThemePreferences.themeModeFlow.collectAsState()

            MyApplicationTheme(themeMode = currentThemeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    FamilyRadarApp(repository = repository)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        repository.setAppForeground(true)
    }

    override fun onStop() {
        super.onStop()
        repository.setAppForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val destination = intent.getStringExtra("destination") ?: intent.getStringExtra("type")?.let {
            when (it) {
                "chat_message" -> "CHAT"
                "sos_alert" -> "ALERT"
                "geofence_entry", "geofence_exit" -> "MAP"
                "join_request" -> "MEMBERS"
                else -> null
            }
        } ?: return

        val groupId = intent.getStringExtra("groupId")
        val latStr = intent.getStringExtra("latitude")
        val lonStr = intent.getStringExtra("longitude")
        val latitude = try {
            val d = intent.getDoubleExtra("latitude", Double.NaN)
            if (d.isNaN()) latStr?.toDoubleOrNull() else d
        } catch (_: Exception) { null }
        val longitude = try {
            val d = intent.getDoubleExtra("longitude", Double.NaN)
            if (d.isNaN()) lonStr?.toDoubleOrNull() else d
        } catch (_: Exception) { null }
        val senderId = intent.getStringExtra("senderId") ?: intent.getStringExtra("userId")

        val target = DeepLinkTarget(
            destination = destination,
            groupId = groupId,
            latitude = latitude,
            longitude = longitude,
            senderId = senderId
        )
        repository.setDeepLinkTarget(target)
    }
}

@Composable
fun FamilyRadarApp(repository: FirebaseRepository) {
    val currentUser by repository.currentUserState.collectAsState()
    val userGroups by repository.userGroupsState.collectAsState()
    val deepLinkTarget by repository.deepLinkTarget.collectAsState()
    val isChoosingGroup by repository.isChoosingGroup.collectAsState()

    var currentScreen by remember { mutableStateOf(AppScreen.MAIN_RADAR) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    // Synchronize screen state with auth, group & deep link state
    LaunchedEffect(currentUser, userGroups, deepLinkTarget, isChoosingGroup) {
        if (currentUser == null) {
            currentScreen = AppScreen.AUTH
            return@LaunchedEffect
        }

        // L'utente ha premuto "cambia gruppo": deve restare sulla schermata di
        // scelta finche' non ne seleziona uno. Senza questo controllo il ramo di
        // auto-selezione qui sotto lo rispediva dentro al primo gruppo attivo,
        // ed era meta' del motivo per cui il pulsante sembrava non funzionare.
        if (isChoosingGroup) {
            currentScreen = AppScreen.GROUP_SELECT
            return@LaunchedEffect
        }

        val target = deepLinkTarget
        val activeGroups = userGroups.filter { it.userMembershipStatus == "ACTIVE" }

        if (target?.groupId != null && activeGroups.any { it.id == target.groupId }) {
            repository.selectGroup(target.groupId)
            currentScreen = AppScreen.MAIN_RADAR
            return@LaunchedEffect
        }

        val currentGid = currentUser?.currentGroupId
        val currentGroupActive = activeGroups.find { it.id == currentGid }
        currentScreen = when {
            currentGroupActive != null -> AppScreen.MAIN_RADAR

            // Con UN SOLO gruppo entrare da soli e' comodo e non c'e' scelta da
            // fare. Con piu' gruppi no: prima si prendeva `activeGroups.first()`,
            // cioe' un gruppo qualunque nell'ordine in cui era arrivato da
            // Firestore. Dopo un logout e un nuovo accesso era proprio questo a
            // far entrare nel gruppo sbagliato, o a lasciare la UI in bilico
            // mentre l'elenco si popolava. Se la scelta non e' ovvia, la fa
            // l'utente.
            currentGid.isNullOrBlank() && activeGroups.size == 1 -> {
                repository.selectGroup(activeGroups.first().id)
                AppScreen.MAIN_RADAR
            }

            else -> AppScreen.GROUP_SELECT
        }
    }

    // Permission request launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // ACTIVITY_RECOGNITION puo' essere stato appena concesso: al primo
        // tentativo di registrazione non c'era e le transizioni erano state
        // saltate, quindi si riprova ora.
        repository.ensureMotionSensing()
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect("updateCheck") {
        // checkDetailed e non check: quest'ultimo restituisce null sia quando si e'
        // gia' aggiornati sia quando la rete non risponde, e all'avvio le due cose
        // erano indistinguibili — sembrava che il controllo non funzionasse piu'.
        // In caso di errore non si mostra nulla (un avviso a ogni avvio offline
        // sarebbe fastidioso), ma il motivo resta nel log.
        //
        // E si riprova: questo controllo parte all'apertura dell'app, quando la
        // rete puo' non essere ancora pronta — soprattutto sui dispositivi con
        // gestione energetica aggressiva. Con un solo tentativo un errore di rete
        // significava nessun avviso di aggiornamento per tutta la sessione.
        var attempt = 0
        while (attempt < UPDATE_CHECK_ATTEMPTS) {
            when (val result = AppUpdater.checkDetailed()) {
                is CheckResult.Available -> {
                    val dismissed = context.getSharedPreferences("family_radar_settings_prefs", android.content.Context.MODE_PRIVATE)
                        .getInt("dismissed_update_version_code", 0)
                    if (result.info.versionCode != dismissed) {
                        updateInfo = result.info
                    }
                    return@LaunchedEffect
                }
                CheckResult.UpToDate -> {
                    android.util.Log.d(
                        "AppUpdater",
                        "Nessun aggiornamento: installata ${BuildConfig.VERSION_CODE}"
                    )
                    return@LaunchedEffect
                }
                CheckResult.NetworkError -> {
                    attempt++
                    android.util.Log.w(
                        "AppUpdater",
                        "Controllo aggiornamenti non riuscito (tentativo $attempt di $UPDATE_CHECK_ATTEMPTS)"
                    )
                    if (attempt < UPDATE_CHECK_ATTEMPTS) {
                        kotlinx.coroutines.delay(UPDATE_CHECK_RETRY_DELAY_MS * attempt)
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Serve al rilevamento automatico dei viaggi: senza, Android non manda le
        // transizioni di attivita' e si ripiega sul controllo a intervalli.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsToRequest.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        permissionsLauncher.launch(permissionsToRequest.toTypedArray())

        // Richiesta automatica esenzione risparmio energetico (stile WhatsApp)
        try {
            val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                if (!isIgnoring) {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            }
        } catch (_: Exception) {}
    }

    updateInfo?.let { info ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { updateInfo = null }) {
            androidx.compose.material3.Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = RadarDark.Bg),
                border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.CardBorder)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(RadarDark.AccentLight.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = RadarDark.AccentLight,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Text(
                        text = "È disponibile la versione ${info.versionName}.\nScaricala e installala ora.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RadarDark.TextMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                context.getSharedPreferences("family_radar_settings_prefs", android.content.Context.MODE_PRIVATE)
                                    .edit().putInt("dismissed_update_version_code", info.versionCode).apply()
                                updateInfo = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.SurfaceBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RadarDark.TextPrimary)
                        ) { Text("Dopo") }
                        Button(
                            onClick = {
                                AppUpdater.downloadAndInstall(context, info.apkUrl)
                                updateInfo = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = RadarDark.Accent, contentColor = Color.White)
                        ) { Text("Aggiorna") }
                    }
                }
            }
        }
    }

    Crossfade(targetState = currentScreen, label = "screen_crossfade") { screen ->
        when (screen) {
            AppScreen.AUTH -> {
                AuthScreen(
                    repository = repository,
                    onAuthSuccess = {
                        val activeGroups = repository.userGroupsState.value
                            .filter { it.userMembershipStatus == "ACTIVE" }
                        currentScreen = when {
                            activeGroups.size == 1 -> {
                                repository.selectGroup(activeGroups.first().id)
                                AppScreen.MAIN_RADAR
                            }
                            else -> AppScreen.GROUP_SELECT
                        }
                    }
                )
            }
            AppScreen.GROUP_SELECT -> {
                GroupSelectScreen(
                    repository = repository,
                    onGroupSelected = { group ->
                        repository.selectGroup(group.id)
                        currentScreen = AppScreen.MAIN_RADAR
                    }
                )
            }
            AppScreen.MAIN_RADAR -> {
                MainRadarScreen(
                    repository = repository,
                    onSwitchGroup = {
                        repository.clearCurrentGroupSelection()
                        currentScreen = AppScreen.GROUP_SELECT
                    }
                )
            }
        }
    }
}

