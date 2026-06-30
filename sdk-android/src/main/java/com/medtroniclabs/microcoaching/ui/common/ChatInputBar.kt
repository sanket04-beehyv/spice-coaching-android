package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R

/**
 * Chat input row with text field, optional mic button, and send button.
 *
 * The mic button is rendered when [onMicTap] is non-null. Hosts that don't
 * want a mic can pass `null`; SDK callers (e.g. [com.medtroniclabs.microcoaching.ui.chat.CoachingChatFragment])
 * always pass a real handler that forwards to the configured
 * [com.medtroniclabs.microcoaching.ai.voice.VoiceInputController].
 *
 * The text field's input is exposed via the [externalText] companion of state
 * so transcription results can be set programmatically — pass the same
 * `inputState` instance to [ChatInputBar] from a parent and call
 * `inputState.setText(...)` from the mic transcription callback.
 */
@Composable
fun ChatInputBar(
    onSend: (String) -> Unit,
    enabled: Boolean = true,
    placeholder: String = stringResource(R.string.chat_input_placeholder),
    onMicTap: (() -> Unit)? = null,
    inputState: ChatInputState = rememberChatInputState(),
    isRecording: Boolean = false,
    modifier: Modifier = Modifier,
) {
    fun submit() {
        // Stop the mic first so the recognizer doesn't repopulate the field
        // with a late onResult after we clear it below.
        if (isRecording) onMicTap?.invoke()
        if (inputState.text.isNotBlank()) {
            onSend(inputState.text.trim())
            inputState.text = ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = inputState.text,
            onValueChange = { inputState.text = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            },
            enabled = enabled,
            readOnly = isRecording,
            singleLine = false,
            maxLines = 4,
            shape = RoundedCornerShape(40.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = if (isRecording) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        if (onMicTap != null) {
            // Recording uses the error-container palette so the active state is
            // visually distinct from the idle primary-tinted button. Matches the
            // pattern in the reference Compose impl this is derived from.
            val micBackground = if (isRecording) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
            val micTint = if (isRecording) {
                MaterialTheme.colorScheme.onError
            } else {
                MaterialTheme.colorScheme.onPrimary
            }
            IconButton(
                // Allow tapping the mic to STOP recording even while the input
                // field is rendered as `enabled = false` upstream (e.g. while
                // LLM generation is in flight) — otherwise a user couldn't end
                // their dictation.
                onClick = onMicTap,
                enabled = enabled || isRecording,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(micBackground),
            ) {
                Icon(
                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = stringResource(
                        if (isRecording) {
                            R.string.chat_voice_tap_to_stop
                        } else {
                            R.string.chat_voice_input_hint
                        },
                    ),
                    tint = micTint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        IconButton(
            onClick = { submit() },
            enabled = enabled && inputState.text.isNotBlank(),
            modifier = Modifier
                .padding(start = 8.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (enabled && inputState.text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    }
                ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.chat_send_message),
                tint = if (enabled && inputState.text.isNotBlank()) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Holder for the chat input field. Hoist this in a parent if you need to
 * push a transcription result into the input from outside (e.g. STT callback).
 */
class ChatInputState(initial: String = "") {
    var text by mutableStateOf(initial)
    fun append(suffix: String) {
        text = if (text.isBlank()) suffix else "$text $suffix"
    }
}

@Composable
fun rememberChatInputState(initial: String = ""): ChatInputState =
    rememberSaveable(saver = androidx.compose.runtime.saveable.Saver(
        save = { it.text },
        restore = { ChatInputState(it) },
    )) { ChatInputState(initial) }
