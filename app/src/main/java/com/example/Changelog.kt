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
    const val VERSION = "0.71.6-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🪄 Menu Livelli: pulsanti tondi e ventaglio più compatto attorno al pulsante.",
        "📱 Chat e Luoghi non coprono più la barra in basso; il tasto Indietro torna alla mappa."
    )
}
