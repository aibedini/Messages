package com.autonomousone.messages.ui.conversation

import com.autonomousone.messages.messaging.SimInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The sticky-SIM selector's decision function.
 *
 * ## Why this is a value and not a screen test
 *
 * The selector is a routing control, and what it refuses is safety-relevant. Asserting it here means
 * "an unresolvable preference shows Unavailable and never quietly becomes another line" is a direct
 * assertion rather than something inferred by driving a whole Conversation.
 *
 * ## The distinction under test
 *
 * ```text
 * no preference                     -> Default SIM, platform default allowed
 * preference, resolved              -> that SIM, selected
 * preference, device cannot look     -> NOT "unavailable": we do not know, so we do not claim
 * preference, device CAN look, absent -> Unavailable, and a send must fail closed
 * ```
 */
class ConversationSimStateTest {

    private val secret = "selector-test-key".toByteArray()

    private fun provider() = com.autonomousone.messages.messaging.SimRefProvider { message ->
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.doFinal(message.toByteArray())
    }

    private val p = provider()

    private fun sim(subId: Int, slot: Int, carrier: String) = SimInfo(
        subscriptionId = subId,
        slotIndex = slot,
        carrierName = carrier,
        displayName = "SIM ${slot + 1}",
        number = "",
        isSystemDefault = false,
        simRef = p.simRefFor(subId).value
    )

    private val sim1 = sim(5, 0, "MCI")
    private val sim2 = sim(9, 1, "Irancell")

    // ── no preference ────────────────────────────────────────────────────────

    @Test
    fun `no preference means the platform default and no override`() {
        val state = ConversationSimState.of(null, listOf(sim1, sim2), inventoryReadable = true, simRefProvider = p)

        assertTrue(state.usesPlatformDefault)
        assertFalse(state.preferenceUnavailable)
        assertNull(state.selectedSim)
        assertNull("the default line must not become an override", state.oneShotOverride)
    }

    @Test
    fun `a blank preference is treated as no preference`() {
        val state = ConversationSimState.of("", listOf(sim1), inventoryReadable = true, simRefProvider = p)

        assertTrue(state.usesPlatformDefault)
        assertNull(state.oneShotOverride)
    }

    // ── resolved ─────────────────────────────────────────────────────────────

    @Test
    fun `a saved preference selects its own line`() {
        val state = ConversationSimState.of(
            preferredSimRef = p.simRefFor(9).value,
            activeSims = listOf(sim1, sim2),
            inventoryReadable = true,
            simRefProvider = p
        )

        assertEquals(9, state.resolvedSubscriptionId)
        assertEquals(sim2, state.selectedSim)
        assertFalse(state.preferenceUnavailable)
        assertEquals("the resolved line is the one the send is pinned to", 9, state.oneShotOverride)
    }

    @Test
    fun `two conversations holding different refs resolve independently`() {
        // The requirement stated as a property: thread A -> SIM1 and thread B -> SIM2 must not leak.
        val a = ConversationSimState.of(p.simRefFor(5).value, listOf(sim1, sim2), true, p)
        val b = ConversationSimState.of(p.simRefFor(9).value, listOf(sim1, sim2), true, p)

        assertEquals(5, a.selectedSim?.subscriptionId)
        assertEquals(9, b.selectedSim?.subscriptionId)
    }

    // ── unavailable: the fail-closed case ────────────────────────────────────

    @Test
    fun `a preference whose line is gone is unavailable, never another SIM`() {
        val removed = p.simRefFor(5).value

        val state = ConversationSimState.of(removed, listOf(sim2), inventoryReadable = true, simRefProvider = p)

        assertTrue(state.preferenceUnavailable)
        assertNull("must not fall back to the surviving line", state.selectedSim)
        assertNull("must not pin a send to a line the user did not choose", state.oneShotOverride)
    }

    @Test
    fun `slot reuse cannot silently select the new card`() {
        // The old SIM was subId 5 in slot 0; the new one is subId 9 in the SAME slot 0.
        val oldRef = p.simRefFor(5).value
        val replacement = sim(9, 0, "Irancell")

        val state = ConversationSimState.of(oldRef, listOf(replacement), inventoryReadable = true, simRefProvider = p)

        assertTrue(state.preferenceUnavailable)
        assertNull(state.selectedSim)
    }

    @Test
    fun `an unreadable inventory is not reported as an unavailable SIM`() {
        // "The app could not look" is a different fact from "the SIM is gone". Claiming the SIM was
        // removed would tell the user to reselect a card that is still in the phone.
        val state = ConversationSimState.of(
            preferredSimRef = p.simRefFor(5).value,
            activeSims = emptyList(),
            inventoryReadable = false,
            simRefProvider = p
        )

        assertTrue(state.preferenceUnverified)
        assertFalse("we must not claim the SIM is gone when we could not look", state.preferenceUnavailable)
        assertNull(state.selectedSim)
        // And it must NOT pin a send to a stale id the app cannot verify.
        assertNull(state.oneShotOverride)
    }

    @Test
    fun `a ref from a different install is unavailable, not silently remapped`() {
        // Simulates a lost/regenerated Keystore secret: every current ref differs from the stored one.
        val otherProvider = com.autonomousone.messages.messaging.SimRefProvider { message ->
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec("a-different-key".toByteArray(), "HmacSHA256"))
            mac.doFinal(message.toByteArray())
        }
        val oldRef = otherProvider.simRefFor(5).value

        val state = ConversationSimState.of(oldRef, listOf(sim1, sim2), inventoryReadable = true, simRefProvider = p)

        assertTrue(state.preferenceUnavailable)
        assertNull(state.oneShotOverride)
    }

    @Test
    fun `an empty readable inventory with a preference is unavailable`() {
        val state = ConversationSimState.of(p.simRefFor(5).value, emptyList(), inventoryReadable = true, simRefProvider = p)

        assertTrue(state.preferenceUnavailable)
        assertNull(state.selectedSim)
    }

    // ── selection -> ref ─────────────────────────────────────────────────────

    @Test
    fun `choosing a SIM yields its opaque ref, never a subscription id`() {
        val ref = ConversationSimState.refForSelection(sim1, p)

        assertEquals(p.simRefFor(5).value, ref)
        assertTrue(com.autonomousone.messages.messaging.SimRefFormat.isValid(ref))
        // The ref must not merely BE the id.
        assertFalse(ref!!.contains("5") && ref.length < 10)
    }

    @Test
    fun `choosing Default yields null, which is a CLEAR and not a missing value`() {
        assertNull(ConversationSimState.refForSelection(null, p))
    }

    @Test
    fun `a SIM carrying no usable ref is refused rather than stored as an empty string`() {
        // An empty ref is not a valid ref, and storing one would later compare equal to nothing while
        // looking like a preference that exists.
        val noRef = sim1.copy(simRef = "")

        // The provider can still derive it, so this yields the real ref rather than empty.
        val derived = ConversationSimState.refForSelection(noRef, p)
        assertTrue(com.autonomousone.messages.messaging.SimRefFormat.isValid(derived))
    }
}
