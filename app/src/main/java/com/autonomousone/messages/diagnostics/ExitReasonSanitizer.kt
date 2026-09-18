package com.autonomousone.messages.diagnostics

import com.autonomousone.messages.utils.DiagnosticLog
import java.io.InputStream

/**
 * Phase 14 privacy gate for text taken from [android.app.ApplicationExitInfo].
 *
 * Process exit records and their ANR/crash traces are platform-produced blobs
 * that can accidentally contain anything the process held in memory — an SMS
 * body echoed into a log line, a phone number from an intent, a session token
 * in a header. This object is deliberately free of Android APIs so the exact
 * redaction rules and the trace bounding can be exercised by pure JVM tests.
 *
 * Rules:
 *  - never read more than [MAX_EXCERPT_BYTES] from a trace stream;
 *  - redact SMS-body-like assignments to end-of-line;
 *  - redact credential-like assignments, bearer headers, JWTs and long opaque
 *    tokens;
 *  - redact phone-number-like digit runs, reusing [DiagnosticLog.phoneToken]
 *    for the stable, irreversible replacement;
 *  - cap the sanitized fragment so a hostile/huge trace can never flood the
 *    rotating diagnostic log.
 *
 * Redaction is intentionally aggressive: losing a little diagnostic detail is
 * always preferable to persisting a message body or a credential.
 */
object ExitReasonSanitizer {

    /** Hard cap on how many bytes are ever read from one exit-info trace. */
    const val MAX_EXCERPT_BYTES: Int = 64 * 1024

    /** Upper bound on any fragment handed to [DiagnosticLog]. */
    const val MAX_TEXT_CHARS: Int = 24_000

    /** The excerpt persisted for a single exit record is smaller still. */
    const val MAX_PERSISTED_EXCERPT_CHARS: Int = 12_000

    // Key = value|: with an SMS-body-ish key. The value runs to end of line
    // because SMS bodies contain spaces, so stopping at the first space would
    // leave most of the body behind.
    private val bodyAssignmentPattern = Regex(
        "(?i)\\b((?:body|sms_?body|message_?body|msg_?body|message|msg|text|content|snippet))" +
            "\\b\\s*[=:]\\s*[^\\r\\n]*"
    )

    private val secretAssignmentPattern = Regex(
        "(?i)\\b((?:token|secret|password|passwd|passphrase|api[_-]?key|apikey|" +
            "authorization|auth|cookie|session[_-]?id))\\b\\s*[=:]\\s*\\S+"
    )

    private val bearerPattern = Regex("(?i)\\bbearer\\s+\\S+")

    private val jwtPattern = Regex("eyJ[A-Za-z0-9_\\-]{6,}(?:\\.[A-Za-z0-9_\\-]{6,}){1,2}")

    private val longSecretPattern = Regex("(?<![A-Za-z0-9_\\-])[A-Za-z0-9_\\-]{32,}(?![A-Za-z0-9_\\-])")

    // Same broad international/mobile-like sequence used by DiagnosticLog: it
    // may also redact an occasional long numeric id, which is preferable to
    // leaking a real number.
    private val phonePattern = Regex("(?<![\\dA-Za-z])\\+?\\d{7,15}(?!\\d)")

    /**
     * Redacts credential-, body- and phone-like content from [text].
     * Returns the empty string for null/empty input.
     */
    fun sanitize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var out = text.take(MAX_TEXT_CHARS)
        out = bodyAssignmentPattern.replace(out) { match ->
            match.groupValues[1] + "=[redacted-body]"
        }
        out = secretAssignmentPattern.replace(out) { match ->
            match.groupValues[1] + "=[redacted-secret]"
        }
        out = bearerPattern.replace(out, "Bearer [redacted-secret]")
        out = jwtPattern.replace(out, "[redacted-token]")
        out = longSecretPattern.replace(out, "[redacted-token]")
        out = phonePattern.replace(out) { match ->
            "phone#" + DiagnosticLog.phoneToken(match.value)
        }
        return out
    }

    /**
     * Reads at most [maxBytes] from [input] and decodes them as UTF-8. The
     * stream is closed. A null stream, an empty stream, a read failure or a
     * [maxBytes] <= 0 all yield the empty string — never an exception.
     *
     * This is the only place that touches a trace stream: callers must invoke
     * it off the main thread.
     */
    fun boundedExcerpt(input: InputStream?, maxBytes: Int = MAX_EXCERPT_BYTES): String {
        if (input == null || maxBytes <= 0) return ""
        val buffer = ByteArray(maxBytes)
        var total = 0
        try {
            input.use { stream ->
                while (total < maxBytes) {
                    val read = stream.read(buffer, total, maxBytes - total)
                    if (read <= 0) break
                    total += read
                }
            }
        } catch (_: Throwable) {
            // Keep whatever was read before the failure.
        }
        return String(buffer, 0, total, Charsets.UTF_8)
    }

    /**
     * The caller-facing convenience: a bounded, sanitized excerpt no larger
     * than [MAX_PERSISTED_EXCERPT_CHARS] characters.
     */
    fun sanitizeExcerpt(input: InputStream?, maxBytes: Int = MAX_EXCERPT_BYTES): String =
        sanitize(boundedExcerpt(input, maxBytes)).take(MAX_PERSISTED_EXCERPT_CHARS)
}
