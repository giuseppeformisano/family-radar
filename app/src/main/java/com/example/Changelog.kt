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
    const val VERSION = "0.13.19-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🎙️ Push-to-talk finalmente live: il vocale di un membro parte da solo sulla mappa con il pulse sul suo pallino.",
        "🐞 Corretto il mittente mancante sui vocali, che bloccava l'ascolto in diretta."
    )
}
