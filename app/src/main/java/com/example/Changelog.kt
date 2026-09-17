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
    const val VERSION = "0.27.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🧪 Nuova mappa (beta): i pallini ora si muovono in modo morbido tra un aggiornamento e l'altro."
    )
}
