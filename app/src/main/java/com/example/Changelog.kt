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
    const val VERSION = "0.13.32-beta"

    /** Poche righe, corte. Una per novita'/fix. */
    val LINES = listOf(
        "🎙️ Push-to-talk: autoplay del vocale ora attivo di default e niente notifica che lo interrompe.",
        "🔵 Spunte blu \"visto da\" ora funzionano anche a chat già aperta (risolto lo sfasamento di orario tra telefoni)."
    )
}
