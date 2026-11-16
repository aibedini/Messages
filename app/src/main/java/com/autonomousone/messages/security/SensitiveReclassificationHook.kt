package com.autonomousone.messages.security

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * LDV-004 PR-E (slice 2c) — the classification → revocation seam.
 *
 * A conversation can become sensitive AFTER it was granted: a NORMAL thread receives an OTP or
 * a bank code, or the classifier re-reads a message differently. Design §4.6's rule is that a
 * sensitive conversation is never granted, and §4.5's rule is that a revoked conversation gets
 * a tombstone at a higher `scopeSequence`. This class is the bridge: it turns "this message was
 * classified as X" into "withdraw any AI grant on its conversation".
 *
 * ## Why a seam and not a direct call
 *
 * The classification writers ([com.autonomousone.messages.repository.ClassificationRepository])
 * live behind Android `Context`/Room, so they cannot be unit-tested on a plain JVM. Everything
 * that can be got wrong — the sensitive-category decision, the missing-conversation-identity
 * skip, deduplication, one failing device not stopping the others, and the fail-safe rule that
 * a revocation problem must never break classification — lives here, behind two injected
 * ports, and is covered by `SensitiveReclassificationHookTest`.
 *
 * ## Off the hot path
 *
 * [onClassified] is called from the classification path only, which is already off the SMS
 * ingest path: the immediate hook runs inside `MessageClassificationService`'s own
 * `Dispatchers.Default` scope AFTER the message transaction committed, and the backfill runs
 * inside a `CoroutineWorker` on `Dispatchers.IO`. No new WorkManager job is needed, and SMS
 * handling never waits on it. What it costs per call: one in-memory category check
 * ([ConversationScope.isSensitiveCategory]) and, for a sensitive category only, one indexed
 * `remote_conversation_map` read plus the coordinator's per-device scope read. Key material is
 * never involved — a revoke is key-less — so the Keystore is touched only when a real grant
 * must actually be withdrawn (the tombstone signature).
 *
 * ## Privacy
 *
 * Nothing in this class logs. [SensitiveReclassificationOutcome] carries a
 * [conversationRef] hash, never the raw conversation id, and never a message body or phone
 * number; the production wiring logs that outcome, and only counts plus the hash.
 */
class SensitiveReclassificationHook(
    /** Maps a provider thread id to its opaque cloud conversation identity, or `null`. */
    private val conversationIdOf: suspend (threadId: Long) -> String?,
    /** Withdraws [conversationId] from every linked AI device; `superseded` on the wire. */
    private val revoke: suspend (conversationId: String, category: String) -> SensitiveRevocationResult,
    /** Diagnostics sink; receives counts only. Must not throw. */
    private val onOutcome: (SensitiveReclassificationOutcome) -> Unit = {},
) {

    /**
     * Handle a batch of just-classified messages.
     *
     * Non-sensitive categories, messages without a thread id and conversations without a
     * cloud identity are skipped before any device is read. The same conversation is revoked at
     * most once per call: a batch that classifies several messages of one OTP thread with an
     * equally sensitive category results in ONE revocation.
     *
     * Fail-safe: any failure (a lookup, a database read, the outbox) is reported through
     * [onOutcome] and never thrown — a classification write must never fail because revocation
     * could not be attempted. [CancellationException] still propagates.
     */
    suspend fun onClassified(classified: List<ClassifiedConversation>) {
        if (classified.isEmpty()) return
        val sensitiveThreads = LinkedHashMap<Long, String>()
        for (item in classified) {
            if (item.threadId <= 0L) continue
            if (!ConversationScope.isSensitiveCategory(item.category)) continue
            sensitiveThreads.putIfAbsent(item.threadId, item.category)
        }
        for ((threadId, category) in sensitiveThreads) {
            val conversationId = try {
                conversationIdOf(threadId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onOutcome(
                    SensitiveReclassificationOutcome(
                        conversationRef = NONE,
                        category = category,
                        error = t,
                    )
                )
                continue
            }
            if (conversationId.isNullOrBlank()) {
                // Cloud sync never saw this thread, so no grant can name it.
                onOutcome(
                    SensitiveReclassificationOutcome(
                        conversationRef = NONE,
                        category = category,
                        skipped = true,
                    )
                )
                continue
            }
            val ref = conversationRef(conversationId)
            val result = try {
                revoke(conversationId, category)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onOutcome(
                    SensitiveReclassificationOutcome(
                        conversationRef = ref,
                        category = category,
                        error = t,
                    )
                )
                continue
            }
            onOutcome(outcomeOf(ref, category, result))
        }
    }

    /** Convenience for the single-message (immediate) classification path. */
    suspend fun onClassified(threadId: Long, category: String) {
        onClassified(listOf(ClassifiedConversation(threadId, category)))
    }

    private fun outcomeOf(
        ref: String,
        category: String,
        result: SensitiveRevocationResult,
    ): SensitiveReclassificationOutcome = when (result) {
        SensitiveRevocationResult.NotSensitive -> SensitiveReclassificationOutcome(
            conversationRef = ref,
            category = category,
            skipped = true,
        )

        is SensitiveRevocationResult.Evaluated -> SensitiveReclassificationOutcome(
            conversationRef = ref,
            category = category,
            devices = result.outcomes.size,
            revoked = result.outcomes.count { it.result is ScopeRevokeResult.Revoked },
            alreadyRevoked = result.outcomes.count {
                it.result == ScopeRevokeResult.AlreadyRevoked
            },
            notGranted = result.outcomes.count { it.result == ScopeRevokeResult.NotGranted },
            failed = result.outcomes.count { it.result is ScopeRevokeResult.Failed },
        )

        is SensitiveRevocationResult.Failed -> SensitiveReclassificationOutcome(
            conversationRef = ref,
            category = category,
            enumerationFailed = true,
            error = result.cause,
        )
    }

    private companion object {
        /** Marker for "no conversation identity to hash". */
        const val NONE = "-"
    }
}

/** One just-classified message: the provider thread it belongs to and the stored category. */
data class ClassifiedConversation(val threadId: Long, val category: String)

/**
 * The outcome of one [SensitiveReclassificationHook] branch — counts and a hashed reference
 * only. Never a conversation id, a body, an address or a key.
 */
data class SensitiveReclassificationOutcome(
    /** Short stable hash of the conversation id, or `-` when there was none. */
    val conversationRef: String,
    val category: String,
    /** True when the conversation did not need (or could not have) a revocation. */
    val skipped: Boolean = false,
    val devices: Int = 0,
    val revoked: Int = 0,
    val alreadyRevoked: Int = 0,
    val notGranted: Int = 0,
    val failed: Int = 0,
    /** True when the linked AI devices could not be enumerated, so nothing is known. */
    val enumerationFailed: Boolean = false,
    val error: Throwable? = null,
) {
    /** True when every AI device that could be revoked was revoked, or there was nothing to do. */
    val clean: Boolean
        get() = error == null && !enumerationFailed && failed == 0
}

/**
 * A short, stable, non-reversible diagnostic reference for an opaque conversation id.
 *
 * The design allows `conversationId` as metadata (§4.2, §7 Q3), but a diagnostics log does not
 * need it: a 12-hex-character SHA-256 prefix correlates the lines of one revocation without
 * storing the routing id the sync layer uses. Message content, phone numbers and keys are never
 * loggable at all (and are not part of this value).
 */
fun conversationRef(conversationId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(conversationId.toByteArray(Charsets.UTF_8))
    val hex = StringBuilder(12)
    for (byte in digest) {
        if (hex.length >= 12) break
        val value = byte.toInt() and 0xFF
        hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }
    return hex.toString()
}

private const val HEX = "0123456789abcdef"
