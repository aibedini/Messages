package com.autonomousone.messages.sms

/**
 * The authoritative decoder for the TP-Status of an SMS-STATUS-REPORT.
 *
 * ## The production bug this replaces
 *
 * The previous classifier was four bit-ranges:
 *
 * ```kotlin
 * in 0x00..0x1f -> DELIVERED
 * in 0x20..0x3f -> TEMPORARY
 * in 0x40..0x7f -> FAILED
 * else          -> UNKNOWN
 * ```
 *
 * Treating the whole `0x00..0x1F` range as delivered is wrong. Per 3GPP TS 23.040 §9.2.3.15 only
 * `0x00` means "received by the SME"; the same range also holds
 *
 * ```text
 * 0x01  forwarded by the SC, but the SC is UNABLE TO CONFIRM DELIVERY
 * 0x02  short message REPLACED by the SC
 * ```
 *
 * so the app was claiming confirmed delivery for two codes that explicitly deny it — the worst
 * direction to be wrong in, because it tells the user a message arrived when nobody confirmed that.
 *
 * The range boundaries were also lossy in the other direction: `0x40..0x7F` is two different
 * situations that a user needs told apart.
 *
 * ## The single source of truth
 *
 * One decoder, used by the receiver, persistence, the presentation mapper and the technical export, so
 * no two surfaces can disagree about what the network said. Only [evidence] and the raw code are
 * persisted; every other field is derived at display time, so **no Room migration is needed** and a
 * historical row keeps its original raw code untouched.
 *
 * Reference: 3GPP TS 23.040 §9.2.3.15 (TP-Status), Release 1999 onwards. The category boundaries are
 * identical across releases; only individual code meanings were added over time.
 */
object SmsTpStatus {

    /**
     * What the network's report means as EVIDENCE.
     *
     * [NOT_CONFIRMED] is separate from [FAILED] on purpose. `0x01`/`0x02` are neither a delivery nor a
     * failure: the SC accepted the message and then declined to confirm what happened to it. Folding
     * them into FAILED would tell the user the message was not delivered, which the network never said.
     */
    enum class Evidence { DELIVERED, NOT_CONFIRMED, TEMPORARY, FAILED, UNKNOWN }

    /** The category the top bits place a code in, independent of any individual code meaning. */
    enum class Category {
        /** `0x00..0x1F` — the transaction completed; the low bits carry the outcome. */
        COMPLETED,

        /** `0x20..0x3F` — temporary error, SC still trying. */
        TEMPORARY_RETRYING,

        /** `0x40..0x5F` — permanent error, no further attempts. */
        PERMANENT_FAILURE,

        /** `0x60..0x7F` — temporary error, SC no longer making attempts. */
        TEMPORARY_STOPPED,

        /** `0x80..0xFF` — not defined by the standard. */
        RESERVED
    }

    /**
     * A decoded TP-Status. [rawCode] is always preserved so a historical row can be re-decoded and the
     * user can be shown the exact number the network sent.
     */
    data class Decoded(
        val rawCode: Int,
        val symbolicName: String,
        /** A plain-language reason. NEVER a claim about credit, blocking or throttling. */
        val reason: String,
        val evidence: Evidence,
        val category: Category,
        /** True when the SC has stopped trying: no further report will arrive for this message. */
        val isTerminal: Boolean,
        /** True only while the SC may still deliver and report. */
        val carrierStillRetrying: Boolean,
        /** True when the code means something only the carrier knows. */
        val carrierSpecific: Boolean,
        val standardReference: String
    ) {
        /** `0x63`, and for a reserved code its raw value — never a fabricated name. */
        val hexCode: String get() = "0x%02X".format(rawCode)
    }

    private const val REF = "3GPP TS 23.040 §9.2.3.15"

    fun decode(status: Int): Decoded {
        // The TP-ST octet is a single byte. Anything wider cannot be one, so it is not decoded as one.
        if (status !in 0x00..0xFF) {
            return Decoded(
                rawCode = status,
                symbolicName = "OUT_OF_RANGE",
                reason = "The reported status is not a valid single-byte TP-Status.",
                evidence = Evidence.UNKNOWN,
                category = Category.RESERVED,
                isTerminal = false,
                carrierStillRetrying = false,
                carrierSpecific = false,
                standardReference = REF
            )
        }

        val category = categoryOf(status)
        // Only 0x00 is a confirmed delivery. Nothing else in the completed range says "it arrived".
        if (status == 0x00) {
            return Decoded(
                rawCode = status,
                symbolicName = "DELIVERED",
                reason = "The message was received by the recipient.",
                evidence = Evidence.DELIVERED,
                category = category,
                isTerminal = true,
                carrierStillRetrying = false,
                carrierSpecific = false,
                standardReference = REF
            )
        }

        namedCode(status)?.let { return it.copy(category = category) }

        // ── codes the standard does not define ───────────────────────────────
        //
        // Reserved, or "values specific to each SC". These MUST NOT be read as success: a range being
        // adjacent to 0x00 is not evidence that anything arrived. They are reported as uninterpretable
        // with the raw code preserved, which is the only honest reading.
        return when (category) {
            Category.COMPLETED -> Decoded(
                rawCode = status,
                symbolicName = if (status <= 0x0F) "RESERVED_COMPLETED" else "SC_SPECIFIC_COMPLETED",
                reason = if (status <= 0x0F) {
                    "The message transaction completed, but the network used a status code the " +
                        "standard reserves, so delivery cannot be confirmed."
                } else {
                    "The message transaction completed with a status code specific to this carrier, " +
                        "so delivery cannot be confirmed."
                },
                evidence = Evidence.NOT_CONFIRMED,
                category = category,
                isTerminal = true,
                carrierStillRetrying = false,
                carrierSpecific = status > 0x0F,
                standardReference = REF
            )

            Category.TEMPORARY_RETRYING -> Decoded(
                rawCode = status,
                symbolicName = "TEMPORARY_UNSPECIFIED",
                reason = "The network hit a temporary problem and may still deliver the message.",
                evidence = Evidence.TEMPORARY,
                category = category,
                isTerminal = false,
                carrierStillRetrying = true,
                carrierSpecific = status >= 0x30,
                standardReference = REF
            )

            Category.PERMANENT_FAILURE -> Decoded(
                rawCode = status,
                symbolicName = if (status <= 0x4F) "RESERVED_PERMANENT" else "SC_SPECIFIC_PERMANENT",
                reason = "The network could not deliver the message and will not try again.",
                evidence = Evidence.FAILED,
                category = category,
                isTerminal = true,
                carrierStillRetrying = false,
                carrierSpecific = status > 0x4F,
                standardReference = REF
            )

            Category.TEMPORARY_STOPPED -> Decoded(
                rawCode = status,
                symbolicName = "SC_SPECIFIC_STOPPED",
                reason = "The network reported a carrier-specific failure and will not try again.",
                evidence = Evidence.FAILED,
                category = category,
                isTerminal = true,
                carrierStillRetrying = false,
                carrierSpecific = true,
                standardReference = REF
            )

            Category.RESERVED -> Decoded(
                rawCode = status,
                symbolicName = "RESERVED_UNDEFINED",
                reason = "The network sent a status code the standard does not define. No delivery " +
                    "verdict can be drawn from it.",
                evidence = Evidence.UNKNOWN,
                category = category,
                isTerminal = false,
                carrierStillRetrying = false,
                carrierSpecific = true,
                standardReference = REF
            )
        }
    }

    /** The category from the top bits. Boundaries are identical in every release of the standard. */
    fun categoryOf(status: Int): Category = when {
        status < 0x00 -> Category.RESERVED
        status <= 0x1F -> Category.COMPLETED
        status <= 0x3F -> Category.TEMPORARY_RETRYING
        status <= 0x5F -> Category.PERMANENT_FAILURE
        status <= 0x7F -> Category.TEMPORARY_STOPPED
        status <= 0xFF -> Category.RESERVED
        else -> Category.RESERVED
    }

    /**
     * The codes the standard names explicitly.
     *
     * `0x43` is "Not obtainable" — NOT `0x63`. `0x63` lies in `0x60..0x7F`, which the standard defines
     * as *values specific to each SC* within the "temporary error, SC is not making any more transfer
     * attempts" family, so its precise cause is known only to the carrier. Labelling it "Not obtainable"
     * would attribute a standard meaning to a code the standard leaves to the operator.
     */
    private fun namedCode(status: Int): Decoded? = when (status) {
        // ── transaction completed ────────────────────────────────────────────
        0x01 -> Decoded(
            rawCode = status, symbolicName = "FORWARDED_NOT_CONFIRMED",
            reason = "The message was forwarded to the recipient, but the network could not confirm " +
                "that it was delivered.",
            evidence = Evidence.NOT_CONFIRMED, category = Category.COMPLETED,
            isTerminal = true, carrierStillRetrying = false, carrierSpecific = false,
            standardReference = REF
        )
        0x02 -> Decoded(
            rawCode = status, symbolicName = "REPLACED_BY_SC",
            reason = "The message was replaced by the network before delivery, so this copy was not " +
                "delivered.",
            evidence = Evidence.NOT_CONFIRMED, category = Category.COMPLETED,
            isTerminal = true, carrierStillRetrying = false, carrierSpecific = false,
            standardReference = REF
        )

        // ── temporary error, still trying ────────────────────────────────────
        0x20 -> temp(status, "CONGESTION", "The network is congested.")
        0x21 -> temp(status, "SME_BUSY", "The recipient's phone was busy.")
        0x22 -> temp(status, "NO_RESPONSE", "The recipient's phone did not respond.")
        0x23 -> temp(status, "SERVICE_REJECTED", "The network rejected the delivery attempt.")
        0x24 -> temp(status, "QOS_UNAVAILABLE", "The requested quality of service was unavailable.")
        0x25 -> temp(status, "ERROR_IN_SME", "The recipient's phone reported an error.")

        // ── permanent error, no more attempts ────────────────────────────────
        0x40 -> perm(status, "REMOTE_PROCEDURE_ERROR", "The network reported a remote procedure error.")
        0x41 -> perm(status, "INCOMPATIBLE_DESTINATION", "The recipient's device cannot accept this message.")
        0x42 -> perm(status, "CONNECTION_REJECTED", "The connection to the recipient was rejected.")
        0x43 -> perm(status, "NOT_OBTAINABLE", "The recipient could not be reached.")
        0x44 -> perm(status, "QOS_UNAVAILABLE_PERMANENT", "The requested quality of service is not available.")
        0x45 -> perm(status, "NO_INTERWORKING", "No interworking with the recipient's network was available.")
        0x46 -> perm(status, "VALIDITY_PERIOD_EXPIRED", "The message expired before it could be delivered.")
        0x47 -> perm(status, "DELETED_BY_ORIGINATOR", "The message was deleted before delivery.")
        0x48 -> perm(status, "DELETED_BY_ADMIN", "The network deleted the message before delivery.")
        0x49 -> perm(status, "SM_DOES_NOT_EXIST", "The network no longer has any record of this message.")

        // `0x50..0x5F` is NOT a separate named family: it falls inside the permanent range
        // (`0x40..0x5F`), so those codes are handled by the category fallback below as permanent
        // failures. The "temporary error, SC is not making any more transfer attempts" family is
        // `0x60..0x7F`, and the standard leaves every code in it to the individual SC — which is why
        // there is nothing to name there either.

        else -> null
    }

    private fun temp(status: Int, name: String, reason: String) = Decoded(
        rawCode = status, symbolicName = name,
        reason = "$reason The network may still deliver the message.",
        evidence = Evidence.TEMPORARY, category = Category.TEMPORARY_RETRYING,
        isTerminal = false, carrierStillRetrying = true, carrierSpecific = false,
        standardReference = REF
    )

    private fun perm(status: Int, name: String, reason: String) = Decoded(
        rawCode = status, symbolicName = name,
        reason = reason,
        evidence = Evidence.FAILED, category = Category.PERMANENT_FAILURE,
        isTerminal = true, carrierStillRetrying = false, carrierSpecific = false,
        standardReference = REF
    )
}

