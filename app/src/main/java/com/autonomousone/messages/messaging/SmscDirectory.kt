package com.autonomousone.messages.messaging

/**
 * v2.6.13: known SMSC (Service Centre) addresses for Iranian operators.
 *
 * Field data captured from Google Messages on the user's own SIMs:
 *  - Irancell (IRN-Talia / MTN):  +98935 000 1400  → +9893500001400
 *  - IR-MCI (Hamrahe Aval):       +98911 00500     → +9891100500
 *  - Rightel:                     +98999xxx (not confirmed on-device — no
 *    hardcoded entry; falls through to network default)
 *
 * Why: some operators' default SMSC handles UCS-2 (Persian) submits poorly
 * and reports GENERIC_FAILURE while the message is actually delivered. When
 * the user has not chosen an SMSC, we seed the known-good per-operator one
 * automatically — matching what the reference app uses on the same SIM.
 */
object SmscDirectory {

    data class Entry(val carrierPatterns: List<Regex>, val smsc: String)

    /**
     * Carrier matching is by [SimInfo.carrierName]/[SimInfo.displayName],
     * lower-cased. Values are the exact SMSCs observed on-device.
     */
    private val known = listOf(
        Entry(
            carrierPatterns = listOf(Regex("irancell|ir-mtn|mtn|talia|0935")),
            smsc = "+9893500001400"
        ),
        Entry(
            carrierPatterns = listOf(Regex("ir-mci|mci|hamrahe|hamrah|aval|0911|0919")),
            smsc = "+9891100500"
        )
    )

    /** Best-effort SMSC for a carrier label, or null when unknown. */
    fun forCarrier(carrierName: String): String? {
        val c = carrierName.lowercase()
        return known.firstOrNull { entry -> entry.carrierPatterns.any { it.containsMatchIn(c) } }?.smsc
    }
}
