package com.autonomousone.messages.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autonomousone.messages.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI test for HomeFilterBar — verifies the All/Unread/Archived chips
 * reflect the selected filter and emit the correct selection (PR-02, RFP §34).
 * Requires a device/emulator (androidTest).
 */
@RunWith(AndroidJUnit4::class)
class HomeFilterBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chipsReflectSelection_andEmitChange() {
        var selected by mutableStateOf(ConversationFilter.All)

        composeRule.setContent {
            MaterialTheme {
                HomeFilterBar(
                    selected = selected,
                    onSelect = { selected = it }
                )
            }
        }

        val allLabel = composeRule.activity.getString(R.string.home_tab_all)
        val unreadLabel = composeRule.activity.getString(R.string.home_tab_unread)
        val archivedLabel = composeRule.activity.getString(R.string.home_tab_archived)

        // All is initially selected.
        composeRule.onNodeWithText(allLabel).assertIsSelected()

        // Click Unread → selection state updates and the emitted value reaches `selected`.
        composeRule.onNodeWithText(unreadLabel).performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(ConversationFilter.Unread, selected) }
        composeRule.onNodeWithText(unreadLabel).assertIsSelected()

        // Click Archived → selection moves.
        composeRule.onNodeWithText(archivedLabel).performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(ConversationFilter.Archived, selected) }
        composeRule.onNodeWithText(archivedLabel).assertIsSelected()
    }
}
