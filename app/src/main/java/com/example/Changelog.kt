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
    const val VERSION = "0.24.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🧪 Nuova mappa attivabile: in Impostazioni > Aspetto > \"Usa nuova mappa (beta)\" puoi sostituire la mappa attuale con quella nuova. Per ora mostra pallini e scia (agganciata alle strade); luoghi, snapshot e 'segui' arrivano nei prossimi step."
    )
}
