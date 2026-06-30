package com.medtroniclabs.microcoaching.progress

import android.util.Log
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.mapper.decodeIncompleteQuizIds

private const val TAG = "ToReinforceResolver"

/**
 * Resolves the per-module set of quiz question IDs the CHW still needs to
 * answer correctly ("to reinforce"). Merges the server's authoritative
 * `chw_module_partial_completion.incomplete_quiz_ids` with the CHW's local
 * `coaching_event` history so:
 *
 *   - Cross-device recovery works (server's set seeds the unanswered-locally
 *     questions on a fresh device).
 *   - Local progress is reflected immediately (any question whose latest local
 *     answer is correct is removed; any question whose latest local answer is
 *     wrong is added — even if the server doesn't know yet).
 *
 * Formula:
 *     baseline      = if (partial row exists) serverIncomplete else allQuestionIds
 *     toReinforce   = ((baseline ∪ localWrong) − localCorrect) ∩ allQuestionIds
 *
 * The **baseline branch** is load-bearing: when no partial row exists for a
 * module (fresh CHW, never-attempted module, or backend hasn't computed one
 * yet), the SDK has zero information about which questions are server-known
 * to be incomplete. The safe assumption — matching pre-server-merge behaviour
 * — is that **every** question is incomplete until proven otherwise by a
 * local `coaching_event` row marking it correct. Treating an absent partial
 * row as "no incomplete questions" would silently mark every fresh module as
 * mastered → broken QuickLearn banner, missing refresher tiles, and a
 * fresh-CHW fallback that can't find any candidate to surface.
 *
 * The intersection with [allQuestionIds] keeps stale IDs (e.g. questions
 * removed in a newer module version) from leaking into the UI.
 *
 * Defensive against an undocumented backend semantic: even if the server
 * never removes a question from `incomplete_quiz_ids` after the CHW answers
 * it correctly, the local-correct subtraction keeps the UI in sync.
 */
suspend fun toReinforceQuestionIds(
    db: MicroCoachingDatabase,
    chwId: String,
    moduleFamilyId: String,
    allQuestionIds: Set<String>,
): Set<String> {
    if (allQuestionIds.isEmpty()) return emptySet()

    val coachingEventDao = db.coachingEventDao()
    val localCorrect = coachingEventDao.getLatestCorrectQuestionIds(chwId, moduleFamilyId).toSet()
    val localWrong = coachingEventDao.getLatestWrongQuestionIds(chwId, moduleFamilyId).toSet()
    val partial = db.chwModulePartialCompletionDao().get(chwId, moduleFamilyId)
    val baseline: Set<String> = if (partial != null) {
        partial.decodeIncompleteQuizIds().toSet()
    } else {
        allQuestionIds
    }

    val toReinforce = ((baseline + localWrong) - localCorrect) intersect allQuestionIds

    Log.d(
        TAG,
        "module=$moduleFamilyId allQ=${allQuestionIds.size} " +
            "baseline=${baseline.size}(${if (partial != null) "serverIncomplete" else "assumed-all"}) " +
            "localCorrect=${localCorrect.size} localWrong=${localWrong.size} " +
            "→ toReinforce=${toReinforce.size}",
    )

    return toReinforce
}

/**
 * Pure (no-DB) variant of [toReinforceQuestionIds] — same formula, but the caller
 * supplies the already-fetched inputs. Used by [com.medtroniclabs.microcoaching.ui.learn.LearnViewModel.mapModules]
 * so a screen full of modules doesn't re-query each module's latest-correct /
 * latest-wrong / partial rows that it already loaded.
 *
 * [serverIncomplete] = the partial row's decoded incomplete-quiz ids, or null
 * when there's no partial row (→ baseline-all, same as the DB variant).
 */
fun toReinforceQuestionIds(
    allQuestionIds: Set<String>,
    localCorrect: Set<String>,
    localWrong: Set<String>,
    serverIncomplete: Set<String>?,
): Set<String> {
    if (allQuestionIds.isEmpty()) return emptySet()
    val baseline = serverIncomplete ?: allQuestionIds
    return ((baseline + localWrong) - localCorrect) intersect allQuestionIds
}
