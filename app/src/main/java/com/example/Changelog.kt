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
    const val VERSION = "0.13.25-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "✅ Spunta di stato sui tuoi messaggi: orologio (in invio) → spunta (inviato) → doppia spunta (letto).",
        "👀 \"Visto da…\" sotto il tuo ultimo messaggio quando gli altri lo leggono."
    )
}
