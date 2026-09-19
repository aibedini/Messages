package com.autonomousone.messages.utils

/**
 * Presentation direction of ONE piece of text.
 *
 * Deliberately NOT "is this app Persian". Bubble side is ownership (incoming /
 * outgoing); this is paragraph direction. A Persian OUTGOING message stays on the
 * outgoing side and simply starts from the right, and an English OUTGOING message
 * stays there and starts from the left.
 */
enum class ContentDirection {
    RTL,
    LTR,

    /**
     * No strong directional character at all: emoji, digits (ASCII or Persian/
     * Arabic-Indic), punctuation, symbols, whitespace, or an empty string.
     *
     * The caller falls back to the surrounding UI layout, which is why number-only
     * text (`۱۲۳۴۵۶`, `+98 912…`) must land here rather than being called RTL just
     * because it contains Persian digits — those are NUMBERS, not strong RTL letters.
     */
    NEUTRAL
}

/**
 * Text direction from the CONTENT, using Unicode bidi character directionality.
 *
 * Pure and Android-free so it is unit-tested directly. It never transforms the text:
 * it returns a direction and leaves the string alone, so highlight ranges, OTP
 * extraction, copying and accessibility all keep working on the original characters.
 * No bidi control marks are ever inserted into a message body.
 */
object ContentDirectionResolver {

    /**
     * Direction of [text] from its FIRST STRONG directional character.
     *
     * Characters that carry no strong direction — whitespace, punctuation, emoji,
     * symbols and all digits — are skipped, so a leading emoji or punctuation can
     * never decide the paragraph direction:
     *
     *  - `"😊 سلام"` → RTL (the emoji is skipped, `س` decides)
     *  - `"!!! Hello"` → LTR
     *  - `"123 سلام"` → RTL
     *  - `"۱۲۳۴۵۶"` → NEUTRAL (digits only)
     *
     * Mixed content resolves from the first strong run, which is what makes a
     * mixed line read the way a human reads it:
     *
     *  - `"سلام John"` → RTL   and `"Hello علی"` → LTR
     *  - `"https://example.com سلام"` → LTR
     *  - `"سلام https://example.com"` → RTL
     *
     * Iteration is by CODE POINT (not `Char`), so astral-plane content such as emoji
     * is inspected as one character instead of two surrogate halves.
     */
    fun resolve(text: CharSequence?): ContentDirection {
        if (text.isNullOrEmpty()) return ContentDirection.NEUTRAL

        var index = 0
        val length = text.length
        while (index < length) {
            val codePoint = Character.codePointAt(text, index)
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return ContentDirection.LTR
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return ContentDirection.RTL
                else -> Unit // neutral: keep looking for the first strong character
            }
            index += Character.charCount(codePoint)
        }
        return ContentDirection.NEUTRAL
    }
}
