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
    const val VERSION = "0.71.21-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🧩 Widget: risolto il testo tagliato — ora le scritte hanno lo spazio giusto e vanno a capo (1x1 e 2x1)."
    )
}
