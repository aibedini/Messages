package com.autonomousone.messages.model

data class Sms(
    val id: Long,
    val sender: String,
    val message: String,
    val time: String,
    val unread: Boolean = false
)