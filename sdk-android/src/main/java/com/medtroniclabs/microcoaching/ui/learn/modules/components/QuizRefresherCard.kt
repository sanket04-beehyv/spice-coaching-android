package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.components.swipeToDismiss
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark

/**
 * Quiz refresher card shown on the modules screen. Displays the first quiz
 * question from the highest-priority morning module (same module shown in
 * [MorningCard] on the home screen — both use [MicroCoachingSDK.getSelectedMorningModule]).
 *
 * Renamed from `QuickLearnCard` (v0.3.2). Tap opens [RefresherBottomSheet]
 * in question-first mode (1 question → lesson cards).
 *
 * @param questionText The first quiz question text from the selected module.
 * @param participantCount Placeholder count (backend endpoint deferred).
 * @param xpReward XP display label — display only.
 * @param onClick Open [RefresherBottomSheet].
 * @param onDismiss Swiped away (arc gesture). The module stays in the Refresher
 *   list and counts toward the home "Coaching" tile skip badge.
 * @param dismissKey Identity of the shown question; resets the swipe offset when
 *   the banner advances to a different module.
 */
@Composable
fun QuizRefresherCard(
    questionText: String,
    participantCount: Int,
    xpReward: Int,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissKey: Any? = questionText,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .swipeToDismiss(onDismiss = onDismiss, resetKey = dismissKey)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(SpiceBlue, SpiceBlueDark)))
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.quick_learn_label),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                ),
            )
            Text(
                text = questionText,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            // TODO: Uncomment this when the backend is able to update the participant count
            // Text(
            //     text = stringResource(R.string.quick_learn_meta_count, participantCount),
            //     color = Color.White.copy(alpha = 0.8f),
            //     style = MaterialTheme.typography.bodySmall,
            // )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RefresherPill(stringResource(R.string.quick_learn_tap_to_answer))
                // Points/XP display temporarily disabled — UI only; [xpReward] + scoring logic retained.
                // RefresherPill(stringResource(R.string.quick_learn_xp_reward, xpReward))
            }
        }
    }
}

@Composable
private fun RefresherPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.18f))
            .padding(PaddingValues(horizontal = 14.dp, vertical = 8.dp)),
    ) {
        Text(text = text, color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}
