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
    const val VERSION = "0.13.5-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🔧 Vocali ottimizzati: l'audio non appesantisce più la chat in tempo reale, si scarica solo quando premi play. Molto meno consumo di dati/quota.",
        "Nota: i vocali inviati prima di questo aggiornamento potrebbero non riprodursi."
    )
}
