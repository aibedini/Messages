package com.autonomousone.messages.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room v18 — CUSTOM (user-created) categories.
 *
 * A SECOND, independent axis beside the existing Smart Categories. A conversation can
 * be automatically `TRANSACTION` and simultaneously belong to the user's `VPN`,
 * `مشتری‌ها` and `مهم`. That is why this is NOT `categoryOverride` (which stays the
 * single-valued smart override) and NOT a column on [ConversationEntity].
 *
 * `categoryId` is a UUID and the ONLY identity: renaming `VPN` → `VPN Clients` must not
 * break a single membership. The name is presentation; [normalizedName] exists solely
 * for duplicate protection.
 */
@Entity(
    tableName = "user_categories",
    indices = [
        Index(value = ["normalizedName"], unique = true),
        Index("sortOrder")
    ]
)
data class UserCategoryEntity(
    @PrimaryKey
    val categoryId: String,

    /** Display form, trimmed and whitespace-collapsed (never the identity). */
    val name: String,

    /**
     * Case-folded, NFKC-normalized comparison form. UNIQUE, so duplicate protection is
     * enforced by the database and not by a read-then-write race in the repository.
     */
    val normalizedName: String,

    /** Stable user ordering; user categories render in this order after the system ones. */
    val sortOrder: Int,

    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Many-to-many membership between ONE category and ONE conversation scope.
 *
 * The scope is `(scopeType, scopeKey)` — see [com.autonomousone.messages.repository.UserCategoryScopeCodec]:
 *
 *  - `ADDRESS` + a stable phone/sender key: a ONE-TO-ONE conversation. Keyed by the
 *    NUMBER, so the membership survives the Telephony provider deleting and recreating
 *    the thread.
 *  - `THREAD` + the thread id: a group / multi-recipient conversation, which has no
 *    single identifying address.
 *
 * THE ONLY FOREIGN KEY IS `categoryId`. There is deliberately NO foreign key to
 * `messages`, `conversations` or any Telephony row: an ADDRESS membership is user-owned
 * durable state that must outlive projection churn and thread recreation, and a thread
 * membership is structurally valid even when the conversation projection is empty at
 * that moment. Deleting a conversation must never delete category membership.
 *
 * The primary key is `(categoryId, scopeType, scopeKey)`, which makes re-assigning
 * idempotent by construction.
 */
@Entity(
    tableName = "user_category_assignments",
    primaryKeys = ["categoryId", "scopeType", "scopeKey"],
    foreignKeys = [
        ForeignKey(
            entity = UserCategoryEntity::class,
            parentColumns = ["categoryId"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("categoryId"),
        Index(value = ["scopeType", "scopeKey"])
    ]
)
data class UserCategoryAssignmentEntity(
    val categoryId: String,

    /** Persisted vocabulary: `ADDRESS` | `THREAD`. Never a Kotlin class name. */
    val scopeType: String,

    /** `ADDRESS`: the stable scope key. `THREAD`: `threadId.toString()`. */
    val scopeKey: String,

    val createdAt: Long
)
