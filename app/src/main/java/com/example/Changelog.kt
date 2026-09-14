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
    const val VERSION = "0.16.3-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🐞 Alta precisione in movimento: ora manda davvero ogni fix su Firestore — il filtro di distanza non la bloccava piu'.",
        "🐞 Pallino GPS: eliminato il salto enorme in avanti e il ritorno lento indietro.",
        "✨ Timestamp ultimo fix preciso al secondo.",
        "✨ Interpolazione GPS per tutti i membri in movimento."
    )
}
