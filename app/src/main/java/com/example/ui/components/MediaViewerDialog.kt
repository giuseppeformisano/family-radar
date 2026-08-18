package com.example.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFA080C14))
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
                            contentDescription = "Chiudi visualizzatore",
                            tint = Color.White
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = title ?: authorName ?: "Foto",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (timestamp != null && timestamp > 0) {
                            val timeStr = remember(timestamp) {
                                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
                            }
                            Text(
                                text = timeStr,
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (isDownloading) return@IconButton
                            coroutineScope.launch {
                                isDownloading = true
                                val success = when {
                                    decodedBitmap != null -> {
                                        ImageUtils.saveBitmapToGallery(context, decodedBitmap) != null
                                    }
                                    imageSource is String -> {
                                        ImageUtils.saveBase64ToGallery(context, imageSource)
                                    }
                                    else -> false
                                }
                                isDownloading = false
                                if (success) {
                                    Toast.makeText(context, "Foto salvata nella Galleria del dispositivo!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Impossibile salvare la foto", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Salva nella galleria",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // Main image display centered at high fidelity, occupies remaining space without pushing content off screen
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (decodedBitmap != null) {
                    Image(
                        bitmap = decodedBitmap.asImageBitmap(),
                        contentDescription = "Visualizzazione immagine a schermo intero",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageSource)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Visualizzazione immagine a schermo intero",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Bottom Caption Bar (if caption present)
            if (!caption.isNullOrBlank()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Text(
                        text = caption,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
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
                            contentDescription = "Chiudi",
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
                            text = "Istantanea Geografica",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${pagerState.currentPage + 1} di ${snapshots.size}",
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
                                        Toast.makeText(context, "Foto salvata nella Galleria!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Errore durante il salvataggio", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Salva",
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
                                    contentDescription = "Elimina istantanea",
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
                    title = { Text("Elimina istantanea") },
                    text = { Text("L'istantanea verrà rimossa dalla mappa e dalla chat.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            onDelete?.invoke(currentSnapshot)
                            if (snapshots.size == 1) onDismiss()
                        }) {
                            Text("Elimina")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Annulla")
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
                            contentDescription = "Istantanea ${page + 1} di ${snapshots.size}",
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
                            contentDescription = "Istantanea ${page + 1} di ${snapshots.size}",
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
                            text = "Posizione: %.4f, %.4f".format(Locale.US, currentSnapshot.latitude, currentSnapshot.longitude),
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}
