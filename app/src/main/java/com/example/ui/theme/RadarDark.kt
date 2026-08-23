package com.example.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette scura condivisa, identica a quella usata a mano dalle schermate
 * principali (mappa, login, selezione gruppi). Vive qui come sorgente unica di
 * verita' cosi' che le dialog di dettaglio e il pannello impostazioni non
 * ridefiniscano gli stessi esadecimali: se un domani il tema cambia, si tocca
 * solo questo file.
 *
 * Non passa da MaterialTheme di proposito: quelle schermate hanno un look scuro
 * fisso a prescindere da dynamic color / tema di sistema, e le superfici che lo
 * adottano devono restare coerenti con loro, non col colorScheme.
 */
object RadarDark {
    /** Sfondo pieno delle schermate a tutto campo. */
    val Bg = Color.Black

    /** Superficie primaria (card) su fondo nero: tint grigio appena percettibile. */
    val Card = Color(0x0A71717A)
    val CardBorder = Color(0x1F71717A)

    /** Superficie annidata (chip, righe, sotto-card): velo bianco tenue. */
    val Surface = Color(0x0EFFFFFF)
    val SurfaceBorder = Color(0x1AFFFFFF)

    /** Accento primario (pulsanti pieni) e sua variante piu' chiara (icone, stati attivi). */
    val Accent = Color(0xFF4F46E5)
    val AccentLight = Color(0xFF6366F1)

    /** Testo principale e testo attenuato/secondario. */
    val TextPrimary = Color(0xFFF2F2F7)
    val TextMuted = Color(0xFFA1A1AA)

    /** Divisori sottili su fondo scuro. */
    val Divider = Color(0x1FFFFFFF)
}
