package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainer = SurfaceVariantDark,
    surfaceContainerHigh = OutlineDark,
    outline = OutlineDark,
    outlineVariant = OutlineDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    scrim = androidx.compose.ui.graphics.Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainer = SurfaceVariantLight,
    surfaceContainerHigh = OutlineLight,
    outline = OutlineLight,
    outlineVariant = OutlineLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    scrim = androidx.compose.ui.graphics.Color.Black
)

/**
 * Colori che Material 3 non modella (stati di presenza, batteria, categorie di luogo,
 * gradienti e "vetro"). Si leggono con `RadarTheme.palette` da qualunque Composable.
 */
data class RadarPalette(
    val isDark: Boolean,
    val gradients: RadarGradients
)

val LocalRadarPalette = staticCompositionLocalOf {
    RadarPalette(isDark = false, gradients = LightGradients)
}

/** Punto d'accesso ai token non-Material. Uso: `RadarTheme.palette.gradients.glassTint`. */
object RadarTheme {
    val palette: RadarPalette
        @Composable get() = LocalRadarPalette.current
}

/**
 * Tema dell'app.
 *
 * @param themeMode SYSTEM segue il dispositivo; LIGHT e DARK forzano.
 * @param dynamicColor su Android 12+ deriva la palette dallo sfondo del dispositivo
 *        (Material You). Se disattivato — o sotto API 31 — si usa la palette del brand.
 */
@Composable
fun MyApplicationTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val supportsDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme: ColorScheme = when {
        supportsDynamic && darkTheme -> runCatching { dynamicDarkColorScheme(context) }
            .getOrDefault(DarkColorScheme)

        supportsDynamic -> runCatching { dynamicLightColorScheme(context) }
            .getOrDefault(LightColorScheme)

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val palette = RadarPalette(
        isDark = darkTheme,
        gradients = if (darkTheme) DarkGradients else LightGradients
    )

    // Icone di status bar e navigation bar leggibili sopra lo sfondo corrente.
    // In preview/test non c'è una Activity: la SideEffect viene saltata.
    val view = LocalView.current
    val inInspection = LocalInspectionMode.current
    if (!inInspection) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            runCatching {
                val controller = WindowCompat.getInsetsController(activity.window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    // Clamp del font scale: impedisce che testi enormi (150%+) rompano i layout.
    // Il limite 1.3 copre accessibilità comune senza far esplodere contenitori fissi.
    val density = LocalDensity.current
    val cappedDensity = remember(density) {
        Density(density.density, fontScale = density.fontScale.coerceAtMost(1.3f))
    }

    CompositionLocalProvider(
        LocalRadarPalette provides palette,
        LocalDensity provides cappedDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RadarTypography,
            shapes = RadarShapes,
            content = content
        )
    }
}
