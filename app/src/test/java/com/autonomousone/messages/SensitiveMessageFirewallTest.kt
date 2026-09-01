package com.autonomousone.messages

import com.autonomousone.messages.security.SensitiveMessageFirewall
import com.autonomousone.messages.security.SensitiveMessageFirewall.AmbiguityMode
import com.autonomousone.messages.security.SensitiveMessageFirewall.Category
import com.autonomousone.messages.security.SensitiveMessageFirewall.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-006 §22 — minimum regression matrix for the on-device classifier.
 *
 * NOTE: these are pure-function tests — the classifier never touches Android
 * or network. The "no outbox row" invariant is enforced at the gate
 * (TelephonySyncCoordinator.enqueueCloudEvent) and pinned in
 * SyncEligibilityTest.
 */
class SensitiveMessageFirewallTest {

    private val noOverrides = emptySet<String>()

    private fun policyOf(
        sender: String,
        body: String,
        localOnly: Set<String> = noOverrides,
        allowlist: Set<String> = noOverrides,
        financial: Policy = Policy.ASK,
        ambiguity: AmbiguityMode = AmbiguityMode.PRIVACY_STRICT
    ): Policy {
        val v = SensitiveMessageFirewall.classify(sender, body)
        return SensitiveMessageFirewall.resolvePolicy(
            v, sender, localOnly, allowlist, financial, ambiguity
        )
    }

    // ── §22 matrix ──────────────────────────────────────────────────────────

    @Test fun `persian otp is local only`() {
        val v = SensitiveMessageFirewall.classify("4478", "کد تایید شما: ۸۴۶۲")
        assertEquals(Category.OTP_SECURITY_CODE, v.category)
        assertEquals(Policy.LOCAL_ONLY, policyOf("4478", "کد تایید شما: ۸۴۶۲"))
    }

    @Test fun `persian digit otp is local only`() {
        // Digits arrive as Persian numerals — normalization must happen
        // BEFORE the code pattern runs. No bank marker here: generic OTP.
        val v = SensitiveMessageFirewall.classify("+985004", "رمز پویای شما ۳۹۲۸۱۸ است")
        assertEquals(Category.OTP_SECURITY_CODE, v.category)
        assertEquals(Policy.LOCAL_ONLY, policyOf("+985004", "رمز پویای شما ۳۹۲۸۱۸ است"))
    }

    @Test fun `bank dynamic code from numeric sender is bank security code`() {
        // بانک in the BODY + رمز پویا + code → BANK_SECURITY_CODE (§15 case 1)
        val v = SensitiveMessageFirewall.classify("+9870055", "بانک ملت؛ رمز پویای شما ۳۹۲۸۱۸ است")
        assertEquals(Category.BANK_SECURITY_CODE, v.category)
        assertEquals(Policy.LOCAL_ONLY, policyOf("+9870055", "بانک ملت؛ رمز پویای شما ۳۹۲۸۱۸ است"))
    }

    @Test fun `english otp is local only`() {
        val v = SensitiveMessageFirewall.classify("Telegram", "Login code: 63812. Do not share.")
        assertEquals(Category.OTP_SECURITY_CODE, v.category)
    }

    @Test fun `arabic digit otp is local only`() {
        val v = SensitiveMessageFirewall.classify("SERVICE", "كود التحقق ٤٥٦٧")
        assertEquals(Category.OTP_SECURITY_CODE, v.category)
    }

    @Test fun `alphanumeric bank sender with dynamic code is local only`() {
        val v = SensitiveMessageFirewall.classify("BANKMELLAT", "رمز پویا: 728191 برای ورود به اینترنت‌بانک")
        assertEquals(Category.BANK_SECURITY_CODE, v.category)
    }

    @Test fun `numeric bank sender with security code is local only`() {
        val v = SensitiveMessageFirewall.classify("+9870055", "بانک ملت؛ کد امنیتی 554102")
        assertEquals(Category.BANK_SECURITY_CODE, v.category)
    }

    @Test fun `password reset is local only`() {
        val v = SensitiveMessageFirewall.classify("noreply@example.com", "Your password reset code is 882913")
        assertEquals(Category.PASSWORD_RESET_CODE, v.category)
    }

    @Test fun `login verification without bank context is otp`() {
        val v = SensitiveMessageFirewall.classify("Google", " verification code 774921")
        assertEquals(Category.OTP_SECURITY_CODE, v.category)
    }

    @Test fun `invoice number without otp keywords stays normal`() {
        // §22: a bare 6-digit number is NOT enough — context required.
        val v = SensitiveMessageFirewall.classify("Shop", "فاکتور شماره 123456 صادر شد")
        assertEquals(Category.NORMAL, v.category)
    }

    @Test fun `bank transfer notification without otp is financial`() {
        val v = SensitiveMessageFirewall.classify("BANKMELLAT", "مبلغ 500,000 ریال به حساب شما واریز شد")
        assertEquals(Category.FINANCIAL_NOTIFICATION, v.category)
        // §10: financial is user-configurable.
        assertEquals(Policy.SYNC, policyOf("BANKMELLAT", "مبلغ 500,000 ریال واریز شد", financial = Policy.SYNC))
        assertEquals(Policy.LOCAL_ONLY, policyOf("BANKMELLAT", "مبلغ 500,000 ریال واریز شد", financial = Policy.LOCAL_ONLY))
    }

    @Test fun `multiple numbers in financial sms still financial`() {
        val v = SensitiveMessageFirewall.classify(
            "IR-MCI", "قبض شماره 9912345678 به مبلغ 120,000 ریال پرداخت شد"
        )
        assertEquals(Category.FINANCIAL_NOTIFICATION, v.category)
    }

    @Test fun `custom local only sender wins over everything`() {
        assertEquals(
            Policy.LOCAL_ONLY,
            policyOf("MyBank", "hello", localOnly = setOf("mybank"))
        )
    }

    @Test fun `sync allowlist lets normal senders sync but never otp`() {
        // NORMAL + allowlist → SYNC
        assertEquals(
            Policy.SYNC,
            policyOf("IR-MCI", "سلام", allowlist = setOf("ir-mci"))
        )
        // OTP classification is NEVER bypassed by the allowlist (ADR-006 §4).
        assertEquals(
            Policy.LOCAL_ONLY,
            policyOf("IR-MCI", "کد تایید 443218", allowlist = setOf("ir-mci"))
        )
    }

    // ── §16 fail-safe ───────────────────────────────────────────────────────

    @Test fun `ambiguous normal message in privacy strict stays local`() {
        // No rule fired (NORMAL), user is in privacy strict and there is no
        // allowlist entry → keep local (fail-safe).
        assertEquals(
            Policy.LOCAL_ONLY,
            policyOf("Unknown", "plain text")
        )
    }

    @Test fun `ambiguous normal message in balanced mode syncs`() {
        assertEquals(
            Policy.SYNC,
            policyOf("Unknown", "plain text", ambiguity = AmbiguityMode.BALANCED)
        )
    }

    // ── §21 audit safety: the rule id must never contain message content ────

    @Test fun `verdict rule id is a constant not message content`() {
        val v = SensitiveMessageFirewall.classify("4478", "کد تایید شما: ۸۴۶۲ - NEVER-LEAK-THIS")
        assertTrue(v.rule.all { it.code < 128 } && !v.rule.contains("NEVER"))
    }
}
