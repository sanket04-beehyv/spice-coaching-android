package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.KnowledgeDocument

/**
 * Bounded, lightly-scrollable row of Knowledge document tiles that **fills the
 * row width** — mirrors [TrainingRow]. Cards are sized from [tileWidth] as a
 * target: when all fit they stretch to fill; when there are more, one extra card
 * **peeks** at the right edge. Only those few are rendered, so the row scrolls
 * *just enough* to pull the peeked card fully into view — not through the whole
 * list ("See all" covers that; see [computeRowPeek]). Opens the full grid
 * (`AllModulesScreen` with `ALL_MODULES_TYPE_KNOWLEDGE`).
 */
@Composable
fun KnowledgeRow(
    documents: List<KnowledgeDocument>,
    onSelect: (KnowledgeDocument) -> Unit,
    onSeeAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    cachedDocIds: Set<String> = emptySet(),
    tileWidth: Dp = 150.dp,
    tileSpacing: Dp = 12.dp,
    horizontalPadding: Dp = 16.dp,
) {
    if (documents.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val available = maxWidth - horizontalPadding * 2
        val layout = computeRowPeek(available, documents.size, tileWidth, tileSpacing)
        val showSeeAll = onSeeAll != null && layout.hasMore

        Column(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                title = stringResource(R.string.modules_section_knowledge),
                seeAllLabel = if (showSeeAll) stringResource(R.string.modules_see_all) else null,
                onSeeAllClick = if (showSeeAll) onSeeAll else null,
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(tileSpacing),
            ) {
                documents.take(layout.visibleCount).forEach { doc ->
                    KnowledgeCard(
                        title = doc.title,
                        thumbnailUrl = doc.thumbnailUrl,
                        fileName = doc.fileName,
                        cached = doc.sourceDocumentId in cachedDocIds,
                        onClick = { onSelect(doc) },
                        modifier = Modifier.width(layout.cardWidth),
                    )
                }
            }
        }
    }
}
