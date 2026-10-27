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
    const val VERSION = "0.69.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🏠 Nuovo widget per la home: scegli un familiare e vedi al volo dove si trova, se è online e la batteria.",
        "ℹ️ Tieni premuto sulla home → Widget → Family Radar per aggiungerlo."
    )
}
