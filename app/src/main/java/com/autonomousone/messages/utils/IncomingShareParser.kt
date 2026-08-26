package com.autonomousone.messages.utils

/**
 * Parses external share/send intents (ACTION_SEND / ACTION_SENDTO) into a
 * (phone, text) pair. Pure string logic — no android.net.Uri — so it runs in
 * JVM unit tests.
 *
 * Handles:
 *   - sms: / smsto: / mms: / mmsto: data URIs, with or without ?body=/sms_body=
 *   - ACTION_SEND's EXTRA_TEXT (no recipient)
 *
 * NOTE: '+' in the address must survive decoding, so %-escapes are decoded
 * manually instead of URLDecoder.decode (which would turn '+' into ' ').
 */
object IncomingShareParser {

    data class Result(val phone: String, val text: String)

    /** ACTION_SENDTO family: [dataUri] like "smsto:09121234567?body=Hi". */
    fun fromSendTo(
        dataUri: String?,
        smsBody: String?,
        extraText: String?
    ): Result {
        val fallbackText = smsBody?.takeIf { it.isNotBlank() }
            ?: extraText?.takeIf { it.isNotBlank() }
            ?: ""
        if (dataUri.isNullOrBlank()) return Result("", fallbackText)

        val raw = dataUri.substringAfter(':', "")
        val query = raw.substringAfter('?', "")
        val address = percentDecode(raw.substringBefore('?')).trim()

        val bodyParam = query.split('&')
            .firstOrNull {
                it.startsWith("body=", ignoreCase = true) ||
                        it.startsWith("sms_body=", ignoreCase = true)
            }
            ?.substringAfter('=')
            ?.let { percentDecode(it) }
            .orEmpty()

        val text = smsBody?.takeIf { it.isNotBlank() }
            ?: bodyParam.takeIf { it.isNotBlank() }
            ?: extraText.orEmpty()
        return Result(address, text)
    }

    /** ACTION_SEND: shared text with no recipient. */
    fun fromSend(extraText: CharSequence?): Result =
        Result("", extraText?.toString().orEmpty())

    /** Decodes %XX escapes WITHOUT treating '+' as space. */
    private fun percentDecode(value: String): String =
        Regex("%([0-9A-Fa-f]{2})").replace(value) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
}
