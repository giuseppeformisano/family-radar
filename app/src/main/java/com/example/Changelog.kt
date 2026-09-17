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
    const val VERSION = "0.19.0-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "✨ Tracciamento affidabile: nelle Impostazioni ora c'e' una scheda che ti dice se il telefono blocca l'app in background e ti porta all'impostazione giusta per risolvere.",
        "✨ Ti avvisa anche del blocco extra dei produttori (Xiaomi, Samsung, Huawei), la causa principale delle posizioni che non si aggiornano."
    )
}
