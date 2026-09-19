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
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.People
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
 * FEATURE 12 — the horizontally scrollable Smart Categories row on Home.
 *
 *  - Stateless: the selected chip and its change callback are owned by the
 *    screen, matching [HomeFilterBar].
 *  - A chip appears ONLY when its category actually contains data, so the row
 *    can never offer a filter that would render an empty list. Tapping the
 *    selected chip clears the selection, which is how the user returns to All.
 *  - RTL-safe: a plain `Row` inside `horizontalScroll` follows the layout
 *    direction, so Persian mirrors without extra work.
 *  - Accessibility: every chip carries a label-and-count `contentDescription`,
 *    and the selected chip announces how to clear the filter.
 */
@Composable
fun CategoryFilterBar(
    selected: CategoryFilter?,
    counts: Map<CategoryFilter, Int>,
    onSelect: (CategoryFilter?) -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = CategoryFilter.displayOrder.filter { (counts[it] ?: 0) > 0 }
    if (visible.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        visible.forEach { filter ->
            val count = counts[filter] ?: 0
            val isSelected = selected == filter
            val label = stringResource(filter.labelRes)
            val description = if (isSelected) {
                stringResource(R.string.category_chip_selected_fmt, label, count)
            } else {
                stringResource(R.string.category_chip_count_fmt, label, count)
            }
            FilterChip(
                selected = isSelected,
                // Tapping the active chip clears the filter: no hidden state,
                // and no separate "All" chip competing with the tab row.
                onClick = { onSelect(if (isSelected) null else filter) },
                label = {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = filter.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                shape = RoundedCornerShape(16.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                modifier = Modifier.semantics {
                    contentDescription = description
                }
            )
        }
    }
}

/** Material icon per category; a sentinel-free exhaustive mapping. */
private fun CategoryFilter.icon(): ImageVector = when (this) {
    CategoryFilter.Personal -> Icons.Default.People
    CategoryFilter.Otp -> Icons.Default.Password
    CategoryFilter.Transaction -> Icons.Default.Payments
    CategoryFilter.Promotion -> Icons.Default.LocalOffer
    CategoryFilter.Spam -> Icons.Default.Block
}
