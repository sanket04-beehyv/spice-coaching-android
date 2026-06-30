package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy

/** Which trailing affordance a [ModuleTile] shows. */
enum class ModuleTileVariant {
    /** Trailing circular completion ring (0–1). */
    TRAINING,

    /** Trailing circular download button. */
    KNOWLEDGE,
}

private val META_TEXT_COLOR = Color(0xFF6B7280)

/**
 * Full-width horizontal list-row tile for a module: leading thumbnail, title +
 * subtitle, and a trailing circular affordance that depends on [variant].
 *
 * - [ModuleTileVariant.KNOWLEDGE] → a circular download button, or a "view"
 *   (eye) button when [knowledgeCached] is true (the document is already on
 *   disk — tapping previews it offline).
 * - [ModuleTileVariant.TRAINING]  → a circular completion ring driven by
 *   [progress] (0f–1f), with the percentage in the centre.
 *
 * Everything except the trailing affordance is identical between variants.
 */
@Composable
fun ModuleTile(
    title: String,
    subtitle: String,
    variant: ModuleTileVariant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailUrl: String? = null,
    progress: Float = 0f,
    onDownloadClick: (() -> Unit)? = null,
    knowledgeCached: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Leading thumbnail (shared) ──────────────────────────────────
            ModuleThumbnail(
                thumbnailUrl = thumbnailUrl,
                contentDescription = title,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp)),
                fallback = {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(SpiceBlueContainer),
                    )
                },
            )

            // ── Title + subtitle (shared) ───────────────────────────────────
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = SpiceNavy,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = META_TEXT_COLOR,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ── Trailing affordance (variant-specific) ──────────────────────
            when (variant) {
                ModuleTileVariant.KNOWLEDGE -> KnowledgeAction(
                    cached = knowledgeCached,
                    onClick = onDownloadClick ?: onClick,
                )
                ModuleTileVariant.TRAINING -> CompletionRing(
                    progress = progress.coerceIn(0f, 1f),
                )
            }
        }
    }
}

/**
 * Circular light-blue trailing action for the Knowledge variant: a download icon
 * normally, or a "view" (eye) icon once the document is [cached] on disk.
 */
@Composable
private fun KnowledgeAction(cached: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(SpiceBlueContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (cached) Icons.Outlined.Visibility else Icons.Outlined.FileDownload,
            contentDescription = stringResource(
                if (cached) R.string.knowledge_view_cd else R.string.modules_download_cd,
            ),
            tint = SpiceBlueDark,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Circular completion ring with centred percentage — the Training-variant trailing action. */
@Composable
private fun CompletionRing(progress: Float) {
    Box(
        modifier = Modifier.size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(40.dp),
            color = SpiceBlueDark,
            trackColor = SpiceBlueContainer,
            strokeWidth = 3.dp,
        )
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = SpiceBlueDark,
        )
    }
}

// ── Previews ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun PreviewModuleTile_Knowledge() {
    MicroCoachingTheme {
        ModuleTile(
            title = "BRAC Health Program",
            subtitle = "Program Implementation Guidelines 2024-25",
            variant = ModuleTileVariant.KNOWLEDGE,
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewModuleTile_Training() {
    MicroCoachingTheme {
        ModuleTile(
            title = "Fundal Height Assessment",
            subtitle = "5 min · 2 questions",
            variant = ModuleTileVariant.TRAINING,
            progress = 0.6f,
            onClick = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
