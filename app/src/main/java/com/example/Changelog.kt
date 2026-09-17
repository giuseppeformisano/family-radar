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
    const val VERSION = "0.32.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "✨ Nuovo filtro unico della posizione: pulisce il GPS in modo coerente (in viaggio e da fermo), al posto dei tanti filtri sparsi.",
        "✨ In Impostazioni c'e' una manopola 'Reattivita' filtro posizione' per tararlo dal vivo, senza aspettare nuove versioni."
    )
}
