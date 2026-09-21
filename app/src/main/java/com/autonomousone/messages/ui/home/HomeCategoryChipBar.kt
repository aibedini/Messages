package com.autonomousone.messages.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonomousone.messages.R

/**
 * v3.5.0 — Home's ONE category row: the Smart Categories and the user's own categories,
 * side by side, each carrying its live unread badge.
 *
 *  - Stateless: the chips, the selection and the change callback are owned by the screen,
 *    matching [HomeFilterBar] and the bar this replaces.
 *  - The chips come from [HomeCategoryProjection], so what a chip says (its count and its
 *    badge) and what tapping it lists are the same computation. In particular the Smart
 *    Categories are NOT re-derived here: they keep the counts and the visibility they
 *    already had, and simply gain a badge.
 *  - A system chip with no data is absent (unchanged); a user category is always present,
 *    so a category the user just created is visible before it has anything in it.
 *  - Tapping the selected chip clears the selection, which is how the user returns to All.
 *  - RTL-safe: a plain `Row` inside `horizontalScroll` follows the layout direction, so
 *    Persian mirrors without extra work.
 *  - Accessibility: every chip announces its label and the number of UNREAD conversations,
 *    and the selected chip announces how to clear the filter. The badge is drawn as a
 *    Material `Badge`, and its text is also part of that description so a screen reader
 *    never has to rely on the visual bubble.
 */
@Composable
fun HomeCategoryChipBar(
    chips: List<HomeCategoryChip>,
    selected: HomeCategoryKey?,
    onSelect: (HomeCategoryKey?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (chips.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { chip ->
            val isSelected = selected == chip.key
            val label = chipLabel(chip)
            val description = chipDescription(chip, label, isSelected)
            FilterChip(
                selected = isSelected,
                // Tapping the active chip clears the filter: no hidden state, and no
                // separate "All" chip competing with the tab row.
                onClick = { onSelect(if (isSelected) null else chip.key) },
                label = {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = chipIcon(chip.key),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                trailingIcon = chip.badge?.let { badgeText ->
                    // Explicit type: a lambda assigned through `let` has no expected type,
                    // and the slot is a COMPOSABLE function type.
                    val slot: @Composable () -> Unit = { CategoryChipBadge(badgeText) }
                    slot
                },
                shape = RoundedCornerShape(16.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier.semantics { contentDescription = description }
            )
        }
    }
}

/**
 * The unread bubble.
 *
 * A Material [Badge] on the error container colour: it is the same visual language the
 * rest of the app uses for "something is waiting", and it is deliberately tiny — the
 * number is a hint that opens a filtered list, not a statistic.
 */
@Composable
private fun CategoryChipBadge(text: String) {
    Badge(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError
    ) {
        Text(text = text, fontSize = 10.sp)
    }
}

/**
 * The chip's own text: a user category carries its stored name, a Smart Category its
 * localized resource. A user category whose name is somehow empty still renders its icon
 * rather than collapsing the row.
 */
@Composable
private fun chipLabel(chip: HomeCategoryChip): String = when (val key = chip.key) {
    is HomeCategoryKey.System ->
        key.asCategoryFilter()?.let { stringResource(it.labelRes) } ?: key.category.name

    is HomeCategoryKey.Custom -> chip.label.orEmpty()
}

/**
 * The announcement. A badge is a CONVERSATION count, so the description says
 * "conversations" and not "messages" — the whole point of the badge is that seven unread
 * SMS from one person is one waiting chat.
 */
@Composable
private fun chipDescription(
    chip: HomeCategoryChip,
    label: String,
    isSelected: Boolean
): String = when {
    isSelected && chip.unreadConversations > 0 -> stringResource(
        R.string.category_chip_unread_selected_fmt,
        label,
        chip.unreadConversations
    )

    isSelected -> stringResource(R.string.category_chip_selected_fmt, label, chip.conversations)
    chip.unreadConversations > 0 -> stringResource(
        R.string.category_chip_unread_fmt,
        label,
        chip.unreadConversations
    )

    else -> stringResource(R.string.category_chip_count_fmt, label, chip.conversations)
}

/**
 * Material icon per chip.
 *
 * A Smart Category keeps exactly the icon it already had; a user category gets the label
 * icon, because it is the user's own tag rather than a classification the app made.
 */
private fun chipIcon(key: HomeCategoryKey): ImageVector = when (key) {
    is HomeCategoryKey.Custom -> Icons.Default.Label
    is HomeCategoryKey.System -> when (key.asCategoryFilter()) {
        CategoryFilter.Personal -> Icons.Default.People
        CategoryFilter.Otp -> Icons.Default.Password
        CategoryFilter.Transaction -> Icons.Default.Payments
        CategoryFilter.Promotion -> Icons.Default.LocalOffer
        CategoryFilter.Spam -> Icons.Default.Block
        null -> Icons.Default.Label
    }
}
