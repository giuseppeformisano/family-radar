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
    const val VERSION = "0.69.10-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🖼️ Widget: ora si vede la foto (o le iniziali se manca) in entrambi i formati.",
        "📍 Widget 1x1: aggiunta la posizione (luogo o \"In giro\")."
    )
}
