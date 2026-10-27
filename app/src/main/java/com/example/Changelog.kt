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
    const val VERSION = "0.69.2-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🏠 Widget home ora anche piccolo (2x2): foto, stato e batteria del familiare scelto.",
        "⚡ Dopo aver scelto il familiare, il widget si aggiorna subito."
    )
}
