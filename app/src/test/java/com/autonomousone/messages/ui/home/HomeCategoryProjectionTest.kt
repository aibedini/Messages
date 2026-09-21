package com.autonomousone.messages.ui.home

import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.data.UserCategoryAssignmentEntity
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.UserCategoryScopeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.5.0 Phase 5 — the Home category row and its unread badges.
 *
 * The badge is the only number this feature puts in front of the user, so its three
 * promises are pinned here rather than left to a screenshot or a manual check:
 *
 *  1. it counts UNREAD CONVERSATIONS, never messages;
 *  2. `0` draws NOTHING;
 *  3. above 99 it saturates at `99+`.
 *
 * …and so is its BASE, because "the badge says 3 but the list shows 2" is exactly the bug
 * a pure projection exists to prevent: the count must describe what the current context
 * can actually show, custom chips must not count reported spam, and the SPAM chip — the
 * one place that spam is reachable — must still count its own.
 */
class HomeCategoryProjectionTest {

    private val vpn = "cat-vpn"
    private val clients = "cat-clients"
    private val phone = "+989121234567"

    private fun custom(
        categoryId: String,
        name: String,
        sortOrder: Int
    ) = HomeCategoryProjection.CustomCategory(categoryId, name, sortOrder)

    private fun addressRow(
        categoryId: String,
        key: String,
        createdAt: Long = 1_000L
    ) = UserCategoryAssignmentEntity(
        categoryId = categoryId,
        scopeType = UserCategoryScopeCodec.ADDRESS,
        scopeKey = key,
        createdAt = createdAt
    )

    private fun threadRow(
        categoryId: String,
        threadId: Long,
        createdAt: Long = 1_000L
    ) = UserCategoryAssignmentEntity(
        categoryId = categoryId,
        scopeType = UserCategoryScopeCodec.THREAD,
        scopeKey = threadId.toString(),
        createdAt = createdAt
    )

    private fun conversation(
        threadId: Long,
        sender: String,
        unread: Boolean = true
    ) = HomeCategoryProjection.Conversation(
        threadId = threadId,
        rawAddress = sender,
        normalizedAddress = null,
        unread = unread
    )

    private fun chips(
        context: List<HomeCategoryProjection.Conversation> = emptyList(),
        spam: List<HomeCategoryProjection.Conversation> = emptyList(),
        systemCategories: Map<Long, MessageCategory> = emptyMap(),
        assignments: List<UserCategoryAssignmentEntity> = emptyList(),
        customCategories: List<HomeCategoryProjection.CustomCategory> = emptyList()
    ): List<HomeCategoryChip> = HomeCategoryProjection.chips(
        HomeCategoryProjection.Input(
            context = context,
            spam = spam,
            systemCategories = systemCategories,
            assignments = assignments,
            customCategories = customCategories
        )
    )

    private fun List<HomeCategoryChip>.custom(categoryId: String): HomeCategoryChip? =
        firstOrNull { it.key == HomeCategoryKey.Custom(categoryId) }

    private fun List<HomeCategoryChip>.system(category: MessageCategory): HomeCategoryChip? =
        firstOrNull { it.key == HomeCategoryKey.System(category) }

    // ═══════════════════════════════════════════════════════════════════════════
    // The three badge rules
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `badge zero is null so the chip draws no bubble`() {
        assertNull(HomeCategoryBadge.format(0))
        assertNull(HomeCategoryBadge.format(-1))
    }

    @Test
    fun `badge is the plain count up to the cap`() {
        assertEquals("1", HomeCategoryBadge.format(1))
        assertEquals("9", HomeCategoryBadge.format(9))
        assertEquals("98", HomeCategoryBadge.format(98))
        assertEquals("99", HomeCategoryBadge.format(99))
    }

    @Test
    fun `badge saturates at 99 plus`() {
        assertEquals("99+", HomeCategoryBadge.format(100))
        assertEquals("99+", HomeCategoryBadge.format(4_000))
    }

    @Test
    fun `chip exposes no badge when nothing is unread`() {
        val chip = HomeCategoryChip(HomeCategoryKey.System(MessageCategory.OTP), conversations = 4, unreadConversations = 0)

        assertNull(chip.badge)
        assertFalse(chip.hasBadge)
    }

    @Test
    fun `chip exposes the saturated badge above the cap`() {
        val chip = HomeCategoryChip(HomeCategoryKey.Custom(vpn), label = "VPN", conversations = 250, unreadConversations = 250)

        assertEquals("99+", chip.badge)
        assertTrue(chip.hasBadge)
    }

    /**
     * `badgeCountsConversationsNotMessages`.
     *
     * Seven unread SMS from one person is ONE unread conversation. The projection is fed
     * conversations, so the rule is that a thread can never be counted twice — including
     * when the same thread id arrives on two rows.
     */
    @Test
    fun `badge counts a conversation once even if its row is duplicated`() {
        val rows = listOf(
            conversation(7L, phone, unread = true),
            conversation(7L, phone, unread = true)
        )

        val chip = chips(
            context = rows,
            assignments = listOf(addressRow(vpn, phone)),
            customCategories = listOf(custom(vpn, "VPN", 0))
        ).custom(vpn)!!

        assertEquals(1, chip.unreadConversations)
        assertEquals("1", chip.badge)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // System (Smart Category) chips
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `system chip appears only when its category has conversations`() {
        val present = chips(
            context = listOf(conversation(7L, phone, unread = true)),
            systemCategories = mapOf(7L to MessageCategory.OTP)
        )

        assertEquals(1, present.size)
        assertEquals(MessageCategory.OTP, (present.first().key as HomeCategoryKey.System).category)
        // The count is the DB-wide membership, unchanged from the existing chip row.
        assertEquals(1, present.first().conversations)
        assertEquals("1", present.first().badge)

        // A category nobody is in produces NO chip: the row must never offer a filter that
        // would render an empty list.
        val absent = chips(
            context = listOf(conversation(7L, phone, unread = true)),
            systemCategories = mapOf(7L to MessageCategory.PROMOTION)
        )
        assertNull(absent.system(MessageCategory.OTP))
    }

    @Test
    fun `system chip count stays DB wide so the spam chip stays reachable`() {
        // Room knows about two OTP threads; the CURRENT tab can only show one of them.
        val result = chips(
            context = listOf(conversation(7L, phone, unread = true)),
            systemCategories = mapOf(7L to MessageCategory.OTP, 8L to MessageCategory.OTP)
        )

        val otp = result.system(MessageCategory.OTP)!!
        assertEquals("DB-wide membership is what keeps a chip visible", 2, otp.conversations)
        assertEquals("…while the badge describes the current context", 1, otp.unreadConversations)
    }

    @Test
    fun `system chip renders in the declared order and omits empty categories`() {
        val result = chips(
            context = listOf(
                conversation(1L, phone, unread = false),
                conversation(2L, phone, unread = false),
                conversation(3L, phone, unread = false)
            ),
            systemCategories = mapOf(
                1L to MessageCategory.PROMOTION,
                2L to MessageCategory.PERSONAL,
                3L to MessageCategory.SPAM
            )
        )

        assertEquals(
            listOf(
                MessageCategory.PERSONAL,
                MessageCategory.PROMOTION,
                MessageCategory.SPAM
            ),
            result.map { (it.key as HomeCategoryKey.System).category }
        )
        assertEquals(
            "no badge anywhere: nothing is unread",
            listOf(null, null, null),
            result.map { it.badge }
        )
    }

    @Test
    fun `system chip counts only unread conversations`() {
        val result = chips(
            context = listOf(
                conversation(1L, phone, unread = true),
                conversation(2L, phone, unread = true),
                conversation(3L, phone, unread = false)
            ),
            systemCategories = mapOf(
                1L to MessageCategory.PERSONAL,
                2L to MessageCategory.PERSONAL,
                3L to MessageCategory.PERSONAL
            )
        )

        val personal = result.system(MessageCategory.PERSONAL)!!
        assertEquals(3, personal.conversations)
        assertEquals(2, personal.unreadConversations)
        assertEquals("2", personal.badge)
    }

    @Test
    fun `system chip ignores a conversation it cannot classify`() {
        val result = chips(
            context = listOf(conversation(7L, phone, unread = true)),
            systemCategories = emptyMap()
        )

        assertTrue("an unclassified conversation belongs to no chip", result.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Custom (My Categories) chips
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `custom chip is present even when it is empty`() {
        val result = chips(
            context = emptyList(),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        val chip = result.custom(vpn)!!
        assertEquals("VPN", chip.label)
        assertEquals(0, chip.conversations)
        assertEquals(0, chip.unreadConversations)
        assertNull("an empty category draws no badge", chip.badge)
    }

    @Test
    fun `system chips come before custom chips and custom order is the stored order`() {
        val result = chips(
            context = listOf(conversation(7L, phone, unread = true)),
            systemCategories = mapOf(7L to MessageCategory.OTP),
            customCategories = listOf(
                custom(clients, "Clients", 5),
                custom(vpn, "VPN", 0)
            )
        )

        assertEquals(
            listOf(
                HomeCategoryKey.System(MessageCategory.OTP),
                HomeCategoryKey.Custom(vpn),
                HomeCategoryKey.Custom(clients)
            ),
            result.map { it.key }
        )
    }

    @Test
    fun `custom chip counts an unread conversation by its phone identity`() {
        // The PERSISTED key is always the resolver's canonical output
        // (`PhoneIdentity.stableKey`), so `+989121234567`. Home, meanwhile, renders whatever
        // spelling the provider stored. The badge must find the membership from any of them.
        val result = chips(
            context = listOf(conversation(7L, "+98 912 123 4567", unread = true)),
            assignments = listOf(addressRow(vpn, "+989121234567")),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        val chip = result.custom(vpn)!!
        assertEquals(1, chip.conversations)
        assertEquals(1, chip.unreadConversations)
        assertEquals("1", chip.badge)

        // The local spelling is the same number.
        assertEquals(
            1,
            chips(
                context = listOf(conversation(7L, "09121234567", unread = true)),
                assignments = listOf(addressRow(vpn, "+989121234567")),
                customCategories = listOf(custom(vpn, "VPN", 0))
            ).custom(vpn)!!.unreadConversations
        )

        // …and so is a DIFFERENT number spelled the same way.
        assertEquals(
            0,
            chips(
                context = listOf(conversation(7L, "09120000000", unread = true)),
                assignments = listOf(addressRow(vpn, "+989121234567")),
                customCategories = listOf(custom(vpn, "VPN", 0))
            ).custom(vpn)!!.unreadConversations
        )
    }

    @Test
    fun `custom chip does not count a read conversation in the badge`() {
        val result = chips(
            context = listOf(conversation(7L, phone, unread = false)),
            assignments = listOf(addressRow(vpn, phone)),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        val chip = result.custom(vpn)!!
        assertEquals("the conversation is still a member…", 1, chip.conversations)
        assertEquals("…it is just not waiting for the user", 0, chip.unreadConversations)
        assertNull(chip.badge)
    }

    @Test
    fun `custom chip counts a group conversation by its thread scope`() {
        // A reported group address is multi-recipient, so its scope is the THREAD.
        val result = chips(
            context = listOf(conversation(42L, "+989121234567,+989120000000", unread = true)),
            assignments = listOf(threadRow(vpn, 42L)),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        assertEquals(1, result.custom(vpn)!!.unreadConversations)
    }

    @Test
    fun `custom chip counts an alphanumeric sender by its sender key`() {
        // `IR-MCI1` must not be reduced to the phone number `1`: the membership is keyed by
        // the canonical sender id (`sender:` + NFKC + case-fold), and the badge matches it
        // case-insensitively because the key is already folded.
        val result = chips(
            context = listOf(conversation(7L, "IR-MCI1", unread = true)),
            assignments = listOf(addressRow(vpn, "sender:ir-mci1")),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        assertEquals(1, result.custom(vpn)!!.unreadConversations)

        val mirrored = chips(
            context = listOf(conversation(7L, "ir-mci1", unread = true)),
            assignments = listOf(addressRow(vpn, "sender:ir-mci1")),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )
        assertEquals(1, mirrored.custom(vpn)!!.unreadConversations)

        // A sender id is NOT a phone number: the numeric-only membership must not match it.
        val phoneMembership = chips(
            context = listOf(conversation(7L, "IR-MCI1", unread = true)),
            assignments = listOf(addressRow(vpn, "+989121234567")),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )
        assertEquals(0, phoneMembership.custom(vpn)!!.unreadConversations)
    }

    @Test
    fun `custom chip counts a conversation once per category even when it is in several`() {
        val result = chips(
            context = listOf(conversation(7L, phone, unread = true)),
            assignments = listOf(addressRow(vpn, phone), addressRow(clients, phone)),
            customCategories = listOf(custom(vpn, "VPN", 0), custom(clients, "Clients", 1))
        )

        assertEquals(1, result.custom(vpn)!!.unreadConversations)
        assertEquals(1, result.custom(clients)!!.unreadConversations)
    }

    @Test
    fun `custom chip ignores a conversation with no stable scope`() {
        val result = chips(
            // Punctuation only, and no thread id: nothing stable to key a membership on.
            context = listOf(
                HomeCategoryProjection.Conversation(
                    threadId = 0L,
                    rawAddress = "...",
                    normalizedAddress = null,
                    unread = true
                )
            ),
            assignments = listOf(addressRow(vpn, phone)),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        assertEquals(0, result.custom(vpn)!!.unreadConversations)
    }

    @Test
    fun `custom chip ignores an unknown stored scope type`() {
        val result = chips(
            context = listOf(conversation(7L, phone, unread = true)),
            assignments = listOf(
                UserCategoryAssignmentEntity(vpn, "FUTURE_SCOPE", phone, 1_000L)
            ),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        assertEquals(
            "a row written by a future version must be ignored, not reinterpreted",
            0,
            result.custom(vpn)!!.unreadConversations
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The base: tab context, spam, and trashed conversations
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `tabContextChangesTheBadge`() {
        val inboxRow = conversation(7L, phone, unread = true)
        val archivedRow = conversation(9L, "+989120000000", unread = true)
        val assignments = listOf(addressRow(vpn, phone), addressRow(vpn, "+989120000000"))
        val categories = listOf(custom(vpn, "VPN", 0))

        val allTab = chips(
            context = listOf(inboxRow),
            assignments = assignments,
            customCategories = categories
        ).custom(vpn)!!

        val archivedTab = chips(
            context = listOf(archivedRow),
            assignments = assignments,
            customCategories = categories
        ).custom(vpn)!!

        assertEquals("the badge describes the current tab", 1, allTab.unreadConversations)
        assertEquals(1, archivedTab.unreadConversations)
        // …and a tab that can show none of the memberships says so.
        val emptyTab = chips(
            context = emptyList(),
            assignments = assignments,
            customCategories = categories
        ).custom(vpn)!!
        assertEquals(0, emptyTab.unreadConversations)
    }

    @Test
    fun `aTrashedConversationIsNotCounted`() {
        // Trash removes the row from the rendered lists; the membership row survives, so the
        // count comes back as soon as the conversation is restored.
        val visible = chips(
            context = listOf(conversation(7L, phone, unread = true)),
            assignments = listOf(addressRow(vpn, phone)),
            customCategories = listOf(custom(vpn, "VPN", 0))
        ).custom(vpn)!!
        assertEquals(1, visible.unreadConversations)

        val trashed = chips(
            context = emptyList(),
            assignments = listOf(addressRow(vpn, phone)),
            customCategories = listOf(custom(vpn, "VPN", 0))
        ).custom(vpn)!!
        assertEquals(0, trashed.unreadConversations)
    }

    @Test
    fun `reportedSpamIsNotCountedByACustomChip`() {
        val result = chips(
            context = emptyList(),
            spam = listOf(conversation(7L, phone, unread = true)),
            systemCategories = mapOf(7L to MessageCategory.SPAM),
            assignments = listOf(addressRow(vpn, phone)),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        assertEquals(
            "a badge must never send the user to a list that hides the row",
            0,
            result.custom(vpn)!!.unreadConversations
        )
        assertNull(result.custom(vpn)!!.badge)
    }

    @Test
    fun `theSpamChipCountsItsOwnReportedUnreadConversations`() {
        val result = chips(
            context = emptyList(),
            spam = listOf(
                conversation(7L, phone, unread = true),
                conversation(8L, "+989120000000", unread = false)
            ),
            systemCategories = mapOf(
                7L to MessageCategory.SPAM,
                8L to MessageCategory.SPAM
            )
        )

        val spam = result.system(MessageCategory.SPAM)!!
        assertEquals(2, spam.conversations)
        assertEquals("the SPAM chip is the one place a report stays reachable", 1, spam.unreadConversations)
        assertEquals("1", spam.badge)
    }

    @Test
    fun `nonSpamSystemChipsNeverCountReportedRows`() {
        val result = chips(
            context = listOf(conversation(1L, phone, unread = true)),
            spam = listOf(conversation(7L, phone, unread = true)),
            systemCategories = mapOf(
                1L to MessageCategory.OTP,
                7L to MessageCategory.OTP
            )
        )

        assertEquals(2, result.system(MessageCategory.OTP)!!.conversations)
        assertEquals(1, result.system(MessageCategory.OTP)!!.unreadConversations)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Home-row mapping
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `homeRowsMapTheirRawAddressAndReadState`() {
        val rows = listOf(
            Sms(
                id = 1L,
                threadId = 7L,
                sender = "+98 912 123 4567",
                message = "hi",
                date = 1_000L,
                unread = true,
                type = 1
            ),
            Sms(
                id = 2L,
                threadId = 8L,
                sender = "",
                message = "x",
                date = 2_000L,
                unread = false,
                type = 1
            )
        )

        val conversations = HomeCategoryProjection.conversationsOf(rows)

        assertEquals(7L, conversations[0].threadId)
        assertEquals("the RAW provider spelling is what the resolver must see", "+98 912 123 4567", conversations[0].rawAddress)
        assertNull(conversations[0].normalizedAddress)
        assertTrue(conversations[0].unread)
        assertNull("a blank address stays blank rather than becoming an empty string", conversations[1].rawAddress)
    }

    /**
     * The end-to-end shape of the feature: Home rows → the row of chips, with the badge
     * derived from the SAME rows the user can tap.
     */
    @Test
    fun `homeRowsDriveBothSystemAndCustomBadges`() {
        val rows = listOf(
            Sms(1L, 7L, "+989121234567", "a", 1_000L, unread = true, type = 1),
            Sms(2L, 8L, "+989120000000", "b", 2_000L, unread = true, type = 1),
            Sms(3L, 9L, "BANK", "c", 3_000L, unread = false, type = 1)
        )

        val result = HomeCategoryProjection.chips(
            HomeCategoryProjection.Input(
                context = HomeCategoryProjection.conversationsOf(rows),
                systemCategories = mapOf(
                    7L to MessageCategory.PERSONAL,
                    8L to MessageCategory.TRANSACTION,
                    9L to MessageCategory.OTP
                ),
                assignments = listOf(
                    addressRow(vpn, "+989121234567"),
                    addressRow(vpn, "+989120000000"),
                    addressRow(clients, "+989120000000")
                ),
                customCategories = listOf(custom(vpn, "VPN", 0), custom(clients, "Clients", 1))
            )
        )

        // System chips follow SYSTEM_DISPLAY_ORDER: PERSONAL, OTP, TRANSACTION.
        assertEquals(listOf("1", null, "1"), result.filter { it.key is HomeCategoryKey.System }.map { it.badge })
        assertEquals("2", result.custom(vpn)!!.badge)
        assertEquals("1", result.custom(clients)!!.badge)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Key helpers
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `aSystemKeyResolvesToItsChipFilterOrToNothing`() {
        assertEquals(
            CategoryFilter.Otp,
            HomeCategoryKey.System(MessageCategory.OTP).asCategoryFilter()
        )
        assertEquals(
            CategoryFilter.Spam,
            HomeCategoryKey.System(MessageCategory.SPAM).asCategoryFilter()
        )
        assertNull(
            "UNKNOWN is classified but has no chip",
            HomeCategoryKey.System(MessageCategory.UNKNOWN).asCategoryFilter()
        )
    }

    @Test
    fun `anEmptyInputProducesAnEmptyRow`() {
        assertTrue(chips().isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The thread ids each chip narrows to (the unified chip bar filters with these)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun project(
        context: List<HomeCategoryProjection.Conversation> = emptyList(),
        spam: List<HomeCategoryProjection.Conversation> = emptyList(),
        systemCategories: Map<Long, MessageCategory> = emptyMap(),
        assignments: List<UserCategoryAssignmentEntity> = emptyList(),
        customCategories: List<HomeCategoryProjection.CustomCategory> = emptyList()
    ) = HomeCategoryProjection.project(
        HomeCategoryProjection.Input(
            context = context,
            spam = spam,
            systemCategories = systemCategories,
            assignments = assignments,
            customCategories = customCategories
        )
    )

    @Test
    fun `everyChipHasAThreadIdSetEvenWhenItIsEmpty`() {
        val result = project(
            context = listOf(conversation(1L, phone, unread = true)),
            systemCategories = mapOf(1L to MessageCategory.OTP),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        // A chip that narrows to nothing must be an EMPTY SET, never a missing key: the
        // screen distinguishes "no chip selected" (null) from "nothing matches" (empty).
        assertEquals(setOf(1L), result.threadIds[HomeCategoryKey.System(MessageCategory.OTP)])
        assertEquals(emptySet<Long>(), result.threadIds[HomeCategoryKey.Custom(vpn)])
        assertEquals(result.chips.map { it.key }.toSet(), result.threadIds.keys.toSet())
    }

    @Test
    fun `aSystemChipNarrowsToEveryMemberNotOnlyTheUnreadOnes`() {
        val result = project(
            context = listOf(
                conversation(1L, phone, unread = true),
                conversation(2L, phone, unread = false)
            ),
            systemCategories = mapOf(1L to MessageCategory.PERSONAL, 2L to MessageCategory.PERSONAL)
        )

        assertEquals(
            "the badge counts 1, but the chip must still list both conversations",
            setOf(1L, 2L),
            result.threadIds[HomeCategoryKey.System(MessageCategory.PERSONAL)]
        )
        assertEquals(1, result.chips.single().unreadConversations)
    }

    @Test
    fun `theSpamChipNarrowsToTheReportedRows`() {
        val result = project(
            context = listOf(conversation(1L, phone, unread = true)),
            spam = listOf(conversation(7L, phone, unread = true)),
            systemCategories = mapOf(
                1L to MessageCategory.OTP,
                7L to MessageCategory.SPAM
            )
        )

        assertEquals(setOf(1L), result.threadIds[HomeCategoryKey.System(MessageCategory.OTP)])
        assertEquals(setOf(7L), result.threadIds[HomeCategoryKey.System(MessageCategory.SPAM)])
    }

    @Test
    fun `aCustomChipNarrowsToItsMembersInTheCurrentContext`() {
        val assignments = listOf(addressRow(vpn, "+989121234567"), addressRow(vpn, "+989120000000"))
        val result = project(
            // Only one of the two members is in this tab's context.
            context = listOf(conversation(7L, "+989120000000", unread = false)),
            assignments = assignments,
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        assertEquals(
            setOf(7L),
            result.threadIds[HomeCategoryKey.Custom(vpn)]
        )
        assertEquals(1, result.chips.single().conversations)
        assertEquals(0, result.chips.single().unreadConversations)
    }

    @Test
    fun `aCustomChipExcludesReportedSpamFromWhatItOpens`() {
        val result = project(
            context = emptyList(),
            spam = listOf(conversation(7L, phone, unread = true)),
            assignments = listOf(addressRow(vpn, phone)),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        assertEquals(
            "a chip must never open a list that hides the rows it counted",
            emptySet<Long>(),
            result.threadIds[HomeCategoryKey.Custom(vpn)]
        )
        assertEquals(0, result.chips.single().conversations)
    }

    @Test
    fun `aChipNarrowsToDistinctThreadIdsEvenWhenARowIsDuplicated`() {
        val result = project(
            context = listOf(
                conversation(7L, phone, unread = true),
                conversation(7L, phone, unread = true)
            ),
            assignments = listOf(addressRow(vpn, phone)),
            customCategories = listOf(custom(vpn, "VPN", 0))
        )

        assertEquals(setOf(7L), result.threadIds[HomeCategoryKey.Custom(vpn)])
        assertEquals(1, result.chips.single().unreadConversations)
    }

    @Test
    fun `projectKeepsTheChipsAndTheThreadIdsInTheSameOrder`() {
        val result = project(
            context = listOf(conversation(1L, phone, unread = true)),
            systemCategories = mapOf(1L to MessageCategory.PROMOTION),
            customCategories = listOf(custom(clients, "Clients", 1), custom(vpn, "VPN", 0))
        )

        assertEquals(
            listOf(
                HomeCategoryKey.System(MessageCategory.PROMOTION),
                HomeCategoryKey.Custom(vpn),
                HomeCategoryKey.Custom(clients)
            ),
            result.chips.map { it.key }
        )
        assertEquals(result.chips.map { it.key }, result.threadIds.keys.toList())
    }
}
