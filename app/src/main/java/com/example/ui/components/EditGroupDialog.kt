package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.model.GroupData
import com.example.ui.theme.Radius
import com.example.ui.theme.Sizes
import com.example.ui.theme.Spacing
import com.example.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modifica di nome, descrizione e immagine del gruppo.
 *
 * Stessa impaginazione di [EditGroupProfileDialog] — Card in un Dialog, anteprima
 * circolare cliccabile, campi sotto — così le due schermate di modifica si leggono
 * come la stessa cosa applicata a soggetti diversi (il membro e il gruppo).
 */
@Composable
fun EditGroupDialog(
    group: GroupData,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, photoBase64: String?, isPublic: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(group.name) }
    var description by remember { mutableStateOf(group.description) }
    var isPublic by remember { mutableStateOf(group.isPublic) }
    var photoBase64 by remember { mutableStateOf(group.photoBase64.ifBlank { null }) }
    var photoBitmap by remember {
        mutableStateOf<Bitmap?>(ImageUtils.base64ToBitmap(group.photoBase64.ifBlank { null }))
    }
    var isProcessingImage by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessingImage = true
            scope.launch {
                val base64 = ImageUtils.uriToBase64(context, uri, maxDimension = 300, quality = 80)
                if (base64 != null) {
                    photoBase64 = base64
                    photoBitmap = ImageUtils.base64ToBitmap(base64)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.toast_image_load_error), Toast.LENGTH_SHORT).show()
                    }
                }
                isProcessingImage = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            shape = RoundedCornerShape(Radius.xl),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(Sizes.avatarLg)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Group,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Sizes.iconLg)
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.dialog_edit_group_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.dialog_edit_group_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Spacing.lg))

                GroupPhotoPicker(
                    bitmap = photoBitmap,
                    fallbackLetter = name.firstOrNull()?.uppercaseChar()?.toString() ?: "G",
                    isLoading = isProcessingImage,
                    onClick = { photoPickerLauncher.launch("image/*") }
                )

                Spacer(Modifier.height(Spacing.sm))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { photoPickerLauncher.launch("image/*") },
                        enabled = !isProcessingImage && !isSaving
                    ) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(Sizes.iconSm)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.action_choose_photo), style = MaterialTheme.typography.labelLarge)
                    }
                    if (photoBitmap != null) {
                        TextButton(
                            onClick = {
                                photoBase64 = null
                                photoBitmap = null
                            },
                            enabled = !isProcessingImage && !isSaving,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.action_remove), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.md))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_group_name)) },
                    placeholder = { Text(stringResource(R.string.placeholder_group_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.sm),
                    leadingIcon = { Icon(Icons.Default.Group, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(Spacing.md))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.label_group_description)) },
                    placeholder = { Text(stringResource(R.string.edit_group_desc_placeholder)) },
                    shape = RoundedCornerShape(Radius.sm),
                    minLines = 2,
                    maxLines = 3,
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(Spacing.md))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.group_public_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.group_public_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = isPublic,
                        onCheckedChange = { isPublic = it },
                        enabled = !isSaving
                    )
                }

                Spacer(Modifier.height(Spacing.xxl))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Radius.sm),
                        enabled = !isSaving
                    ) { Text(stringResource(R.string.action_cancel)) }

                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                Toast.makeText(context, context.getString(R.string.toast_enter_valid_name), Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSaving = true
                            onSave(name.trim(), description.trim(), photoBase64, isPublic)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Radius.sm),
                        enabled = !isSaving && name.isNotBlank()
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Sizes.iconMd),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Anteprima circolare dell'immagine di gruppo con badge fotocamera.
 * Condivisa fra la modifica e la creazione del gruppo.
 */
@Composable
fun GroupPhotoPicker(
    bitmap: Bitmap?,
    fallbackLetter: String,
    isLoading: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = Sizes.avatarXl
) {
    // Il contenitore esterno NON ritaglia: prima il badge era figlio del cerchio
    // dell'avatar, che gli tagliava l'angolo in basso a destra e mostrava una
    // fotocamera mozzata. Qui il ritaglio circolare resta solo sull'avatar e il
    // badge gli si appoggia sopra libero.
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(Sizes.iconXl),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )

                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.content_desc_group_image),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                else -> Text(
                    text = fallbackLetter,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(Sizes.iconLg)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                // Anello del colore della superficie: stacca il badge dal bordo
                // dell'avatar, che altrimenti gli passa dietro e lo confonde.
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = stringResource(R.string.content_desc_change_image_badge),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(Sizes.iconSm)
            )
        }
    }
}
