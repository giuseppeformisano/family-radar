package com.example.model

data class UserData(
    val uid: String = "",
    val displayName: String = "Utente",
    val email: String? = null,
    val phoneNumber: String? = null,
    val photoUrl: String? = null,
    val photoBase64: String? = null,
    val currentGroupId: String? = null,
    val isAnonymous: Boolean = false,
    val fcmToken: String? = null,
    val lastSeen: Long = System.currentTimeMillis()
)

data class GroupMember(
    val userId: String = "",
    val displayName: String = "",
    val nickname: String? = null,
    val email: String? = null,
    val phoneNumber: String? = null,
    val photoUrl: String? = null,
    val photoBase64: String? = null,
    val role: String = "member", // "owner", "admin", "member"
    val status: String = "ACTIVE", // "ACTIVE", "PENDING"
    val joinedAt: Long = System.currentTimeMillis(),
    val batteryLevel: Int = 100,
    val isTrackingActive: Boolean = true,
    val isOnline: Boolean = true,
    // Timestamp dell'ultimo messaggio letto in chat da questo membro: alimenta le
    // spunte di lettura ("visto da…"). 0 = non ha ancora aperto la chat.
    val chatLastReadAt: Long = 0L
)

data class DeepLinkTarget(
    val destination: String, // "CHAT", "ALERT", "MAP", "MEMBERS"
    val groupId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val senderId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

