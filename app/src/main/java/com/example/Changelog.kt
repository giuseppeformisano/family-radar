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
    const val VERSION = "0.71.10-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🪶 Meno memoria: le foto di chat non vengono più tenute per intero in RAM quando è già disponibile il link, e le immagini si aprono ridimensionate."
    )
}
