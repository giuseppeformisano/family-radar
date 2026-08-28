package com.example.model

enum class MessageType {
    TEXT,
    IMAGE,
    SOS_ALERT,
    GEOFENCE_ALERT,
    LOCATION_SHARE,
    VOICE
}

data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val senderPhoto: String? = null,
    val text: String = "",
    val imageBase64: String? = null,
    val imageUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val type: MessageType = MessageType.TEXT,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val snapshotId: String = "",
    // Nota vocale: audio AAC/m4a in Base64 + durata. La posizione (lat/lon sopra)
    // e' quella in cui e' stata registrata, mostrata in chat.
    val audioBase64: String? = null,
    val audioUrl: String? = null,
    val audioDurationMs: Long = 0L,
    val placeName: String? = null
) {
    /**
     * Returns the image model suitable for Coil AsyncImage.
     * Converts raw Base64 string to a data URL format if needed.
     */
    fun getImageSource(): Any? {
        if (!imageBase64.isNullOrBlank()) {
            return if (imageBase64.startsWith("data:")) {
                imageBase64
            } else {
                "data:image/jpeg;base64,$imageBase64"
            }
        }
        return imageUrl
    }
}
