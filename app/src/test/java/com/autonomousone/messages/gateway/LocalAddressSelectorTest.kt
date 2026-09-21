package com.autonomousone.messages.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which address the Local Phone API advertises and binds to.
 *
 * THE FIELD BUG: the UI showed `http://30.194.216.38:8080` on a phone with a VPN active.
 * `30.194.216.38` is not RFC1918 — the old code returned the FIRST non-loopback IPv4 the OS
 * enumerated, which was a TUN interface, and the REST server binds to the same value.
 *
 * Nothing here may hardcode `192.168.x.x`: a valid LAN can also be 10/8 or 172.16/12, and a
 * hardcoded prefix would silently prefer one over another.
 */
class LocalAddressSelectorTest {

    private fun wifi(vararg addresses: String) = InterfaceCandidate(
        name = "wlan0", isUp = true, isLoopback = false, isPointToPoint = false,
        addresses = addresses.toList()
    )

    private fun cellular(vararg addresses: String) = InterfaceCandidate(
        name = "rmnet_data0", isUp = true, isLoopback = false, isPointToPoint = false,
        addresses = addresses.toList()
    )

    private fun vpn(vararg addresses: String) = InterfaceCandidate(
        name = "tun0", isUp = true, isLoopback = false, isPointToPoint = true,
        addresses = addresses.toList()
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // The reported case: Wi-Fi + VPN
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `wifiPlusVpnPrefersTheLanAddressOverTheTunnel`() {
        val selected = LocalAddressSelector.select(
            listOf(
                // Exactly the field situation: a tunnel holding a non-private address, listed
                // FIRST, which is why the old first-match logic picked it.
                vpn("30.194.216.38"),
                wifi("192.168.1.42")
            )
        )!!

        assertEquals("192.168.1.42", selected.address)
        assertEquals("wlan0", selected.interfaceName)
        assertEquals(AddressScope.PRIVATE_LAN, selected.scope)
        assertFalse("a real LAN address is not a fallback", selected.isFallback)
    }

    @Test
    fun `theEnumerationOrderCannotChangeTheAnswer`() {
        val forward = LocalAddressSelector.select(listOf(vpn("30.194.216.38"), wifi("192.168.1.42")))!!
        val reverse = LocalAddressSelector.select(listOf(wifi("192.168.1.42"), vpn("30.194.216.38")))!!

        assertEquals(forward.address, reverse.address)
    }

    @Test
    fun `aPublicAddressOnWifiStillLosesToAPrivateLanAddress`() {
        val selected = LocalAddressSelector.select(
            listOf(wifi("203.0.113.7", "10.0.0.5"))
        )!!

        assertEquals("10.0.0.5", selected.address)
        assertFalse(selected.isFallback)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Every RFC1918 range is equally acceptable — no hardcoded prefix
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `allThreePrivateRangesAreTreatedAsLan`() {
        listOf("192.168.4.9", "10.1.2.3", "172.16.9.9", "172.31.255.254").forEach { address ->
            val selected = LocalAddressSelector.select(listOf(wifi(address)))!!
            assertEquals(address, selected.address)
            assertEquals(AddressScope.PRIVATE_LAN, selected.scope)
            assertFalse("$address is a real LAN address", selected.isFallback)
        }
    }

    @Test
    fun `172_32_isNotPrivate`() {
        assertEquals(AddressScope.GLOBAL, NetworkAddressFacts.classify("172.32.0.1").scope)
        assertEquals(AddressScope.PRIVATE_LAN, NetworkAddressFacts.classify("172.31.0.1").scope)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Wi-Fi only / mobile only
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `wifiOnlySelectsTheWifiAddress`() {
        val selected = LocalAddressSelector.select(listOf(wifi("192.168.0.7")))!!

        assertEquals("192.168.0.7", selected.address)
    }

    @Test
    fun `mobileOnlySelectsTheCellularAddress`() {
        // Carrier addresses are frequently in a shared range; it is still the best available,
        // so it is returned and honestly flagged as a fallback.
        val selected = LocalAddressSelector.select(listOf(cellular("10.44.1.9")))!!

        assertEquals("10.44.1.9", selected.address)
        assertEquals(AddressScope.PRIVATE_LAN, selected.scope)
    }

    @Test
    fun `aCarrierGradeNatAddressIsNeverAdvertised`() {
        // 100.64/10 is shared, not a LAN, and not reachable from a LAN client.
        assertNull(LocalAddressSelector.select(listOf(cellular("100.96.1.9"))))
        assertEquals(AddressScope.CARRIER_NAT, NetworkAddressFacts.classify("100.96.1.9").scope)
    }

    @Test
    fun `aFakeIpRangeIsNeverAdvertised`() {
        assertNull(LocalAddressSelector.select(listOf(vpn("198.18.223.193"))))
        assertEquals(
            AddressScope.SYNTHETIC_RANGE,
            NetworkAddressFacts.classify("198.18.223.193").scope
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Multiple interfaces and unusable ones
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `multipleInterfacesPickTheBestAndStayDeterministic`() {
        val candidates = listOf(
            InterfaceCandidate("lo", isUp = true, isLoopback = true, isPointToPoint = false,
                addresses = listOf("127.0.0.1")),
            vpn("10.8.0.2"),
            cellular("100.96.1.9"),
            wifi("192.168.1.42"),
            InterfaceCandidate("wlan1", isUp = true, isLoopback = false, isPointToPoint = false,
                addresses = listOf("192.168.1.43"))
        )

        val first = LocalAddressSelector.select(candidates)!!
        val second = LocalAddressSelector.select(candidates.reversed())!!

        assertEquals("192.168.1.42", first.address)
        assertEquals("a stable tie-break keeps the reported value from flapping", first.address, second.address)
        assertFalse(first.isFallback)
    }

    @Test
    fun `aDownInterfaceIsIgnored`() {
        val down = InterfaceCandidate("wlan0", isUp = false, isLoopback = false,
            isPointToPoint = false, addresses = listOf("192.168.1.42"))

        assertNull(LocalAddressSelector.select(listOf(down)))
    }

    @Test
    fun `loopbackAndLinkLocalAreNeverSelected`() {
        assertNull(
            LocalAddressSelector.select(
                listOf(
                    InterfaceCandidate("lo", true, true, false, addresses = listOf("127.0.0.1")),
                    wifi("169.254.10.20")
                )
            )
        )
    }

    @Test
    fun `aPrivateTunnelAddressStillBeatsAGlobalOneButIsFlagged`() {
        // Between a private address on a tunnel and a public address on Wi-Fi, the private one
        // is more likely to be reachable by something — but it is still a tunnel, so the UI is
        // told it is a fallback.
        val selected = LocalAddressSelector.select(
            listOf(vpn("10.8.0.2"), wifi("203.0.113.7"))
        )!!

        assertEquals("10.8.0.2", selected.address)
        assertTrue(selected.isFallback)
    }

    @Test
    fun `onlyAGlobalAddressLeavesTheUiAbleToSayThereIsNoLanAddress`() {
        val selected = LocalAddressSelector.select(listOf(wifi("203.0.113.7")))!!

        assertTrue("the UI must be able to say 'no LAN address found'", selected.isFallback)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IPv6
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `anIpv6UlaIsUsableWhenThereIsNoIpv4`() {
        val selected = LocalAddressSelector.select(listOf(wifi("fd00::1")))!!

        assertEquals("fd00::1", selected.address)
        assertEquals(AddressScope.PRIVATE_LAN, selected.scope)
    }

    @Test
    fun `ipv6LoopbackAndLinkLocalAreRejected`() {
        assertEquals(AddressScope.LOOPBACK, NetworkAddressFacts.classify("::1").scope)
        assertEquals(AddressScope.LINK_LOCAL, NetworkAddressFacts.classify("fe80::1").scope)
        assertNull(LocalAddressSelector.select(listOf(wifi("fe80::1"))))
    }

    @Test
    fun `ipv4IsPreferredOverIpv6WhenBothArePresent`() {
        val selected = LocalAddressSelector.select(listOf(wifi("fd00::1", "192.168.1.42")))!!

        assertEquals("192.168.1.42", selected.address)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Address classification
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `theFakeIpRangeIsLabelledSoItIsNeverReadAsTheServersRealIp`() {
        val fact = NetworkAddressFacts.classify("198.18.223.193")

        assertTrue(fact.isSyntheticOrIntercepted)
        assertTrue(fact.label.contains("VPN"))
    }

    @Test
    fun `unparseableValuesAreUnknownRatherThanGuessed`() {
        listOf("", "   ", "not-an-ip", "999.1.1.1", "192.168.1", "01.2.3.4").forEach { bad ->
            assertEquals("<$bad> must not be classified", AddressScope.UNKNOWN, NetworkAddressFacts.classify(bad).scope)
        }
        assertEquals(AddressScope.UNKNOWN, NetworkAddressFacts.classify(null).scope)
    }

    @Test
    fun `isPrivateLanAnswersTheOneQuestionTheCallersAsk`() {
        assertTrue(NetworkAddressFacts.isPrivateLan("192.168.1.1"))
        assertTrue(NetworkAddressFacts.isPrivateLan("10.0.0.1"))
        assertFalse(NetworkAddressFacts.isPrivateLan("30.194.216.38"))
        assertFalse(NetworkAddressFacts.isPrivateLan("198.18.223.193"))
        assertFalse(NetworkAddressFacts.isPrivateLan(null))
    }

    @Test
    fun `noCandidateMeansNoAddressRatherThanAGuess`() {
        assertNull(LocalAddressSelector.select(emptyList()))
    }
}
