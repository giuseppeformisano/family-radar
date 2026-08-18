@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.screens.groups

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.GroupData
import com.example.repository.FirebaseRepository
import com.example.ui.components.EmptyState
import com.example.ui.components.GroupPhotoPicker
import com.example.ui.components.InfoBanner
import com.example.ui.components.RadarBadge
import com.example.ui.components.SectionHeader
import com.example.ui.theme.RadarTheme
import com.example.ui.theme.Radius
import com.example.ui.theme.Sizes
import com.example.ui.theme.Spacing
import com.example.util.ImageUtils
import kotlinx.coroutines.launch

@Composable
fun GroupSelectScreen(
    repository: FirebaseRepository,
    onGroupSelected: (GroupData) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val userGroups by repository.userGroupsState.collectAsState()
    val currentUser by repository.currentUserState.collectAsState()
    val gradients = RadarTheme.palette.gradients

    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var newGroupDesc by remember { mutableStateOf("") }
    var newGroupRequiresApproval by remember { mutableStateOf(true) }
    var newGroupPhotoBase64 by remember { mutableStateOf<String?>(null) }
    var pendingGroupInfoDialog by remember { mutableStateOf<GroupData?>(null) }
    var joinCodeInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    // Non appena l'admin approva, il listener del repository aggiorna lo stato
    // e la schermata passa da sola al radar: nessun refresh manuale.
    LaunchedEffect(currentUser?.currentGroupId, userGroups) {
        val currentGid = currentUser?.currentGroupId
        if (!currentGid.isNullOrBlank()) {
            val activeGroup = userGroups.find {
                it.id == currentGid && it.userMembershipStatus == "ACTIVE"
            }
            if (activeGroup != null) {
                showJoinDialog = false
                showCreateDialog = false
                pendingGroupInfoDialog = null
                onGroupSelected(activeGroup)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    newGroupName = ""
                    newGroupDesc = ""
                    newGroupRequiresApproval = true
                    showCreateDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Nuovo gruppo") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(Radius.md),
                modifier = Modifier.testTag("create_group_fab")
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(gradients.heroTop, gradients.heroBottom),
                        endY = 700f
                    )
                )
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.sm,
                bottom = 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // ---- Intestazione ----
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ciao, ${currentUser?.displayName ?: "utente"}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Scegli un gruppo da seguire sul radar",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { repository.signOut() },
                        modifier = Modifier.testTag("logout_button")
                    ) {
                        Icon(
                            Icons.Default.Logout,
                            contentDescription = "Disconnetti",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ---- Ingresso con codice ----
            item {
                Surface(
                    onClick = {
                        joinCodeInput = ""
                        errorMessage = null
                        infoMessage = null
                        showJoinDialog = true
                    },
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("join_with_code_button")
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(Sizes.avatarMd)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(Sizes.iconMd)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Hai un codice invito?",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Sei caratteri e sei dentro al radar del gruppo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // ---- Elenco gruppi ----
            if (userGroups.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "I tuoi gruppi",
                        subtitle = "${userGroups.size} in totale",
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                }

                items(userGroups, key = { it.id }) { group ->
                    GroupCard(
                        group = group,
                        isCurrent = group.id == currentUser?.currentGroupId,
                        isPending = group.userMembershipStatus == "PENDING",
                        onSelect = {
                            if (group.userMembershipStatus == "PENDING") {
                                pendingGroupInfoDialog = group
                            } else {
                                repository.selectGroup(group.id)
                                onGroupSelected(group)
                            }
                        }
                    )
                }
            } else {
                item {
                    EmptyState(
                        title = "Nessun gruppo",
                        description = "Crea un gruppo o inserisci un codice invito per " +
                            "iniziare a condividere la posizione in tempo reale.",
                        icon = Icons.Default.GroupAdd,
                        lottieAsset = "empty_groups",
                        modifier = Modifier.padding(top = Spacing.xxl)
                    )
                }
            }
        }
    }

    // ======================= DIALOG =======================

    if (showCreateDialog) {
        CreateGroupDialog(
            name = newGroupName,
            onNameChange = { newGroupName = it },
            description = newGroupDesc,
            onDescriptionChange = { newGroupDesc = it },
            requiresApproval = newGroupRequiresApproval,
            onRequiresApprovalChange = { newGroupRequiresApproval = it },
            photoBase64 = newGroupPhotoBase64,
            onPhotoChange = { newGroupPhotoBase64 = it },
            isSubmitting = isSubmitting,
            onConfirm = {
                if (newGroupName.isNotBlank()) {
                    isSubmitting = true
                    coroutineScope.launch {
                        val result = repository.createGroup(
                            newGroupName.trim(),
                            newGroupDesc.trim(),
                            newGroupRequiresApproval,
                            newGroupPhotoBase64 ?: ""
                        )
                        isSubmitting = false
                        showCreateDialog = false
                        result.getOrNull()?.let(onGroupSelected)
                    }
                }
            },
            onDismiss = { if (!isSubmitting) showCreateDialog = false }
        )
    }

    if (showJoinDialog) {
        JoinGroupDialog(
            code = joinCodeInput,
            onCodeChange = {
                joinCodeInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6)
                errorMessage = null
            },
            errorMessage = errorMessage,
            infoMessage = infoMessage,
            isSubmitting = isSubmitting,
            onConfirm = {
                if (joinCodeInput.isNotBlank()) {
                    isSubmitting = true
                    errorMessage = null
                    infoMessage = null
                    coroutineScope.launch {
                        val result = repository.joinGroupByCode(joinCodeInput.trim())
                        isSubmitting = false
                        if (result.isSuccess) {
                            showJoinDialog = false
                            repository.userGroupsState.value
                                .find { it.id == repository.currentUserState.value?.currentGroupId }
                                ?.let(onGroupSelected)
                        } else {
                            val msg = result.exceptionOrNull()?.message
                                ?: "Codice non valido o gruppo inesistente"
                            // Il repository segnala l'attesa di approvazione come errore:
                            // qui lo distinguiamo per mostrarlo come informazione, non come fallimento.
                            if (msg.contains("approvazione") || msg.contains("inviata")) {
                                infoMessage = msg
                            } else {
                                errorMessage = msg
                            }
                        }
                    }
                }
            },
            onDismiss = {
                if (!isSubmitting) {
                    showJoinDialog = false
                    errorMessage = null
                    infoMessage = null
                }
            }
        )
    }

    pendingGroupInfoDialog?.let { group ->
        PendingRequestDialog(
            groupName = group.name,
            onAcknowledge = { pendingGroupInfoDialog = null },
            onCancelRequest = {
                coroutineScope.launch {
                    repository.leaveGroup(group.id)
                    pendingGroupInfoDialog = null
                }
            }
        )
    }
}

// ============================================================================
// CARD GRUPPO
// ============================================================================

@Composable
private fun GroupCard(
    group: GroupData,
    isCurrent: Boolean,
    isPending: Boolean,
    onSelect: () -> Unit
) {
    val accent = when {
        isPending -> MaterialTheme.colorScheme.tertiary
        isCurrent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }

    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (isCurrent) 6.dp else 1.dp,
        border = if (isCurrent) androidx.compose.foundation.BorderStroke(2.dp, accent) else null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("group_card_${group.id}")
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(Sizes.avatarLg)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(
                        Brush.linearGradient(
                            listOf(accent, accent.copy(alpha = 0.65f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isPending) {
                    Icon(
                        Icons.Default.HourglassTop,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(Sizes.iconLg)
                    )
                } else {
                    Text(
                        text = group.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "G",
                        style = MaterialTheme.typography.headlineSmall,
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    when {
                        isPending -> RadarBadge(
                            text = "In attesa",
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        )
                        isCurrent -> RadarBadge("Attivo")
                    }
                }

                Text(
                    text = when {
                        isPending -> "Richiesta inviata, in attesa dell'amministratore"
                        group.description.isNotBlank() -> group.description
                        else -> "Nessuna descrizione"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPending) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(Spacing.xs))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Icon(
                        Icons.Default.Tag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Sizes.iconSm)
                    )
                    Text(
                        text = group.joinCode,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Icon(
                imageVector = if (isPending) Icons.Default.Info else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (isPending) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// DIALOG
// ============================================================================

@Composable
private fun CreateGroupDialog(
    name: String,
    onNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    requiresApproval: Boolean,
    onRequiresApprovalChange: (Boolean) -> Unit,
    photoBase64: String?,
    onPhotoChange: (String?) -> Unit,
    isSubmitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isProcessingImage by remember { mutableStateOf(false) }
    val photoBitmap = remember(photoBase64) { ImageUtils.base64ToBitmap(photoBase64) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessingImage = true
            scope.launch {
                val base64 = ImageUtils.uriToBase64(context, uri, maxDimension = 300, quality = 80)
                if (base64 == null) {
                    Toast.makeText(context, "Errore nel caricamento immagine", Toast.LENGTH_SHORT).show()
                }
                onPhotoChange(base64)
                isProcessingImage = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.xl),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            DialogIcon(Icons.Default.GroupAdd, MaterialTheme.colorScheme.primary)
        },
        title = {
            Text(
                text = "Crea un gruppo",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Una stanza privata per condividere posizione e messaggi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    GroupPhotoPicker(
                        bitmap = photoBitmap,
                        fallbackLetter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "G",
                        isLoading = isProcessingImage,
                        onClick = { photoPickerLauncher.launch("image/*") },
                        size = Sizes.avatarLg
                    )
                    TextButton(
                        onClick = { photoPickerLauncher.launch("image/*") },
                        enabled = !isProcessingImage && !isSubmitting
                    ) {
                        Text(
                            if (photoBitmap != null) "Cambia immagine" else "Aggiungi immagine",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Nome del gruppo") },
                    placeholder = { Text("es. Famiglia Rossi") },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_name_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text("Descrizione (facoltativa)") },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Chi può entrare",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                AccessPolicyOption(
                    selected = requiresApproval,
                    title = "Con approvazione",
                    description = "Approvi tu ogni richiesta. Consigliato.",
                    onClick = { onRequiresApprovalChange(true) }
                )
                AccessPolicyOption(
                    selected = !requiresApproval,
                    title = "Accesso diretto",
                    description = "Chi ha il codice entra subito.",
                    onClick = { onRequiresApprovalChange(false) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank() && !isSubmitting,
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier.testTag("confirm_create_group_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Crea")
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                shape = RoundedCornerShape(Radius.sm)
            ) { Text("Annulla") }
        }
    )
}

@Composable
private fun AccessPolicyOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
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
        }
    }
}

@Composable
private fun JoinGroupDialog(
    code: String,
    onCodeChange: (String) -> Unit,
    errorMessage: String?,
    infoMessage: String?,
    isSubmitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.xl),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { DialogIcon(Icons.Default.VpnKey, MaterialTheme.colorScheme.secondary) },
        title = {
            Text(
                text = "Unisciti con il codice",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = "Inserisci i sei caratteri ricevuti dal proprietario del gruppo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text("Codice invito") },
                    placeholder = { Text("FAM982") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        textAlign = TextAlign.Center
                    ),
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("join_code_input")
                )

                AnimatedVisibility(
                    visible = errorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    InfoBanner(
                        text = errorMessage.orEmpty(),
                        icon = Icons.Default.ErrorOutline
                    )
                }

                AnimatedVisibility(
                    visible = infoMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    InfoBanner(
                        text = infoMessage.orEmpty(),
                        icon = Icons.Default.HourglassTop,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        trailing = {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = code.isNotBlank() && !isSubmitting,
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier.testTag("confirm_join_group_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Invia richiesta")
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                shape = RoundedCornerShape(Radius.sm)
            ) { Text("Chiudi") }
        }
    )
}

@Composable
private fun PendingRequestDialog(
    groupName: String,
    onAcknowledge: () -> Unit,
    onCancelRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        shape = RoundedCornerShape(Radius.xl),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { DialogIcon(Icons.Default.HourglassTop, MaterialTheme.colorScheme.tertiary) },
        title = {
            Text(
                text = "Richiesta in sospeso",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = "Hai chiesto di entrare in \"$groupName\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                InfoBanner(
                    text = "Appena l'amministratore approva, il radar si sblocca da solo. " +
                        "Non serve riaprire l'app.",
                    icon = Icons.Default.Schedule,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    accentColor = MaterialTheme.colorScheme.tertiary
                )
            }
        },
        confirmButton = {
            Button(onClick = onAcknowledge, shape = RoundedCornerShape(Radius.sm)) {
                Text("Ho capito")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancelRequest,
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Annulla richiesta") }
        }
    )
}

@Composable
private fun DialogIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color
) {
    Box(
        modifier = Modifier
            .size(Sizes.avatarLg)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Sizes.iconLg))
    }
}
