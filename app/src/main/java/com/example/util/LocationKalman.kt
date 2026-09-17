package com.example.util

import kotlin.math.max

/**
 * Filtro unico per ripulire il segnale GPS. Sostituisce le tante "pezze" (soglia
 * rumore, scarto salti, scarto scossoni, ecc.) con un solo meccanismo coerente:
 * un filtro di Kalman a sola posizione, quello classico per il GPS.
 *
 * Idea, in parole semplici:
 *  - tiene una stima della posizione e quanto e' incerta (varianza P, in metri²);
 *  - a ogni nuovo fix, il peso dato al fix dipende dalla sua PRECISIONE dichiarata
 *    (accuracy): un fix preciso sposta molto la stima, uno impreciso pochissimo;
 *  - piu' tempo passa, piu' la stima "si apre" (P cresce) e torna a fidarsi dei fix,
 *    cosi' un movimento vero viene seguito; da fermi i fix ballerini pesano poco e
 *    il pallino resta stabile — senza soglie inventate.
 *
 * Unico parametro da tarare: [qMetersPerSec] = quanto velocemente la posizione puo'
 * cambiare (incertezza di processo). Alto = segue in fretta i fix (meno liscio),
 * basso = piu' liscio e stabile (ma piu' "molle"). E' la manopola esposta in app.
 */
class LocationKalman(
    private var qMetersPerSec: Double = 3.0
) {
    private var lat = 0.0
    private var lon = 0.0
    private var variance = -1.0 // <0 = non inizializzato
    private var lastTimeMs = 0L

    fun setProcessNoise(q: Double) {
        qMetersPerSec = q.coerceIn(0.1, 30.0)
    }

    /** Reset: al prossimo fix riparte da capo (es. cambio utente/gruppo). */
    fun reset() {
        variance = -1.0
    }

    /**
     * Elabora un fix grezzo e ritorna la posizione filtrata (lat, lon).
     * [accuracyMeters] e' il raggio d'errore del fix (piu' piccolo = piu' preciso).
     */
    fun process(newLat: Double, newLon: Double, accuracyMeters: Float, timeMs: Long): Pair<Double, Double> {
        val acc = max(accuracyMeters.toDouble(), 1.0)
        if (variance < 0) {
            // Primo fix: si parte da qui.
            lat = newLat
            lon = newLon
            variance = acc * acc
            lastTimeMs = timeMs
            return lat to lon
        }

        // Predizione: piu' tempo passa, piu' l'incertezza cresce (la persona puo'
        // essersi spostata). Q e' in m/s, quindi Q²·dt e' varianza in metri².
        val dtSec = max((timeMs - lastTimeMs) / 1000.0, 0.0)
        lastTimeMs = timeMs
        variance += dtSec * qMetersPerSec * qMetersPerSec

        // Aggiornamento: guadagno di Kalman = quanto credere al nuovo fix.
        val k = variance / (variance + acc * acc)
        lat += k * (newLat - lat)
        lon += k * (newLon - lon)
        variance *= (1.0 - k)

        return lat to lon
    }
}
