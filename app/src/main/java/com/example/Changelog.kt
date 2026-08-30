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
    const val VERSION = "0.13.18-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🔊 Backend audio corretto: le nuove note vocali ora sono riproducibili.",
        "🐞 Push-to-talk: messaggi d'errore a schermo se un vocale non parte, per capire dove si rompe."
    )
}
