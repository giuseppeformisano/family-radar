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
    const val VERSION = "0.41.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "📍 Pallino e scia agganciati alle strade reali usando i dati gia' caricati in mappa.",
        "🗺️ Heatmap disponibile anche sulla nuova mappa: tocca un membro e premi Heatmap."
    )
}
