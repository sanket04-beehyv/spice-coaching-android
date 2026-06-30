package com.medtroniclabs.microcoaching.sync

import android.util.Log
import com.medtroniclabs.microcoaching.BuildConfig
import com.medtroniclabs.microcoaching.data.db.MicroCoachingDatabase
import com.medtroniclabs.microcoaching.data.db.entity.AssignedModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.DigitalProficiencyEventEntity
import com.medtroniclabs.microcoaching.data.mapper.toConfigEntities
import com.medtroniclabs.microcoaching.data.mapper.toEntity
import com.medtroniclabs.microcoaching.data.mapper.toPayload
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.network.CoachingApiService
import com.medtroniclabs.microcoaching.data.db.entity.SourceDocumentThumbnailEntity
import com.medtroniclabs.microcoaching.network.ModuleThumbnailPresignedUrlRequest
import com.medtroniclabs.microcoaching.network.SourceDocumentThumbnailPresignedUrlRequest
import com.medtroniclabs.microcoaching.network.SyncDefaults
import com.medtroniclabs.microcoaching.network.TelemetryBatch
import java.io.IOException
import java.util.UUID

/**
 * Domain-level sync gateway used by [OutboundSyncWorker] and [InboundSyncWorker].
 *
 * Responsibilities:
 * - Read pending entities from Room and map them to API payloads ([SyncPayloadMapper])
 * - Call [CoachingApiService] and interpret the HTTP response
 * - Write sync-state updates back to Room on success or failure
 * - Record a `sync_attempt` [DigitalProficiencyEventEntity] for every push attempt (SDK-032)
 *
 * Mapping logic lives in [com.medtroniclabs.microcoaching.data.mapper]:
 *   Entity → Payload  :  SyncPayloadMapper.kt  (outbound)
 *   DTO    → Entity   :  ScenarioBundleMapper.kt (inbound)
 *
 * All functions are suspend — call from an IO dispatcher.
 */
class SyncApi(
    private val apiService: CoachingApiService,
    private val db: MicroCoachingDatabase,
    private val sessionId: String,
    private val chwId: String,
    private val tenantId: String? = null,
    private val sdkVersion: String = BuildConfig.SDK_VERSION,
    private val syncPrefs: SyncPrefs? = null,
) {

    /** Running count of telemetry inserts that themselves failed (see [recordSyncAttempt]). */
    private var failedSyncAttemptInserts: Int = 0

    // ── Outbound ──────────────────────────────────────────────────────────────

    /**
     * Push all pending coaching events, LLM traces, and digital events to the backend.
     *
     * On success: marks all sent rows as `synced` in Room.
     * On failure: increments retry counts; rows stay `pending` for the next attempt.
     * Always records a `sync_attempt` event regardless of outcome.
     *
     * @return [OutboundResult] with counts and any failure reason.
     */
    suspend fun pushPendingEvents(): OutboundResult {
        // chw_id is passed through verbatim — backend accepts whatever
        // identifier shape the host supplies (SPICE forwards its own integer
        // user id today; backend has been relaxed to handle non-UUID values).
        val events = db.coachingEventDao().getPending()
        val traces = db.llmTraceDao().getPending()
        val digitalEvents = db.digitalProficiencyEventDao().getPending()

        if (events.isEmpty() && traces.isEmpty() && digitalEvents.isEmpty()) {
            Log.d(TAG, "Nothing pending — skip outbound sync.")
            return OutboundResult(skipped = true)
        }

        val eventTypeBreakdown = events.groupingBy { it.eventType }.eachCount()
        Log.i(
            TAG,
            "Pushing pending events — coaching_events=${events.size} " +
                "llm_traces=${traces.size} digital_events=${digitalEvents.size} | " +
                "by_type=$eventTypeBreakdown",
        )

        val allPayloads = events.map { it.toPayload() } +
            traces.map { it.toPayload() } +
            digitalEvents.map { it.toPayload() }

        val batch = TelemetryBatch(
            events = allPayloads,
            sdkVersion = sdkVersion,
            chwId = chwId,
            tenantId = tenantId,
        )

        return try {
            val response = apiService.pushTelemetry(batch)
            val now = System.currentTimeMillis()

            if (response.isSuccessful) {
                val body = response.body()!!
                // The backend's ack splits ids into five buckets. We treat
                // `accepted`, `duplicates` (already ingested via Redis dedup),
                // and `buffered` (queued to backend Redis after ClickHouse
                // failure — HTTP 202 from our perspective) as "the backend has
                // them, mark synced". `rejected` (with per-event reasons in
                // `errors`) retry up to MAX_OUTBOUND_RETRIES, then escalate to
                // `failed` so the queue drains instead of cycling forever.
                val syncedSet = body.accepted.toSet() + body.duplicates.toSet() + body.buffered.toSet()
                val rejectedSet = body.rejected.toSet()

                val syncedEventIds   = events.map { it.eventId }.filter { it in syncedSet }
                val syncedTraceIds   = traces.map { it.id }.filter { it in syncedSet }
                val syncedDigitalIds = digitalEvents.map { it.id }.filter { it in syncedSet }

                if (syncedEventIds.isNotEmpty())   db.coachingEventDao().markSynced(syncedEventIds, now)
                if (syncedTraceIds.isNotEmpty())    db.llmTraceDao().markSynced(syncedTraceIds, now)
                if (syncedDigitalIds.isNotEmpty())  db.digitalProficiencyEventDao().markSynced(syncedDigitalIds, now)

                if (body.errors.isNotEmpty()) {
                    body.errors.forEach { reason -> Log.w(TAG, "Event rejected by backend — $reason") }
                }

                if (rejectedSet.isNotEmpty()) {
                    val rejectedEventIds = events.map { it.eventId }.filter { it in rejectedSet }
                    val rejectedTraceIds = traces.map { it.id }.filter { it in rejectedSet }
                    val rejectedDigitalIds = digitalEvents.map { it.id }.filter { it in rejectedSet }
                    if (rejectedEventIds.isNotEmpty()) db.coachingEventDao().incrementRetryCount(rejectedEventIds)
                    if (rejectedTraceIds.isNotEmpty()) db.llmTraceDao().incrementRetryCount(rejectedTraceIds)
                    if (rejectedDigitalIds.isNotEmpty()) db.digitalProficiencyEventDao().incrementRetryCount(rejectedDigitalIds)

                    // Move rows that just hit the retry cap to `failed` so they stop
                    // being re-batched on every subsequent run. Applied symmetrically
                    // to all three outbound tables — previously only coaching_event
                    // capped, which meant permanently malformed traces / digital rows
                    // cycled forever (caught in code review).
                    if (rejectedEventIds.isNotEmpty()) {
                        val exhausted = db.coachingEventDao().getRetryCounts(rejectedEventIds)
                            .filter { it.retryCount >= MAX_OUTBOUND_RETRIES }
                            .map { it.eventId }
                        if (exhausted.isNotEmpty()) {
                            Log.w(TAG, "Giving up on ${exhausted.size} coaching_event row(s) after $MAX_OUTBOUND_RETRIES retries: $exhausted")
                            db.coachingEventDao().markFailed(exhausted)
                        }
                    }
                    if (rejectedTraceIds.isNotEmpty()) {
                        val n = db.llmTraceDao().markFailedIfExhausted(rejectedTraceIds, MAX_OUTBOUND_RETRIES)
                        if (n > 0) Log.w(TAG, "Giving up on $n llm_trace row(s) after $MAX_OUTBOUND_RETRIES retries")
                    }
                    if (rejectedDigitalIds.isNotEmpty()) {
                        val n = db.digitalProficiencyEventDao()
                            .markFailedIfExhausted(rejectedDigitalIds, MAX_OUTBOUND_RETRIES)
                        if (n > 0) Log.w(TAG, "Giving up on $n digital_proficiency_event row(s) after $MAX_OUTBOUND_RETRIES retries")
                    }
                }

                recordSyncAttempt(success = true, networkState = "online")
                Log.i(
                    TAG,
                    "Outbound sync OK — sent: coaching_events=${events.size} " +
                        "llm_traces=${traces.size} digital_events=${digitalEvents.size} | " +
                        "accepted=${body.accepted.size} rejected=${body.rejected.size} " +
                        "duplicates=${body.duplicates.size} buffered=${body.buffered.size} " +
                        "errors=${body.errors.size}",
                )
                OutboundResult(syncedCount = syncedSet.size, failedCount = rejectedSet.size)
            } else {
                val errorMsg = "HTTP ${response.code()}"
                recordSyncAttempt(success = false, errorType = errorMsg, networkState = "online")
                Log.w(TAG, "Outbound sync server error: $errorMsg")
                OutboundResult(error = errorMsg, errorKind = httpKindFor(response.code()))
            }
        } catch (e: IOException) {
            recordSyncAttempt(success = false, errorType = e.javaClass.simpleName, networkState = "offline")
            Log.w(TAG, "Outbound sync network error: ${e.message}")
            OutboundResult(error = e.message ?: "network error", errorKind = SyncErrorKind.NETWORK)
        } catch (e: Exception) {
            recordSyncAttempt(success = false, errorType = e.javaClass.simpleName, networkState = "online")
            Log.w(TAG, "Outbound sync unexpected error: ${e.message}", e)
            OutboundResult(error = e.message ?: "unexpected error", errorKind = SyncErrorKind.UNEXPECTED)
        }
    }

    // ── Inbound ───────────────────────────────────────────────────────────────

    /**
     * Fetch published modules updated after [sinceWatermark]. Backend requires
     * a non-null `since` query param; the SDK supplies [SyncDefaults.EPOCH_ISO]
     * on first sync (empty cache) so the device gets the full catalogue.
     *
     * **Retirement handling (F1).** A terminally-retired family (no published
     * version remains) is removed via two complementary signals:
     *  - **(A) explicit `retired_family_ids`** in the bundle — authoritative and
     *    incremental, applied on every pull (even a retirement-only delta with empty
     *    `modules`). The backend doesn't send this today, so it's currently a no-op;
     *    it activates automatically if/when the field appears.
     *  - **(B) full-catalogue reconcile** — on a `since=EPOCH` fetch
     *    ([forceFullCatalogue] or an empty cache), any locally-cached family absent
     *    from a **non-empty** full response is deleted. The fallback that needs no
     *    backend change. We never prune on an empty response, so a transient blank
     *    bundle can't wipe the cache.
     *
     * Both subtract the families present in this response, so neither can delete a
     * family the server just published (the supersede-vs-terminal safeguard).
     * Deleted families also have their `module_trigger_binding` rows removed.
     *
     * @param forceFullCatalogue force a `since=EPOCH` fetch so signal (B) can run
     *   even when a watermark exists. Driven periodically by [InboundSyncWorker].
     */
    suspend fun pullModules(
        sinceWatermark: String?,
        forceFullCatalogue: Boolean = false,
    ): ModulesResult {
        // Single `/sync/modules` call (with `user_id`): the backend returns the
        // full published catalogue (cached in `module_cache`, powers BM25) AND the
        // caller's `assigned_module_ids`. We populate both `module_cache` and the
        // `assigned_module` join table from this one response — no second call.
        return try {
            val localCount = db.moduleDao().countActive()
            val effectiveSince = when {
                forceFullCatalogue -> SyncDefaults.EPOCH_ISO
                localCount == 0 -> SyncDefaults.EPOCH_ISO
                sinceWatermark.isNullOrBlank() -> SyncDefaults.EPOCH_ISO
                else -> sinceWatermark
            }
            val isFullCatalogue = effectiveSince == SyncDefaults.EPOCH_ISO
            if (isFullCatalogue && sinceWatermark != null) {
                val why = if (forceFullCatalogue) "periodic retirement reconcile" else "cache empty"
                Log.i(TAG, "Modules: requesting full bundle ($why; ignoring stored watermark $sinceWatermark).")
            }
            val response = apiService.pullModules(since = effectiveSince, userId = chwId.ifBlank { null })
            val now = System.currentTimeMillis()

            if (response.isSuccessful) {
                val bundle = response.body()!!
                val rows = bundle.modules.map { it.toEntity(now) }
                val serverFamilies = rows.map { it.moduleFamilyId }.toSet()
                var prunedCount = 0
                if (rows.isNotEmpty()) {
                    db.moduleDao().upsertAll(rows)
                    // After upsert, drop any older versions of the same family
                    // so the cache only holds the latest published version.
                    serverFamilies.forEach { familyId ->
                        prunedCount += db.moduleDao().pruneOldVersions(familyId)
                    }
                }

                // ── Retirement — two complementary signals, unified into one delete ──
                // (A) Explicit `retired_family_ids` from the backend: authoritative and
                //     incremental, so it works even on a retirement-only delta (empty
                //     `modules`). No-op today since the backend doesn't send it yet.
                // (B) Full-catalogue reconcile: families absent from a *non-empty* full
                //     response have no published version remaining. The fallback that
                //     works without any backend change.
                // Both subtract `serverFamilies` so we never delete a family the server
                // just published (the supersede-vs-terminal safeguard from the review).
                val toRetire = mutableSetOf<String>()
                toRetire += bundle.retiredFamilyIds.toSet() - serverFamilies              // (A)
                if (isFullCatalogue && rows.isNotEmpty()) {
                    toRetire += db.moduleDao().distinctFamilyIds().toSet() - serverFamilies // (B)
                }
                if (toRetire.isNotEmpty()) {
                    val retiredList = toRetire.toList()
                    val deletedRows = db.moduleDao().deleteByFamilyIds(retiredList)
                    val deletedBindings = db.moduleTriggerBindingDao().deleteByModuleFamilyIds(retiredList)
                    prunedCount += deletedRows
                    syncPrefs?.addRetiredFamilyIds(toRetire)
                    Log.i(TAG, "Modules retirement: removed ${toRetire.size} family(ies) " +
                        "($deletedRows rows, $deletedBindings bindings) " +
                        "[explicit=${bundle.retiredFamilyIds.size}, fullCatalogue=$isFullCatalogue]: $toRetire")
                }
                // ── Assigned modules ── populate the join table from this same
                // response. `assigned_module_ids` is the full current assignment set
                // for the CHW (the version `module.id`); we resolve each id's family
                // (from this bundle, else the cache) and reconcile. Skip entirely
                // when no CHW (no user_id sent → list empty by contract). Never
                // delete on an empty list — mirrors the module-cache "never prune on
                // empty" safeguard, so a delta that omits the set can't wipe it.
                val assignedCount = if (chwId.isNotBlank()) {
                    val assignedIds = bundle.assignedModuleIds
                    val familyByModuleId = rows.associate { it.moduleId to it.moduleFamilyId }
                    val assignedRows = assignedIds.map { moduleId ->
                        AssignedModuleEntity(
                            userId = chwId,
                            moduleId = moduleId,
                            moduleFamilyId = familyByModuleId[moduleId]
                                ?: db.moduleDao().getById(moduleId)?.moduleFamilyId,
                            assignedAt = now,
                            lastSynced = now,
                        )
                    }
                    val dao = db.assignedModuleDao()
                    if (assignedRows.isNotEmpty()) {
                        dao.upsertAll(assignedRows)
                        dao.deleteForUserNotIn(chwId, assignedIds)
                    }
                    assignedRows.size
                } else {
                    0
                }

                Log.i(
                    TAG,
                    "Modules sync OK: upserted=${rows.size} pruned=$prunedCount " +
                        "assigned=$assignedCount " +
                        "families=${bundle.moduleFamilies.size} fullCatalogue=$isFullCatalogue " +
                        "server_time=${bundle.serverTimeUtc}",
                )
                ModulesResult(
                    upsertedCount = rows.size,
                    prunedCount = prunedCount,
                    assignedCount = assignedCount,
                    newWatermark = bundle.serverTimeUtc,
                )
            } else {
                val errorMsg = "HTTP ${response.code()}"
                recordInboundFailure("inbound_modules", errorMsg)
                Log.w(TAG, "Modules sync server error: $errorMsg")
                ModulesResult(error = errorMsg, errorKind = httpKindFor(response.code()))
            }
        } catch (e: IOException) {
            recordInboundFailure("inbound_modules", e.javaClass.simpleName, offline = true)
            Log.w(TAG, "Modules sync network error: ${e.message}")
            ModulesResult(error = e.message ?: "network error", errorKind = SyncErrorKind.NETWORK)
        } catch (e: Exception) {
            recordInboundFailure("inbound_modules", e.javaClass.simpleName)
            Log.w(TAG, "Modules sync unexpected error: ${e.message}", e)
            ModulesResult(error = e.message ?: "unexpected error", errorKind = SyncErrorKind.UNEXPECTED)
        }
    }

    /**
     * Fetch the behavioural-gap taxonomy. When [chwId] is a valid UUID, the
     * response also includes per-CHW gap state and module-completion rows,
     * which we upsert into the local mirror so CoachingCard UI reflects
     * server-known progress on first load.
     */
    suspend fun pullGaps(sinceWatermark: String?, chwId: String? = null): GapsResult {
        return try {
            // Watermark survives Room wipes (lives in SharedPreferences), so a stale
            // watermark + freshly-wiped Room returns an empty delta and progress stays
            // at 0%. If either local mirror is empty, ignore the watermark and ask the
            // backend for the full snapshot so completions repopulate.
            val gapsEmpty = db.behaviouralGapDao().countActive() == 0
            val completionsEmpty = chwId != null &&
                db.chwModuleCompletionDao().getAllForChw(chwId).isEmpty()
            val effectiveSince = if (gapsEmpty || completionsEmpty) null else sinceWatermark
            if (sinceWatermark != null && effectiveSince == null) {
                val reason = when {
                    gapsEmpty && completionsEmpty -> "gaps + completions empty"
                    gapsEmpty -> "gaps cache empty"
                    else -> "completions cache empty for chw=$chwId"
                }
                Log.i(TAG, "Forcing full /sync/gaps bundle — $reason (ignoring stored watermark $sinceWatermark).")
            }
            val response = apiService.pullGaps(
                since = effectiveSince,
                chwId = chwId,
            )
            val now = System.currentTimeMillis()

            if (response.isSuccessful) {
                val bundle = response.body()!!
                val gapRows = bundle.behaviouralGaps.map { it.toEntity(now) }
                if (gapRows.isNotEmpty()) db.behaviouralGapDao().upsertAll(gapRows)

                // Resolve domain by gap_id so we can stamp ChwGapProfile with it.
                val domainByGapId = gapRows.associate { it.gapId to (it.domain ?: "unknown") }
                bundle.chwBehaviouralGapStates.forEach { state ->
                    val domain = domainByGapId[state.behaviouralGapId] ?: "unknown"
                    db.chwGapProfileDao().upsert(state.toEntity(domain))
                }
                // Quiz-level refresher baseline (backend default mode). Additive to the
                // gap states above; the on-device quiz engine reads this snapshot.
                if (bundle.chwQuizQuestionStates.isNotEmpty()) {
                    db.chwQuizQuestionStateDao().upsertAll(bundle.chwQuizQuestionStates.map { it.toEntity() })
                }
                bundle.chwModuleCompletions.forEach { completion ->
                    db.chwModuleCompletionDao().upsert(completion.toEntity())
                }
                bundle.chwModulePartialCompletions.forEach { partial ->
                    db.chwModulePartialCompletionDao().upsert(partial.toEntity())
                }

                val withRules = gapRows.count { it.detectionRule != null }
                Log.i(
                    TAG,
                    "Gaps sync OK: gaps=${gapRows.size} (with_rules=$withRules) " +
                        "states=${bundle.chwBehaviouralGapStates.size} " +
                        "quizStates=${bundle.chwQuizQuestionStates.size} " +
                        "completions=${bundle.chwModuleCompletions.size} " +
                        "partials=${bundle.chwModulePartialCompletions.size} server_time=${bundle.serverTimeUtc}",
                )
                GapsResult(
                    upsertedCount = gapRows.size,
                    // Structurally 0: pullGaps only upserts the gap taxonomy + CHW
                    // state; it never deletes/prunes gap rows, so there is nothing
                    // to count here (unlike modules/triggers). See F10.
                    prunedCount = 0,
                    partialUpserted = bundle.chwModulePartialCompletions.size,
                    newWatermark = bundle.serverTimeUtc,
                )
            } else {
                val errorMsg = "HTTP ${response.code()}"
                recordInboundFailure("inbound_gaps", errorMsg)
                Log.w(TAG, "Gaps sync server error: $errorMsg")
                GapsResult(error = errorMsg, errorKind = httpKindFor(response.code()))
            }
        } catch (e: IOException) {
            recordInboundFailure("inbound_gaps", e.javaClass.simpleName, offline = true)
            Log.w(TAG, "Gaps sync network error: ${e.message}")
            GapsResult(error = e.message ?: "network error", errorKind = SyncErrorKind.NETWORK)
        } catch (e: Exception) {
            recordInboundFailure("inbound_gaps", e.javaClass.simpleName)
            Log.w(TAG, "Gaps sync unexpected error: ${e.message}", e)
            GapsResult(error = e.message ?: "unexpected error", errorKind = SyncErrorKind.UNEXPECTED)
        }
    }

    /**
     * Fetch trigger definitions + module-trigger bindings updated since
     * [sinceWatermark]. Backend requires non-null `since`; first sync uses
     * [SyncDefaults.EPOCH_ISO].
     */
    suspend fun pullTriggers(sinceWatermark: String?): TriggersResult {
        return try {
            val localCount = db.triggerDefinitionDao().countActive()
            val effectiveSince = when {
                localCount == 0 -> SyncDefaults.EPOCH_ISO
                sinceWatermark.isNullOrBlank() -> SyncDefaults.EPOCH_ISO
                else -> sinceWatermark
            }
            if (effectiveSince == SyncDefaults.EPOCH_ISO && sinceWatermark != null) {
                Log.i(TAG, "Triggers cache empty — requesting full bundle (ignoring stored watermark $sinceWatermark).")
            }
            val response = apiService.pullTriggers(since = effectiveSince)
            val now = System.currentTimeMillis()

            if (response.isSuccessful) {
                val bundle = response.body()!!
                val activeTriggers = bundle.triggers.filter { it.status != "deprecated" }.map { it.toEntity(now) }
                val deprecatedTriggerIds = bundle.triggers.filter { it.status == "deprecated" }.map { it.id }
                // Backend binds to a specific module_id; resolve it to the module family
                // (modules sync before triggers). Drop bindings whose module isn't cached.
                var unresolvedBindings = 0
                val bindings = bundle.bindings.mapNotNull { payload ->
                    val family = db.moduleDao().getById(payload.moduleId)?.moduleFamilyId
                    if (family == null) {
                        unresolvedBindings++
                        null
                    } else {
                        payload.toEntity(moduleFamilyId = family, lastSynced = now)
                    }
                }

                if (activeTriggers.isNotEmpty()) db.triggerDefinitionDao().upsertAll(activeTriggers)
                if (deprecatedTriggerIds.isNotEmpty()) db.triggerDefinitionDao().deleteByIds(deprecatedTriggerIds)
                if (bindings.isNotEmpty()) db.moduleTriggerBindingDao().upsertAll(bindings)
                if (deprecatedTriggerIds.isNotEmpty()) {
                    val orphanedBindingIds = deprecatedTriggerIds.flatMap { tid ->
                        db.moduleTriggerBindingDao().getByTrigger(tid).map { it.bindingId }
                    }
                    if (orphanedBindingIds.isNotEmpty()) {
                        db.moduleTriggerBindingDao().deleteByIds(orphanedBindingIds)
                    }
                }

                Log.i(
                    TAG,
                    "Triggers sync OK: triggers=${activeTriggers.size} (pruned ${deprecatedTriggerIds.size}) " +
                        "bindings=${bindings.size} (dropped $unresolvedBindings unresolved module_id) " +
                        "server_time=${bundle.serverTimeUtc}",
                )
                TriggersResult(
                    triggerCount = activeTriggers.size,
                    bindingCount = bindings.size,
                    prunedCount = deprecatedTriggerIds.size,
                    newWatermark = bundle.serverTimeUtc,
                )
            } else {
                val errorMsg = "HTTP ${response.code()}"
                recordInboundFailure("inbound_triggers", errorMsg)
                Log.w(TAG, "Triggers sync server error: $errorMsg")
                TriggersResult(error = errorMsg, errorKind = httpKindFor(response.code()))
            }
        } catch (e: IOException) {
            recordInboundFailure("inbound_triggers", e.javaClass.simpleName, offline = true)
            Log.w(TAG, "Triggers sync network error: ${e.message}")
            TriggersResult(error = e.message ?: "network error", errorKind = SyncErrorKind.NETWORK)
        } catch (e: Exception) {
            recordInboundFailure("inbound_triggers", e.javaClass.simpleName)
            Log.w(TAG, "Triggers sync unexpected error: ${e.message}", e)
            TriggersResult(error = e.message ?: "unexpected error", errorKind = SyncErrorKind.UNEXPECTED)
        }
    }

    /**
     * Fetch the config-threshold snapshot. Backend ships a single flat
     * `thresholds` dict — every entry is upserted as a global-scoped
     * [ConfigThresholdEntity] row.
     */
    suspend fun pullConfig(): ConfigResult {
        return try {
            val response = apiService.pullConfig()
            val now = System.currentTimeMillis()

            if (response.isSuccessful) {
                val bundle = response.body()!!
                val rows = bundle.thresholds.toConfigEntities(now)
                if (rows.isNotEmpty()) db.configThresholdDao().upsertAll(rows)
                Log.i(
                    TAG,
                    "Config sync OK: upserted=${rows.size} server_time=${bundle.serverTimeUtc}",
                )
                ConfigResult(
                    upsertedCount = rows.size,
                    newWatermark = bundle.serverTimeUtc,
                )
            } else {
                val errorMsg = "HTTP ${response.code()}"
                recordInboundFailure("inbound_config", errorMsg)
                Log.w(TAG, "Config sync server error: $errorMsg")
                ConfigResult(error = errorMsg, errorKind = httpKindFor(response.code()))
            }
        } catch (e: IOException) {
            recordInboundFailure("inbound_config", e.javaClass.simpleName, offline = true)
            Log.w(TAG, "Config sync network error: ${e.message}")
            ConfigResult(error = e.message ?: "network error", errorKind = SyncErrorKind.NETWORK)
        } catch (e: Exception) {
            recordInboundFailure("inbound_config", e.javaClass.simpleName)
            Log.w(TAG, "Config sync unexpected error: ${e.message}", e)
            ConfigResult(error = e.message ?: "unexpected error", errorKind = SyncErrorKind.UNEXPECTED)
        }
    }


    /**
     * Fetch the backend-prioritised morning-module list and atomically replace
     * the local [morning_card_cache] table. On network failure the previous
     * cache is left intact so the device can still surface a ranked list.
     *
     * @param chwId Integer CHW id as a string — forwarded as-is (backend accepts
     *   non-UUID values). Null to get recently-added modules without gap
     *   personalisation.
     * @param tenantId Optional tenant UUID filter.
     */
    suspend fun pullMorningCards(chwId: String?, tenantId: String?): MorningCardsResult {
        return try {
            val response = apiService.getMorningCards(chwId = chwId, tenantId = tenantId)
            if (response.isSuccessful) {
                val body = response.body()!!
                val now = System.currentTimeMillis()
                val entities = body.items.mapIndexed { idx, item ->
                    MorningCardCacheEntity(
                        moduleId = item.moduleId,
                        moduleFamilyId = item.moduleFamilyId,
                        source = item.source,
                        behaviouralGapId = item.behaviouralGapId,
                        quizId = item.quizId,
                        rank = idx,
                        fetchedAt = now,
                    )
                }
                // Replace only the backend rows — never the on-device gap cards
                // (e.g. referral compliance), which coexist via `on_device = 1`.
                db.morningCardCacheDao().replaceBackend(entities)
                Log.i(
                    TAG,
                    "Morning cards sync OK: items=${entities.size} " +
                        "quiz=${entities.count { it.source == "quiz" }} gap=${entities.count { it.source == "gap" }}",
                )
                MorningCardsResult(count = entities.size)
            } else {
                val errorMsg = "HTTP ${response.code()}"
                Log.w(TAG, "Morning cards sync server error: $errorMsg")
                MorningCardsResult(error = errorMsg, errorKind = httpKindFor(response.code()))
            }
        } catch (e: IOException) {
            Log.w(TAG, "Morning cards sync network error: ${e.message}")
            MorningCardsResult(error = e.message ?: "network error", errorKind = SyncErrorKind.NETWORK)
        } catch (e: Exception) {
            Log.w(TAG, "Morning cards sync unexpected error: ${e.message}", e)
            MorningCardsResult(error = e.message ?: "unexpected error", errorKind = SyncErrorKind.UNEXPECTED)
        }
    }

    /**
     * Refresh presigned thumbnail URLs for modules that have a thumbnail but no
     * usable cached URL (never fetched, or expired). Expiry-driven, not
     * cursor-driven — there is no watermark. Writes via a targeted column update
     * so it never collides with the module-sync `upsertAll`.
     *
     * Run right after [pullModules] so any freshly-created/REPLACEd module row
     * gets its thumbnail repopulated in the same inbound pass. Non-fatal:
     * on failure the previous URLs (if any) stay intact.
     */
    suspend fun pullModuleThumbnails(): ThumbnailsResult {
        return try {
            val nowSec = System.currentTimeMillis() / 1000L
            val ids = db.moduleDao().moduleIdsNeedingThumbnail(nowSec)
            if (ids.isEmpty()) {
                Log.d(TAG, "Module thumbnails sync: nothing stale — skipping.")
                return ThumbnailsResult(updatedCount = 0)
            }

            var updated = 0
            var missing = 0
            // Chunk so a large catalogue doesn't blow the request body / URL list.
            for (batch in ids.chunked(THUMBNAIL_BATCH_SIZE)) {
                val response = apiService.getModuleThumbnailPresignedUrls(
                    ModuleThumbnailPresignedUrlRequest(moduleIds = batch),
                )
                if (!response.isSuccessful) {
                    val errorMsg = "HTTP ${response.code()}"
                    Log.w(TAG, "Module thumbnails sync server error: $errorMsg")
                    return ThumbnailsResult(
                        updatedCount = updated,
                        error = errorMsg,
                        errorKind = httpKindFor(response.code()),
                    )
                }
                val body = response.body() ?: continue
                for (entry in body.urls) {
                    if (entry.presignedUrl.isBlank()) continue
                    val expiresAt = nowSec +
                        (entry.expiresSeconds - THUMBNAIL_EXPIRY_SAFETY_MARGIN_SEC).coerceAtLeast(0)
                    db.moduleDao().updateThumbnail(entry.moduleId, entry.presignedUrl, expiresAt)
                    updated++
                }
                missing += body.missingIds.size
            }
            Log.i(TAG, "Module thumbnails sync OK: requested=${ids.size} updated=$updated missing=$missing")
            ThumbnailsResult(updatedCount = updated)
        } catch (e: IOException) {
            Log.w(TAG, "Module thumbnails sync network error: ${e.message}")
            ThumbnailsResult(error = e.message ?: "network error", errorKind = SyncErrorKind.NETWORK)
        } catch (e: Exception) {
            Log.w(TAG, "Module thumbnails sync unexpected error: ${e.message}", e)
            ThumbnailsResult(error = e.message ?: "unexpected error", errorKind = SyncErrorKind.UNEXPECTED)
        }
    }

    /**
     * Fetches and caches presigned thumbnail URLs for source documents whose
     * thumbnail is missing or expired. Mirrors [pullModuleThumbnails] exactly:
     *
     * 1. Collect all source-document IDs with `has_thumbnail = true` from the
     *    module cache (parsed from `source_documents_json`).
     * 2. Seed placeholder rows in `source_document_thumbnail` for any new IDs
     *    not yet seen (so [idsNeedingThumbnail] can return them on the first pass).
     * 3. Batch-fetch presigned URLs for IDs whose row is null/expired.
     * 4. Write back URL + expiry per entry.
     *
     * Non-fatal: on failure the previous URL (if any) stays intact.
     */
    suspend fun pullSourceDocumentThumbnails(): ThumbnailsResult {
        return try {
            val nowSec = System.currentTimeMillis() / 1000L

            // Collect all source-document IDs with hasThumbnail from module cache.
            val allModules = db.moduleDao().getAllOrderedOnce()
            val hasThumbnailIds = allModules
                .flatMap { it.sourceDocuments }
                .filter { it.hasThumbnail }
                .map { it.id }
                .distinct()

            if (hasThumbnailIds.isEmpty()) {
                Log.d(TAG, "Source-doc thumbnails sync: no documents with has_thumbnail — skipping.")
                return ThumbnailsResult(updatedCount = 0)
            }

            // Seed placeholder rows so idsNeedingThumbnail can return them.
            val placeholders = hasThumbnailIds.map { SourceDocumentThumbnailEntity(it) }
            db.sourceDocumentThumbnailDao().insertIfAbsent(placeholders)

            val ids = db.sourceDocumentThumbnailDao().idsNeedingThumbnail(nowSec)
                .filter { it in hasThumbnailIds }

            if (ids.isEmpty()) {
                Log.d(TAG, "Source-doc thumbnails sync: nothing stale — skipping.")
                return ThumbnailsResult(updatedCount = 0)
            }

            var updated = 0
            var missing = 0
            for (batch in ids.chunked(THUMBNAIL_BATCH_SIZE)) {
                val response = apiService.getSourceDocumentThumbnailPresignedUrls(
                    SourceDocumentThumbnailPresignedUrlRequest(sourceDocumentIds = batch),
                )
                if (!response.isSuccessful) {
                    val errorMsg = "HTTP ${response.code()}"
                    Log.w(TAG, "Source-doc thumbnails sync server error: $errorMsg")
                    return ThumbnailsResult(
                        updatedCount = updated,
                        error = errorMsg,
                        errorKind = httpKindFor(response.code()),
                    )
                }
                val body = response.body() ?: continue
                for (entry in body.urls) {
                    if (entry.presignedUrl.isBlank()) continue
                    val expiresAt = nowSec +
                        (entry.expiresSeconds - THUMBNAIL_EXPIRY_SAFETY_MARGIN_SEC).coerceAtLeast(0)
                    db.sourceDocumentThumbnailDao()
                        .updateThumbnail(entry.sourceDocumentId, entry.presignedUrl, expiresAt)
                    updated++
                }
                missing += body.missingIds.size
            }
            Log.i(TAG, "Source-doc thumbnails sync OK: requested=${ids.size} updated=$updated missing=$missing")
            ThumbnailsResult(updatedCount = updated)
        } catch (e: IOException) {
            Log.w(TAG, "Source-doc thumbnails sync network error: ${e.message}")
            ThumbnailsResult(error = e.message ?: "network error", errorKind = SyncErrorKind.NETWORK)
        } catch (e: Exception) {
            Log.w(TAG, "Source-doc thumbnails sync unexpected error: ${e.message}", e)
            ThumbnailsResult(error = e.message ?: "unexpected error", errorKind = SyncErrorKind.UNEXPECTED)
        }
    }

    /**
     * Fetch the full published source-document catalogue and atomically replace
     * the local [published_source_document] table — the durable source for the
     * Knowledge section. Pages through `GET /sync/source-documents/published`
     * with [PUBLISHED_DOCS_PAGE_SIZE] until a short page signals the end, then
     * swaps the whole table in one transaction so the grid reflects exactly
     * what's currently published (no stale rows).
     *
     * Inline presigned URLs (document + thumbnail) are persisted with absolute
     * expiries so the list renders offline; they're refreshed every sync before
     * they lapse. On any failure the previous catalogue is left intact (the
     * replace only runs on a fully-successful paginate).
     */
    suspend fun pullPublishedSourceDocuments(): PublishedSourceDocumentsResult {
        return try {
            val nowSec = System.currentTimeMillis() / 1000L
            val now = System.currentTimeMillis()
            val rows = mutableListOf<com.medtroniclabs.microcoaching.data.db.entity.PublishedSourceDocumentEntity>()
            var offset = 0
            while (true) {
                val response = apiService.getPublishedSourceDocuments(
                    limit = PUBLISHED_DOCS_PAGE_SIZE,
                    offset = offset,
                )
                if (!response.isSuccessful) {
                    val errorMsg = "HTTP ${response.code()}"
                    recordInboundFailure("inbound_published_docs", errorMsg)
                    Log.w(TAG, "Published source-docs sync server error: $errorMsg")
                    return PublishedSourceDocumentsResult(error = errorMsg, errorKind = httpKindFor(response.code()))
                }
                val page = response.body()?.sourceDocuments.orEmpty()
                page.forEachIndexed { idx, item ->
                    rows += com.medtroniclabs.microcoaching.data.db.entity.PublishedSourceDocumentEntity(
                        sourceDocumentId = item.sourceDocumentId,
                        title = item.title,
                        originalFilename = item.originalFilename,
                        presignedUrl = item.presignedUrl,
                        presignedExpiresAt = item.presignedExpiresSeconds?.let { absoluteExpiry(nowSec, it) },
                        thumbnailUrl = item.thumbnailPresignedUrl,
                        thumbnailExpiresAt = item.thumbnailPresignedExpiresSeconds?.let { absoluteExpiry(nowSec, it) },
                        rank = offset + idx,
                        lastSynced = now,
                    )
                }
                // A short (or empty) page means we've reached the end.
                if (page.size < PUBLISHED_DOCS_PAGE_SIZE) break
                offset += PUBLISHED_DOCS_PAGE_SIZE
                // Defensive cap so a misbehaving backend can't loop forever.
                if (offset >= PUBLISHED_DOCS_MAX) {
                    Log.w(TAG, "Published source-docs sync hit the $PUBLISHED_DOCS_MAX-row cap — stopping pagination.")
                    break
                }
            }

            // De-dupe by id (the catalogue can contain repeated source ids across
            // re-ingested files) keeping first-seen order; the table PK would
            // otherwise drop later duplicates non-deterministically.
            val deduped = rows.distinctBy { it.sourceDocumentId }
            db.publishedSourceDocumentDao().replaceAll(deduped)
            Log.i(TAG, "Published source-docs sync OK: fetched=${rows.size} stored=${deduped.size}")
            PublishedSourceDocumentsResult(count = deduped.size)
        } catch (e: IOException) {
            recordInboundFailure("inbound_published_docs", e.javaClass.simpleName, offline = true)
            Log.w(TAG, "Published source-docs sync network error: ${e.message}")
            PublishedSourceDocumentsResult(error = e.message ?: "network error", errorKind = SyncErrorKind.NETWORK)
        } catch (e: Exception) {
            recordInboundFailure("inbound_published_docs", e.javaClass.simpleName)
            Log.w(TAG, "Published source-docs sync unexpected error: ${e.message}", e)
            PublishedSourceDocumentsResult(error = e.message ?: "unexpected error", errorKind = SyncErrorKind.UNEXPECTED)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Relative URL lifetime → absolute epoch-second expiry, trimmed by the safety margin. */
    private fun absoluteExpiry(nowSec: Long, expiresSeconds: Long): Long =
        nowSec + (expiresSeconds - THUMBNAIL_EXPIRY_SAFETY_MARGIN_SEC).coerceAtLeast(0)

    /**
     * Classify an HTTP status code into the kind that drives WorkManager retry
     * decisions. 4xx → permanent (client misconfiguration, won't fix itself);
     * 5xx → transient (server hiccup, worth retrying with backoff).
     */
    private fun httpKindFor(code: Int): SyncErrorKind =
        if (code in 400..499) SyncErrorKind.HTTP_CLIENT else SyncErrorKind.HTTP_SERVER

    private suspend fun recordSyncAttempt(
        success: Boolean,
        errorType: String? = null,
        networkState: String? = null,
    ) {
        try {
            db.digitalProficiencyEventDao().insert(
                DigitalProficiencyEventEntity(
                    id = UUID.randomUUID().toString(),
                    sdkVersion = sdkVersion,
                    sessionId = sessionId,
                    chwId = chwId,
                    eventType = "sync_attempt",
                    success = success,
                    errorType = errorType,
                    networkState = networkState,
                )
            )
        } catch (e: Exception) {
            // A telemetry insert failing is itself a field-observability signal —
            // count it rather than swallowing it entirely (F11).
            failedSyncAttemptInserts++
            Log.w(TAG, "Failed to record sync_attempt event (#$failedSyncAttemptInserts): ${e.message}")
        }
    }

    /**
     * Record a failed *inbound* pull as a `sync_attempt` so inbound failures are
     * observable in the field, not just a `Log.w` (F11). [stage] (e.g.
     * `inbound_modules`) is folded into `error_type` so the failing pull is
     * identifiable downstream.
     *
     * Inbound **successes** are intentionally NOT recorded: the four inbound
     * pulls run automatically on every sync cycle, so emitting success rows would
     * multiply `sync_attempt` volume ~5× and skew the CHW digital-proficiency
     * success-rate (which is meant to reflect CHW-driven outbound sync). Only the
     * failure signal is needed for observability.
     */
    private suspend fun recordInboundFailure(stage: String, error: String?, offline: Boolean = false) {
        recordSyncAttempt(
            success = false,
            errorType = listOfNotNull(stage, error).joinToString(": "),
            networkState = if (offline) "offline" else "online",
        )
    }

    companion object {
        private const val TAG = "SyncApi"

        /**
         * Maximum number of times a single event can be rejected by the backend
         * before the SDK gives up and marks the row `sync_status = 'failed'`.
         * Stops permanently malformed payloads from cycling on every sync run.
         */
        private const val MAX_OUTBOUND_RETRIES = 5

        /** Max module ids per `/sync/modules/presigned-thumbnails` request. */
        private const val THUMBNAIL_BATCH_SIZE = 50

        /** Page size for the published source-document catalogue pull. */
        private const val PUBLISHED_DOCS_PAGE_SIZE = 200

        /** Hard ceiling on total published docs fetched, guarding runaway pagination. */
        private const val PUBLISHED_DOCS_MAX = 5_000

        /** Expire a few seconds early so a thumbnail URL never lapses mid-load. */
        private const val THUMBNAIL_EXPIRY_SAFETY_MARGIN_SEC = 10L
    }
}

// ── Result types ──────────────────────────────────────────────────────────────

/**
 * Why a sync attempt failed. Drives WorkManager retry decisions in
 * [InboundSyncWorker] and [OutboundSyncWorker] — see each worker's `doWork()`
 * for the mapping from kind to [androidx.work.ListenableWorker.Result].
 */
enum class SyncErrorKind {
    /** I/O failure — DNS, timeout, connection reset. Transient; worth retrying. */
    NETWORK,

    /** Backend returned 4xx. Permanent (auth, malformed request, missing endpoint). */
    HTTP_CLIENT,

    /** Backend returned 5xx. Transient (server load, deploy in progress). */
    HTTP_SERVER,

    /** Unexpected runtime exception (deserialization, NPE). Treat as permanent. */
    UNEXPECTED,
}

/**
 * Shared shape across every result type so workers can apply a uniform retry
 * predicate without caring which endpoint produced the result.
 */
interface SyncResult {
    val success: Boolean
    val errorKind: SyncErrorKind?
}

data class OutboundResult(
    val syncedCount: Int = 0,
    val failedCount: Int = 0,
    val skipped: Boolean = false,
    val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null && !skipped
}

data class ModulesResult(
    val upsertedCount: Int = 0,
    val prunedCount: Int = 0,
    /** Rows written to `assigned_module` for the CHW from this pull's `assigned_module_ids`. */
    val assignedCount: Int = 0,
    val newWatermark: String? = null,
    val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class GapsResult(
    val upsertedCount: Int = 0,
    val prunedCount: Int = 0,
    /**
     * Number of `chw_module_partial_completion` rows upserted in this pull.
     * Consumed by [InboundSyncWorker] to decide whether to refilter the
     * morning-modules list, since partial-completion state contributes to the
     * "to-reinforce" set used by the morning-cards filter.
     */
    val partialUpserted: Int = 0,
    val newWatermark: String? = null,
    val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class TriggersResult(
    val triggerCount: Int = 0,
    val bindingCount: Int = 0,
    val prunedCount: Int = 0,
    val newWatermark: String? = null,
    val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class ConfigResult(
    val upsertedCount: Int = 0,
    val newWatermark: String? = null,
    val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class MorningCardsResult(
    val count: Int = 0,
    val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class ThumbnailsResult(
    val updatedCount: Int = 0,
    val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}

data class PublishedSourceDocumentsResult(
    val count: Int = 0,
    val error: String? = null,
    override val errorKind: SyncErrorKind? = null,
) : SyncResult {
    override val success get() = error == null
}
