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

// ============================================================================
// PANNELLO: CHAT
// ============================================================================

@Composable
internal fun ChatPanel(
    messages: List<ChatMessage>,
    currentUserId: String,
    groupId: String,
    repository: FirebaseRepository,
    onImageClick: (Any, String?, Long?) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val strNoPhoto = stringResource(R.string.toast_no_photo)
    val strCameraError = stringResource(R.string.toast_camera_error)
    val strCameraPermNeeded = stringResource(R.string.toast_camera_permission_needed)
    val strPhotoFileError = stringResource(R.string.toast_photo_file_error)
    val strTextCopied = stringResource(R.string.toast_text_copied)

    var inputText by remember { mutableStateOf("") }
    var isUploading by remember { mutableStateOf(false) }
    var pendingChatCamera by remember { mutableStateOf(false) }
    var pendingChatCameraUri by remember { mutableStateOf<Uri?>(null) }
    // Messaggio a cui si sta rispondendo (null = nessuna citazione in corso).
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }

    // Reazioni emoji ai messaggi: prototipo solo-UI, non persistito su Firestore.
    // Mappa messageId -> emoji scelta. Sopravvive allo scroll perche' vive qui in
    // ChatPanel (fuori dai singoli item della LazyColumn) invece che dentro ChatBubble.
    val messageReactions = remember { mutableStateMapOf<String, String>() }

    // Anteprima breve di un messaggio citato: testo, o etichetta del tipo media.
    fun replyPreviewText(m: ChatMessage): String = when {
        m.text.isNotBlank() -> m.text
        m.type == MessageType.IMAGE -> "📷 Foto"
        m.type == MessageType.VOICE -> "🎤 Vocale"
        m.type == MessageType.LOCATION_SHARE -> "📍 Posizione"
        else -> "Messaggio"
    }

    fun sendImage(uri: Uri, caption: String) {
        if (groupId.isBlank()) return
        isUploading = true
        coroutineScope.launch {
            // Qualita' alta: l'immagine va su Cloudinary, non piu' sul documento
            // Firestore da 1 MB, quindi non serve comprimerla in modo aggressivo.
            val res = repository.compressImageToBase64(uri, maxDimension = 2560, quality = 92)
            val base64 = res.getOrNull()
            if (res.isSuccess && !base64.isNullOrBlank()) {
                // Prova upload su Cloudinary; fallback a Base64 inline se fallisce.
                val imageUrl = repository.uploadImageToCloudinary(base64)
                // Se Cloudinary fallisce si ripiega sul Base64 dentro il documento
                // Firestore, che ha il limite di 1 MB: l'alta qualita' (2560/92) lo
                // sforerebbe, quindi per l'inline si ricomprime piu' piccolo.
                val inlineBase64 = if (imageUrl != null) null else
                    repository.compressImageToBase64(uri, maxDimension = 1280, quality = 80)
                        .getOrNull() ?: base64
                isUploading = false
                val reply = replyingTo
                repository.sendMessage(
                    groupId,
                    ChatMessage(
                        text = caption,
                        imageBase64 = inlineBase64,
                        imageUrl = imageUrl,
                        type = MessageType.IMAGE,
                        replyToId = reply?.id ?: "",
                        replyToText = reply?.let { replyPreviewText(it) } ?: "",
                        replyToSender = reply?.senderName ?: ""
                    )
                )
                replyingTo = null
            } else {
                isUploading = false
                Toast.makeText(context, "Errore elaborazione immagine", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val cameraPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { isSuccess ->
        val uri = pendingChatCameraUri
        if (isSuccess && uri != null) {
            sendImage(uri, "Foto scattata in chat")
        } else {
            Toast.makeText(context, strNoPhoto, Toast.LENGTH_SHORT).show()
        }
    }

    val chatCameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && pendingChatCamera) {
            pendingChatCamera = false
            val uri = pendingChatCameraUri ?: ImageUtils.createTempImageUri(context)
            pendingChatCameraUri = uri
            if (uri != null) {
                runCatching { cameraPhotoLauncher.launch(uri) }.onFailure {
                    Toast.makeText(context, strCameraError.format(it.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        } else if (!isGranted) {
            pendingChatCamera = false
            Toast.makeText(context, strCameraPermNeeded, Toast.LENGTH_SHORT).show()
        }
    }

    fun launchChatCameraSafe() {
        val tempUri = ImageUtils.createTempImageUri(context)
        if (tempUri == null) {
            Toast.makeText(context, strPhotoFileError, Toast.LENGTH_SHORT).show()
            return
        }
        pendingChatCameraUri = tempUri
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            runCatching { cameraPhotoLauncher.launch(tempUri) }.onFailure {
                Toast.makeText(context, strCameraError.format(it.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingChatCamera = true
            chatCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) sendImage(uri, "Immagine condivisa") }

    fun sendText() {
        val trimmed = inputText.trim()
        if (trimmed.isNotBlank() && groupId.isNotBlank()) {
            val reply = replyingTo
            repository.sendMessage(
                groupId,
                ChatMessage(
                    text = trimmed,
                    type = MessageType.TEXT,
                    replyToId = reply?.id ?: "",
                    replyToText = reply?.let { replyPreviewText(it) } ?: "",
                    replyToSender = reply?.senderName ?: ""
                )
            )
            inputText = ""
            replyingTo = null
            coroutineScope.launch {
                runCatching { listState.animateScrollToItem(Int.MAX_VALUE) }
            }
        }
    }

    val hiddenMessages by repository.locallyHiddenMessages.collectAsState()
    val allLocations by repository.currentGroupLocations.collectAsState()
    val chatMembers by repository.currentGroupMembers.collectAsState()
    val myLocation = allLocations.find { it.userId == currentUserId }
    val visibleMessages = remember(messages, hiddenMessages) {
        messages.filterNot { it.id in hiddenMessages }
    }
    // Id dell'ultimo mio messaggio: solo sotto quello mostro "visto da…".
    val lastMyMessageId = visibleMessages.lastOrNull { it.senderId == currentUserId }?.id

    val hasMoreChat by repository.hasMoreChat.collectAsState()

    // Scroll intelligente:
    // - primo caricamento -> salta in fondo (istantaneo)
    // - nuovo messaggio in coda (ultimo id cambiato) -> scorri in fondo
    // - storia precedente caricata in cima (size cresce, ultimo id invariato) ->
    //   mantieni la posizione compensando i messaggi aggiunti sopra.
    var prevSize by remember { mutableStateOf(0) }
    var prevLastId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(visibleMessages) {
        if (visibleMessages.isEmpty()) { prevSize = 0; prevLastId = null; return@LaunchedEffect }
        val lastId = visibleMessages.last().id
        val added = visibleMessages.size - prevSize
        when {
            prevSize == 0 -> runCatching { listState.scrollToItem(visibleMessages.size - 1) }
            lastId != prevLastId -> runCatching { listState.animateScrollToItem(visibleMessages.size - 1) }
            added > 0 -> runCatching {
                listState.scrollToItem(
                    listState.firstVisibleItemIndex + added,
                    listState.firstVisibleItemScrollOffset
                )
            }
        }
        prevSize = visibleMessages.size
        prevLastId = lastId
    }

    // Caricamento a blocchi: quando si arriva quasi in cima si chiede il blocco
    // precedente. Il repository e' protetto contro chiamate ripetute.
    val shouldLoadMore by remember {
        derivedStateOf { hasMoreChat && listState.firstVisibleItemIndex <= 2 }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) repository.loadMoreChat()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                })
            }
    ) {
        if (visibleMessages.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.chat_empty_title),
                    description = stringResource(R.string.chat_empty_body),
                    icon = Icons.Default.ChatBubbleOutline,
                    lottieAsset = "empty_chat"
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                itemsIndexed(visibleMessages, key = { _, m -> m.id }) { index, msg ->
                    // Separatore data: appare quando cambia il giorno rispetto al
                    // messaggio precedente (o all'inizio della lista).
                    val prev = visibleMessages.getOrNull(index - 1)
                    if (prev == null || !isSameDay(prev.timestamp, msg.timestamp)) {
                        ChatDateSeparator(msg.timestamp)
                    }
                    val isMine = msg.senderId == currentUserId
                    val readers = if (isMine) {
                        chatMembers
                            .filter { it.userId != currentUserId && it.chatLastReadAt >= msg.timestamp }
                            .map { it.nickname?.takeIf { n -> n.isNotBlank() } ?: it.displayName }
                    } else emptyList()
                    ChatBubble(
                        message = msg,
                        isMe = isMine,
                        onImageClick = onImageClick,
                        groupId = groupId,
                        repository = repository,
                        myLatitude = myLocation?.latitude,
                        myLongitude = myLocation?.longitude,
                        isPending = msg.pending,
                        readerNames = readers,
                        showReadReceipt = msg.id == lastMyMessageId,
                        onReply = { replyingTo = msg },
                        onCopy = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("messaggio", msg.text))
                            Toast.makeText(context, strTextCopied, Toast.LENGTH_SHORT).show()
                        },
                        onDeleteForMe = { repository.deleteMessageForMe(msg.id) },
                        onDeleteForEveryone = { repository.deleteMessageForEveryone(groupId, msg.id) },
                        reaction = messageReactions[msg.id],
                        onReactionSelected = { emoji ->
                            // Toggle: selezionare di nuovo la stessa emoji la rimuove.
                            if (messageReactions[msg.id] == emoji) {
                                messageReactions.remove(msg.id)
                            } else {
                                messageReactions[msg.id] = emoji
                            }
                        }
                    )
                }
            }
        }

        AnimatedVisibility(visible = isUploading) {
            RadarLinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Barra "stai rispondendo a…": mostra l'anteprima del messaggio citato.
        AnimatedVisibility(visible = replyingTo != null) {
            replyingTo?.let { reply ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md),
                    shape = RoundedCornerShape(Radius.sm),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(34.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Rispondi a ${reply.senderName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = replyPreviewText(reply),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { replyingTo = null }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Annulla risposta",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Barra di input stile Floating Capsule
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { launchChatCameraSafe() },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("chat_camera_button")
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = stringResource(R.string.chat_take_photo_desc),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(
                    onClick = { photoPickerLauncher.launch("image/*") },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("attach_photo_button")
                ) {
                    Icon(
                        Icons.Default.AddPhotoAlternate,
                        contentDescription = stringResource(R.string.chat_attach_image_desc),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .testTag("chat_input_field"),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.5.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { sendText() }),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (inputText.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_input_placeholder),
                                    fontSize = 15.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                val canSend = inputText.isNotBlank()
                Surface(
                    onClick = { sendText() },
                    enabled = canSend,
                    shape = CircleShape,
                    color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("send_message_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = stringResource(R.string.chat_send_desc),
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    isMe: Boolean,
    onImageClick: (Any, String?, Long?) -> Unit,
    groupId: String,
    repository: FirebaseRepository,
    myLatitude: Double? = null,
    myLongitude: Double? = null,
    isPending: Boolean = false,
    readerNames: List<String> = emptyList(),
    showReadReceipt: Boolean = false,
    onReply: () -> Unit = {},
    onCopy: () -> Unit = {},
    onDeleteForMe: () -> Unit = {},
    onDeleteForEveryone: () -> Unit = {},
    reaction: String? = null,
    onReactionSelected: (String) -> Unit = {}
) {
    // Messaggio eliminato per tutti: si mostra un segnaposto, niente contenuto.
    if (message.deleted) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(Radius.md),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Icon(
                        Icons.Default.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Sizes.iconSm)
                    )
                    Text(
                        text = "Messaggio eliminato",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            }
        }
        return
    }

    when (message.type) {
        MessageType.GEOFENCE_ALERT -> {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Sizes.iconSm)
                        )
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            return
        }

        MessageType.SOS_ALERT -> {
            Surface(
                shape = RoundedCornerShape(Radius.md),
                color = RadarSemantic.Sos.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, RadarSemantic.Sos),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Icon(
                        Icons.Default.CrisisAlert,
                        contentDescription = null,
                        tint = RadarSemantic.Sos,
                        modifier = Modifier.size(Sizes.iconXl)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.chat_sos_alert_label, message.senderName),
                            style = MaterialTheme.typography.titleSmall,
                            color = RadarSemantic.Sos
                        )
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            return
        }

        else -> Unit
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        if (!isMe) {
            Text(
                text = message.senderName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = Spacing.md, bottom = 3.dp)
            )
        }

        var menuOpen by remember { mutableStateOf(false) }
        Box {
          Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isMe) 18.dp else 6.dp,
                bottomEnd = if (isMe) 6.dp else 18.dp
            ),
            color = if (isMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { menuOpen = true }
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                // Anteprima del messaggio citato (reply).
                if (message.replyToId.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Spacing.xs)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(30.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Column {
                            Text(
                                text = message.replyToSender.ifBlank { "Messaggio" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = message.replyToText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (message.type == MessageType.VOICE) {
                    VoiceMessagePlayer(message = message, isMe = isMe, groupId = groupId, repository = repository)
                }
                val bitmap = remember(message.imageBase64) {
                    ImageUtils.base64ToBitmap(message.imageBase64)
                }
                val imageSource = message.getImageSource()

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Immagine condivisa",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable { message.imageBase64?.let { onImageClick(it, message.senderName.takeIf { n -> n.isNotBlank() }, message.timestamp) } },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(Spacing.xs))
                } else if (imageSource != null) {
                    AsyncImage(
                        model = imageSource,
                        contentDescription = "Immagine condivisa",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable { onImageClick(imageSource, message.senderName.takeIf { it.isNotBlank() }, message.timestamp) },
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }

                val hidePlaceholderCaption = message.text == "Immagine condivisa" ||
                    message.text == "Foto condivisa" ||
                    message.text == "Foto scattata in chat"

                if (message.text.isNotBlank() && !hidePlaceholderCaption) {
                    Text(
                        text = message.text,
                        fontSize = 15.5.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Stato di consegna: solo sull'ultimo mio messaggio per non
                    // riempire ogni bolla di icone. Orologio = in invio,
                    // spunta singola = inviato, doppia blu = letto da qualcuno.
                    if (isMe && showReadReceipt) {
                        val (icon, tint) = when {
                            isPending -> Icons.Default.Schedule to MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            readerNames.isNotEmpty() -> Icons.Default.DoneAll to RadarSemantic.Online
                            else -> Icons.Default.Done to MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        }
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
          }

          DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
              // Riga emoji in cima, stessa larghezza del menu.
              Row(
                  modifier = Modifier
                      .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                  horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
              ) {
                  listOf("👍", "❤️", "😂", "😮", "😢").forEach { emoji ->
                      Text(
                          text = emoji,
                          fontSize = 22.sp,
                          modifier = Modifier
                              .clip(CircleShape)
                              .clickable {
                                  onReactionSelected(emoji)
                                  menuOpen = false
                              }
                              .padding(Spacing.xs)
                      )
                  }
              }
              HorizontalDivider()
              DropdownMenuItem(
                  text = { Text(stringResource(R.string.action_reply)) },
                  leadingIcon = { Icon(Icons.Default.Reply, contentDescription = null) },
                  onClick = { menuOpen = false; onReply() }
              )
              if (message.text.isNotBlank()) {
                  DropdownMenuItem(
                      text = { Text(stringResource(R.string.action_copy)) },
                      leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                      onClick = { menuOpen = false; onCopy() }
                  )
              }
              DropdownMenuItem(
                  text = { Text(stringResource(R.string.action_delete_for_me)) },
                  leadingIcon = { Icon(Icons.Default.VisibilityOff, contentDescription = null) },
                  onClick = { menuOpen = false; onDeleteForMe() }
              )
              if (isMe) {
                  DropdownMenuItem(
                      text = { Text(stringResource(R.string.action_delete_for_everyone)) },
                      leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                      onClick = { menuOpen = false; onDeleteForEveryone() }
                  )
              }
          }

          // Badge della reazione scelta, in overlap sull'angolo in basso a destra della bolla.
          if (reaction != null) {
              Surface(
                  shape = CircleShape,
                  color = MaterialTheme.colorScheme.surface,
                  shadowElevation = 2.dp,
                  border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                  modifier = Modifier
                      .align(Alignment.BottomEnd)
                      .offset(x = 6.dp, y = 6.dp)
                      .clickable { onReactionSelected(reaction) }
              ) {
                  Text(
                      text = reaction,
                      fontSize = 13.sp,
                      modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                  )
              }
          }
        }

        // Etichetta luogo del mittente + distanza da me (calcolo locale, zero rete).
        // La distanza ha senso solo sui messaggi ALTRUI: sui propri sarebbe la
        // distanza da se stessi (~0), inutile.
        val distanceLabel = remember(message.latitude, message.longitude, myLatitude, myLongitude, isMe) {
            val mLat = message.latitude; val mLon = message.longitude
            if (!isMe && mLat != null && mLon != null && myLatitude != null && myLongitude != null &&
                !(mLat == 0.0 && mLon == 0.0)
            ) {
                val res = FloatArray(1)
                android.location.Location.distanceBetween(myLatitude, myLongitude, mLat, mLon, res)
                val m = res[0]
                when {
                    m < 100f -> "qui vicino"
                    m < 1000f -> "a ${m.toInt()} m da te"
                    else -> "a ${"%.1f".format(m / 1000f)} km da te"
                }
            } else null
        }
        val placeLabel = message.placeName?.takeIf { it.isNotBlank() }
        val geoLine = listOfNotNull(placeLabel?.let { "📍 $it" }, distanceLabel).joinToString(" • ")
        if (geoLine.isNotBlank()) {
            Text(
                text = geoLine,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    top = Spacing.xxs,
                    start = if (isMe) 0.dp else Spacing.md,
                    end = if (isMe) Spacing.md else 0.dp
                )
            )
        }

        // "Visto da…" solo sotto il proprio ultimo messaggio letto da altri.
        if (isMe && showReadReceipt && readerNames.isNotEmpty()) {
            Text(
                text = "Visto da ${readerNames.joinToString(", ")}",
                fontSize = 12.sp,
                color = RadarSemantic.Online,
                modifier = Modifier.padding(top = Spacing.xxs, end = Spacing.md)
            )
        }
    }
}

/** Due timestamp cadono nello stesso giorno solare? */
private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/** Separatore data centrato nel flusso chat: "Oggi", "Ieri" o la data. */
@Composable
private fun ChatDateSeparator(timestamp: Long) {
    val now = System.currentTimeMillis()
    val yesterday = now - 24L * 60 * 60 * 1000
    val label = when {
        isSameDay(timestamp, now) -> "Oggi"
        isSameDay(timestamp, yesterday) -> "Ieri"
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(timestamp))
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(Radius.pill),
            color = MaterialTheme.colorScheme.surface
        ) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
            )
        }
    }
}

/** Player di una nota vocale in chat: play/pausa, durata e luogo di registrazione. */
@Composable
private fun VoiceMessagePlayer(
    message: ChatMessage,
    isMe: Boolean,
    groupId: String,
    repository: FirebaseRepository
) {
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(message.id) {
        onDispose {
            runCatching { player.value?.release() }
            player.value = null
        }
    }

    val onSurface = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val muted = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val durationSec = (message.audioDurationMs / 1000.0).roundToInt().coerceAtLeast(1)
    val place = message.placeName?.takeIf { it.isNotBlank() }
        ?: message.latitude?.let { lat ->
            message.longitude?.let { lon ->
                if (lat != 0.0 || lon != 0.0) String.format(Locale.US, "%.4f, %.4f", lat, lon) else null
            }
        }

    fun toggle() {
        if (isPlaying) {
            runCatching { player.value?.pause() }
            isPlaying = false
            return
        }
        if (player.value != null) {
            runCatching { player.value?.start() }
            isPlaying = true
            return
        }
        if (isLoading) return
        // Prima riproduzione: scarica l'audio dal doc separato (una volta) e suona.
        isLoading = true
        scope.launch {
            val path = repository.getVoiceNotePath(groupId, message.id, message.audioUrl)
            isLoading = false
            if (path == null) return@launch
            player.value = runCatching {
                MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                    setOnCompletionListener { isPlaying = false; runCatching { seekTo(0) } }
                }
            }.getOrNull()
            runCatching { player.value?.start() }
            isPlaying = player.value != null
        }
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary)
                    .clickable { toggle() },
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pausa" else "Riproduci",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = onSurface.copy(alpha = 0.9f),
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "0:%02d".format(durationSec),
                style = MaterialTheme.typography.labelMedium,
                color = onSurface
            )
        }
        if (place != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(top = Spacing.xxs)
            ) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = place,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
