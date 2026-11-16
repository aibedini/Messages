package com.autonomousone.messages.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonomousone.messages.R
import com.autonomousone.messages.data.GatewayEventFactory
import com.autonomousone.messages.messaging.QuickReply
import com.autonomousone.messages.model.Sms
import com.autonomousone.messages.repository.DeliveredSuggestionBatch
import com.autonomousone.messages.repository.MessageIdentity
import com.autonomousone.messages.sync.SuggestionsReadyCommand

/**
 * Suggestion cards (XR-GA-017): two or three alternatives, directly under the inbound message they
 * answer, in one horizontally scrollable row.
 *
 * ## What this row may and may not do
 *
 * A tap puts the suggestion's text into the composer and **stops there**. Nothing here calls a sender,
 * a transport gate or the send state machine — sending stays a separate, explicit user action on the
 * composer's own send control. That is the whole safety property of the feature, and it is why the row
 * exposes `onSelect` (fill the draft) rather than any "send" callback.
 *
 * ## Why the state is a sealed value
 *
 * `Visible`, `Hidden` and the quick-reply fallback are mutually exclusive by construction, so the
 * conversation screen cannot render cards without having decided they are showable first. The decision
 * itself is [SuggestionSlotPolicy] — pure, so every rule (expiry, a newer inbound message, LOCAL_ONLY)
 * is unit-tested without Compose.
 */
enum class SuggestionBadge {
    /** Grounded in approved knowledge; the calm, low-emphasis badge. */
    KNOWLEDGE,

    /** Model-generated; must never look more certain than a template. */
    NEEDS_REVIEW;

    companion object {
        /**
         * Map the wire vocabulary, tolerantly.
         *
         * `badge` is authoritative when it names something this build knows. When it is absent or
         * unknown, the `kind` decides: `approved_template` IS an approval, so it earns the knowledge
         * badge; an unrecognised kind falls back to [NEEDS_REVIEW], because a newer GMweb may invent a
         * label and the only safe default is the one that claims less. Confidence is never invented.
         */
        fun of(kind: String, badge: String): SuggestionBadge = when {
            badge.equals("knowledge", ignoreCase = true) -> KNOWLEDGE
            kind.equals("approved_template", ignoreCase = true) -> KNOWLEDGE
            else -> NEEDS_REVIEW
        }
    }
}

/** One suggestion as the row renders it. */
data class SuggestionCardUi(
    val id: String,
    val text: String,
    val badge: SuggestionBadge,
    /** Carried for the long-press "why" sheet: which inbound message this answers. */
    val anchorMessageId: String,
    val expiresAtMs: Long,
    val receivedAtMs: Long,
    val generation: Int,
    /** The sender's own estimate; null when it did not give one. Never invented here. */
    val segments: Int?
) {
    companion object {
        fun from(batch: DeliveredSuggestionBatch): List<SuggestionCardUi> =
            batch.suggestions.map { suggestion ->
                SuggestionCardUi(
                    id = suggestion.id,
                    text = suggestion.text,
                    badge = SuggestionBadge.of(suggestion.kind, suggestion.badge),
                    anchorMessageId = batch.anchorMessageId,
                    expiresAtMs = batch.expiresAtMs,
                    receivedAtMs = batch.receivedAt,
                    generation = batch.generation,
                    segments = suggestion.segments
                )
            }
    }
}

/**
 * The exact text a tap is allowed to place in the composer.
 *
 * Deliberately the card's text and NOTHING else — no prefix, no trailing space, no quote characters, no
 * "Re:". A tap must leave the user with a normal draft they can edit, send or discard, and the only way
 * to keep that true is to have exactly one place that decides what "insert" means. A unit test asserts
 * this is the identity, and an architecture guard asserts the file that calls it cannot send.
 */
fun SuggestionCardUi.composerDraft(): String = text

/** What occupies the suggestion slot under the last inbound message. */
sealed interface SuggestionSlotState {
    /** Nothing: no cards, no fallback, or the conversation must not show AI content. */
    data object Hidden : SuggestionSlotState

    /** The AI's alternatives. At most [SuggestionSlotPolicy.MAX_CARDS]. */
    data class Cards(val cards: List<SuggestionCardUi>) : SuggestionSlotState

    /** The local quick replies, used when the network/AI produced nothing. */
    data class QuickReplies(val replies: List<QuickReply>) : SuggestionSlotState
}

/**
 * The visibility rules of the feature, as one pure function.
 *
 * ```text
 * LOCAL_ONLY thread                  => nothing (GOD AI never saw this conversation)
 * a card's anchor is not the newest  => nothing (a newer inbound message has arrived)
 * expires_at passed                  => nothing
 * no showable cards                  => local quick replies, or nothing
 * ```
 *
 * The fallback is deliberately restricted to an EMPTY composer: quick replies exist to start a reply,
 * and popping them over text the user is already writing would push the conversation around for no
 * reason. Suggestion cards do not have that restriction — after tapping one the user may want another.
 */
object SuggestionSlotPolicy {

    /** The contract asks for two to three cards; more is a wall of text, not a choice. */
    const val MAX_CARDS = 3

    /** At most this many quick replies in the fallback slot. */
    const val MAX_QUICK_REPLIES = 3

    fun resolve(
        cards: List<SuggestionCardUi>,
        newestInboundMessageId: String?,
        threadLocalOnly: Boolean,
        nowMs: Long,
        quickReplies: List<QuickReply>,
        composerEmpty: Boolean
    ): SuggestionSlotState {
        if (threadLocalOnly) return SuggestionSlotState.Hidden

        val showable = cards.filter { card ->
            card.anchorMessageId.isNotBlank() &&
                card.anchorMessageId == newestInboundMessageId &&
                card.expiresAtMs > nowMs
        }
        if (showable.isNotEmpty()) {
            return SuggestionSlotState.Cards(showable.take(MAX_CARDS))
        }

        if (quickReplies.isEmpty() || !composerEmpty) return SuggestionSlotState.Hidden
        return SuggestionSlotState.QuickReplies(quickReplies.take(MAX_QUICK_REPLIES))
    }

    /** The message the slot is painted under, or null when there is nothing to paint. */
    fun anchorOf(slot: SuggestionSlotState, newestInboundMessageId: String?): String? =
        when (slot) {
            is SuggestionSlotState.Cards -> slot.cards.firstOrNull()?.anchorMessageId
            is SuggestionSlotState.QuickReplies -> newestInboundMessageId
            SuggestionSlotState.Hidden -> null
        }
}

/**
 * The stable message identity Android publishes for an inbound row.
 *
 * The executor normalises `for_message_ref` to exactly this value, which is what makes the staleness
 * check an equality test instead of a format negotiation.
 */
fun suggestionAnchorId(sms: Sms): String = GatewayEventFactory.messageIdFor(
    MessageIdentity.sourceOf(sms.id),
    MessageIdentity.providerIdOf(sms.id),
    sms.date
)

/**
 * Insert the suggestion row directly BELOW the message it answers.
 *
 * The list is rendered `reverseLayout`, so a higher data index paints HIGHER on screen: the slot is
 * inserted at the anchor's index, which places it between the anchor and everything older. Returns the
 * list unchanged when there is nothing to show or the anchor is not in the current window — a card whose
 * message has scrolled out of the loaded window must not float somewhere arbitrary.
 */
fun withSuggestionSlot(
    items: List<ChatListItem>,
    slot: SuggestionSlotState,
    anchorMessageId: String?
): List<ChatListItem> {
    if (slot is SuggestionSlotState.Hidden) return items
    if (anchorMessageId.isNullOrBlank()) return items
    val index = items.indexOfFirst { item ->
        item is ChatListItem.MessageItem && suggestionAnchorId(item.sms) == anchorMessageId
    }
    if (index < 0) return items
    return buildList(items.size + 1) {
        addAll(items.subList(0, index))
        add(ChatListItem.SuggestionRow(slot))
        addAll(items.subList(index, items.size))
    }
}

/**
 * The row itself.
 *
 * RTL-safety comes from the primitives: [Row] and [horizontalScroll] mirror automatically under an RTL
 * layout direction, so the first card sits at the start edge and scrolling goes the right way without a
 * single `if (isRtl)`. TalkBack reads each card as "badge. text. double tap to put this text in the
 * reply box." and announces the long-press action before it is used.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SuggestionSlotRow(
    slot: SuggestionSlotState,
    onSelect: (SuggestionCardUi) -> Unit,
    onLongPress: (SuggestionCardUi) -> Unit,
    onDismiss: () -> Unit,
    onQuickReply: (QuickReply) -> Unit,
    modifier: Modifier = Modifier
) {
    when (slot) {
        SuggestionSlotState.Hidden -> Unit
        is SuggestionSlotState.Cards -> Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            slot.cards.forEach { card ->
                SuggestionCard(card = card, onSelect = onSelect, onLongPress = onLongPress)
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.suggestion_dismiss_action),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        is SuggestionSlotState.QuickReplies -> Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.quick_replies_fallback_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            slot.replies.forEach { reply ->
                SuggestionChip(
                    onClick = { onQuickReply(reply) },
                    label = {
                        Text(
                            text = reply.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionCard(
    card: SuggestionCardUi,
    onSelect: (SuggestionCardUi) -> Unit,
    onLongPress: (SuggestionCardUi) -> Unit
) {
    val badgeLabel = when (card.badge) {
        SuggestionBadge.KNOWLEDGE -> stringResource(R.string.suggestion_badge_knowledge)
        SuggestionBadge.NEEDS_REVIEW -> stringResource(R.string.suggestion_badge_needs_review)
    }
    val insertLabel = stringResource(R.string.suggestion_insert_action)
    val whyLabel = stringResource(R.string.suggestion_long_press_action)
    val a11y = stringResource(R.string.suggestion_card_a11y_fmt, badgeLabel, card.text)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        tonalElevation = 1.dp,
        modifier = Modifier
            .widthIn(max = 240.dp)
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .combinedClickable(
                onClickLabel = insertLabel,
                onLongClickLabel = whyLabel,
                onClick = { onSelect(card) },
                onLongClick = { onLongPress(card) }
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = card.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = badgeLabel,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
