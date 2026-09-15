package com.example.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
 * mostrata su un fondo neutro indipendentemente dal tema dell'app. Gli elementi di
 * "chrome" (bottoni, testo, dialog) restano invece theme-aware tramite MaterialTheme.
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

    // Stato di trascinamento verticale per lo swipe-to-dismiss.
    val dragOffsetY = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(1f) }
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    var dragStartTime by remember { mutableStateOf(0L) }
    var totalDrag by remember { mutableStateOf(0f) }

    // Strings captured at composable scope for use inside coroutine lambdas
    val strCloseViewer = stringResource(R.string.action_close_viewer)
    val strPhotoFallback = stringResource(R.string.label_photo)
    val strPhotoSavedGallery = stringResource(R.string.toast_photo_saved_gallery)
    val strPhotoSaveFailed = stringResource(R.string.toast_photo_save_failed)
    val strSaveToGallery = stringResource(R.string.action_save_to_gallery)
    val strFullscreenImage = stringResource(R.string.content_desc_fullscreen_image)

    // Decode in-memory bitmap if it is a Base64 string for flawless rendering
    val decodedBitmap = remember(imageSource) {
        when (imageSource) {
            is Bitmap -> imageSource
            is String -> ImageUtils.base64ToBitmap(imageSource)
            else -> null
        }
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
            // Immagine a tutto schermo, trascinabile verticalmente per chiudere quando non ingrandita.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = dragOffsetY.value
                    }
                    .pointerInput(currentZoomScale) {
                        if (currentZoomScale <= 1.05f) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragStartTime = System.currentTimeMillis()
                                    totalDrag = 0f
                                },
                                onDragEnd = {
                                    val elapsedMs = (System.currentTimeMillis() - dragStartTime).coerceAtLeast(1L)
                                    val approxVelocity = totalDrag / elapsedMs * 1000f // px/s stimata
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

            // Controlli minimali sovrapposti in alto: chiudi e salva, si nascondono al tap.
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

            // Didascalia (se presente) sovrapposta in basso, discreta, si nasconde con il chrome.
            AnimatedVisibility(
                visible = chromeVisible && !caption.isNullOrBlank(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart)
            ) {
                Text(
                    text = caption ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x99000000))
                        .navigationBarsPadding()
                        .padding(16.dp)
                )
            }
        }
    }
}

/**
 * Immagine a schermo intero con pinch-to-zoom, pan e doppio-tap per ingrandire.
 * Riutilizzabile: accetta un [bitmap] gia' decodificato (Base64) oppure un
 * [imageSource] qualunque per Coil (URL/data-URL). Lo zoom arriva fino a 5x; il
 * pan e' consentito solo quando si e' ingranditi, e il doppio-tap alterna 1x/2.5x.
 * Il singolo tap (riservato a differenza del doppio-tap) invoca [onTap], usato per
 * alternare la visibilita' dei controlli sovrapposti (chrome).
 */
@Composable
private fun ZoomableImage(
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
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset = if (scale > 1f) offset + pan else Offset.Zero
                onScaleChanged?.invoke(scale)
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
 * Enhanced Full-Screen Carousel for Georeferenced Snapshot Clusters
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
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, snapshots.size - 1),
        pageCount = { snapshots.size }
    )
    var isDownloading by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }

    val currentSnapshot = snapshots.getOrNull(pagerState.currentPage) ?: snapshots.first()

    // Strings captured at composable scope for use inside coroutine/click lambdas
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
                .background(ViewerBackdrop.copy(alpha = 0.98f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar - si nasconde al tap sull'immagine, come i controlli del viewer singolo.
                AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = strClose,
                                        tint = Color.White
                                    )
                                }

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = strGeoTitle,
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (isDownloading) return@IconButton
                                            coroutineScope.launch {
                                                isDownloading = true
                                                val success = ImageUtils.saveBase64ToGallery(context, currentSnapshot.photoBase64)
                                                isDownloading = false
                                                if (success) {
                                                    Toast.makeText(context, strSnapshotSavedGallery, Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, strSaveError, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        if (isDownloading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = Color.White,
                                                trackColor = Color(0x33FFFFFF),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = strSaveToGallery2,
                                                tint = Color.White
                                            )
                                        }
                                    }

                                    if (onDelete != null) {
                                        IconButton(
                                            onClick = { showDeleteConfirm = true },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = strDeleteSnapshotTitle,
                                                tint = Color(0xFFFF6B6B)
                                            )
                                        }
                                    }
                                }
                            }

                            // Indicatore a puntini al posto della pillola "pagina X di Y".
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
                                        Box(
                                            modifier = Modifier
                                                .padding(horizontal = 3.dp)
                                                .size(if (isCurrent) 8.dp else 6.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isCurrent) Color.White else Color.White.copy(alpha = 0.4f)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Horizontal Pager for gallery browsing - fills central space safely
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) { page ->
                    val item = snapshots[page]
                    val decoded = remember(item.photoBase64) {
                        ImageUtils.base64ToBitmap(item.photoBase64)
                    }
                    val pageDescription = stringResource(R.string.snapshot_content_desc_page, page + 1, snapshots.size)

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        ZoomableImage(
                            bitmap = decoded,
                            imageSource = "data:image/jpeg;base64,${item.photoBase64}",
                            contentDescription = pageDescription,
                            onTap = { chromeVisible = !chromeVisible }
                        )
                    }
                }

                // Bottom Information Card - si nasconde con il resto del chrome.
                AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
                                            Icon(
                                                Icons.Default.Person,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = currentSnapshot.userName,
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Schedule,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    val timeStr = remember(currentSnapshot.timestamp) {
                                        SimpleDateFormat("HH:mm - dd/MM/yyyy", Locale.getDefault()).format(Date(currentSnapshot.timestamp))
                                    }
                                    Text(
                                        text = timeStr,
                                        color = Color.White.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            if (currentSnapshot.caption.isNotBlank()) {
                                Text(
                                    text = currentSnapshot.caption,
                                    color = Color.White.copy(alpha = 0.95f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = Color(0xFFEA580C),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = String.format(Locale.US, strPositionFmt, currentSnapshot.latitude, currentSnapshot.longitude),
                                    color = Color.White.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
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

/**
 * Dialog di conferma eliminazione, temato con [GlassSurface] anziche' un AlertDialog
 * a tinte fisse: cosi' resta leggibile sia in tema chiaro sia scuro.
 */
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
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(cancelLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onConfirm) {
                        Text(confirmLabel, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
