package com.example.ui.screens.groups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.GroupData
import com.example.repository.FirebaseRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSelectScreen(
    repository: FirebaseRepository,
    onGroupSelected: (GroupData) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val userGroups by repository.userGroupsState.collectAsState()
    val currentUser by repository.currentUserState.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var newGroupDesc by remember { mutableStateOf("") }
    var newGroupRequiresApproval by remember { mutableStateOf(true) }
    var pendingGroupInfoDialog by remember { mutableStateOf<GroupData?>(null) }
    var joinCodeInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    // Real-time automatic transition: as soon as member is approved or a group becomes active, navigate!
    LaunchedEffect(currentUser?.currentGroupId, userGroups) {
        val currentGid = currentUser?.currentGroupId
        if (!currentGid.isNullOrBlank()) {
            val activeGroup = userGroups.find { it.id == currentGid && it.userMembershipStatus == "ACTIVE" }
            if (activeGroup != null) {
                showJoinDialog = false
                showCreateDialog = false
                pendingGroupInfoDialog = null
                onGroupSelected(activeGroup)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "I Tuoi Gruppi Radar",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            "Bentornato, ${currentUser?.displayName ?: "Utente"}",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                },
                actions = {
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
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    newGroupName = ""
                    newGroupDesc = ""
                    newGroupRequiresApproval = true
                    showCreateDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Nuovo Gruppo") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("create_group_fab")
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Quick Action Card: Join with Code
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Hai un codice invito?",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                        Text(
                            "Unisciti al radar di familiari o amici inserendo il codice di 6 caratteri.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            joinCodeInput = ""
                            errorMessage = null
                            infoMessage = null
                            showJoinDialog = true
                        },
                        modifier = Modifier.testTag("join_with_code_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Unisciti")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                "I tuoi gruppi:",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            if (userGroups.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.GroupAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Text(
                            "Nessun gruppo",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Crea un nuovo gruppo o inserisci un codice invito per iniziare a condividere la posizione in tempo reale.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(userGroups, key = { it.id }) { group ->
                        val isCurrent = group.id == currentUser?.currentGroupId
                        val isPending = group.userMembershipStatus == "PENDING"
                        GroupItemCard(
                            group = group,
                            isCurrent = isCurrent,
                            isPending = isPending,
                            onSelect = {
                                if (isPending) {
                                    pendingGroupInfoDialog = group
                                } else {
                                    repository.selectGroup(group.id)
                                    onGroupSelected(group)
                                }
                            }
                        )
                    }
                }
            }
        }

        // ================== CREATE GROUP DIALOG ==================
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (!isSubmitting) showCreateDialog = false
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.GroupAdd,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                title = {
                    Text(
                        "Crea Nuovo Gruppo",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Crea una stanza privata e sicura per condividere posizione e messaggi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = newGroupName,
                            onValueChange = { newGroupName = it },
                            label = { Text("Nome Gruppo (es. Famiglia)") },
                            placeholder = { Text("es. Famiglia Rossi") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("group_name_input")
                        )
                        OutlinedTextField(
                            value = newGroupDesc,
                            onValueChange = { newGroupDesc = it },
                            label = { Text("Descrizione (facoltativa)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Politica di Accesso:",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (newGroupRequiresApproval) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { newGroupRequiresApproval = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = newGroupRequiresApproval,
                                    onClick = { newGroupRequiresApproval = true }
                                )
                                Column {
                                    Text(
                                        "Approvazione richiesta (Consigliato)",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        "L'amministratore deve approvare ogni richiesta di accesso con codice.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (!newGroupRequiresApproval) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { newGroupRequiresApproval = false }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = !newGroupRequiresApproval,
                                    onClick = { newGroupRequiresApproval = false }
                                )
                                Column {
                                    Text(
                                        "Accesso diretto senza approvazione",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        "Chiunque abbia il codice invito entra immediatamente nel gruppo.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newGroupName.isNotBlank()) {
                                isSubmitting = true
                                coroutineScope.launch {
                                    val result = repository.createGroup(
                                        newGroupName.trim(),
                                        newGroupDesc.trim(),
                                        newGroupRequiresApproval
                                    )
                                    isSubmitting = false
                                    showCreateDialog = false
                                    if (result.isSuccess) {
                                        onGroupSelected(result.getOrThrow())
                                    }
                                }
                            }
                        },
                        enabled = newGroupName.isNotBlank() && !isSubmitting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("confirm_create_group_button")
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Crea Gruppo")
                        }
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showCreateDialog = false },
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSubmitting
                    ) {
                        Text("Annulla")
                    }
                }
            )
        }

        // ================== PENDING GROUP INFO DIALOG ==================
        pendingGroupInfoDialog?.let { group ->
            AlertDialog(
                onDismissRequest = { pendingGroupInfoDialog = null },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.HourglassTop,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                },
                title = {
                    Text(
                        "Richiesta in Sospeso",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Hai richiesto di accedere al gruppo \"${group.name}\".",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    "In attesa di approvazione dall'amministratore del gruppo. L'accesso al radar si attiverà automaticamente appena sarai confermato.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { pendingGroupInfoDialog = null },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Ho capito")
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                repository.leaveGroup(group.id)
                                pendingGroupInfoDialog = null
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Annulla richiesta")
                    }
                }
            )
        }

        // ================== JOIN GROUP DIALOG ==================
        if (showJoinDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (!isSubmitting) {
                        showJoinDialog = false
                        errorMessage = null
                        infoMessage = null
                    }
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                },
                title = {
                    Text(
                        "Unisciti con Codice",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Inserisci il codice di 6 caratteri generato dal proprietario del gruppo:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = joinCodeInput,
                            onValueChange = {
                                joinCodeInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6)
                                errorMessage = null
                            },
                            label = { Text("Codice Invito") },
                            placeholder = { Text("es. FAM982") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("join_code_input")
                        )

                        AnimatedVisibility(visible = errorMessage != null, enter = fadeIn(), exit = fadeOut()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    Text(
                                        errorMessage ?: "",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        AnimatedVisibility(visible = infoMessage != null, enter = fadeIn(), exit = fadeOut()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Column {
                                        Text(
                                            "In attesa di approvazione...",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            "La schermata si sbloccherà automaticamente non appena l'amministratore approverà la richiesta.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (joinCodeInput.isNotBlank()) {
                                isSubmitting = true
                                errorMessage = null
                                infoMessage = null
                                coroutineScope.launch {
                                    val result = repository.joinGroupByCode(joinCodeInput.trim())
                                    isSubmitting = false
                                    if (result.isSuccess) {
                                        showJoinDialog = false
                                        val activeGroup = repository.userGroupsState.value.find { it.id == repository.currentUserState.value?.currentGroupId }
                                        if (activeGroup != null) {
                                            onGroupSelected(activeGroup)
                                        }
                                    } else {
                                        val msg = result.exceptionOrNull()?.message ?: "Codice non valido o gruppo inesistente"
                                        if (msg.contains("approvazione") || msg.contains("inviata")) {
                                            infoMessage = msg
                                        } else {
                                            errorMessage = msg
                                        }
                                    }
                                }
                            }
                        },
                        enabled = joinCodeInput.isNotBlank() && !isSubmitting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("confirm_join_group_button")
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Text("Invia Richiesta")
                        }
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            showJoinDialog = false
                            errorMessage = null
                            infoMessage = null
                        },
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSubmitting
                    ) {
                        Text("Chiudi")
                    }
                }
            )
        }
    }
}

@Composable
private fun GroupItemCard(
    group: GroupData,
    isCurrent: Boolean,
    isPending: Boolean = false,
    onSelect: () -> Unit
) {
    val cardBg = when {
        isPending -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
        isCurrent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surface
    }

    val borderStroke = when {
        isPending -> androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f))
        isCurrent -> androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else -> null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("group_card_${group.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = borderStroke
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (isPending) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                if (isPending) {
                    Icon(
                        Icons.Default.HourglassTop,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        text = group.name.firstOrNull()?.uppercaseChar()?.toString() ?: "G",
                        style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (isCurrent && !isPending) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                "ATTIVO",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1
                            )
                        }
                    } else if (isPending) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiary
                        ) {
                            Text(
                                "IN ATTESA",
                                color = MaterialTheme.colorScheme.onTertiary,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1
                            )
                        }
                    }
                }
                if (isPending) {
                    Text(
                        text = "Richiesta inviata. In attesa di approvazione admin.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                } else if (group.description.isNotBlank()) {
                    Text(
                        text = group.description,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Codice: ${group.joinCode}",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            }

            Icon(
                if (isPending) Icons.Default.Info else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (isPending) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
