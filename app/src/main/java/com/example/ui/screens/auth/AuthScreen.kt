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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.repository.FirebaseRepository
import com.example.ui.components.*
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
            .background(Color.Black)
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
            Spacer(Modifier.height(Spacing.xl))

            // ---- Header ----
            Text(
                text = "Family Radar",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                ),
                color = Color(0xFFF2F2F7),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs)
            )

            Text(
                text = stringResource(R.string.auth_tagline),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                ),
                color = Color(0xFFA1A1AA),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.xs, end = Spacing.xs, top = Spacing.xxs)
            )

            Spacer(Modifier.height(Spacing.xl))

            // ---- Illustrazione Radar Centrale ----
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(vertical = Spacing.md)
            ) {
                RadarPulseAnimation(
                    modifier = Modifier.size(150.dp),
                    color = Color(0xFF6366F1)
                )
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFF6366F1),
                                    Color(0xFF4F46E5)
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

            // ---- Card dei Contenuti (Login Area) ----
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0x0A71717A),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1F71717A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg)
                ) {
                    // Google Login: Rettangolare con angoli arrotondati e sfondo pulito
                    Button(
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
                                        ?: context.getString(R.string.auth_google_failed)
                                }
                            }
                        },
                        enabled = !isLoading,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("google_sign_in_button")
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF4F46E5),
                                trackColor = Color(0x334F46E5)
                            )
                            Spacer(Modifier.width(Spacing.md))
                            Text(
                                stringResource(R.string.auth_connecting),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                                color = Color.Black
                            )
                        } else {
                            GoogleIcon(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(Spacing.md))
                            Text(
                                text = stringResource(R.string.auth_google_button),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                ),
                                color = Color.Black
                            )
                        }
                    }

                    LabeledDivider(stringResource(R.string.label_or))

                    // Selettore metodo di accesso (Phone / Email)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0x0EFFFFFF),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1AFFFFFF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            val isPhone = selectedMethod == AuthMethod.PHONE
                            Button(
                                onClick = { selectedMethod = AuthMethod.PHONE; clearFeedback() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPhone) Color(0xFF6366F1) else Color.Transparent,
                                    contentColor = if (isPhone) Color.White else Color(0xFFA1A1AA)
                                ),
                                contentPadding = PaddingValues(vertical = Spacing.sm),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    modifier = Modifier.size(Sizes.iconSm)
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    stringResource(R.string.auth_method_phone),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                )
                            }
                            Button(
                                onClick = { selectedMethod = AuthMethod.EMAIL; clearFeedback() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!isPhone) Color(0xFF6366F1) else Color.Transparent,
                                    contentColor = if (!isPhone) Color.White else Color(0xFFA1A1AA)
                                ),
                                contentPadding = PaddingValues(vertical = Spacing.sm),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Email,
                                    contentDescription = null,
                                    modifier = Modifier.size(Sizes.iconSm)
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    stringResource(R.string.auth_method_email),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                )
                            }
                        }
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
                                            errorMessage = context.getString(R.string.err_invalid_phone)
                                        activity == null ->
                                            errorMessage = context.getString(R.string.err_activity_context)
                                        else -> {
                                            isLoading = true
                                            repository.sendPhoneVerificationCode(
                                                activity = activity,
                                                phoneNumber = cleaned,
                                                onCodeSent = { vId ->
                                                    isLoading = false
                                                    verificationId = vId
                                                    isCodeSent = true
                                                    successMessage = context.getString(R.string.toast_sms_sent, cleaned)
                                                },
                                                onVerificationCompleted = {
                                                    isLoading = false
                                                    onAuthSuccess()
                                                },
                                                onVerificationFailed = { e ->
                                                    isLoading = false
                                                    errorMessage = e.localizedMessage
                                                        ?: context.getString(R.string.err_sms_send)
                                                }
                                            )
                                        }
                                    }
                                },
                                onVerify = {
                                    clearFeedback()
                                    val vId = verificationId
                                    if (vId == null || smsCode.length < 6) {
                                        errorMessage = context.getString(R.string.err_incomplete_code)
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
                                                    ?: context.getString(R.string.err_invalid_code)
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
                                        errorMessage = context.getString(R.string.err_email_password_required)
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
                                                    ?: context.getString(R.string.err_auth_generic)
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
                text = stringResource(R.string.auth_privacy_note),
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
                label = { Text(stringResource(R.string.label_phone_number)) },
                placeholder = { Text(stringResource(R.string.placeholder_phone)) },
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
                label = stringResource(R.string.action_send_sms),
                icon = Icons.Default.Sms,
                isLoading = isLoading,
                onClick = onSendCode,
                testTag = "send_phone_code_button"
            )
        } else {
            Text(
                text = stringResource(R.string.auth_code_sent, phoneNumber),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = smsCode,
                onValueChange = onSmsCodeChange,
                label = { Text(stringResource(R.string.label_sms_code)) },
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
                label = { Text(stringResource(R.string.label_display_name)) },
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
                label = stringResource(R.string.action_verify_and_login),
                icon = Icons.Default.LockOpen,
                isLoading = isLoading,
                onClick = onVerify,
                testTag = "verify_phone_code_button"
            )

            TextButton(
                onClick = onEditNumber,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(stringResource(R.string.action_change_number))
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
                label = stringResource(R.string.action_sign_in),
                selected = isLoginMode,
                onClick = { onModeChange(true) },
                modifier = Modifier.weight(1f)
            )
            PillChip(
                label = stringResource(R.string.action_register),
                selected = !isLoginMode,
                onClick = { onModeChange(false) },
                modifier = Modifier.weight(1f)
            )
        }

        AnimatedVisibility(visible = !isLoginMode) {
            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text(stringResource(R.string.label_full_name)) },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier.fillMaxWidth()
            )
        }

        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(R.string.auth_method_email)) },
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
                        contentDescription = if (passwordVisible)
                            stringResource(R.string.action_hide_password)
                        else
                            stringResource(R.string.action_show_password)
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
            label = if (isLoginMode) stringResource(R.string.action_sign_in)
                    else stringResource(R.string.action_create_account),
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
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF4F46E5),
            contentColor = Color.White
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color.White,
                trackColor = Color(0x33FFFFFF)
            )
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Sizes.iconMd))
            Spacer(Modifier.width(Spacing.sm))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            )
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
