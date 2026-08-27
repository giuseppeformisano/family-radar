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
    const val VERSION = "0.13.2-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🎙️ Note vocali: tieni premuto il microfono in basso a destra sulla mappa per registrare (max 5s).",
        "Chi guarda la mappa vede l'anello pulsare sul tuo pallino e può toccarlo per ascoltare; il vocale resta anche in chat.",
        "🆕 Questa schermata: da ora ti mostra in breve le novità dopo ogni aggiornamento."
    )
}
