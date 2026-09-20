package com.autonomousone.messages.ui.home

import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.data.UserCategoryAssignmentEntity
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.ConversationCategoryScopeResolver
import com.autonomousone.messages.repository.StoredCategoryScope
import com.autonomousone.messages.repository.UserCategoryScopeCodec

/**
 * v3.5.0 — Home's category row as a PURE projection.
 *
 * The row is one ordered list of [HomeCategoryChip]s built from four conversation-scale
 * inputs: the rows the current tab can show, the reported-spam rows, the live thread →
 * Smart-Category map, and the user's own categories plus their memberships. Nothing here
 * reads a message, the Telephony provider or contacts, and nothing here is Compose.
 *
 * WHY THIS IS PURE AND SEPARATE
 * -----------------------------
 * "The badge says 3 but the list shows 2" is the kind of bug that is invisible in review
 * and obvious to a user. The badge's base — which conversations it counts — is therefore
 * a decision this object owns and a JVM test pins, rather than a filter buried in a
 * composable. The ViewModel only supplies the four inputs.
 *
 * THE BADGE'S BASE, EXACTLY
 * -------------------------
 *  - A badge counts UNREAD CONVERSATIONS among the rows the CURRENT TAB can show. Switch
 *    to Archived and a chip's badge describes archived conversations; the number always
 *    matches what tapping the chip would put on screen. That is what "tab-context aware"
 *    means here, and it is why the base is the rendered rows and not a database count.
 *  - A conversation the user REPORTED as spam is never counted by a custom chip: the
 *    report hides it from the normal inbox, and a badge that counted hidden rows would
 *    send the user to an empty list. The reported rows are passed separately so the SPAM
 *    chip — the one place they ARE reachable — can still show its own badge.
 *  - `manualUnread` needs no special handling: Home already folds the user's bookmark into
 *    `Sms.unread` at render time, so the rows handed in already carry it.
 *  - A TRASHED conversation is not in the rendered rows at all, so it is excluded from
 *    every badge while its membership row survives — restoring it brings the count back.
 *
 * CHIP VISIBILITY
 * ---------------
 *  - A SYSTEM chip appears only when its category actually contains conversations, which is
 *    today's behaviour and is preserved by deriving its count from the DB-wide map.
 *  - A CUSTOM chip is ALWAYS present, even at zero. A category the user created is a place
 *    they made and can put something in; hiding it as soon as it is empty would make it
 *    disappear the moment it is created.
 */
object HomeCategoryProjection {

    /**
     * One conversation as the projection needs it.
     *
     * [rawAddress] / [normalizedAddress] are exactly the two representations
     * [ConversationCategoryScopeResolver] takes, so a conversation resolves to the SAME
     * scope here as it did when it was assigned.
     */
    data class Conversation(
        val threadId: Long,
        val rawAddress: String?,
        val normalizedAddress: String?,
        val unread: Boolean
    )

    /** The user's own category, reduced to what a chip needs. */
    data class CustomCategory(
        val categoryId: String,
        val name: String,
        val sortOrder: Int
    )

    data class Input(
        /** The rows the CURRENT tab can show, reported-spam already excluded. */
        val context: List<Conversation> = emptyList(),

        /** The reported-spam rows: reachable ONLY through the SPAM chip. */
        val spam: List<Conversation> = emptyList(),

        /**
         * Live thread → effective Smart Category. DB-WIDE on purpose: it is also the
         * system chips' count source, and that count is what keeps the SPAM chip on
         * screen while its conversations are hidden from the inbox.
         */
        val systemCategories: Map<Long, MessageCategory> = emptyMap(),

        /** Every user-category membership row. */
        val assignments: List<UserCategoryAssignmentEntity> = emptyList(),

        /** The user's categories, in their stored order. */
        val customCategories: List<CustomCategory> = emptyList()
    )

    /**
     * Smart categories in the order the chip row renders them.
     *
     * Declared here, not read from [CategoryFilter.displayOrder], so this projection has no
     * dependency on the Compose-facing enum and a reordering there cannot silently reshuffle
     * the badge list.
     */
    val SYSTEM_DISPLAY_ORDER: List<MessageCategory> = listOf(
        MessageCategory.PERSONAL,
        MessageCategory.OTP,
        MessageCategory.TRANSACTION,
        MessageCategory.PROMOTION,
        MessageCategory.SPAM
    )

    /**
     * Home rows → projection conversations.
     *
     * `Sms.sender` is the RAW provider address when the row has one (`rawAddress.ifBlank {
     * normalizedAddress }` at the projection boundary), so it is passed as the raw
     * representation. That matters: the resolver's raw-first rule exists so an alphanumeric
     * sender id like `IR-MCI1` is never reduced to the phone number `1`.
     */
    fun conversationsOf(rows: List<Sms>): List<Conversation> = rows.map { row ->
        Conversation(
            threadId = row.threadId,
            rawAddress = row.sender.ifBlank { null },
            normalizedAddress = null,
            unread = row.unread
        )
    }

    /**
     * The ordered chip row: system chips first (only those with data), then the user's
     * categories in their stored order (always present).
     */
    fun chips(input: Input): List<HomeCategoryChip> {
        val systemChips = systemChips(input)
        val customChips = customChips(input)
        return systemChips + customChips
    }

    // ── System (Smart Category) chips ───────────────────────────────────────

    private fun systemChips(input: Input): List<HomeCategoryChip> {
        // DB-wide membership: the same base the existing chip COUNT uses.
        val membership = HashMap<MessageCategory, Int>()
        input.systemCategories.values.forEach { category ->
            membership[category] = (membership[category] ?: 0) + 1
        }

        // UNREAD, per context. Distinct thread ids so one conversation can never be
        // counted twice by a duplicated row.
        val unreadFromContext = unreadThreadIdsByCategory(input.context, input.systemCategories)
        val unreadFromSpam = unreadThreadIdsByCategory(input.spam, input.systemCategories)

        return SYSTEM_DISPLAY_ORDER.mapNotNull { category ->
            val conversations = membership[category] ?: return@mapNotNull null
            val unread = if (category == MessageCategory.SPAM) {
                // A reported conversation is hidden from the inbox, so its own chip is the
                // only place its unread state is reachable — and therefore the only place
                // it may be counted.
                unreadFromSpam[category]?.size ?: 0
            } else {
                unreadFromContext[category]?.size ?: 0
            }
            HomeCategoryChip(
                key = HomeCategoryKey.System(category),
                conversations = conversations,
                unreadConversations = unread
            )
        }
    }

    private fun unreadThreadIdsByCategory(
        conversations: List<Conversation>,
        systemCategories: Map<Long, MessageCategory>
    ): Map<MessageCategory, Set<Long>> {
        val out = HashMap<MessageCategory, MutableSet<Long>>()
        conversations.forEach { conversation ->
            if (!conversation.unread) return@forEach
            val category = systemCategories[conversation.threadId] ?: return@forEach
            out.getOrPut(category) { linkedSetOf() }.add(conversation.threadId)
        }
        return out
    }

    // ── Custom (user) chips ─────────────────────────────────────────────────

    private fun customChips(input: Input): List<HomeCategoryChip> {
        if (input.customCategories.isEmpty()) return emptyList()

        // Persisted scope → the categories it belongs to. Built once, so the per-conversation
        // work below is one scope resolution and one map lookup.
        val categoriesByScope = HashMap<StoredCategoryScope, MutableList<String>>()
        input.assignments.forEach { assignment ->
            val scope = UserCategoryScopeCodec.decode(assignment.scopeType, assignment.scopeKey)
                ?: return@forEach
            categoriesByScope
                .getOrPut(UserCategoryScopeCodec.encode(scope)) { mutableListOf() }
                .add(assignment.categoryId)
        }

        val totals = HashMap<String, Int>()
        val unread = HashMap<String, Int>()
        if (categoriesByScope.isNotEmpty()) {
            val seenPerCategory = HashMap<String, MutableSet<Long>>()
            input.context.forEach { conversation ->
                val scope = ConversationCategoryScopeResolver.resolve(
                    threadId = conversation.threadId,
                    rawAddress = conversation.rawAddress,
                    normalizedAddress = conversation.normalizedAddress
                ) ?: return@forEach
                // A conversation resolves to exactly ONE scope, so it is counted at most
                // once per category; the set is defensive against a duplicated row.
                val categories = categoriesByScope[UserCategoryScopeCodec.encode(scope)]
                    ?: return@forEach
                categories.forEach { categoryId ->
                    if (seenPerCategory.getOrPut(categoryId) { linkedSetOf() }
                            .add(conversation.threadId)
                    ) {
                        totals[categoryId] = (totals[categoryId] ?: 0) + 1
                        if (conversation.unread) {
                            unread[categoryId] = (unread[categoryId] ?: 0) + 1
                        }
                    }
                }
            }
        }

        return input.customCategories
            .sortedWith(compareBy({ it.sortOrder }, { it.categoryId }))
            .map { category ->
                HomeCategoryChip(
                    key = HomeCategoryKey.Custom(category.categoryId),
                    label = category.name,
                    conversations = totals[category.categoryId] ?: 0,
                    unreadConversations = unread[category.categoryId] ?: 0
                )
            }
    }
}
