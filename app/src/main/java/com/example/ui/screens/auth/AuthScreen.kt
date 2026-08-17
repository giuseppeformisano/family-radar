package com.example.ui.screens.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.repository.FirebaseRepository
import com.example.ui.components.RadarPulseAnimation
import kotlinx.coroutines.launch

enum class AuthMethod {
    PHONE,
    EMAIL
}

@Composable
fun AuthScreen(
    repository: FirebaseRepository,
    onAuthSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var selectedMethod by remember { mutableStateOf(AuthMethod.PHONE) }

    // Phone Auth states
    var phoneNumber by remember { mutableStateOf("+39 ") }
    var verificationId by remember { mutableStateOf<String?>(null) }
    var smsCode by remember { mutableStateOf("") }
    var phoneDisplayName by remember { mutableStateOf("") }
    var isCodeSent by remember { mutableStateOf(false) }

    // Email Auth states
    var isLoginMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // General states
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Radar Hero Header
            Box(contentAlignment = Alignment.Center) {
                RadarPulseAnimation(
                    modifier = Modifier.size(100.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ShareLocation,
                        contentDescription = "Radar Logo",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Family Radar & Social",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )

            Text(
                text = "Localizzazione in tempo reale, geofencing e chat di gruppo",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            // Auth Main Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    // 1. Google Sign-In Primary Action
                    Button(
                        onClick = {
                            errorMessage = null
                            successMessage = null
                            isLoading = true
                            coroutineScope.launch {
                                val targetContext = activity ?: context
                                val result = repository.signInWithGoogle(targetContext)
                                isLoading = false
                                if (result.isSuccess) {
                                    onAuthSuccess()
                                } else {
                                    val err = result.exceptionOrNull()
                                    errorMessage = err?.localizedMessage ?: "Accesso Google non riuscito"
                                }
                            }
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("google_sign_in_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Connessione in corso...",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                GoogleIcon(modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Accedi con Google",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Inline Error Feedback for immediate visibility
                    AnimatedVisibility(visible = errorMessage != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Info",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = errorMessage ?: "",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // Divider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            text = " OPPURE ",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }

                    // Auth Method Switcher (Phone vs Email)
                    TabRow(
                        selectedTabIndex = if (selectedMethod == AuthMethod.PHONE) 0 else 1,
                        containerColor = Color.Transparent,
                        divider = {}
                    ) {
                        Tab(
                            selected = selectedMethod == AuthMethod.PHONE,
                            onClick = {
                                selectedMethod = AuthMethod.PHONE
                                errorMessage = null
                                successMessage = null
                            },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Telefono", fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        )
                        Tab(
                            selected = selectedMethod == AuthMethod.EMAIL,
                            onClick = {
                                selectedMethod = AuthMethod.EMAIL
                                errorMessage = null
                                successMessage = null
                            },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Email", fontWeight = FontWeight.Bold, maxLines = 1)
                                }
                            }
                        )
                    }

                    // ====== PHONE AUTH FLOW ======
                    if (selectedMethod == AuthMethod.PHONE) {
                        if (!isCodeSent) {
                            // Step 1: Input Phone Number
                            OutlinedTextField(
                                value = phoneNumber,
                                onValueChange = { phoneNumber = it },
                                label = { Text("Numero Telefono (es. +39...)", maxLines = 1) },
                                placeholder = { Text("+39 333 1234567", maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Phone,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("phone_number_input"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Button(
                                onClick = {
                                    errorMessage = null
                                    successMessage = null
                                    val cleanedPhone = phoneNumber.trim().replace(" ", "")
                                    if (cleanedPhone.length < 8) {
                                        errorMessage = "Inserisci un numero valido con prefisso"
                                        return@Button
                                    }
                                    if (activity == null) {
                                        errorMessage = "Errore di contesto Activity"
                                        return@Button
                                    }

                                    isLoading = true
                                    repository.sendPhoneVerificationCode(
                                        activity = activity,
                                        phoneNumber = cleanedPhone,
                                        onCodeSent = { vId ->
                                            isLoading = false
                                            verificationId = vId
                                            isCodeSent = true
                                            successMessage = "Codice SMS inviato a $cleanedPhone"
                                        },
                                        onVerificationCompleted = {
                                            isLoading = false
                                            onAuthSuccess()
                                        },
                                        onVerificationFailed = { e ->
                                            isLoading = false
                                            errorMessage = e.localizedMessage ?: "Errore invio SMS di verifica"
                                        }
                                    )
                                },
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("send_phone_code_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                } else {
                                    Icon(Icons.Default.Sms, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Invia Codice SMS", fontSize = 15.sp, maxLines = 1)
                                }
                            }
                        } else {
                            // Step 2: Input Verification Code & Name
                            Text(
                                text = "Codice inviato via SMS su $phoneNumber",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )

                            OutlinedTextField(
                                value = smsCode,
                                onValueChange = { smsCode = it.take(6) },
                                label = { Text("Codice di Verifica (6 cifre)", maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Next
                                ),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sms_code_input"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            OutlinedTextField(
                                value = phoneDisplayName,
                                onValueChange = { phoneDisplayName = it },
                                label = { Text("Nome visualizzato", maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("phone_name_input"),
                                shape = RoundedCornerShape(12.dp)
                            )

                            Button(
                                onClick = {
                                    errorMessage = null
                                    successMessage = null
                                    val code = smsCode.trim()
                                    val vId = verificationId
                                    if (vId == null || code.length < 6) {
                                        errorMessage = "Inserisci il codice completo a 6 cifre"
                                        return@Button
                                    }

                                    isLoading = true
                                    coroutineScope.launch {
                                        val result = repository.verifyPhoneCodeAndSignIn(
                                            verificationId = vId,
                                            smsCode = code,
                                            displayName = phoneDisplayName.trim(),
                                            phoneNumber = phoneNumber.trim()
                                        )
                                        isLoading = false
                                        if (result.isSuccess) {
                                            onAuthSuccess()
                                        } else {
                                            errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Codice di verifica non valido o scaduto"
                                        }
                                    }
                                },
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("verify_phone_code_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                } else {
                                    Text("Verifica e Accedi", fontSize = 15.sp, maxLines = 1)
                                }
                            }

                            TextButton(
                                onClick = {
                                    isCodeSent = false
                                    smsCode = ""
                                    errorMessage = null
                                    successMessage = null
                                },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text("Modifica numero di telefono")
                            }
                        }
                    }

                    // ====== EMAIL & PASSWORD FLOW ======
                    if (selectedMethod == AuthMethod.EMAIL) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(
                                onClick = { isLoginMode = true; errorMessage = null }
                            ) {
                                Text(
                                    "Accedi",
                                    fontWeight = if (isLoginMode) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isLoginMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { isLoginMode = false; errorMessage = null }
                            ) {
                                Text(
                                    "Registrati",
                                    fontWeight = if (!isLoginMode) FontWeight.Bold else FontWeight.Normal,
                                    color = if (!isLoginMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!isLoginMode) {
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                label = { Text("Nome Completo") },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = null
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Button(
                            onClick = {
                                errorMessage = null
                                if (email.isBlank() || password.isBlank()) {
                                    errorMessage = "Inserisci email e password per continuare"
                                    return@Button
                                }
                                isLoading = true
                                coroutineScope.launch {
                                    val result = if (isLoginMode) {
                                        repository.signInWithEmail(email.trim(), password)
                                    } else {
                                        repository.signUpWithEmail(email.trim(), password, displayName.trim())
                                    }
                                    isLoading = false
                                    if (result.isSuccess) {
                                        onAuthSuccess()
                                    } else {
                                        errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Errore di autenticazione"
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Text(if (isLoginMode) "Accedi" else "Crea Account", fontSize = 15.sp)
                            }
                        }
                    }

                    // Success Feedback
                    AnimatedVisibility(visible = successMessage != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = successMessage ?: "",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Error Feedback
                    AnimatedVisibility(visible = errorMessage != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun GoogleIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        // Blue, Green, Yellow, Red standard Google "G" quadrant geometry
        val red = Color(0xFFEA4335)
        val blue = Color(0xFF4285F4)
        val yellow = Color(0xFFFBBC05)
        val green = Color(0xFF34A853)

        // Draw stylized Google G
        drawCircle(color = blue, radius = w * 0.45f, center = Offset(cx, cy))
        drawCircle(color = Color.White, radius = w * 0.28f, center = Offset(cx, cy))
        drawRect(
            color = blue,
            topLeft = Offset(cx, cy - h * 0.12f),
            size = androidx.compose.ui.geometry.Size(w * 0.48f, h * 0.24f)
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
