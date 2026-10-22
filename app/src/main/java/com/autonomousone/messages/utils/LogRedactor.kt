package com.autonomousone.messages.utils

/**
 * Strips credentials and personal data from text before it reaches a log sink.
 *
 * Pure JVM so the privacy contract is unit-tested. Order matters: tokens are removed before the
 * phone pattern runs, otherwise digits inside a JWT would be "tokenised" and the rest leaked.
 */
object LogRedactor {

    private const val MAX_CHARS = 24_000

    private val jwt = Regex("eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]+")
    private val bearer = Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+")
    private val secretPair = Regex(
        "(?i)\\b(access_token|refresh_token|token|password|passwd|secret|api_key|apikey|authorization)" +
            "(\"?\\s*[:=]\\s*\"?)([^\"&\\s,;}]+)"
    )
    private val email = Regex("\\b([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b")
    private val phone = Regex("(?<![\\dA-Za-z])\\+?\\d{7,15}(?!\\d)")

    fun redact(value: String): String {
        var out = value.take(MAX_CHARS)
        out = jwt.replace(out, "<jwt>")
        out = bearer.replace(out, "Bearer <redacted>")
        out = secretPair.replace(out) { m -> m.groupValues[1] + m.groupValues[2] + "<redacted>" }
        out = email.replace(out) { m -> "${m.groupValues[1]}***@${m.groupValues[2]}" }
        out = phone.replace(out) { m -> "phone#${PhoneToken.of(m.value)}" }
        return out
    }
}

enum class LogLevel(val letter: Char) {
    DEBUG('D'), INFO('I'), WARN('W'), ERROR('E')
}

/** One structured, single-line record: `<iso> <L>/<TAG> [thread] message k=v k2=v2`. */
object LogLineFormatter {

    fun format(
        isoTime: String,
        level: LogLevel,
        tag: String,
        thread: String,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
        stackTrace: String? = null
    ): String = buildString {
        append(isoTime).append(' ')
        append(level.letter).append('/').append(tag.take(40))
        append(" [").append(thread.take(48)).append("] ")
        append(LogRedactor.redact(message).replace('\n', ' '))
        for ((key, value) in fields) {
            if (value == null) continue
            append(' ').append(key).append('=')
            val text = LogRedactor.redact(value.toString())
            if (text.any { it == ' ' || it == '=' }) append('"').append(text.replace("\"", "'")).append('"')
            else append(text)
        }
        if (!stackTrace.isNullOrBlank()) {
            append('\n').append(LogRedactor.redact(stackTrace).trimEnd())
        }
        append('\n')
    }
}
