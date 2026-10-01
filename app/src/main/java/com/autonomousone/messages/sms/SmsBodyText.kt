package com.autonomousone.messages.sms

/**
 * The outbound SMS body: validation and transmission are two different things.
 *
 * **The defect this exists for.** Two gateway routes read the message and then
 * *transmitted the trimmed value*:
 *
 * ```
 * val message = json.optString("message", "").trim()   // /api/v1/sms/send
 * val text    = json.optString("text", "").trim()      // /send
 * ```
 *
 * `.trim()` is a perfectly good way to test for emptiness and a destructive way
 * to prepare a payload: it removes leading/trailing line breaks, so
 * `"\nline1\nline2\n"` left the device as `"line1\nline2"` — a message the
 * sender never wrote, on a channel where the recipient cannot tell it was
 * altered.
 *
 * The rule, and the reason this is a separate object rather than a line of
 * inline code: **only the line-ending REPRESENTATION is normalised; whitespace
 * and line breaks are content.** Callers ask [isSendable] whether a body is
 * worth sending and transmit the value they were given — never a trimmed copy.
 *
 * Pure and Android-free so the guarantee can be pinned by a JVM test.
 */
object SmsBodyText {

    /**
     * Normalises line-ending *representation* only: CRLF and a lone CR become LF.
     *
     * Not a formatter: runs of newlines (`\n\n`), leading/trailing newlines,
     * indentation and trailing spaces are all preserved exactly. GSM/UCS-2
     * encoding and segmentation are `SmsManager.divideMessage`'s authority, and
     * they must see the body as the caller wrote it.
     */
    fun normalizeLineEndings(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * True when the body has at least one non-whitespace character.
     *
     * The check inspects the body; it never rewrites it. A rejected body is
     * rejected — the sender's bytes are never mutated into an acceptable form.
     */
    fun isSendable(value: String): Boolean = value.isNotBlank()
}
