package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.model.PlaceSnapshot
import com.example.repository.FirebaseRepository
import com.example.util.ImageUtils
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun AddPlaceSnapshotDialog(
    imageUri: Uri? = null,
    bitmap: Bitmap? = null,
    latitude: Double,
    longitude: Double,
    repository: FirebaseRepository,
    onDismiss: () -> Unit,
    onPublished: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var caption by remember { mutableStateOf("") }
    var isPublishing by remember { mutableStateOf(false) }

    // Strings captured at composable scope for use inside coroutine lambdas
    val strSnapshotPublished = stringResource(R.string.toast_snapshot_published)
    val strSnapshotCompressError = stringResource(R.string.toast_snapshot_compress_error)
    val strPhotoContentDesc = stringResource(R.string.snapshot_photo_content_desc)
    val strGeolocationLabel = stringResource(R.string.snapshot_geolocation_label)
    val strCaptionLabel = stringResource(R.string.snapshot_caption_label)
    val strCaptionPlaceholder = stringResource(R.string.snapshot_caption_placeholder)
    val strPublishing = stringResource(R.string.snapshot_publishing)
    val strShareOnMap = stringResource(R.string.snapshot_share_on_map)

    val previewBitmap = remember(imageUri, bitmap) {
        if (bitmap != null) bitmap
        else if (imageUri != null) ImageUtils.uriToBitmap(context, imageUri)
        else null
    }

    // Piccola entrata scale+fade: la dialog appariva a scatto, senza alcuna
    // transizione, mentre tutte le altre dialog dell'app hanno almeno
    // un'animazione minima di apertura/chiusura.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Dialog(
        onDismissRequest = { if (!isPublishing) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isPublishing,
            dismissOnClickOutside = !isPublishing,
            usePlatformDefaultWidth = false
        )
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(initialScale = 0.9f, animationSpec = tween(220)) + fadeIn(tween(220)),
            exit = scaleOut(targetScale = 0.9f, animationSpec = tween(160)) + fadeOut(tween(160))
        ) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 24.dp)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(24.dp),
                contentPadding = 20.dp
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Icona centrata
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Image Preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (previewBitmap != null) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = strPhotoContentDesc,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (imageUri != null) {
                            AsyncImage(
                                model = imageUri,
                                contentDescription = strPhotoContentDesc,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // GPS Location Chip
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = strGeolocationLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = String.format(Locale.US, "Lat: %.5f, Lon: %.5f", latitude, longitude),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Caption Field
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it },
                        label = { Text(strCaptionLabel) },
                        placeholder = { Text(strCaptionPlaceholder) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        maxLines = 3,
                        enabled = !isPublishing
                    )

                    // Publish Button
                    Button(
                        onClick = {
                            if (isPublishing) return@Button
                            coroutineScope.launch {
                                isPublishing = true
                                val base64Result = if (imageUri != null) {
                                    repository.compressImageToBase64(imageUri, maxDimension = 2560, quality = 95)
                                } else if (previewBitmap != null) {
                                    repository.compressBitmapToBase64(previewBitmap, maxDimension = 2560, quality = 95)
                                } else {
                                    Result.failure(Exception("no image"))
                                }

                                if (base64Result.isSuccess) {
                                    val base64Str = base64Result.getOrNull() ?: ""
                                    val snapshot = PlaceSnapshot(
                                        photoBase64 = base64Str,
                                        latitude = latitude,
                                        longitude = longitude,
                                        caption = caption.trim()
                                    )
                                    val result = repository.addPlaceSnapshot(snapshot)
                                    isPublishing = false
                                    if (result.isSuccess) {
                                        Toast.makeText(context, strSnapshotPublished, Toast.LENGTH_SHORT).show()
                                        onPublished()
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.toast_snapshot_save_error, result.exceptionOrNull()?.message ?: ""), Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    isPublishing = false
                                    Toast.makeText(context, strSnapshotCompressError, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isPublishing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        if (isPublishing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(strPublishing)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strShareOnMap, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
