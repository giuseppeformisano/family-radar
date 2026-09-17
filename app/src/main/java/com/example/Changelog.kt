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
    const val VERSION = "0.17.6-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🐞 Basta salti improvvisi di 100 metri: l'app riconosce i singoli punti GPS sballati e li scarta, invece di teletrasportare il pallino e riportarlo indietro.",
        "🐞 Il punto sballato non viene piu' nemmeno salvato, quindi non lo vedono neanche gli altri del gruppo."
    )
}
