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
    const val VERSION = "0.13.24-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "↩️ Rispondi a un messaggio, elimina (per tutti/per me) e copia: tieni premuto sulla bolla.",
        "📍 Sotto ogni messaggio vedi dov'è chi scrive e la distanza da te.",
        "📅 Separatori Oggi/Ieri tra i messaggi; via i vecchi avvisi arrivato/partito dalla chat."
    )
}
