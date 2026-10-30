package com.example.model

data class PlaceSnapshot(
    val id: String = "",
    val groupId: String = "",
    val userId: String = "",
    val userName: String = "",
    val userPhotoBase64: String? = null,
    val photoBase64: String = "",
    // Miniatura piccola (sempre inline nel documento, pochi KB): serve a disegnare
    // il pallino sulla mappa e l'anteprima anche sugli altri dispositivi, dove la
    // foto piena vive su Cloudinary (photoUrl) e photoBase64 resta vuota.
    val thumbBase64: String? = null,
    val photoUrl: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val caption: String = ""
) {
    /** Sorgente immagine piena per Coil: preferisce l'URL Cloudinary, poi il Base64 inline. */
    fun getImageSource(): Any? = when {
        !photoUrl.isNullOrBlank() -> photoUrl
        photoBase64.isNotBlank() -> if (photoBase64.startsWith("data:")) photoBase64
            else "data:image/jpeg;base64,$photoBase64"
        else -> null
    }
}

data class PlaceSnapshotCluster(
    val id: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val snapshots: List<PlaceSnapshot>
) {
    val count: Int get() = snapshots.size
    val latestSnapshot: PlaceSnapshot? get() = snapshots.maxByOrNull { it.timestamp }
}
