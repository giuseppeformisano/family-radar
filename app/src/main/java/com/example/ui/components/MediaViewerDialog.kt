package com.example.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.PlaceSnapshot
import com.example.ui.theme.RadarDark
import com.example.util.ImageUtils
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                .background(Color(0xFF06080D))
        ) {
            // Immagine a tutto schermo: nessuna barra piena, niente spazio sprecato.
            ZoomableImage(
                bitmap = decodedBitmap,
                imageSource = imageSource,
                contentDescription = strFullscreenImage
            )

            // Controlli minimali sovrapposti in alto: chiudi e salva, pillole scure.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
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

            // Didascalia (se presente) sovrapposta in basso, discreta.
            if (!caption.isNullOrBlank()) {
                Text(
                    text = caption,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
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
 */
@Composable
private fun ZoomableImage(
    bitmap: Bitmap?,
    imageSource: Any?,
    contentDescription: String?
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val gestureModifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                }
            )
        }
        .pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset = if (scale > 1f) offset + pan else Offset.Zero
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFA060A10))
        ) {
            // Top Bar
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
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
                        Text(
                            text = stringResource(R.string.snapshot_page_of, pagerState.currentPage + 1, snapshots.size),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            style = MaterialTheme.typography.labelMedium
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
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    containerColor = RadarDark.Bg,
                    title = { Text(strDeleteSnapshotTitle, color = RadarDark.TextPrimary) },
                    text = { Text(strDeleteSnapshotBody, color = RadarDark.TextMuted) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                onDelete?.invoke(currentSnapshot)
                                if (snapshots.size == 1) onDismiss()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(stringResource(R.string.action_delete))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { showDeleteConfirm = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = RadarDark.TextMuted)
                        ) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
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

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (decoded != null) {
                        Image(
                            bitmap = decoded.asImageBitmap(),
                            contentDescription = stringResource(R.string.snapshot_content_desc_page, page + 1, snapshots.size),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        )
                    } else {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data("data:image/jpeg;base64,${item.photoBase64}")
                                .crossfade(true)
                                .build(),
                            contentDescription = stringResource(R.string.snapshot_content_desc_page, page + 1, snapshots.size),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        )
                    }
                }
            }

            // Bottom Information Card - 100% visible and padded with navigationBarsPadding
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
}
