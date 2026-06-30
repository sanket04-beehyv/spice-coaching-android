package com.medtroniclabs.microcoaching.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.network.NetworkModule

/**
 * WorkManager worker that pushes pending SDK events to the Knowledge Layer backend.
 *
 * Batches all `pending` rows from:
 *   - `coaching_event` (card interactions, quiz answers, session markers)
 *   - `llm_trace` (inference audit records)
 *   - `digital_proficiency_event` (sync and digital interaction signals)
 *
 * On success: marks rows `synced` in Room.
 * On `SyncErrorKind.NETWORK` or `SyncErrorKind.HTTP_SERVER`: returns
 * [Result.retry()] with exponential backoff (WorkManager manages the schedule).
 * On `SyncErrorKind.HTTP_CLIENT` (4xx) or `SyncErrorKind.UNEXPECTED`: returns
 * [Result.failure()] — no retry, as the failure won't fix itself.
 *
 * Always records a `sync_attempt` [DigitalProficiencyEventEntity] row via [SyncApi]
 * regardless of outcome, enabling digital proficiency signal capture (SDK-032).
 *
 * Scheduled by [SyncCoordinator]:
 *   - Periodic: every 15 minutes when network is available
 *   - One-shot: immediately on connectivity restore
 */
class OutboundSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!MicroCoachingSDK.isInitialized()) {
            Log.w(TAG, "SDK not initialized — retrying outbound sync later.")
            return Result.retry()
        }

        val sdk = MicroCoachingSDK.getInstance()
        val config = sdk.config

        if (config.backendUrl.isBlank()) {
            Log.d(TAG, "backendUrl not configured — skipping outbound sync.")
            return Result.success()
        }

        // Backend validates chw_id as a numeric primary key; shipping "unknown"
        // would 422 the whole batch and stall every pending event. Hold the
        // queue until the host calls onHomeScreenShown(chwId).
        val currentChwId = sdk.currentCHWId
        if (currentChwId.isNullOrBlank()) {
            Log.w(TAG, "currentCHWId not set — deferring outbound sync until SPICE signs in.")
            return Result.retry()
        }

        val db = MicroCoachingDatabase.getInstance(applicationContext)
        val apiService = NetworkModule.createApiService(config)
        val syncApi = SyncApi(
            apiService = apiService,
            db = db,
            sessionId = "sync-$currentChwId",
            chwId = currentChwId,
            tenantId = config.tenantId.ifBlank { null },
        )

        val result = syncApi.pushPendingEvents()

        return when {
            result.skipped -> Result.success()
            result.success -> Result.success()
            // Permanent failures: 4xx means the payload won't be accepted no matter
            // how often we retry; UNEXPECTED is a programming bug that backoff can't
            // fix. Fail outright so WorkManager stops scheduling retries.
            result.errorKind == SyncErrorKind.HTTP_CLIENT ||
                result.errorKind == SyncErrorKind.UNEXPECTED -> {
                Log.e(TAG, "Outbound sync permanent failure (${result.errorKind}) — not retrying: ${result.error}")
                Result.failure()
            }
            // NETWORK and HTTP_SERVER are transient — let WorkManager back off and try again.
            else -> {
                Log.w(TAG, "Outbound sync transient failure (${result.errorKind}) — will retry: ${result.error}")
                Result.retry()
            }
        }
    }

    companion object {
        const val TAG = "OutboundSyncWorker"
        const val WORK_NAME = "micro_coaching_outbound_sync"
    }
}
