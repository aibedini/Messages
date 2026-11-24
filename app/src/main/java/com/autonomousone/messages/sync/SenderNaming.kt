package com.autonomousone.messages.sync

/**
 * The versioned sender-naming contract shared with GMweb (and any other replicas).
 *
 * ## Why this exists
 *
 * A conversation is displayed by a name, but the phone may only know a raw sender address. The old
 * payload left consumers to guess what `displayName` meant, so a replica could overwrite a directory
 * name ("Parsian Bank") with the raw short code and render "Unknown conversation". This contract
 * makes the provenance explicit:
 *
 * - [SenderName.address] is the raw provider sender, byte for byte. It is always the identity.
 * - [SenderName.displayName] is never blank; it is either a human name or the address itself.
 * - [SenderName.displayNameSource] says which: [SOURCE_CONTACT] or [SOURCE_ADDRESS] (or
 *   [SOURCE_UNKNOWN] when the provider gave nothing usable).
 * - [SenderName.senderKind] tells consumers whether the address is a subscriber number, a short
 *   code, an alphanumeric sender id or unknown — the three cases must not be formatted the same way.
 *
 * A consumer must resolve a title with this precedence:
 *
 * ```text
 * USER rename > CONTACT name > DIRECTORY name > ADDRESS
 * ```
 *
 * `SOURCE_ADDRESS` is a fallback, NOT a human name: a replica must never replace a USER/CONTACT/
 * DIRECTORY name with it. That single rule is what prevents a known bank from turning into
 * "Unknown conversation" after the next upsert. `USER` and `DIRECTORY` are reserved for the consumer
 * side; the phone never emits them.
 *
 * The machine-readable copy lives in `protocol/sender-naming-v1.json` and is asserted by
 * `SenderNamingContractTest`; this object is the producer implementation.
 */
data class SenderName(
    val address: String,
    val displayName: String,
    val displayNameSource: String,
    val senderKind: String,
)

object SenderNaming {

    /** Bump only with a matching `protocol/sender-naming-vN.json`. */
    const val VERSION = 1

    const val SOURCE_CONTACT = "CONTACT"
    const val SOURCE_ADDRESS = "ADDRESS"
    const val SOURCE_UNKNOWN = "UNKNOWN"

    /** Reserved for consumers; the phone never sends these. */
    const val SOURCE_USER = "USER"
    const val SOURCE_DIRECTORY = "DIRECTORY"

    const val KIND_PHONE = "PHONE"
    const val KIND_SHORT_CODE = "SHORT_CODE"
    const val KIND_ALPHANUMERIC = "ALPHANUMERIC"
    const val KIND_UNKNOWN = "UNKNOWN"

    /** Label used only when the provider supplied no usable address at all. */
    const val UNKNOWN_DISPLAY_NAME = "Unknown"

    /**
     * Resolve the wire naming fields.
     *
     * @param rawAddress the provider's exact sender value (display identity truth)
     * @param normalizedAddress phone-matching helper, used only when [rawAddress] is blank
     * @param contactName optional address-book name
     */
    fun resolve(
        rawAddress: String?,
        normalizedAddress: String? = null,
        contactName: String? = null,
    ): SenderName {
        val raw = rawAddress?.trim().orEmpty()
        val address = raw.ifBlank { normalizedAddress?.trim().orEmpty() }
        val kind = SenderIdentity.classify(raw, normalizedAddress).name
        val name = contactName?.trim().orEmpty()

        return when {
            name.isNotEmpty() -> SenderName(
                address = address,
                displayName = name,
                displayNameSource = SOURCE_CONTACT,
                senderKind = kind,
            )

            address.isNotEmpty() -> SenderName(
                address = address,
                displayName = address,
                displayNameSource = SOURCE_ADDRESS,
                senderKind = kind,
            )

            else -> SenderName(
                address = "",
                displayName = UNKNOWN_DISPLAY_NAME,
                displayNameSource = SOURCE_UNKNOWN,
                senderKind = KIND_UNKNOWN,
            )
        }
    }
}
