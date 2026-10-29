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
    const val VERSION = "0.71.15-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🔕 Rimossa la notifica di \"download in corso\"; l'avanzamento si vede nella finestra in-app. Resta l'avviso di nuovo aggiornamento."
    )
}
