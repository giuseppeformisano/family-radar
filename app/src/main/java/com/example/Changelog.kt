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
    const val VERSION = "0.17.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🐞 Tema chiaro: sistemate tutte le schermate che restavano scure anche con il tema chiaro attivo.",
        "✨ Foto e snapshot: zoom con le dita anche nel carosello, controlli che spariscono al tocco, scorri verso il basso per chiudere.",
        "✨ Chat: reazioni con emoji sui messaggi (tieni premuto un messaggio).",
        "✨ Dialoghi piu' coerenti con lo stile dell'app."
    )
}
