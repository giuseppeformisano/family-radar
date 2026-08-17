package com.example.ui.screens.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.repository.FirebaseRepository
import com.example.ui.components.InfoBanner
import com.example.ui.components.PillChip
import com.example.ui.components.RadarPulseAnimation
import com.example.ui.theme.RadarTheme
import com.example.ui.theme.Radius
import com.example.ui.theme.Sizes
import com.example.ui.theme.Spacing
import kotlinx.coroutines.launch

enum class AuthMethod { PHONE, EMAIL }

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
    val gradients = RadarTheme.palette.gradients

    var selectedMethod by remember { mutableStateOf(AuthMethod.PHONE) }

    // Telefono
    var phoneNumber by remember { mutableStateOf("+39 ") }
    var verificationId by remember { mutableStateOf<String?>(null) }
    var smsCode by remember { mutableStateOf("") }
    var phoneDisplayName by remember { mutableStateOf("") }
    var isCodeSent by remember { mutableStateOf(false) }

    // Email
    var isLoginMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // Comuni
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    fun clearFeedback() {
        errorMessage = null
        successMessage = null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(gradients.heroTop, gradients.heroBottom))
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xxl, vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(Spacing.xxl))

            // ---- Hero ----
            Box(contentAlignment = Alignment.Center) {
                RadarPulseAnimation(
                    modifier = Modifier.size(150.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ShareLocation,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(Sizes.iconXl)
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            Text(
                text = "Family Radar",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "La tua famiglia sulla mappa, in tempo reale",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xs)
            )

            Spacer(Modifier.height(Spacing.xxl))

            // ---- Card di accesso ----
            Surface(
                shape = RoundedCornerShape(Radius.xl),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg)
                ) {
                    // Google: azione primaria
                    OutlinedButton(
                        onClick = {
                            clearFeedback()
                            isLoading = true
                            coroutineScope.launch {
                                val result = repository.signInWithGoogle(activity ?: context)
                                isLoading = false
                                if (result.isSuccess) {
                                    onAuthSuccess()
                                } else {
                                    errorMessage = result.exceptionOrNull()?.localizedMessage
                                        ?: "Accesso Google non riuscito"
                                }
                            }
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(Radius.sm),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("google_sign_in_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(Spacing.md))
                            Text("Connessione…", style = MaterialTheme.typography.labelLarge)
                        } else {
                            GoogleIcon(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(Spacing.md))
                            Text(
                                text = "Continua con Google",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    LabeledDivider("oppure")

                    // Selettore metodo
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        PillChip(
                            label = "Telefono",
                            icon = Icons.Default.PhoneAndroid,
                            selected = selectedMethod == AuthMethod.PHONE,
                            onClick = { selectedMethod = AuthMethod.PHONE; clearFeedback() },
                            modifier = Modifier.weight(1f)
                        )
                        PillChip(
                            label = "Email",
                            icon = Icons.Default.Email,
                            selected = selectedMethod == AuthMethod.EMAIL,
                            onClick = { selectedMethod = AuthMethod.EMAIL; clearFeedback() },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    AnimatedContent(
                        targetState = selectedMethod,
                        transitionSpec = {
                            val forward = targetState.ordinal > initialState.ordinal
                            val offset = if (forward) 1 else -1
                            (slideInHorizontally(tween(220)) { it * offset / 4 } + fadeIn(tween(220)))
                                .togetherWith(
                                    slideOutHorizontally(tween(180)) { -it * offset / 4 } + fadeOut(tween(180))
                                )
                        },
                        label = "auth_method"
                    ) { method ->
                        when (method) {
                            AuthMethod.PHONE -> PhoneAuthForm(
                                phoneNumber = phoneNumber,
                                onPhoneChange = { phoneNumber = it },
                                smsCode = smsCode,
                                onSmsCodeChange = { smsCode = it.filter { c -> c.isDigit() }.take(6) },
                                displayName = phoneDisplayName,
                                onDisplayNameChange = { phoneDisplayName = it },
                                isCodeSent = isCodeSent,
                                isLoading = isLoading,
                                onSendCode = {
                                    clearFeedback()
                                    val cleaned = phoneNumber.trim().replace(" ", "")
                                    when {
                                        cleaned.length < 8 ->
                                            errorMessage = "Inserisci un numero valido con prefisso"
                                        activity == null ->
                                            errorMessage = "Errore di contesto Activity"
                                        else -> {
                                            isLoading = true
                                            repository.sendPhoneVerificationCode(
                                                activity = activity,
                                                phoneNumber = cleaned,
                                                onCodeSent = { vId ->
                                                    isLoading = false
                                                    verificationId = vId
                                                    isCodeSent = true
                                                    successMessage = "Codice SMS inviato a $cleaned"
                                                },
                                                onVerificationCompleted = {
                                                    isLoading = false
                                                    onAuthSuccess()
                                                },
                                                onVerificationFailed = { e ->
                                                    isLoading = false
                                                    errorMessage = e.localizedMessage
                                                        ?: "Errore invio SMS di verifica"
                                                }
                                            )
                                        }
                                    }
                                },
                                onVerify = {
                                    clearFeedback()
                                    val vId = verificationId
                                    if (vId == null || smsCode.length < 6) {
                                        errorMessage = "Inserisci il codice completo a 6 cifre"
                                    } else {
                                        isLoading = true
                                        coroutineScope.launch {
                                            val result = repository.verifyPhoneCodeAndSignIn(
                                                verificationId = vId,
                                                smsCode = smsCode.trim(),
                                                displayName = phoneDisplayName.trim(),
                                                phoneNumber = phoneNumber.trim()
                                            )
                                            isLoading = false
                                            if (result.isSuccess) {
                                                onAuthSuccess()
                                            } else {
                                                errorMessage = result.exceptionOrNull()?.localizedMessage
                                                    ?: "Codice non valido o scaduto"
                                            }
                                        }
                                    }
                                },
                                onEditNumber = {
                                    isCodeSent = false
                                    smsCode = ""
                                    clearFeedback()
                                },
                                onImeDone = { focusManager.clearFocus() }
                            )

                            AuthMethod.EMAIL -> EmailAuthForm(
                                isLoginMode = isLoginMode,
                                onModeChange = { isLoginMode = it; clearFeedback() },
                                email = email,
                                onEmailChange = { email = it },
                                password = password,
                                onPasswordChange = { password = it },
                                passwordVisible = passwordVisible,
                                onTogglePasswordVisibility = { passwordVisible = !passwordVisible },
                                displayName = displayName,
                                onDisplayNameChange = { displayName = it },
                                isLoading = isLoading,
                                onSubmit = {
                                    clearFeedback()
                                    if (email.isBlank() || password.isBlank()) {
                                        errorMessage = "Inserisci email e password per continuare"
                                    } else {
                                        isLoading = true
                                        coroutineScope.launch {
                                            val result = if (isLoginMode) {
                                                repository.signInWithEmail(email.trim(), password)
                                            } else {
                                                repository.signUpWithEmail(
                                                    email.trim(), password, displayName.trim()
                                                )
                                            }
                                            isLoading = false
                                            if (result.isSuccess) {
                                                onAuthSuccess()
                                            } else {
                                                errorMessage = result.exceptionOrNull()?.localizedMessage
                                                    ?: "Errore di autenticazione"
                                            }
                                        }
                                    }
                                },
                                onImeDone = { focusManager.clearFocus() }
                            )
                        }
                    }

                    AnimatedVisibility(visible = successMessage != null) {
                        InfoBanner(
                            text = successMessage.orEmpty(),
                            icon = Icons.Default.CheckCircle,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            accentColor = MaterialTheme.colorScheme.secondary
                        )
                    }

                    AnimatedVisibility(visible = errorMessage != null) {
                        InfoBanner(
                            text = errorMessage.orEmpty(),
                            icon = Icons.Default.ErrorOutline
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            Text(
                text = "La posizione è condivisa solo con i gruppi a cui appartieni " +
                    "e puoi interromperla in qualsiasi momento.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.md)
            )

            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

// ============================================================================
// FORM
// ============================================================================

@Composable
private fun PhoneAuthForm(
    phoneNumber: String,
    onPhoneChange: (String) -> Unit,
    smsCode: String,
    onSmsCodeChange: (String) -> Unit,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    isCodeSent: Boolean,
    isLoading: Boolean,
    onSendCode: () -> Unit,
    onVerify: () -> Unit,
    onEditNumber: () -> Unit,
    onImeDone: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (!isCodeSent) {
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = onPhoneChange,
                label = { Text("Numero di telefono") },
                placeholder = { Text("+39 333 1234567") },
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onImeDone() }),
                singleLine = true,
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("phone_number_input")
            )

            PrimaryActionButton(
                label = "Invia codice SMS",
                icon = Icons.Default.Sms,
                isLoading = isLoading,
                onClick = onSendCode,
                testTag = "send_phone_code_button"
            )
        } else {
            Text(
                text = "Abbiamo inviato un codice a $phoneNumber",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = smsCode,
                onValueChange = onSmsCodeChange,
                label = { Text("Codice a 6 cifre") },
                leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                singleLine = true,
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sms_code_input")
            )

            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text("Come ti chiami") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onImeDone() }),
                singleLine = true,
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("phone_name_input")
            )

            PrimaryActionButton(
                label = "Verifica e accedi",
                icon = Icons.Default.LockOpen,
                isLoading = isLoading,
                onClick = onVerify,
                testTag = "verify_phone_code_button"
            )

            TextButton(
                onClick = onEditNumber,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Cambia numero")
            }
        }
    }
}

@Composable
private fun EmailAuthForm(
    isLoginMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisibility: () -> Unit,
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    isLoading: Boolean,
    onSubmit: () -> Unit,
    onImeDone: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            PillChip(
                label = "Accedi",
                selected = isLoginMode,
                onClick = { onModeChange(true) },
                modifier = Modifier.weight(1f)
            )
            PillChip(
                label = "Registrati",
                selected = !isLoginMode,
                onClick = { onModeChange(false) },
                modifier = Modifier.weight(1f)
            )
        }

        AnimatedVisibility(visible = !isLoginMode) {
            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text("Nome completo") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier.fillMaxWidth()
            )
        }

        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            singleLine = true,
            shape = RoundedCornerShape(Radius.sm),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onTogglePasswordVisibility) {
                    Icon(
                        if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (passwordVisible) "Nascondi password" else "Mostra password"
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onImeDone() }),
            singleLine = true,
            shape = RoundedCornerShape(Radius.sm),
            modifier = Modifier.fillMaxWidth()
        )

        PrimaryActionButton(
            label = if (isLoginMode) "Accedi" else "Crea account",
            icon = if (isLoginMode) Icons.Default.Login else Icons.Default.PersonAdd,
            isLoading = isLoading,
            onClick = onSubmit
        )
    }
}

// ============================================================================
// COMPONENTI LOCALI
// ============================================================================

@Composable
private fun PrimaryActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    Button(
        onClick = onClick,
        enabled = !isLoading,
        shape = RoundedCornerShape(Radius.sm),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Sizes.iconMd))
            Spacer(Modifier.width(Spacing.sm))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun LabeledDivider(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md)
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * Logo Google disegnato su Canvas: quattro archi colorati più la barra orizzontale.
 * Evita di dover impacchettare l'asset ufficiale, che ha vincoli di licenza sul brand.
 */
@Composable
fun GoogleIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.22f
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)

        val red = Color(0xFFEA4335)
        val blue = Color(0xFF4285F4)
        val yellow = Color(0xFFFBBC05)
        val green = Color(0xFF34A853)

        // Archi: rosso in alto, giallo a sinistra, verde in basso, blu a destra.
        drawArc(
            color = red, startAngle = -135f, sweepAngle = 100f, useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )
        drawArc(
            color = yellow, startAngle = 125f, sweepAngle = 90f, useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )
        drawArc(
            color = green, startAngle = 35f, sweepAngle = 90f, useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )
        drawArc(
            color = blue, startAngle = -35f, sweepAngle = 70f, useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        )

        // Barra orizzontale della "G".
        drawRect(
            color = blue,
            topLeft = Offset(size.width * 0.5f, size.height * 0.5f - stroke / 2f),
            size = Size(size.width * 0.5f - inset, stroke)
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
