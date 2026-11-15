package com.autonomousone.messages.security

import com.autonomousone.messages.messaging.MessagingPreferences

/**
 * The LOCAL_ONLY verdict for a whole conversation, as one pure rule (XR-GA-017).
 *
 * ## Why a suggestion needs this at all
 *
 * ADR-006's promise is that an OTP, a bank code or a user-blacklisted sender never leaves the device.
 * A `SUGGESTIONS_READY` command for such a conversation would mean the promise was already broken
 * upstream — but the device must not *compound* it by storing AI-written reply drafts for content it
 * never allowed to sync, and must never render them. So the gate is evaluated independently here, at
 * both ends (command intake and card visibility), using the same classifier and policy resolution the
 * message firewall itself uses.
 *
 * ## Why it reuses the firewall instead of a second list
 *
 * A parallel "local-only conversations" table would be a second source of truth that the message path
 * does not consult, and the two would drift the first time the user edits the sender list. Deriving
 * the verdict from the SAME `classify` + `resolvePolicy` calls means a conversation is LOCAL_ONLY for
 * suggestions exactly when its messages were LOCAL_ONLY for replication.
 *
 * `ASK` counts as LOCAL_ONLY here: the ADR is explicit that a policy the user has not answered must
 * fail closed, and a suggestion card for a message still awaiting that answer would leak its content
 * onto a screen the sender was never authorized to reach.
 */
object ThreadLocalOnlyPolicy {

    /**
     * @param sender the conversation's address, exactly as the provider stored it.
     * @param body the newest inbound body — the same input the firewall classified at sync time.
     */
    fun isLocalOnly(
        sender: String,
        body: String,
        localOnlySenders: Set<String>,
        syncAllowlistSenders: Set<String>,
        financialPolicy: SensitiveMessageFirewall.Policy,
        ambiguityMode: SensitiveMessageFirewall.AmbiguityMode
    ): Boolean {
        val verdict = SensitiveMessageFirewall.classify(sender, body)
        val policy = SensitiveMessageFirewall.resolvePolicy(
            verdict = verdict,
            sender = sender,
            localOnlySenders = localOnlySenders,
            syncAllowlist = syncAllowlistSenders,
            financialPolicy = financialPolicy,
            ambiguityMode = ambiguityMode
        )
        return policy == SensitiveMessageFirewall.Policy.LOCAL_ONLY
    }

    /** The same verdict from the app's stored privacy settings. */
    fun isLocalOnly(
        sender: String,
        body: String,
        prefs: MessagingPreferences
    ): Boolean = isLocalOnly(
        sender = sender,
        body = body,
        localOnlySenders = prefs.localOnlySenders,
        syncAllowlistSenders = prefs.syncAllowlistSenders,
        financialPolicy = prefs.financialNotificationPolicy,
        ambiguityMode = prefs.ambiguityMode
    )
}
