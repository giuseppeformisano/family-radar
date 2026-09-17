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
    const val VERSION = "0.18.2-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🐞 Segui un membro: la mappa ora insegue la sua posizione VERA, non piu' un punto indovinato lontano da dove si trova davvero.",
        "✨ Movimento sulla mappa ripensato: il pallino scivola verso l'ultima posizione reale invece di provare a predire dove andrai — niente piu' salti nel vuoto."
    )
}
