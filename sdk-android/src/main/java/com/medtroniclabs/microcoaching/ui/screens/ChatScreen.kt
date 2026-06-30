package com.medtroniclabs.microcoaching.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.util.Log
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.chat.ChatMessage
import com.medtroniclabs.microcoaching.ui.chat.ChatRole
import com.medtroniclabs.microcoaching.ui.chat.ChatUiState
import com.medtroniclabs.microcoaching.ui.chat.MessageSource
import com.medtroniclabs.microcoaching.ui.chat.SuggestedQuestion
import com.medtroniclabs.microcoaching.ui.common.AssistantAvatar
import com.medtroniclabs.microcoaching.ui.common.AssistantBubbleWithAvatar
import com.medtroniclabs.microcoaching.ai.voice.stt.SttModelState
import com.medtroniclabs.microcoaching.ui.common.ChatInputBar
import com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController
import com.medtroniclabs.microcoaching.ui.screens.components.DownloadItemUiState
import com.medtroniclabs.microcoaching.ui.screens.components.ModelNotReadyContent
import com.medtroniclabs.microcoaching.ui.screens.components.RecordingBadge
import com.medtroniclabs.microcoaching.ui.screens.components.SttDownloadBanner
import com.medtroniclabs.microcoaching.ui.common.FullScreenLoader
import com.medtroniclabs.microcoaching.ui.common.MessageBubble
import com.medtroniclabs.microcoaching.ui.common.StreamingBubble
import com.medtroniclabs.microcoaching.ui.components.TranslationModelStateChip
import com.medtroniclabs.microcoaching.ui.common.ChatInputState
import com.medtroniclabs.microcoaching.ui.common.rememberChatInputState
import com.medtroniclabs.microcoaching.ui.screens.components.SourceDocChipRow

// ── Sheet tuning knobs — tweak these to reshape the chat sheet chrome ─────────
/**
 * Vertical padding above and below the AI Coach header row. Smaller = the
 * avatar / title sits closer to the drag handle. Reduce further (e.g. 2.dp)
 * if you want the header to hug the handle.
 */
private val ChatHeaderVerticalPadding = 4.dp

/**
 * Horizontal inset of the AI Coach header row from the sheet edges.
 */
private val ChatHeaderHorizontalPadding = 16.dp


@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onSendMessage: (String) -> Unit,
    onSendSuggested: (SuggestedQuestion) -> Unit,
    onRequestDownload: () -> Unit,
    onSpeakMessage: (String) -> Unit,
    onMicTap: (() -> Unit)? = null,
    onClose: () -> Unit = {},
    showCloseIcon: Boolean = false,
    onPauseDownload: () -> Unit = {},
    onResumeDownload: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    inputState: ChatInputState =
        rememberChatInputState(),
    isRecording: Boolean = false,
    sttDownloadState: SttModelState? = null,
    onRetrySttDownload: () -> Unit = {},
    onCancelSttDownload: () -> Unit = {},
    voiceModelItemState: DownloadItemUiState = DownloadItemUiState.Idle,
    onRequestVoiceDownload: () -> Unit = {},
    onCancelVoiceDownload: () -> Unit = {},
    onRequestBothDownload: () -> Unit = {},
    voiceBackend: ChatVoiceInputController.Backend? = null,
    showVoiceModelDownloadAction: Boolean = false,
    onDownloadVoiceModel: () -> Unit = {},
    networkAvailable: Boolean = true,
    moduleTitleLookup: (String?) -> String? = { null },
    onSourceDocTap: (sourceDocumentId: String, label: String, startPage: Int?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val error = (uiState as? ChatUiState.Ready)?.error
    LaunchedEffect(error) {
        if (!error.isNullOrBlank()) {
            snackbarHostState.showSnackbar(error)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (uiState) {
                is ChatUiState.Loading -> FullScreenLoader()
                is ChatUiState.ModelNotReady -> ModelNotReadyContent(
                    uiState = uiState,
                    voiceModelState = voiceModelItemState,
                    aiModelPresent = false,
                    onRequestAiDownload = onRequestDownload,
                    onPauseAiDownload = onPauseDownload,
                    onResumeAiDownload = onResumeDownload,
                    onCancelAiDownload = onCancelDownload,
                    onRequestVoiceDownload = onRequestVoiceDownload,
                    onCancelVoiceDownload = onCancelVoiceDownload,
                    onRequestBothDownload = onRequestBothDownload,
                    onClose = onClose,
                    showCloseIcon = showCloseIcon,
                )

                is ChatUiState.Ready -> ReadyChatContent(
                    uiState = uiState,
                    onSendMessage = onSendMessage,
                    onSendSuggested = onSendSuggested,
                    onSpeakMessage = onSpeakMessage,
                    onMicTap = onMicTap,
                    inputState = inputState,
                    isRecording = isRecording,
                    sttDownloadState = sttDownloadState,
                    onRetrySttDownload = onRetrySttDownload,
                    onCancelSttDownload = onCancelSttDownload,
                    voiceBackend = voiceBackend,
                    showVoiceModelDownloadAction = showVoiceModelDownloadAction,
                    onDownloadVoiceModel = onDownloadVoiceModel,
                    onClose = onClose,
                    showCloseIcon = showCloseIcon,
                    onClearHistory = onClearHistory,
                    networkAvailable = networkAvailable,
                    moduleTitleLookup = moduleTitleLookup,
                    onSourceDocTap = onSourceDocTap,
                )

                is ChatUiState.Error -> ErrorContent(message = uiState.message)
            }
        }
    }
}

@Composable
private fun ReadyChatContent(
    uiState: ChatUiState.Ready,
    onSendMessage: (String) -> Unit,
    onSendSuggested: (SuggestedQuestion) -> Unit,
    onSpeakMessage: (String) -> Unit,
    onMicTap: (() -> Unit)?,
    inputState: ChatInputState,
    isRecording: Boolean,
    sttDownloadState: SttModelState?,
    onRetrySttDownload: () -> Unit,
    onCancelSttDownload: () -> Unit,
    voiceBackend: ChatVoiceInputController.Backend?,
    showVoiceModelDownloadAction: Boolean,
    onDownloadVoiceModel: () -> Unit,
    onClose: () -> Unit,
    showCloseIcon: Boolean,
    onClearHistory: () -> Unit,
    networkAvailable: Boolean,
    moduleTitleLookup: (String?) -> String?,
    onSourceDocTap: (String, String, Int?) -> Unit,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to the latest item. The +1 accounts for the streaming bubble
    // when generation is in flight. We do NOT include the welcome bubble in this
    // count because it sits above real messages and never animates.
    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        val realCount = uiState.messages.size + (if (uiState.isGenerating) 1 else 0)
        if (realCount > 0) {
            // +1 for the welcome bubble (always rendered as item 0); list indices
            // are stable so this lands on the last real bubble.
            listState.animateScrollToItem(realCount)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatSheetHeader(
            onClose = onClose,
            showCloseIcon = showCloseIcon,
            onClearHistory = onClearHistory,
            showVoiceModelDownloadAction = showVoiceModelDownloadAction,
            onDownloadVoiceModel = onDownloadVoiceModel,
            networkAvailable = networkAvailable,
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        // Translation pack status — slim row below the header; only renders when
        // SDK lang=Bangla and the BN pack is downloading or failed.
        TranslationModelStateChip(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // Today pill — single date marker for now. Real day-boundary logic is
            // a follow-up; the design only shows one entry.
            item { TodayPill() }

            // Welcome seed — UI-only assistant bubble shown above any real
            // messages so the surface always has a friendly opener. Not
            // persisted as a ChatMessage and emits no telemetry.
            //
            // Gated on `messages.isEmpty()`: returning users with restored
            // history see their conversation pick up where it left off without
            // a redundant greeting. Re-appears after the user taps Clear chat.
            if (uiState.messages.isEmpty() && !uiState.isGenerating) {
                item {
                    AssistantBubbleWithAvatar(
                        message = ChatMessage(
                            sessionId = "ui-welcome",
                            role = ChatRole.ASSISTANT,
                            text = stringResource(R.string.chat_welcome_message),
                            source = MessageSource.LOCAL_MODEL,
                        ),
                    )
                }
            }

            items(items = uiState.messages, key = { it.id }) { message ->
                when (message.role) {
                    ChatRole.ASSISTANT -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            AssistantBubbleWithAvatar(message = message)
                            if (message.sourceDocuments.isNotEmpty()) {
                                // Short italic citation line — module title or first doc title
                              /*  val citationText = moduleTitleLookup(message.groundingModuleFamilyId)
                                    ?: message.sourceDocuments.firstOrNull()
                                        ?.title?.takeIf { it.isNotBlank() }
                                if (citationText != null) {
                                    Text(
                                        text = "— $citationText",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontStyle = FontStyle.Italic,
                                        ),
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                        modifier = Modifier.padding(
                                            start = 56.dp,
                                            top = 2.dp,
                                            end = 12.dp,
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } */
                                SourceDocChipRow(
                                    sourceDocuments = message.sourceDocuments,
                                    moduleTitle = moduleTitleLookup(message.groundingModuleFamilyId),
                                    onTap = onSourceDocTap,
                                    startPage = message.startPage,
                                    modifier = Modifier.padding(start = 56.dp, end = 12.dp),
                                )
                            }
                            Row(
                                modifier = Modifier.padding(start = 56.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = { onSpeakMessage(message.text) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = stringResource(R.string.chat_speak),
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                                    )
                                }
                            }
                        }
                    }

                    else -> MessageBubble(message = message)
                }
            }

            if (uiState.isGenerating) {
                item { StreamingBubble(text = uiState.streamingText) }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        // Suggestion chips live above the input (per ai-coach.png) — NOT inside
        // the message list anymore. They surface for as long as the source data
        // has chips to offer; ChatViewModel decides when to refresh them.
        if (uiState.suggestedQuestions.isNotEmpty() && !uiState.isGenerating) {
            SuggestionRow(
                questions = uiState.suggestedQuestions,
                onSendSuggested = onSendSuggested,
            )
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        if (sttDownloadState != null) {
            SttDownloadBanner(
                state = sttDownloadState,
                onRetry = onRetrySttDownload,
                onCancel = onCancelSttDownload,
            )
        }

        // Animated "Listening…" pill — visible only while the mic is active.
        // The active backend (Google on-device / Google cloud / offline
        // sherpa-onnx) isn't actionable for the CHW, so we just log it for
        // debugging and surface a friendly recording indicator instead. The
        // explicit SttBackendBadge composable is preserved for hosts that
        // want the routing detail somewhere else.
        if (isRecording) {
            LaunchedEffect(voiceBackend) {
                if (voiceBackend != null &&
                    voiceBackend != ChatVoiceInputController.Backend.Unknown
                ) {
                    Log.d(
                        "ChatScreen",
                        "STT recording started — backend=$voiceBackend",
                    )
                }
            }
            RecordingBadge()
        }

        ChatInputBar(
            onSend = onSendMessage,
            enabled = !uiState.isGenerating,
            onMicTap = onMicTap,
            inputState = inputState,
            isRecording = isRecording,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}


/**
 * Header row for the chat sheet — avatar, title, online dot, optional close icon.
 * Matches `docs/designs/ai-coach.png`.
 */
@Composable
private fun ChatSheetHeader(
    onClose: () -> Unit,
    showCloseIcon: Boolean,
    onClearHistory: () -> Unit,
    showVoiceModelDownloadAction: Boolean = false,
    onDownloadVoiceModel: () -> Unit = {},
    networkAvailable: Boolean = true,
) {
    // Two local toggles power the overflow flow:
    //   - `showOverflow`: anchors the kebab dropdown to the kebab IconButton
    //   - `showConfirm`: gates the destructive AlertDialog so the user has to
    //     explicitly confirm before chat history is wiped.
    var showOverflow by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ChatHeaderHorizontalPadding,
                vertical = ChatHeaderVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Square avatar — slightly larger than the in-bubble avatar to read as
        // an identity badge rather than a per-message glyph.
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
        ) {
            Text(
                text = stringResource(R.string.chat_header_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = if (networkAvailable) {
                    Color(0xFF2E7D32)
                } else {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                }
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.size(width = 6.dp, height = 0.dp))
                Text(
                    text = if (networkAvailable) {
                        stringResource(R.string.chat_header_online)
                    } else {
                        stringResource(R.string.chat_header_offline)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                )
            }
        }
        if (showCloseIcon) {
            // Overflow kebab + dropdown anchored to it. The dropdown only
            // surfaces destructive actions (Clear chat) — split here from the
            // close button so a stray tap on Close doesn't expand a menu.
            Box {
                IconButton(onClick = { showOverflow = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.chat_overflow_open_menu),
                        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    )
                }
                DropdownMenu(
                    expanded = showOverflow,
                    onDismissRequest = { showOverflow = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_overflow_clear_history)) },
                        onClick = {
                            showOverflow = false
                            showConfirm = true
                        },
                    )
                    // Visible only when the SDK is in Bengali mode and the offline
                    // voice model isn't already present or in flight — see the
                    // visibility gate in CoachingChatSurface for the full predicate.
                    if (showVoiceModelDownloadAction) {
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.chat_overflow_download_voice_model))
                            },
                            onClick = {
                                showOverflow = false
                                onDownloadVoiceModel()
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.chat_close_sheet),
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.chat_clear_history_dialog_title)) },
            text = { Text(stringResource(R.string.chat_clear_history_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onClearHistory()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.chat_clear_history_dialog_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.chat_clear_history_dialog_cancel))
                }
            },
        )
    }
}

/**
 * Single date marker between the header and the message list — matches the
 * "Today" pill in `ai-coach.png`. Real day-boundary logic (split history by
 * date) is a follow-up; the design only shows one pill.
 */
@Composable
private fun TodayPill() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_today),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Horizontally scrollable suggestion chip row pinned above the input. Replaces
 * the prior in-list vertical chip stack to match `ai-coach.png` and to keep
 * suggestions reachable without losing scroll position in the message list.
 */
@Composable
private fun SuggestionRow(
    questions: List<SuggestedQuestion>,
    onSendSuggested: (SuggestedQuestion) -> Unit,
) {
    // Default to BANGLA in Compose previews (no initialised SDK there).
    val sdkLanguage = if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        Language.BANGLA
    } else {
        MicroCoachingSDK.getInstance().language
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        // Key includes the list index because two suggestions can carry identical
        // text — e.g. when a Bangla session emits a chip whose English `question`
        // field was copied from `banglaQuestion` because translation hasn't landed
        // yet (or moduleFamilyId-derived dedup didn't run). Without the prefix
        // index the LazyRow throws `IllegalArgumentException: Key … was already
        // used` and brings down the whole compose tree.
        itemsIndexed(
            items = questions,
            key = { idx, q -> "$idx:${q.banglaQuestion}:${q.question}" },
        ) { _, q ->
            val displayText = when (sdkLanguage) {
                Language.ENGLISH -> q.question.ifBlank { q.banglaQuestion }
                Language.BANGLA -> q.banglaQuestion.ifBlank { q.question }
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onSendSuggested(q) },
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.common_error_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}
