package com.autonomousone.messages.mms

/**
 * Whether an MMS part has a payload worth sending (mission §52: never drop silently).
 *
 * **The defect this exists for.** Every part in `MmsSender` was written like this:
 *
 * ```kotlin
 * cr.openOutputStream(partUri)?.use { out -> out.write(imageBytes) }
 * return mmsId
 * ```
 *
 * The `?.` is the whole problem. If `openOutputStream` returns null, the *write never happens*, the
 * function still returns a valid MMS id, and the caller reports success: an MMS with an EMPTY part is
 * created, handed to the platform, and shown to the user as sent. `compressImage` had the same shape —
 * it returns `ByteArray(0)` when the bitmap will not decode, which the caller wrote into the part as
 * if it were a picture.
 *
 * Two consequences, both bad in different ways. The recipient gets nothing (or a broken attachment)
 * while the sender believes a photo was delivered; and the empty row is a real message in the
 * provider, so the mirror replicates it and GMweb shows a message that never existed. Mission §52 says
 * such a case "must be documented clearly instead of silently dropping it" — this makes it a named
 * refusal instead.
 *
 * Pure, so the boundary conditions (empty, exactly at the cap, one byte over) are testable without a
 * device or a provider.
 */
sealed interface MmsPayloadVerdict {

    /** There is a payload and it fits. */
    data class Usable(val bytes: Int) : MmsPayloadVerdict

    /** Nothing to send — a decode failure, a zero-byte file, or a stream that never opened. */
    data object Empty : MmsPayloadVerdict

    /** Readable, but larger than a carrier will carry, so sending it would fail at the network. */
    data class TooLarge(val bytes: Int) : MmsPayloadVerdict

    /** The source could not be opened at all (the null-stream case, stated explicitly). */
    data object Unavailable : MmsPayloadVerdict

    val isUsable: Boolean get() = this is Usable
}

object MmsPayloadPolicy {

    /**
     * The largest single part this app will build.
     *
     * The same 900 kB the image path already compressed towards, now applied to every part rather than
     * only to images. It is a LOCAL limit on purpose: an oversize part that the network then rejects
     * costs the user a failed send after a full upload attempt, whereas refusing it here costs nothing
     * and says why.
     */
    const val MAX_PART_BYTES = 900_000

    /**
     * Read no more than this from a source while checking it.
     *
     * One byte over the cap is enough to know the payload is too large, so a huge file never has to be
     * buffered to find that out — which matters because this runs on the send path and a voice note can
     * be tens of megabytes.
     */
    const val READ_LIMIT_BYTES = MAX_PART_BYTES + 1

    /** Classify an in-memory payload. */
    fun classify(bytes: ByteArray?): MmsPayloadVerdict = when {
        bytes == null -> MmsPayloadVerdict.Unavailable
        bytes.isEmpty() -> MmsPayloadVerdict.Empty
        bytes.size > MAX_PART_BYTES -> MmsPayloadVerdict.TooLarge(bytes.size)
        else -> MmsPayloadVerdict.Usable(bytes.size)
    }

    /** Classify by size, for a caller that counted bytes while copying rather than buffering them. */
    fun classifySize(size: Int): MmsPayloadVerdict = when {
        size <= 0 -> MmsPayloadVerdict.Empty
        size > MAX_PART_BYTES -> MmsPayloadVerdict.TooLarge(size)
        else -> MmsPayloadVerdict.Usable(size)
    }

    /**
     * The machine-readable reason a part was refused.
     *
     * Stable strings, so a log line, a REST response and a support conversation all name the same
     * thing. `null` for a usable payload: there is no failure to name.
     */
    fun code(verdict: MmsPayloadVerdict): String? = when (verdict) {
        is MmsPayloadVerdict.Usable -> null
        MmsPayloadVerdict.Empty -> "mms_empty_payload"
        is MmsPayloadVerdict.TooLarge -> "mms_payload_too_large"
        MmsPayloadVerdict.Unavailable -> "mms_payload_unavailable"
    }

    /** The sentence a user or a log reader needs, without naming a recipient or any content. */
    fun reason(verdict: MmsPayloadVerdict): String = when (verdict) {
        is MmsPayloadVerdict.Usable -> "usable"
        MmsPayloadVerdict.Empty ->
            "the attachment produced no bytes, so there is nothing to send"
        is MmsPayloadVerdict.TooLarge ->
            "the attachment is ${verdict.bytes} bytes, over the $MAX_PART_BYTES-byte carrier limit"
        MmsPayloadVerdict.Unavailable ->
            "the attachment could not be read"
    }
}
