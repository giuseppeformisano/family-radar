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
    const val VERSION = "0.48.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🗺️ Heatmap piu' leggera: i punti si salvano a blocchi (molte meno scritture) e scadono da soli dopo 30 giorni.",
        "🎨 Indicatori di caricamento ora seguono il colore del tema (anche in modalita' scura).",
        "🧹 App piu' leggera: rimosse librerie inutilizzate."
    )
}
