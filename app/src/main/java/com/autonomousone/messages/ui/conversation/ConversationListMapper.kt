package com.autonomousone.messages.ui.conversation

import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.utils.formatDateHeader
import java.util.Calendar

/**
 * One row of the conversation LazyColumn.
 *
 * The UI list is rendered with `reverseLayout = true`, so the DATA order is
 * newest→oldest and index 0 is the newest message — opening a conversation
 * lands on the latest row with no scroll command at all. The ViewModel's
 * canonical `messages` stay oldest→newest; this mapper owns the flip.
 *
 * In reverse layout the visual order is the data order mirrored, so the date
 * separator belongs AFTER its day's messages inside the data: it then paints
 * ABOVE them, exactly like a section header should.
 */
sealed interface ChatListItem {

    data class MessageItem(
        val sms: Sms
    ) : ChatListItem

    data class DateSeparator(
        /** Stable locale-independent identity for the day (yyyy-DAY_OF_YEAR). */
        val dayKey: String,
        /** Localized display text ("Today", "Yesterday", …). Never an id. */
        val dateText: String
    ) : ChatListItem
}

/**
 * Maps a canonical ASC window into the reverse-layout data order:
 * newest message first, each day's separator trailing its group.
 */
fun buildReverseChatItems(messagesAscending: List<Sms>): List<ChatListItem> {
    if (messagesAscending.isEmpty()) return emptyList()

    // Provider refresh + live incoming can race on self-SMS and briefly hand
    // Compose the same provider row twice. LazyColumn requires unique keys and
    // throws when a duplicate reaches it. Keep the freshest copy for each
    // provider/model id before building UI rows.
    val uniqueMessages = LinkedHashMap<Long, Sms>()
    messagesAscending.forEach { uniqueMessages[it.id] = it }

    return buildList {
        var currentDayKey: String? = null
        var currentHeader: String? = null

        uniqueMessages.values.sortedBy { it.date }.asReversed().forEach { sms ->
            val dayKey = localDayKey(sms.date)
            val header = formatDateHeader(sms.date)

            // Day changed while walking newest→oldest: close the previous
            // group with its separator (paints above that group).
            if (currentDayKey != null && currentDayKey != dayKey) {
                add(ChatListItem.DateSeparator(dayKey = currentDayKey, dateText = currentHeader.orEmpty()))
            }

            add(ChatListItem.MessageItem(sms))
            currentDayKey = dayKey
            currentHeader = header
        }

        if (currentDayKey != null) {
            add(ChatListItem.DateSeparator(dayKey = currentDayKey, dateText = currentHeader.orEmpty()))
        }
    }
}

/** LazyColumn key for any row — identity, not localized text. */
fun chatItemKey(item: ChatListItem): String = when (item) {
    is ChatListItem.DateSeparator -> "date_${item.dayKey}"
    // MMS model ids are negative; "msg_-7_date" stays unique vs "msg_7_date".
    is ChatListItem.MessageItem -> "msg_${item.sms.id}_${item.sms.date}_${item.sms.type}"
}

private fun localDayKey(epochMillis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return buildString {
        append(calendar.get(Calendar.YEAR))
        append('-')
        append(calendar.get(Calendar.DAY_OF_YEAR))
    }
}
