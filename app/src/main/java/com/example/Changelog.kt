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
    const val VERSION = "0.34.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🐞 Velocita' nel dettaglio persona ora calcolata dal movimento reale: niente piu' 'fermo' mentre ci si muove.",
        "🧹 Tolto il pulsante 'Messaggia' dal dettaglio persona (la chat e' gia' condivisa).",
        "✨ Simulazione tragitto che segue le strade vere."
    )
}
