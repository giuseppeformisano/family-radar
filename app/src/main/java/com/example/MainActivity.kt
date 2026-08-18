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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemePreferences
import com.example.util.AppUpdater
import com.example.util.UpdateInfo

enum class AppScreen {
    AUTH,
    GROUP_SELECT,
    MAIN_RADAR
}

class MainActivity : ComponentActivity() {

    private lateinit var repository: FirebaseRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ThemePreferences.init(this)
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

            // Primo ingresso con un solo gruppo disponibile: entra da solo.
            activeGroups.isNotEmpty() && currentGid.isNullOrBlank() -> {
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
        // Permissions handled
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect("updateCheck") {
        updateInfo = AppUpdater.check()
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
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            shape = RoundedCornerShape(Radius.xl),
            containerColor = MaterialTheme.colorScheme.surface,
            icon = {
                Box(
                    modifier = Modifier
                        .size(Sizes.avatarLg)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Sizes.iconLg)
                    )
                }
            },
            title = {
                Text(
                    text = "Aggiornamento disponibile",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "È disponibile la versione ${info.versionName}. Scaricala e installala ora.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        AppUpdater.downloadAndInstall(context, info.apkUrl)
                        updateInfo = null
                    },
                    shape = RoundedCornerShape(Radius.sm)
                ) { Text("Aggiornare") }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { updateInfo = null },
                    shape = RoundedCornerShape(Radius.sm)
                ) { Text("Dopo") }
            }
        )
    }

    Crossfade(targetState = currentScreen, label = "screen_crossfade") { screen ->
        when (screen) {
            AppScreen.AUTH -> {
                AuthScreen(
                    repository = repository,
                    onAuthSuccess = {
                        currentScreen = if (repository.userGroupsState.value.isNotEmpty()) {
                            AppScreen.MAIN_RADAR
                        } else {
                            AppScreen.GROUP_SELECT
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

