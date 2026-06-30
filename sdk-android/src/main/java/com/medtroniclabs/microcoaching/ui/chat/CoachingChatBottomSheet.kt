package com.medtroniclabs.microcoaching.ui.chat

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.medtroniclabs.microcoaching.ui.screens.ChatScreen
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme

/**
 * Bottom-sheet wrapper around [CoachingChatSurface] for quick CHW-AI access.
 *
 * Uses Material's [BottomSheetDialogFragment] with `Theme_Material3_Light_BottomSheetDialog`
 * — the same pattern as `RefresherQuizBottomSheet` — so the sheet:
 *   - Stops below the status bar (no FLAG_LAYOUT_NO_LIMITS)
 *   - Inherits the system scrim and rounded top corners from the theme
 *   - Bridges nested scroll natively: the `LazyColumn` inside `ChatScreen`
 *     consumes scroll deltas first via `ComposeView`'s NestedScrollingChild
 *     implementation, and only spills to `BottomSheetBehavior` when the list
 *     is at the top. This kills the "scrolling the message list up drags the
 *     sheet down" bug we saw with the prior Fragment-in-Fragment-in-ComposeView
 *     indirection.
 *
 * The previous attempt at a Compose `ModalBottomSheet` inside a transparent
 * `DialogFragment` required `FLAG_LAYOUT_NO_LIMITS` to make the scrim cover
 * the full window, which then forced the sheet itself to draw under the status
 * bar. Going back to the Material View-based sheet sidesteps that trade-off
 * entirely — same affordance, correct insets.
 *
 * Dismissal:
 *   - Tap the X icon in the header (wired via [CoachingChatSurface.onClose])
 *   - Drag down on the handle / sheet body
 *   - Tap the scrim outside the sheet
 *
 * **Host integration:** API unchanged — callers call `show(fm, …)` as before.
 */
class CoachingChatBottomSheet : BottomSheetDialogFragment() {

    override fun getTheme(): Int =
        com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            (this as? BottomSheetDialog)?.behavior?.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                // Disable drag-to-dismiss entirely. Material's BottomSheetBehavior
                // bridges drag gestures through nested-scroll, which would steal
                // upward scrolls from the chat's LazyColumn and pull the sheet
                // down. With this off:
                //   - the LazyColumn scrolls freely (no scroll-stealing)
                //   - the visual drag handle stays as a familiar affordance but
                //     is non-interactive
                //   - dismiss paths are the X icon in the header and the scrim tap
                //     (both wired by Material's default behaviour)
                isDraggable = false
            }
        }

    override fun onStart() {
        super.onStart()
        // Material3 BottomSheetDialog caps the sheet width via
        // @dimen/mtrl_bottom_sheet_dialog_max_width on large screens (tablets),
        // producing the wide side margins visible on the tablet layout. Force
        // MATCH_PARENT on the sheet container so the chat fills the full window width.
        (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.updateLayoutParams { width = ViewGroup.LayoutParams.MATCH_PARENT }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val patientId = arguments?.getString(ARG_PATIENT_ID).orEmpty()
        val systemContext = arguments?.getString(ARG_SYSTEM_CONTEXT).orEmpty()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MicroCoachingTheme {
                    Surface(
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Spacer(Modifier.height(8.dp))
                            CoachingChatSurface(
                                patientId = patientId,
                                systemContext = systemContext,
                                onClose = { dismissAllowingStateLoss() },
                                showCloseIcon = true,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "CoachingChatBottomSheet"
        private const val ARG_PATIENT_ID = "patient_id"
        private const val ARG_SYSTEM_CONTEXT = "system_context"

        /**
         * Show the sheet. Returns the tag the host can use to dismiss it.
         *
         * Idempotent: if a sheet with [TAG] is already present (e.g. a rapid
         * double-tap on the FAB, or the SDK FAB and host FAB both firing), this
         * returns without showing a second one. That guarantees one sheet → one
         * [ChatViewModel] → one [InferenceRouter], so the MediaPipe engine's
         * `.task` is never loaded twice (the multi-load native crash the router
         * guards against is not reachable across two separate router instances).
         *
         * @param patientId Optional hashed patient ID for telemetry tagging.
         * @param systemContext Optional pre-seeded system context for focused chat.
         */
        fun show(
            fm: FragmentManager,
            patientId: String = "",
            systemContext: String = "",
        ): String {
            if (fm.findFragmentByTag(TAG) != null) return TAG
            val sheet = CoachingChatBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PATIENT_ID, patientId)
                    putString(ARG_SYSTEM_CONTEXT, systemContext)
                }
            }
            sheet.show(fm, TAG)
            return TAG
        }
    }
}

// ── Compose Previews ─────────────────────────────────────────────────────────
//
// Renders the chat sheet *content* (drag handle + ChatScreen) inside a Surface
// shaped like the live bottom sheet — same rounded top corners, same background.
// We don't actually invoke `BottomSheetDialogFragment` in the preview because
// the dialog isn't a Composable and its animations need a real Activity.
//
// `TranslationModelStateChip` and `SuggestionRow` short-circuit when
// `LocalInspectionMode.current` is true, so the preview renders without an
// initialised SDK singleton.
//
// To debug:
//   1. Open this file in Android Studio
//   2. Switch to "Split" or "Design" view
//   3. Tweak the tuning knobs at the top of ChatScreen.kt
//      (ChatHeaderVerticalPadding, ChatDragHandleVerticalPadding, etc.)
//   4. Click "Build & Refresh" in the preview pane

@Preview(
    name = "Sheet — populated",
    showBackground = true,
    backgroundColor = 0xFFCCCCCC,
    widthDp = 360,
    heightDp = 720,
)
@Composable
private fun ChatSheetPopulatedPreview() {
    MicroCoachingTheme {
        ChatSheetPreviewFrame {
            ChatScreen(
                uiState = ChatUiState.Ready(
                    messages = listOf(
                        ChatMessage(
                            id = 1,
                            sessionId = "preview",
                            role = ChatRole.USER,
                            text = "What should I advise for a PW with BP 145/95?",
                        ),
                        ChatMessage(
                            id = 2,
                            sessionId = "preview",
                            role = ChatRole.ASSISTANT,
                            text = "Encourage rest and follow-up at the next ANC visit. " +
                                "If BP stays above 140/90 across two readings, refer to the upazila health complex.",
                            source = MessageSource.LOCAL_MODEL,
                        ),
                    ),
                    modelPresent = true,
                    suggestedQuestions = listOf(
                        SuggestedQuestion(
                            question = "What should I advise for a PW with Low BP 90/60?",
                            banglaQuestion = "নিম্ন রক্তচাপ ৯০/৬০ গর্ভবতী মহিলার জন্য কী পরামর্শ দেব?",
                        ),
                        SuggestedQuestion(
                            question = "How can breastfeeding help?",
                            banglaQuestion = "স্তন্যপান কীভাবে সাহায্য করে?",
                        ),
                        SuggestedQuestion(
                            question = "Pregnancy danger signs",
                            banglaQuestion = "গর্ভাবস্থার বিপদের লক্ষণ",
                        ),
                    ),
                ),
                onSendMessage = {},
                onSendSuggested = {},
                onRequestDownload = {},
                onSpeakMessage = {},
                onClose = {},
                showCloseIcon = true,
            )
        }
    }
}

@Preview(
    name = "Sheet — empty (welcome only)",
    showBackground = true,
    backgroundColor = 0xFFCCCCCC,
    widthDp = 360,
    heightDp = 720,
)
@Composable
private fun ChatSheetEmptyPreview() {
    MicroCoachingTheme {
        ChatSheetPreviewFrame {
            ChatScreen(
                uiState = ChatUiState.Ready(
                    messages = emptyList(),
                    modelPresent = true,
                    suggestedQuestions = listOf(
                        SuggestedQuestion(
                            question = "What should I advise for a PW with Low BP 90/60?",
                            banglaQuestion = "নিম্ন রক্তচাপ ৯০/৬০ গর্ভবতী মহিলার জন্য কী পরামর্শ দেব?",
                        ),
                        SuggestedQuestion(
                            question = "How can breastfeeding help?",
                            banglaQuestion = "স্তন্যপান কীভাবে সাহায্য করে?",
                        ),
                    ),
                ),
                onSendMessage = {},
                onSendSuggested = {},
                onRequestDownload = {},
                onSpeakMessage = {},
                onClose = {},
                showCloseIcon = true,
            )
        }
    }
}

@Preview(
    name = "Sheet — model downloading",
    showBackground = true,
    backgroundColor = 0xFFCCCCCC,
    widthDp = 360,
    heightDp = 720,
)
@Composable
private fun ChatSheetDownloadingPreview() {
    MicroCoachingTheme {
        ChatSheetPreviewFrame {
            ChatScreen(
                uiState = ChatUiState.ModelNotReady(
                    downloadProgress = 42,
                    isDownloading = true,
                ),
                onSendMessage = {},
                onSendSuggested = {},
                onRequestDownload = {},
                onSpeakMessage = {},
                onClose = {},
                showCloseIcon = true,
            )
        }
    }
}

/**
 * Wraps preview content in a Surface that mimics the live bottom sheet — same
 * rounded top corners, same background. Used by every chat-sheet preview in this file.
 */
@Composable
private fun ChatSheetPreviewFrame(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
