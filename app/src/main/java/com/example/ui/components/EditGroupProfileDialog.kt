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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.GroupMember
import com.example.ui.theme.RadarDark
import com.example.ui.theme.Sizes
import com.example.ui.theme.Spacing
import com.example.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditGroupProfileDialog(
    currentMember: GroupMember,
    onDismiss: () -> Unit,
    onSaveProfile: (displayName: String, nickname: String?, photoBase64: String?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Strings captured at composable scope for use inside lambdas/coroutines
    val strImageLoadError = stringResource(R.string.toast_image_load_error)
    val strProfileTitle = stringResource(R.string.dialog_profile_title)
    val strProfileSubtitle = stringResource(R.string.dialog_profile_subtitle)
    val strChangePhoto = stringResource(R.string.action_change_photo)
    val strChoosePhoto = stringResource(R.string.action_choose_photo)
    val strRemove = stringResource(R.string.action_remove)
    val strLabelName = stringResource(R.string.label_name)
    val strPlaceholderName = stringResource(R.string.placeholder_name_examples)
    val strLabelNickname = stringResource(R.string.label_nickname)
    val strPlaceholderNickname = stringResource(R.string.placeholder_nickname_examples)
    val strCancel = stringResource(R.string.action_cancel)
    val strSave = stringResource(R.string.action_save)
    val strEnterValidName = stringResource(R.string.toast_enter_valid_name)

    var displayName by remember { mutableStateOf(currentMember.displayName) }
    var nickname by remember { mutableStateOf(currentMember.nickname ?: "") }
    var photoBase64 by remember { mutableStateOf(currentMember.photoBase64) }
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(ImageUtils.base64ToBitmap(currentMember.photoBase64)) }
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
                    avatarBitmap = ImageUtils.base64ToBitmap(base64)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, strImageLoadError, Toast.LENGTH_SHORT).show()
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
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = RadarDark.Bg),
            border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.CardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(Sizes.avatarLg)
                        .clip(CircleShape)
                        .background(RadarDark.AccentLight.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = RadarDark.AccentLight,
                        modifier = Modifier.size(Sizes.iconLg)
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = strProfileTitle,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = RadarDark.TextPrimary
                )

                Text(
                    text = strProfileSubtitle,
                    fontSize = 12.sp,
                    color = RadarDark.TextMuted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // Avatar Preview
                //
                // Il contenitore esterno NON ritaglia: prima il badge della
                // fotocamera era figlio del cerchio dell'avatar, che gli tagliava
                // l'angolo in basso a destra e lo mostrava mozzato. Il ritaglio
                // circolare resta solo sull'avatar.
                Box(
                    modifier = Modifier.size(96.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(RadarDark.Surface)
                            .border(2.dp, RadarDark.AccentLight, CircleShape)
                            .clickable { photoPickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isProcessingImage) {
                            RadarProgressIndicator(
                                size = 32.dp,
                                strokeWidth = 3.dp
                            )
                        } else if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap!!.asImageBitmap(),
                                contentDescription = "Avatar",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                color = RadarDark.TextPrimary
                            )
                        }
                    }

                    // Camera overlay icon badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(RadarDark.Accent)
                            .border(2.dp, RadarDark.Bg, CircleShape)
                            .clickable { photoPickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = strChangePhoto,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { photoPickerLauncher.launch("image/*") },
                        enabled = !isProcessingImage && !isSaving,
                        colors = ButtonDefaults.textButtonColors(contentColor = RadarDark.AccentLight)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strChoosePhoto, fontSize = 13.sp)
                    }

                    if (avatarBitmap != null) {
                        TextButton(
                            onClick = {
                                photoBase64 = null
                                avatarBitmap = null
                            },
                            enabled = !isProcessingImage && !isSaving,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(strRemove, fontSize = 13.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Display Name Field
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(strLabelName) },
                    placeholder = { Text(strPlaceholderName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Nickname Field (Optional)
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text(strLabelNickname) },
                    placeholder = { Text(strPlaceholderNickname) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = {
                        Icon(Icons.Default.Badge, contentDescription = null)
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSaving,
                        border = androidx.compose.foundation.BorderStroke(1.dp, RadarDark.SurfaceBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RadarDark.TextPrimary)
                    ) {
                        Text(strCancel)
                    }

                    Button(
                        onClick = {
                            if (displayName.isBlank()) {
                                Toast.makeText(context, strEnterValidName, Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isSaving = true
                            onSaveProfile(
                                displayName.trim(),
                                nickname.trim().ifBlank { null },
                                photoBase64
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSaving && displayName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = RadarDark.Accent, contentColor = Color.White)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.2f),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(strSave)
                        }
                    }
                }
            }
        }
    }
}
