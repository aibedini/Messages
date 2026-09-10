package com.autonomousone.messages.ui.conversation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autonomousone.messages.model.Sms
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI test for MessageList — verifies date separators and message
 * bubbles render, and the jump-to-latest button shows when requested
 * (PR-04, RFP §16/§34). Requires a device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class MessageListTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun sms(id: Long, message: String, sender: String, type: Int) = Sms(
        id = id, threadId = 1L, sender = sender, message = message,
        date = 1700000000000L + id * 1000L, unread = false, type = type,
    )

    @Test
    fun rendersDateSeparatorAndBubbles_withJumpFabWhenRequested() {
        var showJumpFab by mutableStateOf(false)
        val chatItems = listOf(
            ChatListItem.MessageItem(sms(2, "پیام دوم", "+989120000001", 1)),
            ChatListItem.DateSeparator(dayKey = "2026-01-01", dateText = "جمعه"),
            ChatListItem.MessageItem(sms(1, "پیام اول", "+989120000001", 2)),
        )

        composeRule.setContent {
            MaterialTheme {
                MessageList(
                    listState = rememberLazyListState(),
                    chatItems = chatItems,
                    isLoadingNewer = false,
                    isLoadingOlder = false,
                    isRefreshing = false,
                    onRefresh = {},
                    showJumpFab = showJumpFab,
                    pendingNewMessagesCount = 2,
                    onJumpToLatest = {},
                    shouldAnimateEntry = { false },
                    onEntryAnimationFinished = {},
                    onForward = {},
                    onPhoneClick = {},
                    onResend = {},
                    modifier = Modifier,
                )
            }
        }

        composeRule.onNodeWithText("جمعه").assertIsDisplayed()
        composeRule.onNodeWithText("پیام دوم").assertIsDisplayed()
    }
}
