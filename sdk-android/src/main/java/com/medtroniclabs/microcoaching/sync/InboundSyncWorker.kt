package com.medtroniclabs.microcoaching.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.network.NetworkModule

/**
 * WorkManager worker that fetches updated scenarios and quiz questions from the backend.
 *
 * Calls `GET /scenarios/sync?since_version={cursor}` where the cursor is the last
 * successfully received bundle version (stored in [SyncPrefs.lastSyncVersion]).
 * The backend returns only records newer than the cursor, enabling incremental updates.
 *
 * On success: upserts rows to Room and advances the [SyncPrefs] version cursor.
 * On network failure: returns [Result.retry()]; cursor is NOT advanced, so the
 * next attempt re-fetches from the same version.
 *
 * Scheduled by [SyncCoordinator]:
 *   - Periodic: every 15 minutes when network is available
 *   - One-shot: immediately on connectivity restore (after outbound sync completes)
 */
class InboundSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!MicroCoachingSDK.isInitialized()) {
            Log.w(TAG, "SDK not initialized — retrying inbound sync later.")
            return Result.retry()
        }

        val sdk = MicroCoachingSDK.getInstance()
        val config = sdk.config

        if (config.backendUrl.isBlank()) {
            Log.d(TAG, "backendUrl not configured — skipping inbound sync.")
            return Result.success()
        }

        val db = MicroCoachingDatabase.getInstance(applicationContext)
        val syncPrefs = SyncPrefs(applicationContext)
        val apiService = NetworkModule.createApiService(config)
        // chwId is only used for logging here — the per-call chwId is forwarded
        // explicitly to pullGaps below as a nullable param so the backend can
        // return taxonomy-only when no CHW is signed in.
        val syncApi = SyncApi(
            apiService = apiService,
            db = db,
            sessionId = "inbound-sync",
            chwId = sdk.currentCHWId.orEmpty(),
            syncPrefs = syncPrefs,
        )

        // v3 sync — four independent resource pulls. Each is non-fatal so one
        // 404/500 doesn't block the others. Worker returns retry only if every
        // pull failed and every failure was a transient network error — see
        // shouldRetryInbound below for the rationale.
        // Periodically force a full-catalogue fetch so terminally-retired modules
        // get reconciled out of the cache — an incremental delta can never carry a
        // retirement (the family just stops appearing). Bounded to once per
        // interval so steady-state syncs stay incremental / low-bandwidth.
        val now = System.currentTimeMillis()
        val reconcileDue = now - syncPrefs.lastModulesReconcileAt >= MODULES_RECONCILE_INTERVAL_MS
        val modulesResult = syncApi.pullModules(
            syncPrefs.modulesWatermark,
            forceFullCatalogue = reconcileDue,
        )
        if (modulesResult.success) {
            modulesResult.newWatermark?.let { syncPrefs.modulesWatermark = it }
            if (reconcileDue) syncPrefs.lastModulesReconcileAt = now
            if (modulesResult.prunedCount > 0) {
                Log.i(TAG, "Modules reconcile pruned ${modulesResult.prunedCount} stale row(s).")
            }
        } else {
            Log.w(TAG, "Modules sync failed (non-fatal): ${modulesResult.error}")
        }

        // Refresh presigned thumbnail URLs immediately after modules so any
        // freshly-created/REPLACEd module row gets its thumbnail repopulated in
        // the same pass. Non-fatal and not part of the retry predicate below.
        val thumbnailsResult = syncApi.pullModuleThumbnails()
        if (!thumbnailsResult.success) {
            Log.w(TAG, "Module thumbnails sync failed (non-fatal): ${thumbnailsResult.error}")
        }

        val docThumbnailsResult = syncApi.pullSourceDocumentThumbnails()
        if (!docThumbnailsResult.success) {
            Log.w(TAG, "Source-doc thumbnails sync failed (non-fatal): ${docThumbnailsResult.error}")
        }

        // Published source-document catalogue — backs the Knowledge section.
        // Non-fatal and not part of the retry predicate: on failure the previous
        // catalogue stays intact so the grid still renders offline.
        val publishedDocsResult = syncApi.pullPublishedSourceDocuments()
        if (!publishedDocsResult.success) {
            Log.w(TAG, "Published source-docs sync failed (non-fatal): ${publishedDocsResult.error}")
        }

        val gapsResult = syncApi.pullGaps(syncPrefs.gapsWatermark, chwId = sdk.currentCHWId)
        if (gapsResult.success) {
            gapsResult.newWatermark?.let { syncPrefs.gapsWatermark = it }
            // Partial-completion rows feed the to-reinforce set used by the
            // morning-cards filter — re-apply it so a fresh-device CHW sees
            // the right modules surface before they answer anything locally.
            if (gapsResult.partialUpserted > 0) {
                sdk.currentCHWId?.let { chwId -> sdk.refilterMorningModules(chwId) }
            }
        } else {
            Log.w(TAG, "Gaps sync failed (non-fatal): ${gapsResult.error}")
        }

        val triggersResult = syncApi.pullTriggers(syncPrefs.triggersWatermark)
        if (triggersResult.success) {
            triggersResult.newWatermark?.let { syncPrefs.triggersWatermark = it }
        } else {
            Log.w(TAG, "Triggers sync failed (non-fatal): ${triggersResult.error}")
        }

        val configResult = syncApi.pullConfig()
        if (configResult.success) {
            configResult.newWatermark?.let { syncPrefs.configWatermark = it }
        } else {
            Log.w(TAG, "Config sync failed (non-fatal): ${configResult.error}")
        }

        val pulls = listOf(modulesResult, gapsResult, triggersResult, configResult)
        if (shouldRetryInbound(pulls)) {
            Log.w(TAG, "All v3 sync pulls failed with transient network errors — retrying.")
            return Result.retry()
        }
        val anyPermanentFailure = pulls.any { !it.success && it.errorKind != SyncErrorKind.NETWORK }
        if (anyPermanentFailure) {
            // Permanent failures (HTTP 4xx, deserialization) won't fix themselves on
            // backoff retries — let the next periodic worker run try again instead of
            // burning battery here.
            Log.w(TAG, "Some v3 sync pulls failed permanently — deferring to next scheduled run.")
        }

        // Morning cards — non-fatal; on failure the previous cache stays intact.
        val morningResult = syncApi.pullMorningCards(
            chwId = sdk.currentCHWId,
            tenantId = config.tenantId.takeIf { it.isNotBlank() },
        )
        if (!morningResult.success) {
            Log.w(TAG, "Morning cards sync failed (non-fatal): ${morningResult.error}")
        }

        syncPrefs.lastInboundSyncAt = System.currentTimeMillis()
        Log.i(
            TAG,
            "Inbound sync complete. modules=${modulesResult.upsertedCount}, " +
                "assigned=${modulesResult.assignedCount}, " +
                "thumbnails=${thumbnailsResult.updatedCount}, " +
                "publishedDocs=${publishedDocsResult.count}, " +
                "gaps=${gapsResult.upsertedCount}, " +
                "triggers=${triggersResult.triggerCount} bindings=${triggersResult.bindingCount}, " +
                "config=${configResult.upsertedCount}, morningCards=${morningResult.count}.",
        )
        return Result.success()
    }

    /**
     * Retry only when every pull failed AND every failure was a transient network
     * error (IOException family). A mix of HTTP 4xx, HTTP 5xx, deserialization
     * crashes, etc. is treated as permanent for retry purposes — these don't
     * resolve under exponential backoff, and burning battery against a
     * misconfigured backend is worse than waiting for the next 15-minute tick.
     */
    private fun shouldRetryInbound(pulls: List<SyncResult>): Boolean =
        pulls.isNotEmpty() && pulls.all { !it.success && it.errorKind == SyncErrorKind.NETWORK }

    companion object {
        const val TAG = "InboundSyncWorker"
        const val WORK_NAME = "micro_coaching_inbound_sync"

        /**
         * How often to force a full-catalogue modules fetch for retirement
         * reconcile. The worker itself ticks every ~15 min; this throttles the
         * heavier full pull to once a day so a retired module disappears within a
         * day without paying full-bundle bandwidth on every tick.
         */
        const val MODULES_RECONCILE_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
