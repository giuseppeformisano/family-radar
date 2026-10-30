package com.example.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.ArrowBack
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
import com.example.ui.theme.Radius
import com.example.ui.theme.Sizes
import com.example.ui.theme.Spacing
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
    val coroutineScope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }

    val strPhotoSavedGallery = stringResource(R.string.toast_photo_saved_gallery)
    val strPhotoSaveFailed = stringResource(R.string.toast_photo_save_failed)
    val strSaveToGallery = stringResource(R.string.action_save_to_gallery)
    val strFullscreenImage = stringResource(R.string.content_desc_fullscreen_image)

    val decodedBitmap = remember(imageSource) {
        when (imageSource) {
            is Bitmap -> imageSource
            is String -> ImageUtils.base64ToBitmapScaled(imageSource, 1440)
            else -> null
        }
    }

    val metaText = remember(authorName, timestamp) {
        listOfNotNull(
            authorName?.takeIf { it.isNotBlank() },
            timestamp?.let { SimpleDateFormat("HH:mm · dd/MM/yyyy", Locale.getDefault()).format(Date(it)) }
        ).joinToString("  ·  ").takeIf { it.isNotBlank() }
    }

    // Il tasto Indietro chiude, come per Membri/Luoghi/Chat.
    BackHandler { onDismiss() }

    // Stesso identico contenitore dei pannelli e del visore istantanee: Surface a tutto
    // schermo, intestazione con freccia indietro, foto senza cornice, metadati liberi.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_close), tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = title ?: "Foto",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        if (isDownloading) return@IconButton
                        coroutineScope.launch {
                            isDownloading = true
                            val success = when {
                                decodedBitmap != null -> ImageUtils.saveBitmapToGallery(context, decodedBitmap) != null
                                imageSource is String -> ImageUtils.saveBase64ToGallery(context, imageSource)
                                else -> false
                            }
                            isDownloading = false
                            Toast.makeText(context, if (success) strPhotoSavedGallery else strPhotoSaveFailed, Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Download, contentDescription = strSaveToGallery, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // Foto senza cornice.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ZoomableImage(
                    bitmap = decodedBitmap,
                    imageSource = imageSource,
                    contentDescription = strFullscreenImage
                )
            }

            // Metadati liberi sotto (autore + ora, didascalia).
            if (!metaText.isNullOrBlank() || !caption.isNullOrBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    if (!metaText.isNullOrBlank()) {
                        Text(text = metaText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                    if (!caption.isNullOrBlank()) {
                        Text(text = caption, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
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
 * Visore delle istantanee a schermo intero: stessa identica gestione del visore foto
 * della chat (sfondo scuro, zoom, swipe per chiudere, salva), con in piu' lo scorrimento
 * tra le istantanee del cluster, l'eliminazione e le coordinate.
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

    val currentSnapshot = snapshots.getOrNull(pagerState.currentPage) ?: snapshots.first()

    val strGeoTitle = stringResource(R.string.snapshot_geographic_title)
    val strSnapshotSavedGallery = stringResource(R.string.toast_snapshot_saved_gallery)
    val strSaveError = stringResource(R.string.toast_save_error)
    val strSaveToGallery2 = stringResource(R.string.action_save_to_gallery)
    val strDeleteSnapshotTitle = stringResource(R.string.dialog_delete_snapshot_title)
    val strDeleteSnapshotBody = stringResource(R.string.dialog_delete_snapshot_body)
    val strPositionFmt = stringResource(R.string.snapshot_position)
    val strDelete = stringResource(R.string.action_delete)
    val strCancel = stringResource(R.string.action_cancel)

    // Il tasto Indietro chiude, come per Membri/Luoghi/Chat.
    BackHandler { onDismiss() }

    // Stesso identico contenitore dei pannelli Membri/Luoghi/Chat: un Surface che riempie
    // TUTTO lo schermo (coprendo anche la mappa dietro le barre di sistema), con
    // intestazione (freccia indietro + titolo) e contenuto. NON e' un Dialog apposta.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Intestazione identica a quella dei pannelli: indietro + titolo + azioni.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_close), tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = strGeoTitle,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
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
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Download, contentDescription = strSaveToGallery2, tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = strDeleteSnapshotTitle, tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // Foto senza cornice: occupa tutto lo spazio centrale, con zoom. Scorri tra piu' istantanee.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val item = snapshots[page]
                    // Foto piena: preferisce l'URL Cloudinary (presente anche sugli altri
                    // dispositivi); il Base64 inline e' solo un ripiego per le vecchie.
                    val decoded = remember(item.photoBase64) {
                        if (item.photoBase64.isNotBlank()) ImageUtils.base64ToBitmapScaled(item.photoBase64, 1440) else null
                    }
                    ZoomableImage(
                        bitmap = decoded,
                        imageSource = item.getImageSource() ?: "",
                        contentDescription = null
                    )
                }
            }

            // Indicatori di pagina quando ci sono piu' istantanee.
            if (snapshots.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
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
                                    if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                )
                        )
                    }
                }
            }

            // Metadati autore/luogo: testo libero sotto la foto, senza riquadro.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    RadarAvatar(
                        name = currentSnapshot.userName,
                        photoBase64 = currentSnapshot.userPhotoBase64,
                        size = Sizes.avatarMd
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentSnapshot.userName,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                            val timeStr = remember(currentSnapshot.timestamp) {
                                SimpleDateFormat("HH:mm · dd/MM/yy", Locale.getDefault()).format(Date(currentSnapshot.timestamp))
                            }
                            Text(text = timeStr, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (currentSnapshot.caption.isNotBlank()) {
                    Text(
                        text = currentSnapshot.caption,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Text(
                        text = String.format(Locale.US, strPositionFmt, currentSnapshot.latitude, currentSnapshot.longitude),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
