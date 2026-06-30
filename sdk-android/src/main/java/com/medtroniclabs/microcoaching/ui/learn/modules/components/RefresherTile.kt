package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark

/**
 * List-row tile rendering a refresher module. Layout per
 * `docs/designs/Modules-Screen.png`: leading 56.dp square icon block in
 * [SpiceBlueContainer], category label in [SpiceBlueDark], bold title,
 * meta line, optional CRITICAL red badge on the right.
 *
 * @param category Localised category label (e.g. "Clinical Assessment").
 * @param title Module title (Bangla preferred, falls back to English).
 * @param meta Already-formatted meta line ("Quiz · 4 questions" / "Learning").
 * @param isCritical When true a red CRITICAL pill is rendered next to category.
 * @param isGap When true an orange GAP pill is rendered next to category. Indicates
 *   the module was surfaced by the backend gap-detection engine after a previous
 *   incorrect quiz attempt.
 * @param onClick Opens the refresher quiz bottom sheet.
 */
@Composable
fun RefresherTile(
    category: String,
    title: String,
    meta: String,
    isCritical: Boolean,
    isGap: Boolean = false,
    severity: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailUrl: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
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
            RefresherIconBlock(category = category, thumbnailUrl = thumbnailUrl)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = category,
                        color = SpiceBlueDark,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                        ),
                    )
                    /* if (isGap) {
                        GapBadge()
                    } else if  */
                    if (isCritical) {
                        CriticalBadge()
                    }
                    SeverityChip(severity)
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7280),
                )
            }
        }
    }
}

@Composable
private fun RefresherIconBlock(category: String, thumbnailUrl: String? = null) {
    val icon: ImageVector = when {
        category.contains("quiz", ignoreCase = true) -> Icons.Outlined.Assignment
        else -> Icons.Outlined.MedicalServices
    }
    ModuleThumbnail(
        thumbnailUrl = thumbnailUrl,
        contentDescription = null,
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp)),
        fallback = {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(SpiceBlueContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SpiceBlueDark,
                )
            }
        },
    )
}

@Composable
private fun CriticalBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFB91C1C))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(com.medtroniclabs.microcoaching.R.string.badge_critical),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}

/**
 * Small colored chip showing the behavioural-gap severity next to the refresher
 * type label. Hidden (renders nothing) when [severity] is null/unknown — e.g. a
 * fallback (non-gap) refresher.
 */
@Composable
private fun SeverityChip(severity: String?) {
    val (color, labelRes) = when (severity?.lowercase()) {
        "high" -> Color(0xFFB91C1C) to R.string.severity_high
        "moderate" -> Color(0xFFD97706) to R.string.severity_moderate
        "low" -> SpiceBlueDark to R.string.severity_low
        else -> return
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}

@Composable
private fun GapBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFD97706))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(com.medtroniclabs.microcoaching.R.string.badge_gap),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}
