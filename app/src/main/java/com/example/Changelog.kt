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
    const val VERSION = "0.13.20-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🎙️ Vocali fino a 2 minuti (prima erano max 5 secondi).",
        "🗺️ Traccia dei viaggi in diretta più fluida: aggiornata ogni 10s invece di 30-60s.",
        "🔔 Notifica \"in movimento\": una sola volta per spostamento, niente ripetizioni durante lo stesso viaggio."
    )
}
