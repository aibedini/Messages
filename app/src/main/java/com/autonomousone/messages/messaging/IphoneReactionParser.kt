package com.autonomousone.messages.messaging

/**
 * Detects iPhone tapback reactions that arrive as plain-text SMS bodies, e.g.
 *
 *   Loved "See you tomorrow!"
 *   Liked an image
 *   Questioned “are we still on?”
 *
 * and converts them to the emoji Google Messages renders. Parsing is strict on
 * purpose: the trailing part must be a quoted message or a known media noun
 * ("an image", "a photo", …) so ordinary sentences starting with "Loved your
 * gift!" are never misclassified as reactions.
 */
object IphoneReactionParser {

    data class Reaction(
        /** Emoji that replaces the raw reaction text. */
        val emoji: String,
        /** Quoted original message, when the reaction quotes one. Null otherwise. */
        val quotedText: String?
    )

    private val verbToEmoji = mapOf(
        "loved" to "❤️",
        "liked" to "👍",
        "disliked" to "👎",
        "laughed" to "😂",
        "emphasized" to "‼️",
        "questioned" to "❓"
    )

    private val unquotedObjects = setOf(
        "an image", "a photo", "a picture", "a video", "a movie",
        "an audio message", "a voice memo", "a gif", "a sticker", "a location",
        "a contact", "a link", "a document"
    )

    private val regex = Regex(
        """^\s*(Loved|Liked|Disliked|Laughed(?:\s+at)?|Emphasized|Questioned)\s+(.+?)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** Returns the parsed reaction, or null when [text] is not a tapback. */
    fun parse(text: String): Reaction? {
        val trimmed = text.trim()
        // Quick reject: reactions are short, single-line strings.
        if (trimmed.isEmpty() || trimmed.length > 300 || trimmed.contains('\n')) return null
        val match = regex.find(trimmed) ?: return null

        val verb = match.groupValues[1].lowercase().substringBefore(' ')
        val rest = match.groupValues[2].trim()
        val emoji = verbToEmoji[verb] ?: return null

        val quoted = extractQuoted(rest)
        if (quoted == null && rest.lowercase() !in unquotedObjects) return null

        return Reaction(
            emoji = emoji,
            quotedText = quoted?.takeIf { it.isNotBlank() && it.length <= 200 }
        )
    }

    fun isReaction(text: String): Boolean = parse(text) != null

    /** Strips curly or straight surrounding quotes: “hi”, "hi" → hi. */
    private fun extractQuoted(rest: String): String? {
        for ((open, close) in listOf("\u201C" to "\u201D", "\"" to "\"")) {
            if (rest.startsWith(open) &&
                rest.endsWith(close) &&
                rest.length >= open.length + close.length + 1
            ) {
                return rest.substring(open.length, rest.length - close.length).trim()
            }
        }
        return null
    }
}
