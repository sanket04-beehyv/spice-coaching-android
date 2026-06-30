package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.document.DocumentFileType
import com.medtroniclabs.microcoaching.ui.document.icon
import com.medtroniclabs.microcoaching.ui.document.labelRes
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy

/**
 * Horizontal Knowledge-section card for a **source document**.
 *
 * - Top half: thumbnail (from the per-document presigned URL if available);
 *   fallback shows a gradient + centred file-type icon so the card is never
 *   blank (and the icon gives the user a visual cue — PDF, slides, etc.).
 * - Bottom: title + file-type label (derived from the filename extension) +
 *   a trailing affordance. When [cached] is false this is a download icon; once
 *   the document is on disk it becomes a "view" (eye) icon — mirroring
 *   [ModuleTile]'s Knowledge variant. Tapping the card downloads (cached for
 *   offline) and opens the in-app preview.
 */
@Composable
fun KnowledgeCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailUrl: String? = null,
    fileName: String? = null,
    cached: Boolean = false,
) {
    val fileType = DocumentFileType.fromFilename(fileName)

    Card(
        onClick = onClick,
        // Width comes from the caller (KnowledgeRow sets a fixed tile width);
        // height is fixed so all tiles in the row line up.
        modifier = modifier
            .height(200.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ModuleThumbnail(
                thumbnailUrl = thumbnailUrl,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                fallback = {
                    // Gradient background + centred file-type icon — shown when
                    // the thumbnail is null, still loading, or fails to load.
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(SpiceBlueContainer, Color(0xFFFBEFEA)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = fileType.icon,
                            contentDescription = null,
                            tint = SpiceBlueDark.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = SpiceNavy,
                )
                Box(modifier = Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (cached) Icons.Outlined.Visibility else Icons.Outlined.FileDownload,
                        contentDescription = stringResource(
                            if (cached) R.string.knowledge_view_cd else R.string.modules_download_cd,
                        ),
                        tint = SpiceBlueDark,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(fileType.labelRes),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = SpiceBlueDark,
                    )
                }
            }
        }
    }
}
