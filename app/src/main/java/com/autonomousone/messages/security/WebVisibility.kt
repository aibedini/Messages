package com.autonomousone.messages.security

import com.autonomousone.messages.repository.PhoneIdentity
import java.util.Locale

/**
 * "Hide from web" identity policy (XR-GA web visibility).
 *
 * The phone is the trust root, so the visibility decision is made and stored here; GMweb only
 * receives the resulting `CONVERSATION_DELETED` control event and, for contacts, the excluded
 * snapshot.
 *
 * A phone number has several spellings (`0912…`, `+98912…`, `98912…`, `0098912…`) and the
 * address stored on a conversation is the provider's raw string, not necessarily the contact
 * book's spelling. Hiding by exact string would therefore miss the same contact written another
 * way. [hideKeys] reuses [PhoneIdentity]'s one identity policy and returns every spelling that
 * must match; non-phone senders (short codes, brands) fall back to a trimmed, case-folded value.
 *
 * Pure and Android-free so the matching rule is unit-tested.
 */
object WebVisibility {

    /**
     * Every key that identifies [address] for hiding.
     *
     * Empty input (or input with no usable identity) returns an empty set: a blank address can
     * never be hidden, because it would match unrelated conversations.
     */
    fun hideKeys(address: String): Set<String> {
        val phoneKeys = PhoneIdentity.lookupKeys(address)
        if (phoneKeys.isNotEmpty()) return phoneKeys
        val raw = address.trim().lowercase(Locale.ROOT)
        return if (raw.isEmpty()) emptySet() else setOf(raw)
    }

    /** True when any spelling of [address] is in the hidden key set. */
    fun isHidden(hiddenKeys: Set<String>, address: String): Boolean =
        hideKeys(address).any { it in hiddenKeys }

    /** True when this opaque conversation id is hidden. */
    fun isConversationHidden(hiddenConversationIds: Set<String>, conversationId: String): Boolean =
        conversationId.isNotBlank() && conversationId in hiddenConversationIds
}
