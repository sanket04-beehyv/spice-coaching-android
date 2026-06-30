package com.medtroniclabs.microcoaching.ui.components

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
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark
import com.medtroniclabs.microcoaching.ui.theme.SpiceNavy

/**
 * Home-screen morning refresher banner — a **minimal** list-style tile (leading
 * icon block, "Micro-coaching" eyebrow, module title, Skip / Start).
 *
 * Besides the explicit Skip button, the whole tile can be **swiped away** in a
 * shallow arc (left or right) to skip — see [Modifier.swipeToDismiss]. Both the
 * Skip button and a completed swipe call [onSkip].
 *
 * [cardCount] / [questionCount] / [estimatedMinutes] are retained for call-site
 * compatibility (the host passes them); the minimal design no longer renders the
 * meta row.
 */
@Composable
fun MorningCard(
    moduleTitle: String,
    cardCount: Int,
    questionCount: Int,
    estimatedMinutes: Int,
    onStart: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SdkLocalizedTheme {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .swipeToDismiss(onDismiss = onSkip, resetKey = moduleTitle),
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
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SpiceBlueContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Groups,
                        contentDescription = null,
                        tint = SpiceBlueDark,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    // Eyebrow = refresher content-type (Microcoaching / Learning card /
                    // Quiz), mirroring the RefresherTile label. Derived from what the
                    // module carries: both cards+quiz → Microcoaching; cards-only →
                    // Learning card; quiz-only → Quiz.
                    val typeRes = when {
                        cardCount > 0 && questionCount > 0 -> R.string.refresher_type_microcoaching
                        cardCount > 0 -> R.string.refresher_type_learning_card
                        else -> R.string.refresher_type_quiz
                    }
                    Text(
                        text = stringResource(typeRes),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                        ),
                        color = SpiceBlueDark,
                    )
                    Text(
                        text = moduleTitle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = SpiceNavy,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onSkip) {
                    Text(
                        text = stringResource(R.string.card_skip),
                        color = Color(0xFF6B7280),
                        fontWeight = FontWeight.Medium,
                    )
                }
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpiceBlue,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(stringResource(R.string.banner_action_start), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
