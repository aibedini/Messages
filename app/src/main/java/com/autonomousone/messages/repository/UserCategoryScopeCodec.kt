package com.autonomousone.messages.repository

/**
 * The persisted form of a [UserCategoryScope].
 *
 * `scopeType` is the stable vocabulary written to the database. It is deliberately NOT
 * a Kotlin class name (renaming or moving the sealed class must never invalidate stored
 * rows), and decoding an unknown value returns null rather than guessing.
 */
data class StoredCategoryScope(
    val scopeType: String,
    val scopeKey: String
)

/**
 * `UserCategoryScope` ⇄ `(scopeType, scopeKey)`.
 *
 * This is the boundary where an in-memory identity becomes durable user data, so the
 * rules are explicit and strict:
 *
 *  - `Address(key)` → `ADDRESS / key`, and `key` may be a canonical phone
 *    (`+989121234567`) or an alphanumeric sender key (`sender:bank`).
 *  - `Thread(id)` → `THREAD / id`, with `id > 0`.
 *  - decoding an unknown `scopeType`, a blank ADDRESS key, a non-numeric THREAD key or
 *    a non-positive THREAD id returns **null**. Nothing is silently reinterpreted: a row
 *    written by a future version with a new scope type must be ignored by this one, not
 *    read as something it is not.
 *
 * Pure and Android-free, so the round-trip and the rejection rules are unit-tested.
 */
object UserCategoryScopeCodec {

    const val ADDRESS = "ADDRESS"
    const val THREAD = "THREAD"

    /** Every persisted scope type, for diagnostics and validation. */
    val SCOPE_TYPES: Set<String> = setOf(ADDRESS, THREAD)

    fun encode(scope: UserCategoryScope): StoredCategoryScope = when (scope) {
        is UserCategoryScope.Address -> StoredCategoryScope(ADDRESS, scope.key)
        is UserCategoryScope.Thread -> StoredCategoryScope(THREAD, scope.threadId.toString())
    }

    fun decode(scopeType: String, scopeKey: String): UserCategoryScope? = when (scopeType) {
        ADDRESS -> scopeKey
            .takeIf { it.isNotBlank() }
            ?.let { UserCategoryScope.Address(it) }

        THREAD -> scopeKey
            .toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { UserCategoryScope.Thread(it) }

        else -> null
    }

    /** True when [scopeType] is a vocabulary value this build understands. */
    fun isKnownScopeType(scopeType: String): Boolean = scopeType in SCOPE_TYPES
}
