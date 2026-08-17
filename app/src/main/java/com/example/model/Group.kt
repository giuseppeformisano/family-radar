package com.example.model

data class GroupData(
    val id: String = "",
    val name: String = "",
    val joinCode: String = "",
    val ownerId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val description: String = "",
    val requiresApproval: Boolean = true,
    val userMembershipStatus: String = "ACTIVE" // "ACTIVE", "PENDING"
)
