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
    const val VERSION = "0.13.17-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🔔 Notifica \"in movimento\": avvisa il gruppo quando ti sposti (auto/bici/corsa), max una ogni 30 min.",
        "🚗 Auto-trip meno sensibile: soglie più alte, niente viaggi fantasma da fermo.",
        "📍 La notifica movimento funziona anche con l'auto-trip spento."
    )
}
