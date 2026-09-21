package com.autonomousone.messages.gateway

/**
 * What KIND of address an IP literal is.
 *
 * The diagnostic used to print `gmweb.okgfx.ir -> 198.18.223.193` and leave it at that, which
 * reads as "here is your server's IP". `198.18.0.0/15` is the benchmarking range, and some
 * VPN/proxy stacks answer DNS with a synthetic address from it — so the number shown may not
 * exist anywhere on the internet. Presenting it without a label is a claim the measurement does
 * not support.
 *
 * Pure and Android-free, so every range rule is unit-tested.
 */
enum class AddressScope {
    /** `127.0.0.0/8`, `::1`. */
    LOOPBACK,

    /** `169.254.0.0/16`, `fe80::/10`: self-assigned, not routable. */
    LINK_LOCAL,

    /**
     * `10/8`, `172.16/12`, `192.168/16`, IPv6 ULA `fc00::/7`: a real private LAN address.
     */
    PRIVATE_LAN,

    /**
     * `198.18.0.0/15`: reserved for benchmarking. Common as a VPN/proxy "fake IP" handed out
     * so a client's traffic can be routed through the tunnel.
     */
    SYNTHETIC_RANGE,

    /** `100.64.0.0/10`: carrier-grade NAT — shared, not a LAN, not directly reachable. */
    CARRIER_NAT,

    /** `240.0.0.0/4`, `0.0.0.0/8`, multicast: reserved, never a real peer. */
    RESERVED,

    /** A normal, globally routable address. */
    GLOBAL,

    /** Not a parseable IPv4/IPv6 literal. */
    UNKNOWN
}

data class AddressFact(
    val address: String,
    val scope: AddressScope
) {
    /**
     * True when the address must NOT be presented as the server's real origin.
     *
     * A synthetic or carrier-NAT answer usually means a VPN or proxy is in the path, which is
     * legitimate — it just has to be labelled rather than reported as fact.
     */
    val isSyntheticOrIntercepted: Boolean
        get() = scope == AddressScope.SYNTHETIC_RANGE || scope == AddressScope.CARRIER_NAT

    /** A human label for the report and the card. */
    val label: String
        get() = when (scope) {
            AddressScope.LOOPBACK -> "loopback"
            AddressScope.LINK_LOCAL -> "link-local"
            AddressScope.PRIVATE_LAN -> "private LAN"
            AddressScope.SYNTHETIC_RANGE -> "synthetic range (VPN/proxy fake IP)"
            AddressScope.CARRIER_NAT -> "carrier-grade NAT"
            AddressScope.RESERVED -> "reserved"
            AddressScope.GLOBAL -> "public"
            AddressScope.UNKNOWN -> "unrecognised"
        }
}

object NetworkAddressFacts {

    fun classify(address: String?): AddressFact {
        val raw = address?.trim()?.removeSurrounding("[", "]").orEmpty()
        if (raw.isEmpty()) return AddressFact("", AddressScope.UNKNOWN)

        val octets = parseIpv4(raw)
        if (octets != null) {
            val scope = ipv4Scope(octets)
            return AddressFact(raw, scope)
        }

        if (raw.contains(':')) {
            return AddressFact(raw, ipv6Scope(raw.lowercase()))
        }
        return AddressFact(raw, AddressScope.UNKNOWN)
    }

    /** True when [address] is a reasonable thing to advertise on a LAN. */
    fun isPrivateLan(address: String?): Boolean =
        classify(address).scope == AddressScope.PRIVATE_LAN

    private fun parseIpv4(value: String): IntArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3) return null
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            // Reject a leading zero beyond "0" itself, which would be ambiguous with octal.
            if (part.length > 1 && part[0] == '0') return null
            out[index] = n
        }
        return out
    }

    private fun ipv4Scope(o: IntArray): AddressScope {
        val a = o[0]
        val b = o[1]
        return when {
            a == 127 -> AddressScope.LOOPBACK
            a == 169 && b == 254 -> AddressScope.LINK_LOCAL
            a == 10 -> AddressScope.PRIVATE_LAN
            a == 172 && b in 16..31 -> AddressScope.PRIVATE_LAN
            a == 192 && b == 168 -> AddressScope.PRIVATE_LAN
            a == 198 && (b == 18 || b == 19) -> AddressScope.SYNTHETIC_RANGE
            a == 100 && b in 64..127 -> AddressScope.CARRIER_NAT
            a == 0 -> AddressScope.RESERVED
            a >= 240 -> AddressScope.RESERVED
            a in 224..239 -> AddressScope.RESERVED
            else -> AddressScope.GLOBAL
        }
    }

    private fun ipv6Scope(value: String): AddressScope {
        val head = value.substringBefore('%') // strip a zone id
        return when {
            head == "::1" -> AddressScope.LOOPBACK
            head.startsWith("fe8") || head.startsWith("fe9") ||
                head.startsWith("fea") || head.startsWith("feb") -> AddressScope.LINK_LOCAL
            // Unique local addresses fc00::/7.
            head.startsWith("fc") || head.startsWith("fd") -> AddressScope.PRIVATE_LAN
            head == "::" || head.startsWith("ff") -> AddressScope.RESERVED
            else -> AddressScope.GLOBAL
        }
    }
}
