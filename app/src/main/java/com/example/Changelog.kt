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
    const val VERSION = "0.47.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "⚡ 1 aggiornamento/sec solo se 'Alta precisione in movimento' e' attiva.",
        "⏱️ Intervallo minimo impostabile: 1 secondo (era 5).",
        "✅ Conferma visiva quando l'intervallo viene salvato."
    )
}
