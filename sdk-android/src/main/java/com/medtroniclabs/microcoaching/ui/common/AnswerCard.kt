package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Visual state of a multiple-choice answer card.
 *
 * Transitions:
 *   [Unselected] → tap → [Selected]
 *   After answer is submitted: [Selected] becomes [CorrectRevealed] or [WrongRevealed];
 *   the actual correct answer is shown as [CorrectRevealed] regardless of selection.
 */
enum class AnswerCardState {
    Unselected,
    Selected,
    CorrectRevealed,
    WrongRevealed,
}

/**
 * A tappable answer card that animates between visual states.
 *
 * @param text Answer text.
 * @param state Visual state controlling colour and border.
 * @param onClick Invoked when the card is tapped. Should be a no-op after the answer is revealed.
 * @param index 0-based position used to render the circular A/B/C/D badge on the left. Pass -1 to
 *   hide the badge (legacy back-compat).
 * @param unselectedContainerColor Fill for the [AnswerCardState.Unselected] state. Defaults to
 *   white (the standard full-screen quiz look); the refresher sheet passes a soft surface tint so
 *   options read against its white background.
 */
@Composable
fun AnswerCard(
    text: String,
    state: AnswerCardState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = -1,
    unselectedContainerColor: Color = Color.White,
) {
    val containerColor by animateColorAsState(
        targetValue = when (state) {
            AnswerCardState.Unselected -> unselectedContainerColor
            AnswerCardState.Selected -> MaterialTheme.colorScheme.primaryContainer
            AnswerCardState.CorrectRevealed -> Color(0xFFD7F0E5)
            AnswerCardState.WrongRevealed -> Color(0xFFFFEBEE)
        },
        animationSpec = tween(durationMillis = 250),
        label = "answer_card_bg",
    )

    val borderColor by animateColorAsState(
        targetValue = when (state) {
            AnswerCardState.Unselected -> Color(0xFFDDDDDD)
            AnswerCardState.Selected -> MaterialTheme.colorScheme.primary
            AnswerCardState.CorrectRevealed -> Color(0xFF1B6B4A)
            AnswerCardState.WrongRevealed -> Color(0xFFB00020)
        },
        animationSpec = tween(durationMillis = 250),
        label = "answer_card_border",
    )

    val textColor = when (state) {
        AnswerCardState.Unselected -> Color(0xFF1A1A1A)
        AnswerCardState.Selected -> MaterialTheme.colorScheme.onPrimaryContainer
        AnswerCardState.CorrectRevealed -> Color(0xFF0A3D27)
        AnswerCardState.WrongRevealed -> Color(0xFF7F0014)
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(width = 1.5.dp, color = borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index >= 0) {
                AnswerLetterBadge(index = index, state = state)
                Box(modifier = Modifier.size(12.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (state == AnswerCardState.Unselected) FontWeight.Normal else FontWeight.SemiBold,
                ),
                color = textColor,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun AnswerLetterBadge(index: Int, state: AnswerCardState) {
    val letter = ('A' + index).toString()

    val badgeBg = when (state) {
        AnswerCardState.CorrectRevealed -> Color(0xFF1B6B4A)
        AnswerCardState.WrongRevealed -> Color(0xFFB00020)
        AnswerCardState.Selected -> MaterialTheme.colorScheme.primary
        AnswerCardState.Unselected -> Color.Transparent
    }
    val badgeBorderColor = when (state) {
        AnswerCardState.Unselected -> Color(0xFFAAAAAA)
        AnswerCardState.Selected -> MaterialTheme.colorScheme.primary
        AnswerCardState.CorrectRevealed -> Color(0xFF1B6B4A)
        AnswerCardState.WrongRevealed -> Color(0xFFB00020)
    }
    val letterColor = when (state) {
        AnswerCardState.Unselected -> Color(0xFF555555)
        else -> Color.White
    }

    Box(
        modifier = Modifier
            .size(30.dp)
            .background(badgeBg, CircleShape)
            .border(1.5.dp, badgeBorderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (state == AnswerCardState.CorrectRevealed) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Text(
                text = letter,
                color = letterColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
