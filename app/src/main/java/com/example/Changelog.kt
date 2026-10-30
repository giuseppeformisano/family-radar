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
    const val VERSION = "0.71.26-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "💬 Nuove bolle chat: foto profilo, nome, luogo e distanza, orario tutto dentro la bolla (coda a sinistra se ricevuto, a destra se inviato).",
        "👀 \"Visto da…\" ora si trova nel menu che si apre tenendo premuto il messaggio."
    )
}
