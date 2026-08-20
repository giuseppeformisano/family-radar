@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.screens.groups

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
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
import kotlinx.coroutines.delay
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

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GroupData>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var codePreviewGroup by remember { mutableStateOf<GroupData?>(null) }
    var codePreviewLoading by remember { mutableStateOf(false) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        isSearching = true
        searchResults = repository.searchGroupsByName(searchQuery)
        isSearching = false
    }

    val errInvalidJoinCode = stringResource(R.string.err_invalid_join_code)

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
                text = { Text(stringResource(R.string.action_new_group)) },
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
                            text = stringResource(
                                R.string.greeting_user,
                                currentUser?.displayName ?: stringResource(R.string.label_user_fallback)
                            ),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.group_select_subtitle),
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
                            contentDescription = stringResource(R.string.action_sign_out),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ---- Cerca gruppo ----
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_group_hint)) },
                    leadingIcon = {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                        }
                    },
                    trailingIcon = if (searchQuery.isNotBlank()) {
                        { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = null) } }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.md),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- Risultati ricerca ----
            if (searchResults.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.search_results_title),
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                }
                items(searchResults, key = { "search_${it.id}" }) { group ->
                    val userMembership = userGroups.find { it.id == group.id }
                    val memberStatus = userMembership?.userMembershipStatus
                    SearchResultCard(
                        group = group,
                        memberStatus = memberStatus,
                        onJoin = {
                            coroutineScope.launch {
                                isSubmitting = true
                                val result = repository.joinGroupByCode(group.joinCode)
                                isSubmitting = false
                                if (result.isSuccess) {
                                    repository.userGroupsState.value
                                        .find { it.id == repository.currentUserState.value?.currentGroupId }
                                        ?.let(onGroupSelected)
                                }
                            }
                        }
                    )
                }
            } else if (searchQuery.isNotBlank() && !isSearching) {
                item {
                    Text(
                        text = stringResource(R.string.no_search_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
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
                                text = stringResource(R.string.join_code_prompt),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.join_code_hint),
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
                        title = stringResource(R.string.section_your_groups),
                        subtitle = stringResource(R.string.group_count_total, userGroups.size),
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
                        title = stringResource(R.string.empty_groups_title),
                        description = stringResource(R.string.empty_groups_body),
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
                        val preview = repository.getGroupPreviewByCode(joinCodeInput.trim())
                        isSubmitting = false
                        if (preview != null) {
                            codePreviewGroup = preview
                            showJoinDialog = false
                        } else {
                            errorMessage = errInvalidJoinCode
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

    codePreviewGroup?.let { group ->
        GroupCodePreviewDialog(
            group = group,
            isLoading = codePreviewLoading,
            onConfirm = {
                codePreviewLoading = true
                coroutineScope.launch {
                    val result = repository.joinGroupByCode(group.joinCode)
                    codePreviewLoading = false
                    codePreviewGroup = null
                    if (result.isSuccess) {
                        repository.userGroupsState.value
                            .find { it.id == repository.currentUserState.value?.currentGroupId }
                            ?.let(onGroupSelected)
                    }
                }
            },
            onDismiss = { codePreviewGroup = null }
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
                // L'immagine del gruppo era gia' letta dal repository ma qui non
                // veniva mai guardata: la card mostrava sempre l'iniziale, quindi
                // cambiare la foto non si vedeva nell'elenco.
                val groupBitmap = remember(group.photoBase64) {
                    ImageUtils.base64ToBitmap(group.photoBase64.ifBlank { null })
                }
                when {
                    isPending -> Icon(
                        Icons.Default.HourglassTop,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(Sizes.iconLg)
                    )

                    groupBitmap != null -> Image(
                        bitmap = groupBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    else -> Text(
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
                            text = stringResource(R.string.status_pending),
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        )
                        isCurrent -> RadarBadge(stringResource(R.string.status_active))
                    }
                }

                Text(
                    text = when {
                        isPending -> stringResource(R.string.status_pending_desc)
                        group.description.isNotBlank() -> group.description
                        else -> stringResource(R.string.label_no_description)
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
                    Toast.makeText(context, context.getString(R.string.toast_image_load_error), Toast.LENGTH_SHORT).show()
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
                text = stringResource(R.string.dialog_create_group_title),
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
                    text = stringResource(R.string.dialog_create_group_subtitle),
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
                            if (photoBitmap != null) stringResource(R.string.action_change_image)
                            else stringResource(R.string.action_add_image),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.label_group_name)) },
                    placeholder = { Text(stringResource(R.string.placeholder_group_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("group_name_input")
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.label_group_description)) },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.label_who_can_join),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                AccessPolicyOption(
                    selected = requiresApproval,
                    title = stringResource(R.string.join_mode_approval),
                    description = stringResource(R.string.join_mode_approval_desc),
                    onClick = { onRequiresApprovalChange(true) }
                )
                AccessPolicyOption(
                    selected = !requiresApproval,
                    title = stringResource(R.string.join_mode_direct),
                    description = stringResource(R.string.join_mode_direct_desc),
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
                    Text(stringResource(R.string.action_create))
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                shape = RoundedCornerShape(Radius.sm)
            ) { Text(stringResource(R.string.action_cancel)) }
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
                text = stringResource(R.string.dialog_join_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = stringResource(R.string.dialog_join_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text(stringResource(R.string.label_invite_code)) },
                    placeholder = { Text(stringResource(R.string.placeholder_invite_code)) },
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
                    Text(stringResource(R.string.action_send_request))
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                shape = RoundedCornerShape(Radius.sm)
            ) { Text(stringResource(R.string.action_close)) }
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
                text = stringResource(R.string.dialog_pending_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = stringResource(R.string.dialog_pending_body, groupName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                InfoBanner(
                    text = stringResource(R.string.dialog_pending_note),
                    icon = Icons.Default.Schedule,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    accentColor = MaterialTheme.colorScheme.tertiary
                )
            }
        },
        confirmButton = {
            Button(onClick = onAcknowledge, shape = RoundedCornerShape(Radius.sm)) {
                Text(stringResource(R.string.action_got_it))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancelRequest,
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text(stringResource(R.string.action_cancel_request)) }
        }
    )
}

@Composable
private fun SearchResultCard(
    group: GroupData,
    memberStatus: String?,
    onJoin: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
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
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = group.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "G",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (group.description.isNotBlank()) {
                    Text(
                        text = group.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (group.memberCount > 0) {
                    Text(
                        text = stringResource(R.string.group_members_count, group.memberCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when (memberStatus) {
                "ACTIVE" -> OutlinedButton(
                    onClick = {},
                    enabled = false,
                    shape = RoundedCornerShape(Radius.sm)
                ) { Text(stringResource(R.string.already_member)) }
                "PENDING" -> OutlinedButton(
                    onClick = {},
                    enabled = false,
                    shape = RoundedCornerShape(Radius.sm)
                ) { Text(stringResource(R.string.join_request_sent)) }
                else -> Button(
                    onClick = onJoin,
                    shape = RoundedCornerShape(Radius.sm)
                ) { Text(stringResource(R.string.btn_join_group)) }
            }
        }
    }
}

@Composable
private fun GroupCodePreviewDialog(
    group: GroupData,
    isLoading: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        shape = RoundedCornerShape(Radius.xl),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = { DialogIcon(Icons.Default.Groups, MaterialTheme.colorScheme.primary) },
        title = {
            Text(
                text = stringResource(R.string.join_code_preview_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (group.description.isNotBlank()) {
                    Text(
                        text = group.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (group.memberCount > 0) {
                    Text(
                        text = stringResource(R.string.group_members_count, group.memberCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading,
                shape = RoundedCornerShape(Radius.sm)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.group_preview_confirm))
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !isLoading,
                shape = RoundedCornerShape(Radius.sm)
            ) { Text(stringResource(R.string.action_cancel)) }
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
