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
    const val VERSION = "0.16.1-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🐞 Marker GPS: eliminato il salto avanti-indietro al cambio di fix — il ticker ora mantiene la posizione interpolata.",
        "✨ Timestamp ultimo fix preciso al secondo (es. '5s fa', '2m 30s fa').",
        "✨ Interpolazione GPS per tutti i membri in movimento, low-pass filter, recupero post-gap."
    )
}
