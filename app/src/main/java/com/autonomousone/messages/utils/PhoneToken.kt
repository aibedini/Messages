package com.autonomousone.messages.utils

import java.security.MessageDigest

/**
 * Privacy-preserving, DETERMINISTIC reference to a phone number.
 *
 * The digest is what every diagnostic line, and the undo-send ledger, stores in
 * place of the dialable digits. It exists as its own JVM-only object (no Android
 * import) for two reasons:
 *
 *  1. it is the ONE definition — [DiagnosticLog.phoneToken] delegates here, so a
 *     log line and a ledger row can never disagree about which number they name;
 *  2. it is unit-testable, and the row/worker token cross-check in the send
 *     executor is a SECURITY check, not a formatting detail: it is what stops a
 *     job whose payload was tampered with from sending to a different recipient
 *     than the one the user's pending bubble showed.
 */
object PhoneToken {

    /** SHA-256 of the trimmed number, first 5 bytes as hex (10 chars). */
    fun of(phone: String): String {
        if (phone.isBlank()) return "none"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(phone.trim().toByteArray(Charsets.UTF_8))
        return digest.take(5).joinToString("") { "%02x".format(it) }
    }
}
