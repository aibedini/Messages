package com.autonomousone.messages.ui.home

import androidx.annotation.StringRes
import com.autonomousone.messages.R

/** Home conversation-list filter tabs. */
enum class ConversationFilter(@StringRes val labelRes: Int) {
    All(R.string.home_tab_all),
    Unread(R.string.home_tab_unread),
    Archived(R.string.home_tab_archived),

    /**
     * A folder, not a category filter: it lists every conversation the local heuristic or the user
     * placed in Spam. Archived spam lives here too (with an Unarchive action), so the Archived tab
     * stays a spam-free view of deliberately archived conversations.
     */
    Spam(R.string.home_tab_spam)
}
