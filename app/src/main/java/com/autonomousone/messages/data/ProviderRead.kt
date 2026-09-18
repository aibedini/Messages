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

/**
 * Existence of a provider row, kept SEPARATE from its materialized content.
 *
 * MMS rows live in one table and their address/body live in two others
 * (content://mms/addr and content://mms/part). A single "read the whole row"
 * call therefore has THREE failure points, and only the first of them says
 * anything about existence:
 *
 *   base row query OK + row present -> Exists   (deleting it would be wrong)
 *   base row query OK + no row      -> Absent   (a delete is PROVEN)
 *   base row query failed           -> Failure  (UNKNOWN, never absence)
 *
 * Callers that must decide "delete or keep" ask [ProviderExistence] and pay for
 * one provider query. Callers that must write Room authoritatively ask for the
 * materialized row and are allowed to fail on the secondary tables instead of
 * inventing "[MMS]" / "Unknown" placeholders that would overwrite good data.
 */
sealed interface ProviderExistence {
    data object Exists : ProviderExistence
    data object Absent : ProviderExistence
}

/**
 * The strict-MMS guard, extracted as a PURE function.
 *
 * Returns the failure to propagate when either secondary read (Addr or Part)
 * failed, or null when both positively answered.
 *
 * This one line is the difference between two very different worlds:
 *
 *   old: a failed Part read -> empty map -> body falls back to the literal
 *        "[MMS]" -> the sync layer writes "[MMS]" OVER the real body.
 *   new: a failed Part read -> Failure -> Room keeps its row and the durable
 *        repair queue retries later.
 *
 * Pure and Android-free so the invariant is unit-testable without a device.
 */
fun mmsSecondaryFailure(
    addressResult: ProviderRead<*>,
    bodyResult: ProviderRead<*>
): ProviderRead.Failure? = when {
    addressResult is ProviderRead.Failure -> addressResult
    bodyResult is ProviderRead.Failure -> bodyResult
    else -> null
}

/** Maps the value of a successful read; a Failure stays a Failure. */
inline fun <T, R> ProviderRead<T>.map(transform: (T) -> R): ProviderRead<R> = when (this) {
    is ProviderRead.Success -> ProviderRead.Success(transform(value))
    is ProviderRead.Failure -> this
}
