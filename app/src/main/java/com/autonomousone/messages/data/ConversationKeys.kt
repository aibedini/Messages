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
    @Query("SELECT * FROM conversation_key_epochs WHERE conversationId = '__account_keyring__' ORDER BY id")
    suspend fun accountKeyring(): List<ConversationKeyEpochEntity>

    /** FIX 1 (primary unlink): drop every conversation key on this device. */
    @Query("DELETE FROM conversation_key_epochs")
    suspend fun deleteAllEpochs(): Int
}
