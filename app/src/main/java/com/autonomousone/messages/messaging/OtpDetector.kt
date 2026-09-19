package com.autonomousone.messages.messaging

/**
 * THE single OTP detection engine (v3.4.0).
 *
 * NotificationHelper, Smart Categories, the bubble "Copy code" action and the
 * global OTP auto-delete ALL call [detect]. There is deliberately no second
 * detector anywhere in the app: two engines would disagree about whether a
 * message is an OTP, and only one of them would protect it from cleanup.
 *
 * The previous `NotificationHelper.extractOtpCode` is superseded by this class
 * (it stays only as a delegating shim for compatibility, and is scheduled for
 * removal once all callers migrate).
 *
 * PRIVACY: detection is purely local, never uploads content, and callers must
 * never log the returned code or the body.
 */
object OtpDetector {

    /**
     * The confidence floor a STRONG context must reach. Smart Categories treats
     * only a detection at or above this floor as `isOtp`, and the global OTP
     * cleanup consumes that flag — so a weak "code is …" match can never become
     * an auto-deletable OTP by accident.
     */
    const val HIGH_CONFIDENCE = 0.7f

    enum class OtpKind {
        OTP,
        VERIFICATION,
        BANK_DYNAMIC_PASSWORD,
        PASSWORD_RESET
    }

    data class OtpDetection(
        val code: String,
        val confidence: Float,
        val kind: OtpKind
    )

    /**
     * 3-8 digit run, optionally group-split (123-456 / 123 456). The
     * lookbehind/lookahead keeps it from grabbing a digit run that is part of a
     * longer number (phone, account, tracking or price).
     */
    private val CODE = Regex("""(?<![\dA-Za-z])(\d{3,8})(?:[\s.\-](\d{3,8}))?(?![\dA-Za-z])""")

    /** Very strong English contexts. */
    private val STRONG_EN = listOf(
        "verification code", "verify code", "confirm code", "security code",
        "one-time code", "one time code", "one-time password", "otp",
        "passcode", "login code", "activation code", "your code",
        "dynamic password", "reset code", "access code", "authentication code"
    )

    /** Weaker English contexts — require a clean standalone digit run. */
    private val WEAK_EN = listOf("code is", "code:", "code", "verify", "verification", "password")

    /** Persian contexts (bank / service wording used by Iranian operators). */
    private val STRONG_FA = listOf(
        "رمز پویا", "رمز یکبار مصرف", "رمز یک بار مصرف", "یکبار مصرف",
        "کد تایید", "کد تأیید", "کد اعتبارسنجی", "کد یکبار مصرف",
        "رمز دوم", "کد امنیتی", "کد ورود", "کد فعالسازی", "کد فعال‌سازی",
        "تایید دو مرحله", "تأیید دو مرحله"
    )

    private val WEAK_FA = listOf("کد", "رمز", "تایید", "تأیید", "اعتبارسنجی", "ورود")

    /** Reject obvious non-code digit runs. */
    private val YEAR = Regex("""(19|20)\d{2}""")
    private val PRICE = Regex("""\d{1,3}(?:[.,]\d{3})+""")

    // Persian (۰-۹) and Arabic-Indic (٠-٩) digits, folded to ASCII.
    private val FA_AR_DIGITS = mapOf(
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
        '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9'
    )

    /** Folds Persian/Arabic digits to ASCII so one regex handles every script. */
    fun normalizeDigits(input: String): String =
        input.map { FA_AR_DIGITS[it] ?: it }.joinToString("")

    /** True when the code is a plausible 4-8 digit OTP and not some other number. */
    fun isPlausibleCode(candidate: String): Boolean {
        if (candidate.length !in 4..8) return false
        if (!candidate.all { it.isDigit() }) return false
        if (YEAR.containsMatchIn(candidate)) return false
        if (PRICE.containsMatchIn(candidate)) return false
        return true
    }

    /**
     * Detects an OTP in [body]. Returns null when the message is not an OTP.
     *
     * Confidence is deliberately conservative: only a STRONG context reaches the
     * 0.7+ threshold the bubble copy and auto-delete paths require. A weak
     * keyword alone caps below it, so an ordinary message that merely contains
     * the word "code" can never be auto-deleted.
     */
    fun detect(sender: String, body: String): OtpDetection? {
        if (body.isBlank()) return null
        val normalized = normalizeDigits(body)
        val lower = normalized.lowercase()
        val senderDigitCount = normalizeDigits(sender).count { it.isDigit() }

        val strongEn = STRONG_EN.firstOrNull { lower.contains(it) }
        val strongFa = STRONG_FA.firstOrNull { normalized.contains(it) }
        val strongContext = strongEn ?: strongFa

        // Strong context: the code is expected AFTER the keyword, group-split OK.
        if (strongContext != null) {
            for (group in candidatesAfter(normalized, lower, strongContext)) {
                val code = group.replace("-", "").replace(" ", "").replace(".", "")
                if (!isPlausibleCode(code)) continue
                val kind = when {
                    strongFa != null && strongFa.contains("رمز") -> OtpKind.BANK_DYNAMIC_PASSWORD
                    lower.contains("reset") -> OtpKind.PASSWORD_RESET
                    code.length in 6..8 -> OtpKind.OTP
                    else -> OtpKind.VERIFICATION
                }
                return OtpDetection(code, 0.9f, kind)
            }
            // Strong context but no usable run after it: fall through to the
            // general scan rather than giving up on a badly formatted message.
        }

        // Weak context: needs a clean standalone run, and confidence stays low.
        val weakContext = (WEAK_EN.any { lower.contains(it) }) ||
            (WEAK_FA.any { normalized.contains(it) })
        if (!weakContext) return null

        for (match in CODE.findAll(normalized)) {
            val joined = match.value.replace("-", "").replace(" ", "").replace(".", "")
            if (!isPlausibleCode(joined)) continue
            // A sender that is itself a long digit run is a bank/service
            // shortcode, which supports the OTP reading.
            val senderBonus = if (senderDigitCount >= 6) 0.05f else 0f
            return OtpDetection(joined, 0.5f + senderBonus, OtpKind.OTP)
        }
        return null
    }

    /**
     * Candidate runs from the text FOLLOWING the matched keyword — where a
     * sender actually puts the code. Scanning before the keyword would happily
     * return the message's own date or amount.
     */
    private fun candidatesAfter(
        normalized: String,
        lower: String,
        context: String
    ): List<String> {
        val index = when {
            normalized.contains(context) -> normalized.indexOf(context) + context.length
            lower.contains(context) -> lower.indexOf(context) + context.length
            else -> return emptyList()
        }
        if (index >= normalized.length) return emptyList()
        val remainder = normalized.substring(index)
        val lineEnd = remainder.indexOfFirst { it == '\n' || it == '\r' }
        val line = if (lineEnd >= 0) remainder.substring(0, lineEnd) else remainder
        return CODE.findAll(line).map { it.value }.toList()
    }
}