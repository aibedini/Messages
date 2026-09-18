package com.autonomousone.messages.data

/**
 * Explicit result of a SYNC-CRITICAL provider read.
 *
 * The forgiving readers (SmsRepository.querySmsRaw/queryMmsRaw) catch every
 * exception and return emptyList(). That is fine for rendering, and FATAL for a
 * destructive decision, because it makes these two cases identical:
 *
 *   1. the provider answered successfully and the row does not exist  -> REAL DELETE
 *   2. the provider failed (binder, SecurityException, null cursor)  -> UNKNOWN
 *
 * Treating (2) as (1) deletes a valid Room message. This type keeps them apart:
 *
 *   Success(value) -> the provider really answered
 *   Failure(reason) -> UNKNOWN; never evidence of absence
 *
 * A null Cursor is deliberately NOT "zero rows": it is a Failure.
 */
sealed interface ProviderRead<out T> {

    data class Success<out T>(val value: T) : ProviderRead<T>

    data class Failure(
        val reason: Reason,
        val cause: Throwable? = null
    ) : ProviderRead<Nothing>

    enum class Reason {
        /** ContentResolver.query returned a null Cursor. */
        QUERY_RETURNED_NULL,
        /** The app is not allowed to read the provider. */
        SECURITY,
        /** The provider process is missing / not registered. */
        PROVIDER_UNAVAILABLE,
        /** The binder to the provider died. */
        BINDER,
        /** Anything else: still UNKNOWN, never absence. */
        UNEXPECTED
    }

}

/** True only when the provider positively answered. */
val <T> ProviderRead<T>.isSuccess: Boolean
    get() = this is ProviderRead.Success

/**
 * True only when absence was PROVEN by a successful read.
 *
 * A Failure is NEVER absence — that distinction is the entire point of this type.
 */
val <T> ProviderRead<T?>.provesAbsence: Boolean
    get() = this is ProviderRead.Success && value == null

/** Maps the value of a successful read; a Failure stays a Failure. */
inline fun <T, R> ProviderRead<T>.map(transform: (T) -> R): ProviderRead<R> = when (this) {
    is ProviderRead.Success -> ProviderRead.Success(transform(value))
    is ProviderRead.Failure -> this
}
