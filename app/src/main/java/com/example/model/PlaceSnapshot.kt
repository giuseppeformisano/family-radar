package com.example.model

data class PlaceSnapshot(
    val id: String = "",
    val groupId: String = "",
    val userId: String = "",
    val userName: String = "",
    val userPhotoBase64: String? = null,
    val photoBase64: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val caption: String = ""
)

data class PlaceSnapshotCluster(
    val id: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val snapshots: List<PlaceSnapshot>
) {
    val count: Int get() = snapshots.size
    val latestSnapshot: PlaceSnapshot? get() = snapshots.maxByOrNull { it.timestamp }
}
