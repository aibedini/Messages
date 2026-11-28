package com.autonomousone.messages.messaging

import com.autonomousone.messages.utils.DigitNormalizer
import kotlin.math.min

/**
 * On-device spam heuristics for SMS.
 *
 * Google Messages has a server-side reputation graph. This app deliberately does not: the phone
 * classifies locally, from two things it can actually prove:
 *
 *  - **the sender shape** — a service short code or an alphanumeric originator is a broadcast
 *    channel, while a known contact is never spam;
 *  - **the copy** — promotional wording, phishing language, links and a call to action.
 *
 * The bar is deliberately high. A plain “50% off” is a promotion, not spam; a promotion with a link
 * and a “buy/click” action from a service sender is spam. A known contact always wins. OTP and
 * transaction messages are handled by [MessageClassifier] BEFORE this object is consulted, so a
 * bank receipt or a verification code can never be quarantined by a stray word.
 *
 * Everything here is pure and local: no network, no body logged, no model download.
 */
object SpamHeuristics {

    /** Score at or above which the conversation is treated as spam. */
    const val THRESHOLD = 0.70f

    const val KIND_UNKNOWN = "UNKNOWN"
    const val KIND_PROMOTION = "PROMOTION"
    const val KIND_PHISHING = "PHISHING"

    enum class Reason {
        PROMOTIONAL_LANGUAGE,
        PHISHING_LANGUAGE,
        LINK,
        SHORT_LINK,
        CALL_TO_ACTION,
        SERVICE_SENDER,
        OPT_OUT,
    }

    data class Assessment(
        val score: Float,
        val reasons: List<Reason>,
    ) {
        val isSpam: Boolean get() = score >= THRESHOLD
    }

    /**
     * @param knownContact a sender in the address book. Such a sender is never classified as spam,
     *   even when they forward an advertisement.
     */
    fun assess(
        rawSender: String,
        rawBody: String,
        knownContact: Boolean,
    ): Assessment {
        if (knownContact) return Assessment(0f, emptyList())

        val sender = DigitNormalizer.toAsciiDigits(rawSender).trim()
        val body = DigitNormalizer.toAsciiDigits(rawBody).lowercase()
        if (body.isBlank()) return Assessment(0f, emptyList())

        val compactSender = sender.filter { it.isDigit() || it.isLetter() }
        val digits = compactSender.count { it.isDigit() }
        val hasLetter = compactSender.any { it.isLetter() }
        val shortCode = !hasLetter && digits in SHORT_CODE_MIN_DIGITS..SHORT_CODE_MAX_DIGITS &&
            compactSender.length == digits
        val personLike = !hasLetter && digits in MSISDN_MIN_DIGITS..MSISDN_MAX_DIGITS
        val serviceSender = shortCode || hasLetter || (digits > 0 && !personLike)

        val promoHits = PROMO_MARKERS.count { body.contains(it) }
        val phishingHits = PHISHING_MARKERS.count { body.contains(it) }
        val hasLink = URL_PATTERN.containsMatchIn(body)
        val hasShortLink = SHORT_LINK_MARKERS.any { body.contains(it) }
        val hasCallToAction = CTA_MARKERS.any { body.contains(it) }
        val hasOptOut = OPT_OUT_MARKERS.any { body.contains(it) }
        val serviceUpdate = SERVICE_UPDATE_MARKERS.any { body.contains(it) }

        val reasons = ArrayList<Reason>(6)
        var score = 0f

        if (promoHits > 0) {
            score += 0.25f + min(0.20f, (promoHits - 1) * 0.07f)
            reasons += Reason.PROMOTIONAL_LANGUAGE
        }
        if (phishingHits > 0) {
            // Phishing language is more dangerous than a discount, so one strong phrase gets most
            // of the way to the threshold on its own. A bank's "never share your dynamic password"
            // warning has no link or CTA and deliberately stays below it.
            score += 0.55f + min(0.20f, (phishingHits - 1) * 0.08f)
            reasons += Reason.PHISHING_LANGUAGE
        }
        if (hasLink) {
            score += 0.20f
            reasons += Reason.LINK
        }
        if (hasShortLink) {
            score += 0.15f
            reasons += Reason.SHORT_LINK
        }
        if (hasCallToAction) {
            score += 0.15f
            reasons += Reason.CALL_TO_ACTION
        }
        if (serviceSender) {
            score += 0.10f
            reasons += Reason.SERVICE_SENDER
        }
        if (hasOptOut) {
            score -= 0.15f
            reasons += Reason.OPT_OUT
        }
        // An unknown mobile number is not automatically a service, but when it pairs promotional
        // copy with a link/CTA it is the same unsolicited-ad pattern. A known contact returned
        // above, so this cannot silence a real conversation.
        if (personLike) score += 0.10f

        // A link or a CTA on a neutral personal message is not spam.
        if (promoHits == 0 && phishingHits == 0) score = 0f

        // Transactional/service updates ("your order shipped", "payment successful") often carry a
        // tracking link and words like "order" or "purchase". They are not advertising. Phishing
        // messages deliberately never get this discount.
        if (serviceUpdate && phishingHits == 0) score -= 0.35f

        return Assessment(
            score = score.coerceIn(0f, 1f),
            reasons = reasons.distinct(),
        )
    }

    /** Human/diagnostic label for the strongest pattern, never the message body. */
    fun kind(assessment: Assessment): String = when {
        Reason.PHISHING_LANGUAGE in assessment.reasons -> KIND_PHISHING
        Reason.PROMOTIONAL_LANGUAGE in assessment.reasons -> KIND_PROMOTION
        else -> KIND_UNKNOWN
    }

    private const val SHORT_CODE_MIN_DIGITS = 3
    private const val SHORT_CODE_MAX_DIGITS = 8
    private const val MSISDN_MIN_DIGITS = 10
    private const val MSISDN_MAX_DIGITS = 15

    /**
     * Advertising/discount vocabulary (Persian + English). Kept specific: “sale” alone must not
     * match “salary”, and “off” is deliberately absent because it occurs inside ordinary words.
     */
    private val PROMO_MARKERS = listOf(
        "تخفیف", "حراج", "فروش ویژه", "جشنواره", "کد تخفیف", "پیشنهاد ویژه",
        "فقط امروز", "رایگان", "هدیه", "خرید", "فروشگاه",
        "اشتراک", "پکیج", "بسته اینترنت", "تبلیغ", "لغو تبلیغ",
        "discount", "sale", "free", "offer", "shop", "buy now", "order now",
        "limited time", "promo", "coupon", "deal",
    )

    /**
     * Phishing / scam vocabulary. These words are common in fraud and rare in legitimate personal
     * messages; the score still needs a call to action, link or service sender to cross the line.
     */
    private val PHISHING_MARKERS = listOf(
        "رمز دوم", "رمز پویا", "رمز یکبار مصرف", "cvv2", "cv2", "کارت به کارت",
        "اطلاعات کارت", "شماره کارت", "برنده", "جایزه", "قرعه کشی", "وام",
        "یارانه", "سهام", "بورس", "سیگنال", "ارز دیجیتال", "سود تضمینی",
        "سرمایه گذاری", "حساب شما مسدود", "مسدود شده", "واریز معوقه",
        "winner", "prize", "lottery", "loan", "crypto", "guaranteed profit",
        "card number", "cvv", "account suspended",
    )

    private val CTA_MARKERS = listOf(
        "کلیک", "ثبت نام", "ثبتنام", "خرید", "سفارش", "دانلود", "عضویت",
        "دریافت", "ارسال", "پاسخ", "تماس", "شماره را", "لینک",
        "click", "buy", "order", "claim", "subscribe", "register", "download",
        "reply", "call",
    )

    private val OPT_OUT_MARKERS = listOf(
        "لغو", "unsubscribe", "reply stop", "stop to", "لغو 11", "لغو تبلیغ",
    )

    /** Receipts, order tracking and security notices — not advertising. */
    private val SERVICE_UPDATE_MARKERS = listOf(
        "سفارش شما", "خرید شما", "ارسال شد", "تحویل", "رهگیری", "پیگیری",
        "پرداخت موفق", "تراکنش", "واریز", "برداشت", "قبض", "صورتحساب",
        "مانده", "موجودی", "order shipped", "out for delivery", "tracking",
        "payment successful", "transaction",
    )

    /** http(s), www, Telegram/WhatsApp/Instagram links and common shorteners. */
    private val URL_PATTERN = Regex(
        "(?i)(https?://|www\\.|t\\.me/|telegram\\.me/|wa\\.me/|instagram\\.com/|" +
            "bit\\.ly/|cutt\\.ly/|tinyurl\\.com/|goo\\.gl/|s\\.id/)\\S+"
    )

    private val SHORT_LINK_MARKERS = listOf(
        "bit.ly/", "cutt.ly/", "tinyurl.com/", "goo.gl/", "s.id/", "t.me/",
    )
}
