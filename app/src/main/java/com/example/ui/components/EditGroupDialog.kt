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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
    onSave: (name: String, description: String, photoBase64: String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(group.name) }
    var description by remember { mutableStateOf(group.description) }
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
                        Toast.makeText(context, "Errore nel caricamento immagine", Toast.LENGTH_SHORT).show()
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
                Text(
                    text = "Modifica gruppo",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "Nome, descrizione e immagine visibili a tutti i membri",
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
                        Text("Scegli foto", style = MaterialTheme.typography.labelLarge)
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
                            Text("Rimuovi", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.md))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome del gruppo") },
                    placeholder = { Text("es. Famiglia Rossi") },
                    singleLine = true,
                    shape = RoundedCornerShape(Radius.sm),
                    leadingIcon = { Icon(Icons.Default.Group, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(Spacing.md))

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descrizione (facoltativa)") },
                    placeholder = { Text("es. Casa e spostamenti") },
                    shape = RoundedCornerShape(Radius.sm),
                    minLines = 2,
                    maxLines = 3,
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )

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
                    ) { Text("Annulla") }

                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                Toast.makeText(context, "Inserisci un nome valido", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSaving = true
                            onSave(name.trim(), description.trim(), photoBase64)
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
                            Text("Salva")
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
    Box(
        modifier = Modifier
            .size(size)
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
                contentDescription = "Immagine del gruppo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            else -> Text(
                text = fallbackLetter,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(Sizes.iconLg)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Cambia immagine",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(Sizes.iconSm)
            )
        }
    }
}
