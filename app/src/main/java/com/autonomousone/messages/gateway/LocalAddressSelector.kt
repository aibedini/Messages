package com.autonomousone.messages.gateway

/**
 * One network interface, reduced to the facts the selection needs.
 *
 * Pure data so the choice is unit-testable: the real enumeration needs `NetworkInterface`, but
 * the DECISION must not, because it is the decision that was wrong.
 */
data class InterfaceCandidate(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    /** True for TUN/PPP-style tunnels — a strong signal that this is not the LAN. */
    val isPointToPoint: Boolean,
    val isVirtual: Boolean = false,
    /** IPv4/IPv6 literals bound to this interface. */
    val addresses: List<String> = emptyList()
)

data class SelectedAddress(
    val address: String,
    val interfaceName: String,
    val scope: AddressScope,
    /** True when this had to fall back to a tunnel/global address because nothing better exists. */
    val isFallback: Boolean
)

/**
 * Picks the address the Local Phone API should advertise and bind to.
 *
 * ── THE BUG THIS REPLACES ────────────────────────────────────────────────────
 * `GatewayServer.getLocalIpAddress()` returned the FIRST non-loopback IPv4 address from
 * `NetworkInterface.getNetworkInterfaces()` — in whatever order the OS happened to enumerate
 * them, with no preference for a real LAN and no awareness of tunnels. On a phone with a VPN
 * active, a TUN interface holding a non-private address (`30.194.216.38` in the field report)
 * won the race, so the UI advertised an address no LAN client can reach — and, worse, the REST
 * server BINDS to that same value, so it may have been listening on the tunnel interface
 * instead of Wi-Fi.
 *
 * ── THE RULES ────────────────────────────────────────────────────────────────
 *  1. A loopback, link-local, reserved, synthetic or carrier-NAT address is never advertised.
 *  2. A PRIVATE LAN address (10/8, 172.16/12, 192.168/16, or an IPv6 ULA) on a non-tunnel
 *     interface wins outright.
 *  3. A private address on a tunnel interface still beats a public address, because a private
 *     range is more likely to be reachable from something.
 *  4. A public address on a non-tunnel interface is a last resort before a tunnel.
 *  5. If only a tunnel/global address exists, it is returned but flagged [isFallback] so the
 *     UI can say "no LAN address found" instead of presenting it as a LAN address.
 *
 * NOTHING here hardcodes `192.168.x.x`: valid LANs also use 10/8 and 172.16/12, and a
 * hardcoded prefix would silently prefer one over the other.
 */
object LocalAddressSelector {

    /** Interface-name prefixes that are VPN/tunnel devices on Android and Linux. */
    private val TUNNEL_NAME_PREFIXES = listOf(
        "tun", "tap", "ppp", "pptp", "ipsec", "utun", "wg", "wireguard", "vpn", "clat"
    )

    fun isTunnelLike(candidate: InterfaceCandidate): Boolean =
        candidate.isPointToPoint ||
            candidate.isVirtual ||
            TUNNEL_NAME_PREFIXES.any { candidate.name.lowercase().startsWith(it) }

    /** The best address, or null when nothing is usable. */
    fun select(candidates: List<InterfaceCandidate>): SelectedAddress? {
        val scored = candidates
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { candidate ->
                val tunnel = isTunnelLike(candidate)
                candidate.addresses.asSequence().mapNotNull { address ->
                    val fact = NetworkAddressFacts.classify(address)
                    if (!isUsable(fact.scope)) return@mapNotNull null
                    Scored(
                        address = address,
                        interfaceName = candidate.name,
                        fact = fact,
                        tunnel = tunnel,
                        score = score(fact.scope, tunnel)
                    )
                }
            }
            .sortedWith(
                // Highest score first, then a STABLE tie-break on name/address so the same
                // device always reports the same value.
                compareByDescending<Scored> { it.score }
                    .thenBy { it.interfaceName }
                    .thenBy { it.address }
            )
            .toList()

        val best = scored.firstOrNull() ?: return null
        return SelectedAddress(
            address = best.address,
            interfaceName = best.interfaceName,
            scope = best.fact.scope,
            isFallback = best.tunnel || best.fact.scope == AddressScope.GLOBAL
        )
    }

    /** Never advertise one of these. */
    private fun isUsable(scope: AddressScope): Boolean = when (scope) {
        AddressScope.LOOPBACK, AddressScope.LINK_LOCAL, AddressScope.RESERVED,
        AddressScope.SYNTHETIC_RANGE, AddressScope.CARRIER_NAT, AddressScope.UNKNOWN -> false
        AddressScope.PRIVATE_LAN, AddressScope.GLOBAL -> true
    }

    /**
     * A private LAN address on a real interface is the ideal; a private address behind a tunnel
     * is still better than a public one, because something may route to it.
     */
    private fun score(scope: AddressScope, tunnel: Boolean): Int = when (scope) {
        AddressScope.PRIVATE_LAN -> if (tunnel) 70 else 100
        AddressScope.GLOBAL -> if (tunnel) 20 else 40
        else -> 0
    }

    private data class Scored(
        val address: String,
        val interfaceName: String,
        val fact: AddressFact,
        val tunnel: Boolean,
        val score: Int
    )
}
