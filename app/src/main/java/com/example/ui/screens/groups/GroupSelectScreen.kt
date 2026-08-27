@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.screens.groups

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
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
import com.example.ui.components.*
import com.example.ui.theme.RadarDark
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
    var newGroupIsPublic by remember { mutableStateOf(false) }
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

    val searchError by repository.lastSearchError.collectAsState()

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
        containerColor = Color.Black,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    newGroupName = ""
                    newGroupDesc = ""
                    newGroupRequiresApproval = true
                    newGroupIsPublic = false
                    showCreateDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null, tint = Color.White) },
                text = {
                    Text(
                        stringResource(R.string.action_new_group),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        color = Color.White
                    )
                },
                containerColor = Color(0xFF4F46E5),
                contentColor = Color.White,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.testTag("create_group_fab")
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
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
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            ),
                            color = Color(0xFFF2F2F7),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.group_select_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFA1A1AA)
                        )
                    }
                    IconButton(
                        onClick = { repository.signOut() },
                        modifier = Modifier.testTag("logout_button")
                    ) {
                        Icon(
                            Icons.Default.Logout,
                            contentDescription = stringResource(R.string.action_sign_out),
                            tint = Color(0xFFF43F5E)
                        )
                    }
                }
            }

            // ---- Cerca gruppo ----
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_group_hint), color = Color(0xFFA1A1AA)) },
                    leadingIcon = {
                        if (isSearching) {
                            RadarProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFFA1A1AA))
                        }
                    },
                    trailingIcon = if (searchQuery.isNotBlank()) {
                        { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = null, tint = Color(0xFFA1A1AA)) } }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0x0A71717A),
                        unfocusedContainerColor = Color(0x0A71717A),
                        focusedBorderColor = Color(0xFF6366F1),
                        unfocusedBorderColor = Color(0x1F71717A),
                        focusedTextColor = Color(0xFFF2F2F7),
                        unfocusedTextColor = Color(0xFFF2F2F7)
                    ),
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
                    Column(modifier = Modifier.padding(top = Spacing.sm)) {
                        Text(
                            text = stringResource(R.string.no_search_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFA1A1AA)
                        )
                        searchError?.let { err ->
                            Text(
                                text = stringResource(R.string.search_error_detail, err),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFF43F5E),
                                modifier = Modifier.padding(top = Spacing.xs)
                            )
                        }
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
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0x0A71717A),
                    border = BorderStroke(1.dp, Color(0x1F71717A)),
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
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF6366F1)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.VpnKey,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(Sizes.iconMd)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.join_code_prompt),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                color = Color(0xFFF2F2F7)
                            )
                            Text(
                                text = stringResource(R.string.join_code_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFA1A1AA)
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Color(0xFFA1A1AA)
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
            isPublic = newGroupIsPublic,
            onIsPublicChange = { newGroupIsPublic = it },
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
                            newGroupPhotoBase64 ?: "",
                            newGroupIsPublic
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
        isPending -> Color(0xFFF59E0B)
        isCurrent -> Color(0xFF6366F1)
        else -> Color(0xFF34D399)
    }

    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        color = Color(0x0A71717A),
        border = BorderStroke(
            if (isCurrent) 1.5.dp else 1.dp,
            if (isCurrent) Color(0xFF6366F1) else Color(0x1F71717A)
        ),
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(accent, accent.copy(alpha = 0.65f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                val groupBitmap = remember(group.photoBase64) {
                    ImageUtils.base64ToBitmap(group.photoBase64.ifBlank { null })
                }
                when {
                    isPending -> Icon(
                        Icons.Default.HourglassTop,
                        contentDescription = null,
                        tint = Color.White,
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
                        color = Color.White
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
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        color = Color(0xFFF2F2F7),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    when {
                        isPending -> RadarBadge(
                            text = stringResource(R.string.status_pending),
                            containerColor = Color(0x26F59E0B),
                            contentColor = Color(0xFFF59E0B)
                        )
                        isCurrent -> RadarBadge(
                            text = stringResource(R.string.status_active),
                            containerColor = Color(0x266366F1),
                            contentColor = Color(0xFF6366F1)
                        )
                    }
                }

                Text(
                    text = when {
                        isPending -> stringResource(R.string.status_pending_desc)
                        group.description.isNotBlank() -> group.description
                        else -> stringResource(R.string.label_no_description)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPending) Color(0xFFF59E0B) else Color(0xFFA1A1AA),
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
                        tint = Color(0xFFA1A1AA),
                        modifier = Modifier.size(Sizes.iconSm)
                    )
                    Text(
                        text = group.joinCode,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFA1A1AA)
                    )
                }
            }

            Icon(
                imageVector = if (isPending) Icons.Default.Info else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (isPending) Color(0xFFF59E0B) else Color(0xFFA1A1AA)
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
    isPublic: Boolean,
    onIsPublicChange: (Boolean) -> Unit,
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
        containerColor = RadarDark.Bg,
        icon = {
            DialogIcon(Icons.Default.GroupAdd, RadarDark.AccentLight)
        },
        title = {
            Text(
                text = stringResource(R.string.dialog_create_group_title),
                style = MaterialTheme.typography.titleLarge,
                color = RadarDark.TextPrimary
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
                    color = RadarDark.TextMuted
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
                        enabled = !isProcessingImage && !isSubmitting,
                        colors = ButtonDefaults.textButtonColors(contentColor = RadarDark.AccentLight)
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
                    color = RadarDark.TextPrimary
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.group_public_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = RadarDark.TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.group_public_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = RadarDark.TextMuted
                        )
                    }
                    Switch(
                        checked = isPublic,
                        onCheckedChange = onIsPublicChange
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank() && !isSubmitting,
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.buttonColors(containerColor = RadarDark.Accent, contentColor = Color.White),
                modifier = Modifier.testTag("confirm_create_group_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f)
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
                shape = RoundedCornerShape(Radius.sm),
                border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.SurfaceBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RadarDark.TextPrimary)
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
        color = RadarDark.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.SurfaceBorder),
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
                    color = RadarDark.TextPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = RadarDark.TextMuted
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
        containerColor = RadarDark.Bg,
        icon = { DialogIcon(Icons.Default.VpnKey, RadarDark.AccentLight) },
        title = {
            Text(
                text = stringResource(R.string.dialog_join_title),
                style = MaterialTheme.typography.titleLarge,
                color = RadarDark.TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = stringResource(R.string.dialog_join_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RadarDark.TextMuted
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text(stringResource(R.string.label_invite_code)) },
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
                        containerColor = RadarDark.Surface,
                        contentColor = RadarDark.TextPrimary,
                        accentColor = RadarDark.AccentLight,
                        trailing = {
                            RadarProgressIndicator(
                                size = 18.dp,
                                strokeWidth = 2.dp,
                                color = RadarDark.AccentLight,
                                trackColor = RadarDark.AccentLight.copy(alpha = 0.2f)
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
                colors = ButtonDefaults.buttonColors(containerColor = RadarDark.Accent, contentColor = Color.White),
                modifier = Modifier.testTag("confirm_join_group_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f)
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
                shape = RoundedCornerShape(Radius.sm),
                border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.SurfaceBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RadarDark.TextPrimary)
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
        containerColor = RadarDark.Bg,
        icon = { DialogIcon(Icons.Default.HourglassTop, RadarDark.AccentLight) },
        title = {
            Text(
                text = stringResource(R.string.dialog_pending_title),
                style = MaterialTheme.typography.titleLarge,
                color = RadarDark.TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = stringResource(R.string.dialog_pending_body, groupName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RadarDark.TextPrimary
                )
                InfoBanner(
                    text = stringResource(R.string.dialog_pending_note),
                    icon = Icons.Default.Schedule,
                    containerColor = RadarDark.Surface,
                    contentColor = RadarDark.TextPrimary,
                    accentColor = RadarDark.AccentLight
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAcknowledge,
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.buttonColors(containerColor = RadarDark.Accent, contentColor = Color.White)
            ) {
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
        containerColor = RadarDark.Bg,
        icon = { DialogIcon(Icons.Default.Groups, RadarDark.AccentLight) },
        title = {
            Text(
                text = stringResource(R.string.join_code_preview_title),
                style = MaterialTheme.typography.titleLarge,
                color = RadarDark.TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = RadarDark.TextPrimary
                )
                if (group.description.isNotBlank()) {
                    Text(
                        text = group.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = RadarDark.TextMuted
                    )
                }
                if (group.memberCount > 0) {
                    Text(
                        text = stringResource(R.string.group_members_count, group.memberCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = RadarDark.TextMuted
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isLoading,
                shape = RoundedCornerShape(Radius.sm),
                colors = ButtonDefaults.buttonColors(containerColor = RadarDark.Accent, contentColor = Color.White)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f)
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
                shape = RoundedCornerShape(Radius.sm),
                border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.SurfaceBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RadarDark.TextPrimary)
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
