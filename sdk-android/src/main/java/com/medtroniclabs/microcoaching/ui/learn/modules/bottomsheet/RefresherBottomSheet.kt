package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.flow.CoachingFlowActivity
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Bottom sheet for the morning refresher experience.
 *
 * Supports two entry modes controlled by [EntryMode]:
 *
 * - [EntryMode.CARDS_FIRST] (default): **every** refresher entry point — the home
 *   `MorningCard`, the modules-screen `QuizRefresherCard` banner, and the
 *   `RefresherList`. Lesson cards → quiz questions → Done (the list/banner flows
 *   then offer "Next Refresher"; the home card does not).
 *
 * - [EntryMode.QUESTION_FIRST] (quiz-first): retained as an explicit opt-in for a
 *   future quiz-first flow; no caller uses it today.
 *
 * The [fromHomeScreen] flag (true only for the home `MorningCard`) suppresses the
 * "Next Refresher" CTA and drives the morning-card dismiss on completion.
 */
class RefresherBottomSheet : BottomSheetDialogFragment() {

    enum class EntryMode { QUESTION_FIRST, CARDS_FIRST }

    override fun getTheme(): Int =
        com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog

    override fun onCreateDialog(savedInstanceState: Bundle?) =
        super.onCreateDialog(savedInstanceState).apply {
            (this as? BottomSheetDialog)?.behavior?.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val chwId = arguments?.getString(ARG_CHW_ID) ?: CoachingFlowActivity.FALLBACK_CHW_ID
        val fromHomeScreen = arguments?.getBoolean(ARG_FROM_HOME_SCREEN, false) ?: false
        val entryModeName = arguments?.getString(ARG_ENTRY_MODE) ?: EntryMode.CARDS_FIRST.name
        val entryMode = EntryMode.valueOf(entryModeName)
        val targetModuleFamilyId = arguments?.getString(ARG_TARGET_MODULE_FAMILY_ID)
        val queueFamilyIds = arguments?.getStringArrayList(ARG_QUEUE_FAMILY_IDS).orEmpty()

        val viewModel = ViewModelProvider(
            this,
            QuickLearnViewModel.factory(requireContext().applicationContext, chwId),
        )[QuickLearnViewModel::class.java]

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SdkLocalizedTheme {
                    // White sheet surface (rounded top to match the dialog),
                    // overriding the Material bottom-sheet default tint. Quiz
                    // options inside use a soft surface tint so they read against
                    // this white background.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(Color.White),
                    ) {
                        RefresherContent(
                            viewModel = viewModel,
                            fromHomeScreen = fromHomeScreen,
                            entryMode = entryMode,
                            targetModuleFamilyId = targetModuleFamilyId,
                            onDismiss = { dismissAllowingStateLoss() },
                            modifier = Modifier.fillMaxSize(),
                            queueFamilyIds = queueFamilyIds,
                        )
                    }
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        // Every dismissal path (Done CTA, swipe-down, tap-outside) funnels here.
        // Three surfaces need refreshing after a successful quiz:
        //   1) Refresher tile + QuizRefresherCard on ModulesScreen — driven by
        //      the ACTIVITY-scoped LearnViewModel (the one ModulesScreen
        //      observes), NOT `quickLearnVm.learnViewModel` (that's a
        //      fragment-scoped instance about to be destroyed with the sheet).
        //   2) The SPICE-side MorningCard — driven by SDK.morningModules,
        //      refiltered by sdk.refilterMorningModules.
        //   3) The deferred outbound+inbound sync (events were written to
        //      Room during the quiz with `deferSync = true`; here is where we
        //      actually push them and pull the updated partial-completions).
        val sdk = MicroCoachingSDK.getInstance()
        val chwId = sdk.currentCHWId.orEmpty()
        val activityVm = ViewModelProvider(
            requireActivity(),
            LearnViewModel.factory(requireContext().applicationContext, chwId),
        )[LearnViewModel::class.java]
        activityVm.refreshModuleCounts()
        if (chwId.isNotBlank()) {
            MainScope().launch { sdk.refilterMorningModules(chwId) }
        }
        sdk.flushTelemetryNow()
        sdk.syncCoordinator.triggerNow()
        super.onDismiss(dialog)
    }

    companion object {
        const val TAG = "RefresherBottomSheet"
        private const val ARG_CHW_ID = "chw_id"
        private const val ARG_FROM_HOME_SCREEN = "from_home_screen"
        private const val ARG_ENTRY_MODE = "entry_mode"
        private const val ARG_TARGET_MODULE_FAMILY_ID = "target_module_family_id"
        private const val ARG_QUEUE_FAMILY_IDS = "queue_family_ids"

        /**
         * @param queueFamilyIds the validated refresher ordering currently visible
         *   on the modules screen (banner + list). The sheet chains "Next refresher"
         *   only through these, so the sheet's queue matches what the CHW saw.
         *   Empty for the home-screen flow (falls back to the full morning set).
         */
        fun show(
            fm: FragmentManager,
            chwId: String = MicroCoachingSDK.getInstance().currentCHWId ?: "",
            fromHomeScreen: Boolean = false,
            entryMode: EntryMode = EntryMode.CARDS_FIRST,
            targetModuleFamilyId: String? = null,
            queueFamilyIds: List<String> = emptyList(),
        ): String {
            val sheet = RefresherBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CHW_ID, chwId)
                    putBoolean(ARG_FROM_HOME_SCREEN, fromHomeScreen)
                    putString(ARG_ENTRY_MODE, entryMode.name)
                    targetModuleFamilyId?.let { putString(ARG_TARGET_MODULE_FAMILY_ID, it) }
                    if (queueFamilyIds.isNotEmpty()) {
                        putStringArrayList(ARG_QUEUE_FAMILY_IDS, ArrayList(queueFamilyIds))
                    }
                }
            }
            sheet.show(fm, TAG)
            return TAG
        }
    }
}
