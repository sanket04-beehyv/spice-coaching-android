package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.ui.chat.ChatMessage
import com.medtroniclabs.microcoaching.ui.chat.ChatRole
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownDefaults
import com.medtroniclabs.microcoaching.ui.markdown.MarkdownText
import com.medtroniclabs.microcoaching.ui.theme.AssistantBubble
import com.medtroniclabs.microcoaching.ui.theme.AssistantBubbleText
import com.medtroniclabs.microcoaching.ui.theme.UserBubble
import com.medtroniclabs.microcoaching.ui.theme.UserBubbleText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == ChatRole.USER
    val bubbleColor = if (isUser) UserBubble else AssistantBubble
    val textColor = if (isUser) UserBubbleText else AssistantBubbleText
    val alignment = if (isUser) Arrangement.End else Arrangement.Start
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = alignment,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                color = bubbleColor,
                shape = shape,
                modifier = Modifier.widthIn(max = 280.dp),
                // Drop shadow on assistant bubbles for a flatter, ai-coach.png look.
                // User bubbles keep a subtle elevation so the conversation hierarchy stays legible.
                shadowElevation = if (isUser) 1.dp else 0.dp,
            ) {
                Text(
                    text = message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Text(
                text = message.timestampMs.toTimeString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * Assistant message row with a leading sparkle avatar that matches the sheet
 * header — establishes a visual link between the speaker icon in the header
 * ("AI Coach") and each reply. Used by [com.medtroniclabs.microcoaching.ui.screens.ChatScreen]
 * for every assistant bubble (real replies, refusal messages, and the welcome
 * seed when the conversation is empty).
 */
@Composable
fun AssistantBubbleWithAvatar(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AssistantAvatar()
        Column(
            modifier = Modifier.padding(start = 8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Surface(
                color = AssistantBubble,
                shape = RoundedCornerShape(
                    topStart = 4.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp,
                ),
                modifier = Modifier.widthIn(max = 260.dp),
                shadowElevation = 0.dp,
            ) {
                // The on-device model often replies in markdown (**bold**, `*`/`-`
                // bullet lists, numbered steps). Render it through the SDK's GFM
                // renderer so the CHW sees formatted text, not raw markers. Plain
                // text (refusals, the welcome seed) flows through as a paragraph.
                MarkdownText(
                    content = message.text,
                    style = MarkdownDefaults.style(
                        textStyle = MaterialTheme.typography.bodyMedium,
                        textColor = AssistantBubbleText,
                        blockSpacing = 6.dp,
                    ),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Text(
                text = message.timestampMs.toTimeString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                fontSize = 10.sp,
            )
        }
    }
}

/** Streaming in-progress bubble shown while the LLM is generating. Mirrors
 *  [AssistantBubbleWithAvatar] so the typing indicator is anchored to the
 *  same avatar column as completed replies. */
@Composable
fun StreamingBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AssistantAvatar()
        Surface(
            color = AssistantBubble,
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp,
            ),
            modifier = Modifier
                .padding(start = 8.dp)
                .widthIn(max = 260.dp),
            shadowElevation = 0.dp,
        ) {
            if (text.isBlank()) {
                TypingDots(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp))
            } else {
                Text(
                    text = text,
                    color = AssistantBubbleText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * Three dots that light up sequentially — classic "typing" indicator.
 * Each dot is active for one third of the 900 ms cycle, cycling left-to-right.
 */
@Composable
private fun TypingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typing_phase",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0..2) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(AssistantBubbleText.copy(alpha = if (phase.toInt() == i) 1f else 0.25f)),
            )
        }
    }
}

/** Small blue square with a sparkle glyph — the AI Coach's visual identity.
 *  Shared between [AssistantBubbleWithAvatar], [StreamingBubble], and the
 *  sheet header so the icon is recognisable across the surface. */
@Composable
fun AssistantAvatar(
    size: androidx.compose.ui.unit.Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

private fun Long.toTimeString(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(this))
