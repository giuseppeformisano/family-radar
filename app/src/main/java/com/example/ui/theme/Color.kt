package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// PALETTE — token grezzi.
// Non usarli direttamente nelle schermate: passa sempre da MaterialTheme.colorScheme
// o da RadarPalette (vedi Theme.kt), così dark/light restano coerenti.
// ============================================================================

// --- Brand: indigo (primario) ---
private val Indigo50 = Color(0xFFEEF2FF)
private val Indigo100 = Color(0xFFE0E7FF)
private val Indigo200 = Color(0xFFC7D2FE)
private val Indigo300 = Color(0xFFA5B4FC)
private val Indigo400 = Color(0xFF818CF8)
private val Indigo500 = Color(0xFF6366F1)
private val Indigo600 = Color(0xFF4F46E5)
private val Indigo900 = Color(0xFF312E81)
private val Indigo950 = Color(0xFF1E1B4B)

// --- Accento: teal (secondario, "online / sicuro") ---
private val Teal100 = Color(0xFFCCFBF1)
private val Teal300 = Color(0xFF5EEAD4)
private val Teal400 = Color(0xFF2DD4BF)
private val Teal600 = Color(0xFF0D9488)
private val Teal900 = Color(0xFF134E4A)

// --- Terziario: ambra (attesa / avvisi non critici) ---
private val Amber200 = Color(0xFFFDE68A)
private val Amber400 = Color(0xFFFBBF24)
private val Amber600 = Color(0xFFD97706)
private val Amber900 = Color(0xFF78350F)

// --- Errore / SOS ---
private val Red200 = Color(0xFFFECACA)
private val Red400 = Color(0xFFF87171)
private val Red600 = Color(0xFFDC2626)
private val Red950 = Color(0xFF450A0A)

// --- Neutri ---
private val Slate50 = Color(0xFFF8FAFC)
private val Slate100 = Color(0xFFF1F5F9)
private val Slate200 = Color(0xFFE2E8F0)
private val Slate400 = Color(0xFF94A3B8)
private val Slate500 = Color(0xFF64748B)
private val Slate700 = Color(0xFF334155)
private val Slate800 = Color(0xFF1E293B)
private val Slate900 = Color(0xFF0F172A)

// --- Superfici dark dedicate (più fredde e profonde dei neutri Slate) ---
private val Ink900 = Color(0xFF0A0E17)
private val Ink800 = Color(0xFF111827)
private val Ink700 = Color(0xFF1A2235)
private val Ink600 = Color(0xFF273349)

// ============================================================================
// LIGHT
// ============================================================================
val PrimaryLight = Indigo600
val OnPrimaryLight = Color.White
val PrimaryContainerLight = Indigo100
val OnPrimaryContainerLight = Indigo900

val SecondaryLight = Teal600
val OnSecondaryLight = Color.White
val SecondaryContainerLight = Teal100
val OnSecondaryContainerLight = Teal900

val TertiaryLight = Amber600
val OnTertiaryLight = Color.White
val TertiaryContainerLight = Amber200
val OnTertiaryContainerLight = Amber900

val BackgroundLight = Slate50
val SurfaceLight = Color.White
val SurfaceVariantLight = Slate100
val OnSurfaceLight = Slate900
val OnSurfaceVariantLight = Slate500
val OutlineLight = Slate200

val ErrorLight = Red600
val OnErrorLight = Color.White
val ErrorContainerLight = Red200
val OnErrorContainerLight = Color(0xFF7F1D1D)

// ============================================================================
// DARK
// ============================================================================
val PrimaryDark = Indigo400
val OnPrimaryDark = Indigo950
val PrimaryContainerDark = Indigo900
val OnPrimaryContainerDark = Indigo200

val SecondaryDark = Teal400
val OnSecondaryDark = Color(0xFF042F2E)
val SecondaryContainerDark = Teal900
val OnSecondaryContainerDark = Teal300

val TertiaryDark = Amber400
val OnTertiaryDark = Color(0xFF451A03)
val TertiaryContainerDark = Amber900
val OnTertiaryContainerDark = Amber200

val BackgroundDark = Ink900
val SurfaceDark = Ink800
val SurfaceVariantDark = Ink700
val OnSurfaceDark = Slate50
val OnSurfaceVariantDark = Slate400
val OutlineDark = Ink600

val ErrorDark = Red400
val OnErrorDark = Red950
val ErrorContainerDark = Color(0xFF7F1D1D)
val OnErrorContainerDark = Red200

// ============================================================================
// COLORI SEMANTICI — non stanno in ColorScheme perché Material 3 non ha slot
// per "batteria scarica", "membro offline", categorie di luogo, ecc.
// Esposti via RadarPalette / LocalRadarPalette (Theme.kt).
// ============================================================================

/** Colori che non cambiano fra i due temi: mantengono lo stesso significato. */
object RadarSemantic {
    val Online = Color(0xFF22C55E)
    val Idle = Color(0xFFEAB308)
    val Offline = Slate400
    val BatteryLow = Color(0xFFEF4444)
    val BatteryMid = Color(0xFFF59E0B)
    val BatteryOk = Color(0xFF22C55E)
    val Snapshot = Color(0xFFEA580C)
    val Sos = Color(0xFFDC2626)

    // Categorie di luogo — allineate a PlaceCategory
    val PlaceHome = Color(0xFF6366F1)
    val PlaceWork = Color(0xFF0D9488)
    val PlaceSchool = Color(0xFFD97706)
    val PlaceGym = Color(0xFFDC2626)
    val PlaceOther = Slate500
}

/**
 * Gradienti usati per gli sfondi hero e per gli scrim sopra la mappa.
 * Sono coppie [inizio, fine] già ordinate per un gradiente verticale.
 */
data class RadarGradients(
    val heroTop: Color,
    val heroBottom: Color,
    val mapScrimTop: Color,
    val mapScrimBottom: Color,
    val glassTint: Color,
    val glassBorder: Color
)

val LightGradients = RadarGradients(
    heroTop = Indigo50,
    heroBottom = Slate50,
    mapScrimTop = Color(0xB3FFFFFF),
    mapScrimBottom = Color(0x00FFFFFF),
    glassTint = Color(0xF2FFFFFF),
    glassBorder = Color(0x1A0F172A)
)

val DarkGradients = RadarGradients(
    heroTop = Ink800,
    heroBottom = Ink900,
    mapScrimTop = Color(0xB30A0E17),
    mapScrimBottom = Color(0x000A0E17),
    glassTint = Color(0xF2111827),
    glassBorder = Color(0x26FFFFFF)
)
