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
    const val VERSION = "0.71.11-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "⚡ App ora distribuita come build ottimizzata (release): avvio e animazioni più reattivi.",
        "🔄 Aggiornamento automatico mantenuto: ti aggiorni senza reinstallare."
    )
}
