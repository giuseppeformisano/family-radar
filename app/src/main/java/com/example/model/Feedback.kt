package com.example.model

data class FeedbackEntry(
    val id: String = "",
    val text: String = "",
    val userId: String = "",
    val userName: String = "",
    val timestamp: Long = 0L,
    val versionName: String = "",
    val versionCode: Int = 0,
    val status: String = "pending"
)
