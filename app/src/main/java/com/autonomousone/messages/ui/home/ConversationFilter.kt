package com.autonomousone.messages.ui.home

import androidx.annotation.StringRes
import com.autonomousone.messages.R

/** Home conversation-list filter tabs. */
enum class ConversationFilter(@StringRes val labelRes: Int) {
    All(R.string.home_tab_all),
    Unread(R.string.home_tab_unread),
    Archived(R.string.home_tab_archived)
}
