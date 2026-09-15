package com.example.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.PlaceSnapshot
import com.example.util.ImageUtils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Colore di sfondo intenzionalmente scuro per il visualizzatore foto a schermo intero:
 * è la stessa scelta che fa Google Photos anche in tema chiaro, perché una foto va
 * mostrata su un fondo neutro indipendentemente dal tema dell'app.
 */
private val ViewerBackdrop = Color(0xFF06080D)

@Composable
fun FullScreenMediaViewer(
    imageSource: Any?,
    title: String? = null,
    authorName: String? = null,
    timestamp: Long? = null,
    caption: String? = null,
    onDismiss: () -> Unit
) {
    if (imageSource == null) return

    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var currentZoomScale by remember { mutableFloatStateOf(1f) }

    val dragOffsetY = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(1f) }
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    var dragStartTime by remember { mutableStateOf(0L) }
    var totalDrag by remember { mutableStateOf(0f) }

    val strCloseViewer = stringResource(R.string.action_close_viewer)
    val strPhotoSavedGallery = stringResource(R.string.toast_photo_saved_gallery)
    val strPhotoSaveFailed = stringResource(R.string.toast_photo_save_failed)
    val strSaveToGallery = stringResource(R.string.action_save_to_gallery)
    val strFullscreenImage = stringResource(R.string.content_desc_fullscreen_image)

    val decodedBitmap = remember(imageSource) {
        when (imageSource) {
            is Bitmap -> imageSource
            is String -> ImageUtils.base64ToBitmap(imageSource)
            else -> null
        }
    }

    // Metadati da mostrare in basso (autore + ora), se disponibili
    val metaText = remember(authorName, timestamp) {
        val parts = listOfNotNull(
            authorName?.takeIf { it.isNotBlank() },
            timestamp?.let { SimpleDateFormat("HH:mm · dd/MM/yyyy", Locale.getDefault()).format(Date(it)) }
        )
        parts.joinToString("  ·  ").takeIf { it.isNotBlank() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ViewerBackdrop.copy(alpha = scrimAlpha.value))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = dragOffsetY.value }
                    .pointerInput(currentZoomScale) {
                        if (currentZoomScale <= 1.05f) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragStartTime = System.currentTimeMillis()
                                    totalDrag = 0f
                                },
                                onDragEnd = {
                                    val elapsedMs = (System.currentTimeMillis() - dragStartTime).coerceAtLeast(1L)
                                    val approxVelocity = totalDrag / elapsedMs * 1000f
                                    coroutineScope.launch {
                                        if (abs(dragOffsetY.value) > dismissThresholdPx || abs(approxVelocity) > 1200f) {
                                            onDismiss()
                                        } else {
                                            launch { dragOffsetY.animateTo(0f, animationSpec = spring()) }
                                            launch { scrimAlpha.animateTo(1f, animationSpec = spring()) }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    coroutineScope.launch {
                                        launch { dragOffsetY.animateTo(0f, animationSpec = spring()) }
                                        launch { scrimAlpha.animateTo(1f, animationSpec = spring()) }
                                    }
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDrag += dragAmount
                                    coroutineScope.launch {
                                        dragOffsetY.snapTo(dragOffsetY.value + dragAmount)
                                        val progress = (abs(dragOffsetY.value) / dismissThresholdPx).coerceIn(0f, 1f)
                                        scrimAlpha.snapTo((1f - progress * 0.6f).coerceIn(0.35f, 1f))
                                    }
                                }
                            )
                        }
                    }
            ) {
                ZoomableImage(
                    bitmap = decodedBitmap,
                    imageSource = imageSource,
                    contentDescription = strFullscreenImage,
                    onTap = { chromeVisible = !chromeVisible },
                    onScaleChanged = { currentZoomScale = it }
                )
            }

            // Top bar: chiudi + salva
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = strCloseViewer, tint = Color.White, modifier = Modifier.size(22.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                            .clickable {
                                if (isDownloading) return@clickable
                                coroutineScope.launch {
                                    isDownloading = true
                                    val success = when {
                                        decodedBitmap != null -> ImageUtils.saveBitmapToGallery(context, decodedBitmap) != null
                                        imageSource is String -> ImageUtils.saveBase64ToGallery(context, imageSource)
                                        else -> false
                                    }
                                    isDownloading = false
                                    Toast.makeText(
                                        context,
                                        if (success) strPhotoSavedGallery else strPhotoSaveFailed,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, trackColor = Color(0x33FFFFFF), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = strSaveToGallery, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }

            // Bottom: metadati (autore + ora) e/o didascalia
            val hasBottom = !metaText.isNullOrBlank() || !caption.isNullOrBlank()
            AnimatedVisibility(
                visible = chromeVisible && hasBottom,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC000000))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!metaText.isNullOrBlank()) {
                        Text(
                            text = metaText,
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    if (!caption.isNullOrBlank()) {
                        Text(
                            text = caption,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Immagine a schermo intero con pinch-to-zoom, pan e doppio-tap per ingrandire.
 *
 * Il gesture handler è scritto manualmente con [awaitEachGesture] invece di
 * [detectTransformGestures] perché quest'ultimo consuma TUTTI i touch event
 * (anche single-finger) impedendo a [HorizontalPager] di ricevere gli swipe
 * orizzontali quando la scala è 1. Qui si consuma solo quando:
 * - multi-touch (pinch): sempre
 * - single-touch: solo se scale > 1 (pan su immagine ingrandita)
 * Con scale == 1 e single-finger, il parent (Pager) riceve il gesture.
 */
@Composable
fun ZoomableImage(
    bitmap: Bitmap?,
    imageSource: Any?,
    contentDescription: String?,
    onTap: (() -> Unit)? = null,
    onScaleChanged: ((Float) -> Unit)? = null
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val gestureModifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { onTap?.invoke() },
                onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                    onScaleChanged?.invoke(scale)
                }
            )
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                // Loop per tutta la durata del gesto corrente.
                // awaitEachGesture si ripete automaticamente al prossimo touch-down.
                while (true) {
                    val event = awaitPointerEvent()
                    val activeCount = event.changes.count { it.pressed }
                    when {
                        activeCount >= 2 -> {
                            // Pinch: zoom + pan — consuma sempre
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            scale = (scale * zoomChange).coerceIn(1f, 5f)
                            offset = if (scale > 1f) offset + panChange else Offset.Zero
                            onScaleChanged?.invoke(scale)
                            event.changes.forEach { it.consume() }
                        }
                        activeCount == 1 && scale > 1.05f -> {
                            // Single-finger pan su immagine ingrandita — consuma
                            val panChange = event.calculatePan()
                            offset = offset + panChange
                            event.changes.forEach { it.consume() }
                        }
                        // Single-finger con scale==1: NON consumare → il Pager riceve lo swipe
                    }
                    // Esci dal loop quando tutte le dita sono alzate
                    if (!event.changes.any { it.pressed }) break
                }
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = gestureModifier
        )
    } else {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageSource)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = gestureModifier
        )
    }
}

/**
 * Gallery a schermo intero per cluster di snapshot georeferenziati.
 * Stesse funzionalità del viewer singolo: pinch-zoom, swipe-to-dismiss,
 * chrome auto-hide al tap. Navigazione tra snapshot con swipe orizzontale
 * (funziona perché ZoomableImage non consuma il single-finger a scale=1).
 */
@Composable
fun SnapshotClusterGalleryDialog(
    snapshots: List<PlaceSnapshot>,
    initialIndex: Int = 0,
    currentUserId: String = "",
    onDelete: ((PlaceSnapshot) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    if (snapshots.isEmpty()) return

    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, snapshots.size - 1),
        pageCount = { snapshots.size }
    )
    var isDownloading by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }

    // Swipe-to-dismiss verticale (come FullScreenMediaViewer), gated su zoom == 1
    var currentZoomScale by remember { mutableFloatStateOf(1f) }
    val dragOffsetY = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(1f) }
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    var dragStartTime by remember { mutableLongStateOf(0L) }
    var totalDrag by remember { mutableFloatStateOf(0f) }

    val currentSnapshot = snapshots.getOrNull(pagerState.currentPage) ?: snapshots.first()

    val strClose = stringResource(R.string.action_close)
    val strGeoTitle = stringResource(R.string.snapshot_geographic_title)
    val strSnapshotSavedGallery = stringResource(R.string.toast_snapshot_saved_gallery)
    val strSaveError = stringResource(R.string.toast_save_error)
    val strSaveToGallery2 = stringResource(R.string.action_save_to_gallery)
    val strDeleteSnapshotTitle = stringResource(R.string.dialog_delete_snapshot_title)
    val strDeleteSnapshotBody = stringResource(R.string.dialog_delete_snapshot_body)
    val strPositionFmt = stringResource(R.string.snapshot_position)
    val strDelete = stringResource(R.string.action_delete)
    val strCancel = stringResource(R.string.action_cancel)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ViewerBackdrop.copy(alpha = scrimAlpha.value))
        ) {
            // Contenuto principale con swipe-to-dismiss
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = dragOffsetY.value }
                    .pointerInput(currentZoomScale) {
                        if (currentZoomScale <= 1.05f) {
                            detectVerticalDragGestures(
                                onDragStart = { dragStartTime = System.currentTimeMillis(); totalDrag = 0f },
                                onDragEnd = {
                                    val elapsedMs = (System.currentTimeMillis() - dragStartTime).coerceAtLeast(1L)
                                    val velocity = totalDrag / elapsedMs * 1000f
                                    coroutineScope.launch {
                                        if (abs(dragOffsetY.value) > dismissThresholdPx || abs(velocity) > 1200f) {
                                            onDismiss()
                                        } else {
                                            launch { dragOffsetY.animateTo(0f, spring()) }
                                            launch { scrimAlpha.animateTo(1f, spring()) }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    coroutineScope.launch {
                                        launch { dragOffsetY.animateTo(0f, spring()) }
                                        launch { scrimAlpha.animateTo(1f, spring()) }
                                    }
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDrag += dragAmount
                                    coroutineScope.launch {
                                        dragOffsetY.snapTo(dragOffsetY.value + dragAmount)
                                        val progress = (abs(dragOffsetY.value) / dismissThresholdPx).coerceIn(0f, 1f)
                                        scrimAlpha.snapTo((1f - progress * 0.6f).coerceIn(0.35f, 1f))
                                    }
                                }
                            )
                        }
                    }
            ) {
                // Top bar con chrome auto-hide
                AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xCC000000))
                            .statusBarsPadding()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = strClose, tint = Color.White)
                                }

                                Text(
                                    text = strGeoTitle,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )

                                Row {
                                    IconButton(
                                        onClick = {
                                            if (isDownloading) return@IconButton
                                            coroutineScope.launch {
                                                isDownloading = true
                                                val ok = ImageUtils.saveBase64ToGallery(context, currentSnapshot.photoBase64)
                                                isDownloading = false
                                                Toast.makeText(context, if (ok) strSnapshotSavedGallery else strSaveError, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        if (isDownloading) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, trackColor = Color(0x33FFFFFF), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.Download, contentDescription = strSaveToGallery2, tint = Color.White)
                                        }
                                    }
                                    if (onDelete != null) {
                                        IconButton(onClick = { showDeleteConfirm = true }) {
                                            Icon(Icons.Default.Delete, contentDescription = strDeleteSnapshotTitle, tint = Color(0xFFFF6B6B))
                                        }
                                    }
                                }
                            }

                            // Indicatori di pagina: pillola per la corrente, punto per le altre
                            if (snapshots.size > 1) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    snapshots.forEachIndexed { idx, _ ->
                                        val isCurrent = idx == pagerState.currentPage
                                        val dotWidth by animateDpAsState(
                                            targetValue = if (isCurrent) 20.dp else 6.dp,
                                            label = "dot_width_$idx"
                                        )
                                        Box(
                                            modifier = Modifier
                                                .padding(horizontal = 3.dp)
                                                .height(6.dp)
                                                .width(dotWidth)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isCurrent) Color.White else Color.White.copy(alpha = 0.35f)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Pager immagini — occupa tutto lo spazio centrale
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    val item = snapshots[page]
                    val decoded = remember(item.photoBase64) {
                        ImageUtils.base64ToBitmap(item.photoBase64)
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        ZoomableImage(
                            bitmap = decoded,
                            imageSource = "data:image/jpeg;base64,${item.photoBase64}",
                            contentDescription = null,
                            onTap = { chromeVisible = !chromeVisible },
                            onScaleChanged = { if (page == pagerState.currentPage) currentZoomScale = it }
                        )
                    }
                }

                // Bottom info card — metadati snapshot
                AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xCC000000))
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Riga autore + ora
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                val authorAvatar = remember(currentSnapshot.userPhotoBase64) {
                                    ImageUtils.base64ToBitmap(currentSnapshot.userPhotoBase64)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (authorAvatar != null) {
                                        Image(
                                            bitmap = authorAvatar.asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Text(
                                    text = currentSnapshot.userName,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Schedule, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                                val timeStr = remember(currentSnapshot.timestamp) {
                                    SimpleDateFormat("HH:mm · dd/MM/yy", Locale.getDefault()).format(Date(currentSnapshot.timestamp))
                                }
                                Text(text = timeStr, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Didascalia (se presente)
                        if (currentSnapshot.caption.isNotBlank()) {
                            Text(
                                text = currentSnapshot.caption,
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Coordinate GPS
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color(0xFFEA580C), modifier = Modifier.size(14.dp))
                            Text(
                                text = String.format(Locale.US, strPositionFmt, currentSnapshot.latitude, currentSnapshot.longitude),
                                color = Color.White.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            if (showDeleteConfirm) {
                DeleteSnapshotConfirmDialog(
                    title = strDeleteSnapshotTitle,
                    body = strDeleteSnapshotBody,
                    confirmLabel = strDelete,
                    cancelLabel = strCancel,
                    onConfirm = {
                        showDeleteConfirm = false
                        onDelete?.invoke(currentSnapshot)
                        if (snapshots.size == 1) onDismiss()
                    },
                    onDismiss = { showDeleteConfirm = false }
                )
            }
        }
    }
}

@Composable
private fun DeleteSnapshotConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(cancelLabel, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
