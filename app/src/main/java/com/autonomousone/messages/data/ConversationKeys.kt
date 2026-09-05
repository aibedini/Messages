package com.autonomousone.messages.data

import androidx.room.*

/** CKEs are wrapped with an Android Keystore key; raw CKEs never enter SQLite. */
@Entity(tableName = "conversation_key_epochs", indices = [Index(value = ["conversationId", "generation", "historyFloor", "category"], unique = true)])
data class ConversationKeyEpochEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochId: String,
    val conversationId: String,
    val generation: Int,
    val historyFloor: Long,
    val category: String,
    val wrappedKey: ByteArray,
    val createdAt: Long
)

@Dao
interface ConversationKeyDao {
    @Insert suspend fun insert(epoch: ConversationKeyEpochEntity): Long
    @Query("SELECT * FROM conversation_key_epochs WHERE conversationId = :conversationId AND generation = :generation AND historyFloor = :floor AND category = :category LIMIT 1")
    suspend fun current(conversationId: String, generation: Int, floor: Long, category: String): ConversationKeyEpochEntity?
    @Query("SELECT * FROM conversation_key_epochs WHERE id > :after ORDER BY id LIMIT :limit")
    suspend fun page(after: Long, limit: Int): List<ConversationKeyEpochEntity>
}
