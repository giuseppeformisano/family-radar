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
    const val VERSION = "0.17.5-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🐞 Basta pallini che ballano da fermi: quando il GPS e' scarso, l'app riconosce il rumore e tiene il pallino fermo invece di mandarlo a spasso.",
        "✨ La soglia anti-rumore si adatta alla qualita' del segnale: movimento vero in auto fluido, rumore da fermo ignorato."
    )
}
