package com.autonomousone.messages.ui.conversation

import com.autonomousone.messages.messaging.SimInfo
import com.autonomousone.messages.messaging.SimRefFormat
import com.autonomousone.messages.messaging.SimRefProvider
import com.autonomousone.messages.messaging.SimRefResolution

/**
 * What the in-conversation SIM selector must show and do.
 *
 * ## Why this is a pure value and not `remember { ... }` inside the screen
 *
 * The selector is a routing control: what it displays and what it refuses are both safety-relevant.
 * Keeping the decision in Compose would mean the only way to test "an unresolvable preference shows
 * Unavailable and never quietly becomes another line" is to drive a whole screen. Here it is a total
 * function of four inputs and is asserted directly.
 *
 * ## The distinction the whole feature rests on
 *
 * ```text
 * no preference                   -> Default SIM, platform default allowed
 * preference, resolved            -> that SIM, selected
 * preference, device cannot look   -> NOT "unavailable": we do not know, so we do not claim
 * preference, device CAN look, absent -> Unavailable, and a send must fail closed
 * ```
 *
 * Collapsing the last two would tell a user their SIM was removed when the app merely lacked
 * permission to list SIMs — and would block every send on a phone that withholds the list.
 */
data class ConversationSimState(
    /** The durable cross-system preference, or null when this conversation has none. */
    val preferredSimRef: String?,
    /** The live active inventory, as the device reports it right now. */
    val activeSims: List<SimInfo>,
    /**
     * Whether the device could actually read its SIM inventory.
     *
     * `false` (for example `READ_PHONE_STATE` not granted) means "we could not look", which is NOT the
     * same fact as "the SIM is gone".
     */
    val inventoryReadable: Boolean,
    /** The chosen SIM's Android subscription id, or null when nothing is resolvable. */
    val resolvedSubscriptionId: Int?
) {

    /** True when a preference exists and the device answered that it is not present. */
    val preferenceUnavailable: Boolean
        get() = !preferredSimRef.isNullOrBlank() && inventoryReadable && resolvedSubscriptionId == null

    /** True when a preference exists but the app could not check it. */
    val preferenceUnverified: Boolean
        get() = !preferredSimRef.isNullOrBlank() && !inventoryReadable

    /** True when the conversation follows the phone's default line. */
    val usesPlatformDefault: Boolean
        get() = preferredSimRef.isNullOrBlank()

    /** The active SIM currently chosen, for labels. Null when default or unavailable. */
    val selectedSim: SimInfo?
        get() = activeSims.firstOrNull { it.subscriptionId == resolvedSubscriptionId }

    /**
     * The subscription a ONE-SHOT send should be pinned to, or null to let the device decide.
     *
     * This deliberately returns null in two different situations, and both are correct:
     *
     *  - no preference: the platform default is the user's choice, and the sender already resolves it;
     *  - the device could not read the inventory: refusing here would block sends the app cannot
     *    disprove, and the SENDER re-resolves at execution time against a fresh inventory anyway.
     *
     * It NEVER returns a fallback SIM. A preference that cannot be resolved must reach the sender as
     * "preference exists, unresolved", which the sender refuses — not as a different line.
     */
    val oneShotOverride: Int?
        get() = if (usesPlatformDefault || preferenceUnverified) null else resolvedSubscriptionId

    companion object {
        /**
         * Derive the selector state.
         *
         * Resolution is an exact `simRef` match against the CURRENT inventory. There is no slot or
         * positional fallback: the card in slot 0 today is not the card that was there yesterday, so a
         * fallback would silently route a private message to a line the user did not choose.
         */
        fun of(
            preferredSimRef: String?,
            activeSims: List<SimInfo>,
            inventoryReadable: Boolean,
            simRefProvider: SimRefProvider = SimRefProvider()
        ): ConversationSimState {
            val resolved = if (preferredSimRef.isNullOrBlank() || !inventoryReadable) {
                null
            } else {
                when (
                    val outcome = simRefProvider.resolveSubscriptionId(
                        reference = preferredSimRef,
                        activeSubscriptionIds = activeSims.map { it.subscriptionId },
                        inventoryReadable = true
                    )
                ) {
                    is SimRefResolution.Resolved -> outcome.subscriptionId
                    is SimRefResolution.Unavailable -> null
                    SimRefResolution.InventoryUnavailable -> null
                }
            }
            return ConversationSimState(
                preferredSimRef = preferredSimRef,
                activeSims = activeSims,
                inventoryReadable = inventoryReadable,
                resolvedSubscriptionId = resolved
            )
        }

        /**
         * The `simRef` to persist when the user picks [sim], or null for "use the phone default".
         *
         * A malformed result is not invented: if the device secret is unavailable the ref is empty, and
         * `SimRefFormat` rejects it, so the caller's write is refused rather than storing a value that
         * could later compare equal to nothing.
         */
        fun refForSelection(
            sim: SimInfo?,
            simRefProvider: SimRefProvider = SimRefProvider()
        ): String? {
            if (sim == null) return null
            val ref = sim.simRef.takeIf { SimRefFormat.isValid(it) }
                ?: runCatching { simRefProvider.simRefFor(sim.subscriptionId).value }.getOrNull()
            return ref?.takeIf { SimRefFormat.isValid(it) }
        }
    }
}
