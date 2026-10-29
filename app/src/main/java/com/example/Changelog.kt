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
    const val VERSION = "0.71.17-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🧩 Widget rinnovato: unico e ridimensionabile (da 1x1 a 2x1), scritte più leggibili e ora mostra la foto del profilo.",
        "🗺️ Puoi togliere i \"Percorsi\" con un pulsante che compare sulla mappa; se ne vede uno per volta."
    )
}
