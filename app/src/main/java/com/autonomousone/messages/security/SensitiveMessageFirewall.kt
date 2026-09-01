package com.autonomousone.messages.security

import com.autonomousone.messages.utils.DigitNormalizer

/**
 * ADR-006 — SensitiveMessageFirewall (Android Data Plane security boundary).
 *
 * Fully on-device, deterministic classifier: decides BEFORE any cloud event
 * is constructed whether an inbound message may ever leave the phone.
 * No SMS body is ever sent anywhere for classification (ADR-006 §13).
 *
 * v1 detector stack: sender rules + keyword rules + code-pattern analysis,
 * all over DigitNormalizer-canonicalized text. No cloud ML, ever.
 */
object SensitiveMessageFirewall {

    /** ADR-006 §9 categories. Extensible, but ORDER of checks is fixed. */
    enum class Category {
        OTP_SECURITY_CODE,
        BANK_SECURITY_CODE,
        PASSWORD_RESET_CODE,
        AUTHENTICATION_CODE,
        FINANCIAL_NOTIFICATION,
        NORMAL
    }

    /** Policy outcomes (ADR-006 §10 defaults). */
    enum class Policy { LOCAL_ONLY, ASK, SYNC }

    /** ADR-006 §10 — production security mode default: privacy strict. */
    enum class AmbiguityMode { PRIVACY_STRICT, BALANCED;

        val ambiguousPolicy: Policy
            get() = if (this == PRIVACY_STRICT) Policy.LOCAL_ONLY else Policy.SYNC
    }

    /** Result of classification: category + the rule that fired (for local audit). */
    data class Verdict(
        val category: Category,
        /** Rule id, e.g. OTP_PERSIAN_KEYWORD — NEVER contains message content. */
        val rule: String
    )

    // ── Keyword stacks (ADR-006 §14) ────────────────────────────────────────

    private val OTP_KEYWORDS = listOf(
        "otp", "one time password", "one-time password", "one time passcode",
        "verification code", "verify code", "security code", "login code",
        "confirmation code", "activation code", "passcode",
        // Persian
        "رمز پویا", "رمز یکبار مصرف", "رمز یک بار مصرف", "رمز عبور یکبار مصرف",
        "کد تایید", "کد تأیید", "کد ورود", "کد فعالسازی", "کد فعال سازی",
        "کد فعال‌سازی", "کد امنیتی", "کد احراز", "رمز دوم",
        // Arabic
        "رمز التحقق", "كود التحقق", "الرمز المرتد"
    )

    private val PASSWORD_RESET_KEYWORDS = listOf(
        "password reset", "reset your password", "reset code", "recovery code",
        "بازیابی رمز", "بازنشانی رمز", "بازنشانی گذرواژه", "فراموشی رمز"
    )

    private val AUTH_KEYWORDS = listOf(
        "two-factor", "2fa", "authentication code", "auth code",
        "تایید دو مرحله", "تأیید دو مرحله", "ورود دو مرحله"
    )

    private val BANK_SENDER_HINTS = listOf(
        "bank", "بانک",
        // Common Iranian bank alphanumeric sender prefixes
        "bankmellat", "bankmelli", "banksaderat", "banktejarat", "banksaman",
        "bpi", "bsi", "bki", "bmi", "bim", "refah", "maskan", "keshavarzi",
        "parsian", "pasargad", "eghtesadnovin", "samankish", "daybank",
        "ایران‌کیش", "بانک ملی", "بانک ملت", "بانک صادرات", "بانک پارسیان",
        "بانک پاسارگاد", "بانک سامان", "بانک تجارت", "بانک سپه"
    )

    private val FINANCIAL_KEYWORDS = listOf(
        "deposit", "withdraw", "transfer", "balance", "payment",
        "واریز", "برداشت", "انتقال وجه", "موجودی", "پرداخت", "حساب شما",
        "کارمزد", "تراکنش", "قبض"
    )

    /** 4–8 digit code (the typical OTP shape) — used WITH keyword context. */
    private val CODE_PATTERN = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    // ── Classification ──────────────────────────────────────────────────────

    /**
     * Classify a message. [sender] is the raw sender (phone number OR
     * alphanumeric originator — both supported, ADR-006 §12). The body is
     * normalized (Persian/Arabic digits → ASCII) before analysis; original
     * text is never stored or transmitted by this class.
     */
    fun classify(sender: String, body: String): Verdict {
        val normalizedSender = DigitNormalizer.toAsciiDigits(sender).trim().lowercase()
        val text = DigitNormalizer.toAsciiDigits(body)
        val haystack = (normalizedSender + " \n " + text).lowercase()

        // 1) Password reset (most specific keyword family first)
        PASSWORD_RESET_KEYWORDS.firstOrNull { it in haystack }?.let {
            return Verdict(Category.PASSWORD_RESET_CODE, "PWD_RESET_KEYWORD")
        }

        // 2) Authentication / 2FA
        AUTH_KEYWORDS.firstOrNull { it in haystack }?.let {
            return Verdict(Category.AUTHENTICATION_CODE, "AUTH_KEYWORD")
        }

        // 3) Bank security codes: bank context + OTP keyword + code pattern.
        //    Persian bank names in the BODY also count as bank context (many
        //    bank SMS arrive from numeric senders, ADR-006 §22).
        val bankContext = BANK_SENDER_HINTS.any { normalizedSender.contains(it) } ||
            text.lowercase().let { t -> BANK_SENDER_HINTS.any { t.contains(it) } } ||
            text.contains("بانک")
        val hasCode = CODE_PATTERN.containsMatchIn(text)
        val otpKeyword = OTP_KEYWORDS.any { it in haystack }

        if (bankContext && otpKeyword && hasCode) {
            return Verdict(Category.BANK_SECURITY_CODE, "BANK_CODE_KEYWORD")
        }

        // 4) Generic OTP: keyword + code (bare numbers are never enough)
        if (otpKeyword && hasCode) {
            return Verdict(Category.OTP_SECURITY_CODE, "OTP_KEYWORD_CODE")
        }

        // 5) Financial notification: bank/financial context + financial verb,
        //    WITHOUT the OTP pair above (the ADR-006 §15 disambiguation).
        if ((bankContext || FINANCIAL_KEYWORDS.any { it in haystack }) &&
            FINANCIAL_KEYWORDS.any { it in haystack } &&
            text.any { it.isDigit() }
        ) {
            return Verdict(Category.FINANCIAL_NOTIFICATION, "FINANCIAL_KEYWORD")
        }

        return Verdict(Category.NORMAL, "NO_MATCH")
    }

    // ── Policy resolution ───────────────────────────────────────────────────

    /**
     * Resolve the policy for a verdict, honoring ADR-006 §10 defaults,
     * per-sender overrides and the ambiguity mode. Order matters:
     *   1. user local-only sender list (strongest)
     *   2. category default
     *   3. sync allowlist (only affects SYNC-able categories — never an
     *      OTP/bank-code classification, ADR-006 §4)
     */
    fun resolvePolicy(
        verdict: Verdict,
        sender: String,
        localOnlySenders: Set<String>,
        syncAllowlist: Set<String>,
        financialPolicy: Policy,
        ambiguityMode: AmbiguityMode
    ): Policy {
        val s = DigitNormalizer.toAsciiDigits(sender).trim().lowercase()
        if (s.isNotEmpty() && localOnlySenders.any { s.contains(it.lowercase()) }) {
            return Policy.LOCAL_ONLY
        }
        return when (verdict.category) {
            Category.OTP_SECURITY_CODE,
            Category.BANK_SECURITY_CODE,
            Category.PASSWORD_RESET_CODE,
            Category.AUTHENTICATION_CODE -> Policy.LOCAL_ONLY
            Category.FINANCIAL_NOTIFICATION -> financialPolicy
            Category.NORMAL ->
                if (s.isNotEmpty() && syncAllowlist.any { s.contains(it.lowercase()) }) Policy.SYNC
                else ambiguityMode.ambiguousPolicy
        }
    }
}
