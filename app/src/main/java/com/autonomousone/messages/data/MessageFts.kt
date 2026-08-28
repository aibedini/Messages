package com.autonomousone.messages.data

import androidx.room.Entity
import androidx.room.Fts4

/**
 * Full-text index over message BODIES, kept in sync with the `messages`
 * content table by Room-generated triggers (contentEntity binding).
 *
 * Search for 360K-message databases must NOT load every row into Kotlin and
 * filter in memory. This virtual table makes `WHERE body MATCH ?` a B-tree
 * lookup — O(matching_rows), not O(total_messages).
 *
 * The `docid` of every FTS row equals the `rowid` of its `messages` row, so
 * joins back to the content table are exact and index-free.
 */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFts(
    val body: String
)
