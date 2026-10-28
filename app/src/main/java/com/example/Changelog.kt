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
    const val VERSION = "0.71.1-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "✨ Anche i controlli a destra della mappa ora in barra flottante arrotondata; \"Livelli\" si espande con membri/foto/luoghi."
    )
}
