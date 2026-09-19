package com.autonomousone.messages.messaging

import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.security.SensitiveMessageFirewall
import com.autonomousone.messages.utils.DigitNormalizer

/**
 * Does this sender belong to a known contact?
 *
 * Declared as a one-method interface so the classifier stays PURE and
 * Android-free (it unit-tests on the JVM with a lambda). The production wiring
 * reads the process-wide `ContactRepository` name map, which is a plain `Map`
 * lookup — never a provider scan per message.
 */
fun interface KnownContactLookup {
    fun isKnownContact(sender: String): Boolean
}

/**
 * FEATURE 12 — Smart Categories: THE single local message classifier.
 *
 * ONE classification engine. `SensitiveMessageFirewall` (ADR-006) remains the
 * authority for whether a message may EVER leave the device, and [OtpDetector]
 * remains the single OTP detection engine: this class re-implements NEITHER. It
 * composes both into the v3.4.0 category vocabulary ([MessageCategory]) that
 * Home filters on.
 *
 * PRIORITY (fixed, first match wins)
 * ----------------------------------
 *  1. OTP — only when the unified [OtpDetector] reports HIGH confidence. A weak
 *     "code" match is deliberately not enough: classifying a receipt number as
 *     an OTP would both mislabel the thread and (through `isOtp`) hand the
 *     message to global OTP cleanup.
 *  2. PERSONAL (known contact) — only for senders that are actually in the
 *     user's contacts, and only when no OTP fired. A message from a real person
 *     is personal news even when it happens to mention money; without this rule
 *     "۱ میلیون تومان بهت بدهکارم" would be filed as a bank transaction.
 *  3. TRANSACTION — bank/money movement, receipts, invoices. A security OTP is
 *     never a transaction (rule 1 claimed it) and promotional copy is never a
 *     transaction (guarded below).
 *  4. PROMOTION — advertising, discounts, marketing, unsubscribe footers.
 *  5. PERSONAL (person-like sender) — a long, ordinary phone number.
 *  6. SPAM — NEVER derived from a heuristic in v3.4.0. Spam is a USER action
 *     (`conversation_preferences.spam` / `categoryOverride`), so this classifier
 *     never returns it. The constant is listed so the vocabulary stays total.
 *  7. UNKNOWN — fallback, kept at 0.0 confidence.
 *
 * PRIVACY / SAFETY
 * ----------------
 *  - 100% local and deterministic: no network, no cloud, no AI, no clock, no
 *    randomness. The same input always yields the same output.
 *  - The body is never logged, stored or returned; [ClassificationResult]
 *    carries only the category, a bounded confidence and, for OTPs, the single
 *    code the unified detector already produced.
 *  - Never throws: an unexpected failure degrades to UNKNOWN instead of
 *    breaking an ingest path.
 */
class MessageClassifier(
    /**
     * ADR-006 authority, kept as the classifier's security dependency per the
     * v3.4.0 contract. Its rules are NOT duplicated here: the OTP/security
     * decision is delegated to the unified [OtpDetector], and the firewall keeps
     * owning sync eligibility in `TelephonySyncCoordinator.enqueueCloudEvent`.
     * It is injectable so a future security rule can be consulted without a
     * second classifier being written.
     */
    @Suppress("unused")
    private val sensitiveFirewall: SensitiveMessageFirewall = SensitiveMessageFirewall,
    private val knownContacts: KnownContactLookup = KnownContactLookup { false }
) {

    /**
     * One classification outcome. [confidence] is always inside `[0, 1]`.
     *
     * @param type provider message type (`1` = inbox, `2` = sent, …). Accepted
     *   because it is part of the frozen v3.4.0 classifier contract and is the
     *   dimension the OTP-retention workstream filters on; no current rule
     *   branches on it, so incoming and outgoing messages that look alike are
     *   classified alike.
     */
    fun classify(sender: String, body: String, type: Int): ClassificationResult {
        val normalizedSender = DigitNormalizer.toAsciiDigits(sender).trim()
        val text = DigitNormalizer.toAsciiDigits(body)
        if (text.isBlank()) {
            return ClassificationResult(MessageCategory.UNKNOWN, 0f, isOtp = false)
        }

        val lower = text.lowercase()
        val senderDigits = normalizedSender.count { it.isDigit() }
        val shortCode = isShortCode(normalizedSender, senderDigits)
        val personLike = isLikelyPerson(normalizedSender, senderDigits)
        val known = knownContactOrFalse(sender)

        // ── 1. OTP (highest) ────────────────────────────────────────────────
        val otp = OtpDetector.detect(normalizedSender, text)
        if (otp != null && otp.confidence >= OtpDetector.HIGH_CONFIDENCE) {
            return ClassificationResult(
                category = MessageCategory.OTP,
                confidence = otpConfidence(otp.confidence, lower, shortCode),
                isOtp = true,
                otp = otp
            )
        }

        // ── 2. PERSONAL — a real contact beats money/promo wording ──────────
        if (known) {
            return ClassificationResult(
                MessageCategory.PERSONAL,
                PERSONAL_CONTACT,
                isOtp = false
            )
        }

        // ── 3. TRANSACTION ──────────────────────────────────────────────────
        val promoMarker = firstMatch(PROMO_MARKERS, lower)
        if (promoMarker == null || promoMarker !in STRONG_PROMO_MARKERS) {
            transactionConfidence(lower)?.let { confidence ->
                return ClassificationResult(
                    MessageCategory.TRANSACTION,
                    confidence,
                    isOtp = false
                )
            }
        }

        // ── 4. PROMOTION ────────────────────────────────────────────────────
        if (promoMarker != null) {
            return ClassificationResult(
                MessageCategory.PROMOTION,
                PROMO_BASE + if (promoMarker in STRONG_PROMO_MARKERS) 0.15f else 0f,
                isOtp = false
            )
        }

        // ── 5. PERSONAL — an ordinary person-like phone number ──────────────
        //
        // A plain subscriber number (10-15 digits) whose text fired neither the OTP,
        // the transaction nor the promotion rules is PERSONAL. There is deliberately
        // no extra "transaction-ish wording" veto here: the previous version set a
        // `transactionOnly` flag to TRUE whenever the transaction check did NOT match,
        // which made the flag mean the opposite of its name and left every ordinary
        // personal conversation as UNKNOWN.
        if (personLike) {
            val personalMarker = firstMatch(PERSONAL_MARKERS, lower)
            return ClassificationResult(
                MessageCategory.PERSONAL,
                if (personalMarker != null) PERSONAL_LIKELY + 0.10f else PERSONAL_LIKELY,
                isOtp = false
            )
        }

        // ── 6./7. SPAM is user-only in v3.4.0 → UNKNOWN fallback ────────────
        return ClassificationResult(MessageCategory.UNKNOWN, 0f, isOtp = false)
    }

    // ── Sender shape ────────────────────────────────────────────────────────

    /**
     * A pure numeric short code (5-6 digits) is a service originator, not a
     * person: "12345" is how a bank or a delivery service addresses its
     * subscribers.
     */
    private fun isShortCode(sender: String, digits: Int): Boolean =
        sender.isNotEmpty() &&
            digits in SHORTCODE_MIN_DIGITS until MSISDN_MIN_DIGITS &&
            digits == sender.length

    /**
     * A real subscriber number (10-15 digits once `+`, spaces and dashes are
     * ignored) is very likely a person when nothing stronger fired. Deliberately
     * excludes short codes and alphanumeric originators ("BANKMELLAT"), which
     * are services.
     */
    private fun isLikelyPerson(sender: String, digits: Int): Boolean {
        if (sender.isEmpty()) return false
        if (digits !in MSISDN_MIN_DIGITS..MSISDN_MAX_DIGITS) return false
        return sender.none { it.isLetter() }
    }

    private fun knownContactOrFalse(sender: String): Boolean =
        try {
            sender.isNotBlank() && knownContacts.isKnownContact(sender)
        } catch (_: Throwable) {
            // A lookup failure may degrade the category, never the ingest.
            false
        }

    // ── Confidence ──────────────────────────────────────────────────────────

    /**
     * Confidence for a detected OTP: the detector's own value, with small
     * bounded bonuses for confirming wording and a service short code. Never
     * pushed above 1.0 and never dropped below its own detection floor.
     */
    private fun otpConfidence(detected: Float, lower: String, shortCode: Boolean): Float {
        val confirming = firstMatch(OTP_CONFIRMING_MARKERS, lower) != null
        val bonus = (if (confirming) 0.10f else 0f) + (if (shortCode) 0.05f else 0f)
        return clamp(maxOf(detected, OTP_BASE) + bonus)
    }

    /**
     * Confidence for a transaction reading, or null when the text is not one.
     *
     *  - a money-movement verb is a strong signal on its own;
     *  - a bill/invoice/currency mention needs a SECOND marker, so an
     *    incidental "تومان" cannot turn an ordinary sentence into a receipt;
     *  - marketing copy without an unsubscribe footer ("buy now", "offer") is
     *    left to the promotion rules instead.
     */
    private fun transactionConfidence(lower: String): Float? {
        val markers = TX_MARKERS.filter { lower.contains(it) }
        if (markers.isEmpty()) return null
        if (markers.any { it in STRONG_TX_MARKERS }) return TX_STRONG
        if (markers.size < 2) return null
        if (EXTRA_MARKETING_MARKERS.any { lower.contains(it) }) return null
        return TX_BASE
    }

    private fun firstMatch(markers: List<String>, lower: String): String? =
        markers.firstOrNull { lower.contains(it) }

    companion object {
        /** Bounded confidence bands — no band can exceed 1.0. */
        private const val OTP_BASE = 0.75f
        private const val TX_BASE = 0.65f
        private const val TX_STRONG = 0.80f
        private const val PROMO_BASE = 0.60f
        private const val PERSONAL_CONTACT = 0.60f
        private const val PERSONAL_LIKELY = 0.50f

        /** Where "several digits in a row" starts. */
        private const val SHORTCODE_MIN_DIGITS = 5
        private const val MSISDN_MIN_DIGITS = 10
        private const val MSISDN_MAX_DIGITS = 15

        /**
         * Advertising / discount / marketing wording (English + Persian).
         * Deliberately specific: a bare "sale" would fire on
         * "your salary has been deposited", which is a transaction.
         */
        private val PROMO_MARKERS = listOf(
            // ── English ────────────────────────────────────────────────────
            "unsubscribe", "opt out", "opt-out", "reply stop", "text stop",
            "stop to unsubscribe", "limited time", "special offer",
            "off your next", "discount", "coupon", "voucher", "promo code",
            "promotion", "flash sale", "on sale", "free shipping", "buy now",
            "shop now", "order now", "subscribe now", "cashback offer",
            "loyalty", "gift card", "giveaway", "sweepstakes", "winner",
            "you have won", "congratulations you",
            // ── Persian ────────────────────────────────────────────────────
            "لغو 11", "لغو11", "تخفیف", "جشنواره", "فروش ویژه", "حراج",
            "کد تخفیف", "هدیه", "جایزه", "برنده", "قرعه کشی", "قرعه‌کشی",
            "اشتراک ویژه", "عضویت ویژه", "ارسال رایگان", "خرید کنید",
            "همین حالا خرید", "فرصت محدود", "پیشنهاد ویژه", "تبلیغاتی",
            "تبلیغات"
        )

        /**
         * Wording that, ON ITS OWN, settles that the message is marketing rather
         * than a receipt.
         */
        private val STRONG_PROMO_MARKERS = setOf(
            "unsubscribe", "opt out", "opt-out", "reply stop", "text stop",
            "stop to unsubscribe", "لغو 11", "لغو11", "تبلیغاتی"
        )

        /**
         * Money movement / receipt wording (English + Persian).
         *
         * [STRONG_TX_MARKERS] are unambiguous on their own. Everything else in
         * this list (bill, currency, installment, "purchase") needs a second
         * marker so an incidental mention cannot fabricate a receipt.
         */
        private val TX_MARKERS = listOf(
            // ── English ────────────────────────────────────────────────────
            "transaction", "debited", "credited", "withdrawn", "withdrawal",
            "deposited", "payment of", "paid to", "transfer of", "transferred",
            "purchase of", "invoice", "receipt", "balance is",
            "available balance", "card ending", "account ending", "statement",
            "installment",
            // ── Persian ────────────────────────────────────────────────────
            "واریز", "برداشت", "انتقال", "موجودی", "مانده", "تراکنش", "خرید",
            "پرداخت", "قبض", "صورتحساب", "کارمزد", "حساب شما", "کارت شما",
            "شماره کارت", "فاکتور", "قسط", "ریال", "تومان", "درگاه"
        )

        private val STRONG_TX_MARKERS = setOf(
            "transaction", "debited", "credited", "withdrawn", "withdrawal",
            "deposited", "balance is", "available balance", "card ending",
            "account ending",
            "واریز", "برداشت", "موجودی", "مانده", "تراکنش", "حساب شما"
        )

        /** Copy that keeps a weak transaction match from firing at all. */
        private val EXTRA_MARKETING_MARKERS = listOf(
            "offer", "پیشنهاد", "جشنواره", "فرصت"
        )

        /**
         * Everyday personal wording. Corroboration only: a mass-marketing blast
         * that opens with "سلام" is still not personal, so this never
         * classifies a message by itself.
         */
        private val PERSONAL_MARKERS = listOf(
            "سلام", "ممنون", "مرسی", "چطوری", "خوبی", "دوستت دارم",
            "hello", "hi ", "hey ", "thanks", "thank you", "see you",
            "how are you", "good morning", "good night", "love you",
            "are you free", "call me", "miss you"
        )

        /**
         * Wording that CONFIRMS the OTP reading of a code the unified detector
         * only reached on a weak match. Only ever raises the confidence of an
         * already-detected OTP; it can never classify a message on its own.
         */
        private val OTP_CONFIRMING_MARKERS = listOf(
            "verification", "verify", "security code", "login", "sign in",
            "sign-in", "one-time", "one time", "passcode", "authentication",
            "do not share", "never share",
            "رمز", "تایید", "تأیید", "یکبار", "یک بار", "اعتبارسنجی",
            "احراز", "به کسی نگویید", "در اختیار"
        )

        /** Bounded confidence, so every band stays inside `[0, 1]`. */
        internal fun clamp(value: Float): Float = value.coerceIn(0f, 1f)
    }
}

/**
 * The single classifier verdict for ONE message.
 *
 * [otp] is non-null only when [isOtp] is true, and carries the unified
 * detector's own result. The OTP code itself is never persisted by the v3.4.0
 * classification tables — only `isOtp` and the cleanup deadline are.
 */
data class ClassificationResult(
    val category: MessageCategory,
    val confidence: Float,
    val isOtp: Boolean,
    val otp: OtpDetector.OtpDetection? = null
)
