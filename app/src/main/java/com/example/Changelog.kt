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
    const val VERSION = "0.16.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "✨ Interpolazione GPS per tutti i membri in movimento, non solo chi si segue.",
        "✨ Filtro low-pass sui fix GPS: meno sbalzi e jitter sui marker.",
        "✨ Animazione di recupero dopo gap di connettivita': niente piu' salti bruschi.",
        "🐞 Multi-gruppo: con piu' gruppi attivi si vede sempre la lista gruppi all'avvio."
    )
}
