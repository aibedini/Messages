package com.autonomousone.messages.messaging

/**
 * Custom OTP-retention range: a numeric value plus an Hour/Day unit, validated
 * against the supported window.
 *
 * PURE (no Android, no resources) so the "too small / too large / not a number"
 * contract is unit-testable and identical in the UI and in the preferences
 * writer. The UI owns turning a rejection into visible feedback; this type owns
 * the verdict and the CLAMPED value, so a rejected input can never be persisted
 * unvalidated.
 */
object CustomRetentionRange {

    const val MIN_HOURS: Long = 1L
    const val MAX_HOURS: Long = 24L * 30L // 30 days

    const val HOUR_MS: Long = 60L * 60L * 1000L
    const val DAY_MS: Long = 24L * HOUR_MS

    /** Which unit the user typed the custom value in. */
    enum class Unit { HOURS, DAYS }

    /** Why a custom input was rejected — never shown raw, always localized. */
    enum class Rejection {
        NOT_A_NUMBER,
        TOO_SMALL,
        TOO_LARGE;

        /** Suggested valid value (in hours) that the UI may offer to apply. */
        val clampedHours: Long
            get() = when (this) {
                NOT_A_NUMBER, TOO_SMALL -> MIN_HOURS
                TOO_LARGE -> MAX_HOURS
            }
    }

    /** Outcome of validating one custom input. */
    data class Result(
        /** Validated retention, in millis. Non-null exactly when [rejection] is null. */
        val millis: Long?,
        /** Non-null when the input was rejected; [millis] is then null. */
        val rejection: Rejection?
    ) {
        val isValid: Boolean get() = rejection == null
    }

    /**
     * Validates [value] interpreted in [unit].
     *
     * A blank or non-numeric value is [Rejection.NOT_A_NUMBER] rather than a
     * silent 0, because "0 hours" would otherwise reach the worker and mean
     * "clean up immediately". Out-of-range values are rejected — NOT silently
     * clamped — so the user always gets visible feedback; [Rejection.clampedHours]
     * carries the value the UI can offer instead.
     */
    fun validate(value: String, unit: Unit): Result {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return Result(null, Rejection.NOT_A_NUMBER)
        // Reject sign, separators and non-digits explicitly: toLongOrNull would
        // accept "-5", and a user typing "1.5" must get feedback, not a truncation.
        if (!trimmed.all { it.isDigit() }) return Result(null, Rejection.NOT_A_NUMBER)
        val parsed = trimmed.toLongOrNull() ?: return Result(null, Rejection.NOT_A_NUMBER)
        if (parsed <= 0L) return Result(null, Rejection.TOO_SMALL)

        // Multiply in the Long domain; the digit check keeps the parse bounded,
        // and the hour ceiling guards against an overflow before the comparison.
        val hours = if (unit == Unit.DAYS) {
            if (parsed > MAX_HOURS) return Result(null, Rejection.TOO_LARGE)
            parsed * 24L
        } else {
            parsed
        }
        if (hours < MIN_HOURS) return Result(null, Rejection.TOO_SMALL)
        if (hours > MAX_HOURS) return Result(null, Rejection.TOO_LARGE)
        return Result(hours * HOUR_MS, null)
    }

    /** Whole hours in [millis], rounded to nearest — for describing a selection. */
    fun hoursOf(millis: Long): Long = (millis + HOUR_MS / 2) / HOUR_MS

    /**
     * The preset list offered in Settings. `null` millis marks the Custom entry,
     * which opens the numeric editor instead of committing a value.
     */
    val PRESETS: List<Long> = listOf(
        HOUR_MS,          // 1 hour
        6L * HOUR_MS,     // 6 hours
        24L * HOUR_MS,    // 24 hours
        3L * DAY_MS,      // 3 days
        7L * DAY_MS       // 7 days
    )
}
