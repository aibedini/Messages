package com.autonomousone.messages.ui.home

import androidx.annotation.StringRes
import com.autonomousone.messages.R
import com.autonomousone.messages.data.MessageCategory

/**
 * FEATURE 12 — Home's Smarts Categories chip row.
 *
 * A DIFFERENT axis from [ConversationFilter] (All / Unread / Archived): the tab
 * chooses which set of conversations is on screen, the category narrows it. No
 * category selected at all means "All" — the chip row is additive and never
 * replaces the existing tabs.
 *
 * The declaration order IS the display order:
 * Personal / OTP / Transactions / Promotions / Spam.
 */
enum class CategoryFilter(
    @StringRes val labelRes: Int,
    /** The stored category this chip matches; null = no narrowing (All). */
    val category: MessageCategory?
) {
    Personal(R.string.category_chip_personal, MessageCategory.PERSONAL),
    Otp(R.string.category_chip_otp, MessageCategory.OTP),
    Transaction(R.string.category_chip_transactions, MessageCategory.TRANSACTION),
    Promotion(R.string.category_chip_promotions, MessageCategory.PROMOTION),
    Spam(R.string.category_chip_spam, MessageCategory.SPAM);

    companion object {
        /**
         * Chip order for the UI. Explicit so a future insertion into the enum
         * cannot silently reshuffle the row.
         */
        val displayOrder: List<CategoryFilter> = listOf(
            Personal, Otp, Transaction, Promotion, Spam
        )
    }
}
