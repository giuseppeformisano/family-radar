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
    const val VERSION = "0.71.28-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🖼️ Finestra istantanee rinnovata nello stile dell'app (scheda di vetro, maniglia, avatar).",
        "📍 Pallini mappa: immagine interna più grande e colore Casa allineato alle categorie."
    )
}
