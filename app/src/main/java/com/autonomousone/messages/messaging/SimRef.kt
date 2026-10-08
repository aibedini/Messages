package com.autonomousone.messages.messaging

/**
 * The opaque, versioned, cross-system SIM identifier.
 *
 * ## Why this exists
 *
 * The device's authoritative subscription identity is Android's `subscriptionId`. It is also a small
 * enumerable integer (`0`, `1`, `5`, `9`, …) and it is **not** a stable identity: the platform reuses
 * and reassigns it as SIMs are inserted, removed and re-provisioned. Two consequences follow, and both
 * are correctness problems rather than cosmetics:
 *
 *  - `subscriptionId` must never cross into GMweb. Publishing `subId=5` invites a remote party to
 *    *address a line by a number that means something different tomorrow* — and the number is
 *    brute-forceable, so it is weak as an identifier in the first place.
 *  - The **slot index** is display information only. A user-visible "SIM 2" can host a different
 *    subscription after the card is swapped, so routing by slot is exactly how a private message ends
 *    up on the wrong line.
 *
 * `simRef` is the value that crosses the boundary instead: stable for a given install + subscription,
 * useless for enumerating, and impossible to invert into the `subscriptionId` it came from.
 *
 * ## Derivation (canonical)
 *
 * ```text
 * simRef = "sim:v1:" + hmac_sha256(appSecret, "subscription:" + subscriptionId)
 *                             .take(32 hex chars)
 * ```
 *
 * The derivation is **keyed on purpose**. A plain `sha256("subscription:5")` would be trivially
 * reversible by hashing the handful of plausible `subscriptionId` values, which is precisely the
 * enumeration this type exists to prevent. The secret lives in the AndroidKeyStore and never leaves
 * the device (see [SimRefSecret]).
 *
 * ## Explicitly NOT identity inputs
 *
 * ICCID, IMSI and SIM serial must never be read or folded in. `subscriptionId` is what Android
 * recommends for identifying an installed subscription, and the privileged identifiers are both
 * restricted for ordinary apps and unnecessary here. [SimRefProvider.resolveSubscriptionId] matches
 * only against refs derived from the **current active inventory**, so a stale or swapped SIM produces
 * [SimRefResolution.Unavailable] rather than a guess.
 *
 * The format lives here; the derivation and resolution live in [SimRefFormat] / [SimRefProvider].
 * Neither holds **routing logic** — they only convert, format, validate and compare.
 */
@JvmInline
value class SimRef(val value: String) {

    init {
        require(SimRefFormat.isValid(value)) { "not a well-formed simRef: $value" }
    }

    /** The token after `sim:v1:` — for logs and diagnostics. Never the whole identity. */
    val token: String get() = value.removePrefix(SimRefFormat.PREFIX)

    /**
     * A short, non-reversible display form for a UI "technical details" line.
     *
     * Truncating is safe here: the token is a MAC output, so a prefix reveals nothing about the input.
     */
    val shortForm: String get() = "sim:v1:${token.take(8)}…"

    override fun toString(): String = value
}

/**
 * The pure half of the SIM identity: format, strict parsing, derivation and comparison.
 *
 * Android-free and side-effect-free so every branch is unit-testable on the JVM. It reads no
 * platform identifier and calls no telephony API: the only inputs are a `subscriptionId` and an
 * injected MAC.
 */
object SimRefFormat {

    /** Marks the identity scheme. Bumping it is how a future change avoids an ambiguous migration. */
    const val SCHEME = "sim"

    /** The only token version this build can produce or understand. */
    const val VERSION = "v1"

    /** Prefix of every well-formed v1 reference. */
    const val PREFIX = "$SCHEME:$VERSION:"

    /** Length of the hex token: 128 bits of a keyed MAC, which is not invertible by enumeration. */
    const val TOKEN_HEX_LENGTH = 32

    private const val MAC_INPUT_PREFIX = "subscription:"

    private val TOKEN_PATTERN = Regex("^[0-9a-f]{$TOKEN_HEX_LENGTH}$")

    /**
     * The MAC input for [subscriptionId].
     *
     * The fixed domain-separating prefix is required, not decorative: without it the MAC would be over
     * the bare integer, so the same secret used for any other purpose could produce a colliding input.
     */
    fun macInput(subscriptionId: Int): String = "$MAC_INPUT_PREFIX$subscriptionId"

    /**
     * Build the canonical reference from an already-computed MAC.
     *
     * @param mac the raw `HMAC-SHA256(secret, macInput(subscriptionId))` bytes.
     */
    fun fromMac(mac: ByteArray): SimRef = SimRef(PREFIX + hex(mac).take(TOKEN_HEX_LENGTH))

    /**
     * The strict parser.
     *
     * `null` means "this string is not a v1 simRef" and nothing else. It does NOT mean "there is no
     * preference" — that distinction belongs to the caller's three-state model (ABSENT / CLEARED /
     * SET), because conflating "malformed reference" with "no preference" is how a corrupt value
     * silently becomes a permissive default and a message leaves on the wrong SIM.
     */
    fun parse(value: String?): SimRef? {
        if (value == null) return null
        if (!isValid(value)) return null
        return SimRef(value)
    }

    /** True when [value] is a well-formed reference this build produced or understands. */
    fun isValid(value: String?): Boolean {
        if (value == null) return false
        if (!value.startsWith(PREFIX)) return false
        return TOKEN_PATTERN.matches(value.substring(PREFIX.length))
    }

    /**
     * Constant-time-ish comparison, for callers that must not leak *where* two refs first differ.
     *
     * `simRef` is not a bearer secret — possessing one does not grant anything — so this is defence in
     * depth rather than a load-bearing control. It is still the right comparison for an identifier
     * that is compared against device-local values.
     */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == null && b == null
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    /**
     * Lowercase hex, no padding surprises.
     *
     * Written out rather than taken from `Integer.toHexString` because that API drops leading zeroes,
     * which would make the token length — and therefore the identity — depend on the MAC's value.
     */
    internal fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(digits[v ushr 4])
            out.append(digits[v and 0x0F])
        }
        return out.toString()
    }
}

/**
 * `subscriptionId` -> opaque `simRef`, using a device-local key.
 *
 * The MAC function is an injectable parameter so the derivation is testable on the JVM (where
 * AndroidKeyStore does not exist) while production always uses the Keystore-backed key. Tests must
 * never be able to weaken production by accident, which is why the default is the real
 * Keystore-backed function and a test passes its own explicitly.
 *
 * @param mac computes `HMAC-SHA256(deviceSecret, message)`. The default reads [SimRefSecret].
 */
class SimRefProvider(
    private val mac: (String) -> ByteArray = { message -> SimRefSecret.hmacSha256(message) }
) {

    /**
     * Derive the reference for [subscriptionId].
     *
     * Deterministic for a given (key, subscriptionId) pair: the same install and the same active
     * subscription always produce the same ref, and a different subscription always produces a
     * different one.
     */
    fun simRefFor(subscriptionId: Int): SimRef =
        SimRefFormat.fromMac(mac(SimRefFormat.macInput(subscriptionId)))

    /**
     * Resolve [reference] against the device's CURRENT active subscriptions.
     *
     * The only matching rule is exact equality against a freshly derived ref. There is deliberately
     * **no slot-based or positional fallback**: if the requested ref is absent from the active
     * inventory, the answer is [SimRefResolution.Unavailable]. Slot 0 hosting a different SIM after a
     * card swap must not silently inherit the previous SIM's preference.
     *
     * @param activeSubscriptionIds the platform's active subscriptions, read at execution time.
     * @param inventoryReadable `false` when the inventory could not be read at all (for example
     *   `READ_PHONE_STATE` is not granted). This is a *different fact* from "the ref is not there",
     *   and collapsing the two would tell the user their SIM was removed when the app merely could
     *   not look.
     */
    fun resolveSubscriptionId(
        reference: String,
        activeSubscriptionIds: List<Int>,
        inventoryReadable: Boolean = true
    ): SimRefResolution {
        if (!inventoryReadable) return SimRefResolution.InventoryUnavailable
        val target = SimRefFormat.parse(reference)
            ?: return SimRefResolution.Unavailable(reference, SimRefResolution.Reason.NOT_FOUND)

        for (subId in activeSubscriptionIds) {
            if (SimRefFormat.constantTimeEquals(simRefFor(subId).value, target.value)) {
                return SimRefResolution.Resolved(subId, target)
            }
        }
        return SimRefResolution.Unavailable(reference, SimRefResolution.Reason.NOT_FOUND)
    }
}

/**
 * The outcome of asking "which active subscription is this `simRef`?".
 *
 * Three states, because the send path must treat them differently: a resolved line is sendable, a
 * genuinely absent line means the user's chosen SIM is gone and the send must fail closed, and a
 * device that could not answer must not be reported as either.
 */
sealed interface SimRefResolution {

    /** Why a reference could not be resolved to an active subscription. */
    enum class Reason {
        /**
         * The reference is well-formed (or malformed) but matches nothing in the active inventory.
         *
         * A malformed reference and an absent SIM deliberately share this one code on the wire: both
         * mean "this line cannot be selected", and telling a remote caller *which* of the two it was
         * would leak whether a guessed reference format was closer to real.
         */
        NOT_FOUND
    }

    /** The ref belongs to this active subscription, read at execution time. */
    data class Resolved(val subscriptionId: Int, val reference: SimRef) : SimRefResolution

    /** The device answered and the ref matches nothing active it can see. */
    data class Unavailable(
        val reference: String,
        val reason: Reason = Reason.NOT_FOUND
    ) : SimRefResolution

    /** The device could not answer the question at all. Never read as "the SIM is gone". */
    data object InventoryUnavailable : SimRefResolution
}
