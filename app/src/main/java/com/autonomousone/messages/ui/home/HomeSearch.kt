package com.autonomousone.messages.ui.home

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ContactRepository

/**
 * Pure, non-composable search matching for the Home list (RFP §8 — keep
 * non-UI logic out of composition). Matches contact display name, raw sender,
 * normalized digits (so "0912" finds "+98912…") and the message snippet.
 */
object HomeSearch {
    fun matches(
        sms: Sms,
        query: String,
        contactNames: Map<String, String>,
    ): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()

        val displayName = contactNames[ContactRepository.normalizePhone(sms.sender)] ?: ""
        if (displayName.contains(q, ignoreCase = true)) return true

        if (sms.sender.contains(q, ignoreCase = true) ||
            sms.message.contains(q, ignoreCase = true)
        ) return true

        // Digit-normalized match: search "0912" should hit "+98 912 …"
        val qDigits = q.filter { it.isDigit() }
        if (qDigits.length >= 3) {
            if (sms.sender.filter { it.isDigit() }.contains(qDigits)) return true
            if (sms.message.take(120).filter { it.isDigit() }.contains(qDigits)) return true
        }
        return false
    }

    /** Best-practice "looks like a phone number" heuristic for the direct-send row. */
    fun looksLikeNumber(search: String): Boolean {
        val digits = search.filter { it.isDigit() }
        return search.isNotBlank() && digits.length >= 3 && digits.length >= search.length - 2
    }
}
