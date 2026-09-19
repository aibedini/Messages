package com.autonomousone.messages.repository

import com.autonomousone.messages.data.MessageCategory
import com.autonomousone.messages.data.ThreadCategoryOverride
import com.autonomousone.messages.data.ThreadCategoryRow

/**
 * FEATURE 12 — the PURE core of Smart Categories filtering.
 *
 * Lives in the repository layer (not in the UI) because BOTH the repository
 * (server-side of the app's own reads and diagnostics) and Home need the same
 * answer to "what category does this thread effectively have?". One rule, one
 * place.
 *
 * The two rules that matter, and that the JVM tests pin:
 *
 *  1. **The user override ALWAYS wins.** [effectiveCategories] resolves the
 *     override first and only falls back to the automatic classifier output.
 *  2. **A category filter is an INDEXED thread lookup, never a message scan.**
 *     Both inputs are conversation-sized (one row per thread), so nothing here
 *     is O(messages).
 */
object ConversationCategoryResolver {

    /** One thread's effective category, already override-resolved. */
    data class EffectiveCategory(val threadId: Long, val category: MessageCategory)

    /**
     * Resolve every thread's effective category.
     *
     * @param automatic the classifier's projection (`threadId` → category name).
     * @param overrides the user's overrides; a NON-NULL override replaces the
     *   automatic category, a null one means "follow the classifier".
     * @param userSpamThreads threads the user explicitly reported as spam. The
     *   report is itself a manual decision, so it reads as SPAM even when no
     *   `categoryOverride` was written.
     */
    fun effectiveCategories(
        automatic: List<ThreadCategoryRow>,
        overrides: List<ThreadCategoryOverride>,
        userSpamThreads: Set<Long> = emptySet()
    ): List<EffectiveCategory> {
        val overrideByThread = overrides.associate { it.threadId to it.categoryOverride }
        val seen = LinkedHashSet<Long>()
        val out = ArrayList<EffectiveCategory>(automatic.size + overrides.size)

        for (row in automatic) {
            seen += row.threadId
            out += EffectiveCategory(
                threadId = row.threadId,
                category = resolve(
                    override = overrideByThread[row.threadId],
                    automatic = row.category,
                    isUserSpam = row.threadId in userSpamThreads
                )
            )
        }
        // A user override on a thread with no automatic row must still be
        // visible: the classifier never having run is not a reason to hide a
        // category the user chose.
        for (override in overrides) {
            if (override.threadId in seen) continue
            if (override.categoryOverride == null && override.threadId !in userSpamThreads) continue
            out += EffectiveCategory(
                threadId = override.threadId,
                category = resolve(
                    override = override.categoryOverride,
                    automatic = null,
                    isUserSpam = override.threadId in userSpamThreads
                )
            )
        }
        return out
    }

    private fun resolve(
        override: String?,
        automatic: String?,
        isUserSpam: Boolean
    ): MessageCategory = when {
        // 1. An explicit category override outranks everything else.
        override != null -> MessageCategory.from(override)
        // 2. The user's spam report is itself a manual decision.
        isUserSpam -> MessageCategory.SPAM
        // 3. Finally the automatic classifier.
        else -> automatic?.let { MessageCategory.from(it) } ?: MessageCategory.UNKNOWN
    }
}
