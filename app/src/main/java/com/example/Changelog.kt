package com.example

/**
 * Novità della versione corrente, mostrate una volta dopo ogni aggiornamento
 * dalla dialog "Novità" (vedi FamilyRadarApp).
 *
 * REGOLA: aggiornare [VERSION] e [LINES] a OGNI push. Testo brevissimo e semplice:
 * cosa e' stato aggiunto e come si usa, oppure quale bug e' stato risolto.
 */
object Changelog {

    /** Deve combaciare con versionName di questo build. */
    const val VERSION = "0.65.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🗺 Mappa unificata: solo MapLibre. Stile e rilievo 3D sempre disponibili nelle impostazioni.",
        "📍 Dialog luogo usa la stessa mappa principale, parte dalla posizione corrente."
    )
}
