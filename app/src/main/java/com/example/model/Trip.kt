package com.example.model

/** Come è nato il viaggio: premuto da chi guida, o rilevato dall'app. */
enum class TripSource {
    MANUAL,
    AUTO;

    val label: String
        get() = when (this) {
            MANUAL -> "Manuale"
            AUTO -> "Automatico"
        }

    companion object {
        fun fromRaw(raw: String?): TripSource =
            entries.firstOrNull { it.name == raw } ?: MANUAL
    }
}

data class TripPoint(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = 0L
)

/**
 * Viaggio salvato.
 *
 * [points] è deliberatamente vuoto negli elementi che arrivano dall'elenco:
 * il listener carica solo i metadati, altrimenti aprire il gruppo scaricherebbe
 * la traccia completa di cinquanta viaggi per disegnare polilinee che nessuno
 * sta guardando. I punti si leggono su richiesta con `loadTripTrack`, tranne
 * per un viaggio [isLive], che li porta con sé perché serve mostrarlo in
 * tempo reale agli altri membri.
 */
data class Trip(
    val id: String = "",
    val groupId: String = "",
    val userId: String = "",
    val userName: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val durationMs: Long = 0L,
    val distanceMeters: Double = 0.0,
    val pointCount: Int = 0,
    val source: TripSource = TripSource.MANUAL,
    /** Velocità massima registrata, in m/s. */
    val maxSpeedMs: Float = 0f,
    /** Tempo effettivamente in movimento: la differenza col totale è tempo fermo. */
    val movingMs: Long = 0L,
    val startPlaceName: String? = null,
    val endPlaceName: String? = null,
    /** Viaggio in corso: gli altri lo vedono avanzare sulla mappa. */
    val isLive: Boolean = false,
    /** Visibile solo a chi l'ha registrato. */
    val isPrivate: Boolean = false,
    /** Come ci si è spostati, uno fra [ActivityKind]. Vuoto se non riconosciuto. */
    val activityKind: String = "",
    val points: List<TripPoint> = emptyList(),
    /** Punti live appesi in tempo reale via arrayUnion, visibili agli altri membri. */
    val liveTrack: List<TripPoint> = emptyList()
) {
    /** Velocità media sul tempo in movimento, in m/s. Zero se non si è mai mossi. */
    val averageSpeedMs: Float
        get() = if (movingMs > 0) (distanceMeters / (movingMs / 1000.0)).toFloat() else 0f

    /** Tempo trascorso da fermi durante il viaggio (semafori, code, soste). */
    val stoppedMs: Long
        get() = (durationMs - movingMs).coerceAtLeast(0L)

    /** Etichetta leggibile del modo di spostarsi, o null se non riconosciuto. */
    val activityLabel: String?
        get() = when (activityKind) {
            ActivityKind.VEHICLE -> "In auto"
            ActivityKind.BICYCLE -> "In bicicletta"
            ActivityKind.RUNNING -> "Di corsa"
            ActivityKind.WALKING -> "A piedi"
            else -> null
        }
}

data class ActiveTripState(
    val startTime: Long = System.currentTimeMillis(),
    val points: List<TripPoint> = emptyList(),
    val lastLat: Double = 0.0,
    val lastLon: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val source: TripSource = TripSource.MANUAL,
    val maxSpeedMs: Float = 0f,
    val movingMs: Long = 0L,
    /** Istante dell'ultimo fix considerato, per accumulare [movingMs]. */
    val lastFixAt: Long = 0L,
    /** Documento del viaggio in corso su Firestore, se condiviso in diretta. */
    val liveTripId: String? = null,
    val lastLiveWriteAt: Long = 0L,
    val startPlaceName: String? = null,
    /**
     * Modo di spostarsi prevalente, aggiornato dalle transizioni di attività
     * mentre il viaggio è in corso. Si tiene solo l'ultimo modo di *viaggio*
     * riconosciuto: una sosta a piedi in mezzo a un tragitto in auto non deve
     * riscrivere l'etichetta dell'intero viaggio.
     */
    val activityKind: String = ""
)
