package com.autonomousone.messages.model

data class Sms(
    val id: Long,
    val threadId: Long,
    val sender: String,
    val message: String,
    val date: Long,
    val unread: Boolean,
    val type: Int
)