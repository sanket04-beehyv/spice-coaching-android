package com.medtroniclabs.microcoaching.ai.model

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.ModelDownloadStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * Manages the on-device model lifecycle: detection, download scheduling, and integrity verification.
 *
 * Download is performed by [ModelDownloadWorker] via WorkManager — survives process death
 * and respects [MicroCoachingConfig.wifiOnlyModelDownload].
 *
 * Provider fallback order is driven by [MicroCoachingConfig.modelProviders].
 * Default: Backend → HuggingFace → Kaggle (stub).
 *
 * SHA-256 verification is run after every download to detect corrupted files.
 *
 * Readiness is persisted to [PREFS_NAME] so the state survives process death without
 * depending on WorkInfo replay: once a download finishes successfully and the file
 * is on disk, the [KEY_MODEL_READY] flag is set. On construction the manager
 * reconciles this flag against the file system and emits [ModelState.Ready] up-front
 * if both agree.
 */
class ModelManager(private val config: MicroCoachingConfig) {

    private val _state = MutableStateFlow<ModelState>(ModelState.Idle)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    // Long-lived scope for observing WorkManager state. Lives as long as ModelManager (app lifetime).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefs: SharedPreferences =
        config.context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    /**
     * Distinguishes a user-initiated pause from a genuine WorkManager cancellation
     * (e.g. constraint loss). Set by [pauseDownload]; consumed and cleared by the
     * WorkInfo observer's `CANCELLED` branch so a paused download surfaces as
     * [ModelState.Paused] (resumable) instead of [ModelState.DownloadFailed].
     */
    @Volatile
    private var userPauseRequested: Boolean = false

    /** Last progress percent observed while downloading — used to seed the Paused state. */
    @Volatile
    private var lastKnownProgress: Int = 0

    init {
        reconcileReadyState()
        observeUniqueWork()
    }

    /**
     * Reconcile the persisted "model ready" flag against on-disk state.
     *
     * Truth table (prefsReady × fileOnDisk):
     *   - true  × true   → emit [ModelState.Ready] immediately (happy path post-restart)
     *   - true  × false  → flag is stale; clear it. State stays [ModelState.Idle].
     *   - false × true   → file was sideloaded or downloaded before this flag existed.
     *                      Emit [ModelState.Ready] and persist the flag.
     *   - false × false  → genuine fresh-install / wiped state. No change.
     */
    private fun reconcileReadyState() {
        val prefsReady = prefs.getBoolean(KEY_MODEL_READY, false)
        val file = findLocalModel()
        when {
            prefsReady && file != null -> {
                Log.i(TAG, "Reconcile: model ready (prefs+file) → ${file.absolutePath}")
                _state.value = ModelState.Ready(file)
            }
            prefsReady && file == null -> {
                Log.w(TAG, "Reconcile: prefs says ready but file is missing — clearing flag")
                prefs.edit().remove(KEY_MODEL_READY).remove(KEY_MODEL_PATH).apply()
            }
            !prefsReady && file != null -> {
                // File present without the ready flag — could be:
                //   (a) sideloaded by an installer / a complete download from a previous SDK
                //       version that didn't persist the flag yet, or
                //   (b) a partial download still in flight from a background worker.
                //
                // Distinguish by size: only adopt if the file is plausibly complete
                // (≥ the selected variant's size floor). Otherwise leave state at Idle
                // and let WorkManager finish the job — the SUCCEEDED branch will persist
                // the flag when the worker truly completes.
                val sizeMb = file.length() / 1_048_576
                if (file.length() >= minValidModelSizeBytes()) {
                    Log.i(TAG, "Reconcile: file present ($sizeMb MB) without flag — adopting → ${file.absolutePath}")
                    persistReadyFlag(file)
                    _state.value = ModelState.Ready(file)
                } else {
                    Log.i(TAG, "Reconcile: file present but partial ($sizeMb MB) — skipping adoption, awaiting worker")
                }
            }
            else -> { /* clean slate */ }
        }
    }

    private fun persistReadyFlag(file: File) {
        prefs.edit()
            .putBoolean(KEY_MODEL_READY, true)
            .putString(KEY_MODEL_PATH, file.absolutePath)
            .apply()
    }

    private fun clearReadyFlag() {
        prefs.edit().remove(KEY_MODEL_READY).remove(KEY_MODEL_PATH).apply()
    }

    /**
     * Returns the on-disk file for the **selected** model variant, or null.
     *
     * Matches the selected variant's exact [ModelVariant.fileName] (not "first
     * `.task` on disk") so multiple variants can coexist during A/B testing and
     * switching the selection is deterministic.
     */
    fun findLocalModel(): File? {
        val dir = config.context.getExternalFilesDir(null) ?: return null
        val expected = config.selectedModelVariant().fileName
        return dir.listFiles()?.firstOrNull { it.name == expected }
    }

    /** Per-variant lower bound for "this download is complete" — replaces the old
     *  global 750 MB floor that was tuned only for the 1B. */
    private fun minValidModelSizeBytes(): Long =
        ModelCatalog.minValidSizeBytes(config.selectedModelVariant())

    /** Returns true if a model file is present on device (regardless of integrity). */
    fun isModelPresent(): Boolean = findLocalModel() != null

    /**
     * Schedule a download via WorkManager if no model is present.
     * Download respects [MicroCoachingConfig.wifiOnlyModelDownload].
     *
     * No-op if:
     *   - A model file already exists on device
     *   - Strategy is [ModelDownloadStrategy.MANUAL] or [ModelDownloadStrategy.PROVIDED]
     */
    fun scheduleDownloadIfNeeded() {
        if (config.modelDownloadStrategy == ModelDownloadStrategy.PROVIDED ||
            config.modelDownloadStrategy == ModelDownloadStrategy.MANUAL
        ) return

        if (isModelPresent()) {
            Log.i(TAG, "Model already present — skipping download")
            _state.value = ModelState.Ready(findLocalModel()!!)
            return
        }

        scheduleDownload()
    }

    /**
     * Manually trigger model download. Use when strategy is [ModelDownloadStrategy.MANUAL].
     * Safe to call multiple times — WorkManager deduplicates by unique work name.
     *
     * State-aware behaviour:
     *   - [ModelState.LoadFailed]: the engine couldn't use the file on disk, so a
     *     user-initiated re-tap is an explicit "wipe and retry" request. Delete
     *     the file, clear the ready flag, and schedule a fresh download. This is
     *     the escape hatch for genuinely corrupt-but-size-passing files that
     *     [onModelLoadFailed] intentionally keeps around (it can't distinguish a
     *     transient I/O failure from real corruption — the user's explicit retry
     *     resolves the ambiguity).
     *   - Otherwise, if the model is already present AND the persisted ready
     *     flag is set, no-op and re-emit [ModelState.Ready] (prevents the
     *     "tap Download again on an already downloaded model" footgun that
     *     would re-fetch ~900 MB unnecessarily).
     *   - Otherwise, schedule a new download.
     */
    fun triggerDownload() {
        Log.i(TAG, "triggerDownload entry — currentState=${_state.value::class.simpleName}")
        logNetworkSnapshot("triggerDownload")
        if (_state.value is ModelState.LoadFailed) {
            Log.i(TAG, "triggerDownload: state=LoadFailed → wipe + redownload")
            findLocalModel()?.let { f ->
                if (f.delete()) Log.w(TAG, "Deleted unloadable model file: ${f.name}")
            }
            clearReadyFlag()
            scheduleDownload()
            return
        }

        val file = findLocalModel()
        if (file != null && prefs.getBoolean(KEY_MODEL_READY, false)) {
            Log.i(TAG, "triggerDownload: model already present and ready — no-op")
            _state.value = ModelState.Ready(file)
            return
        }
        scheduleDownload()
    }

    /**
     * Pause an in-flight download. Cancels the WorkManager job (stopping
     * network activity and freeing constraints) but leaves the partial file
     * on disk so [resumeDownload] can continue via HTTP `Range`.
     *
     * No-op when the current state is anything other than [ModelState.Downloading].
     * Sets [userPauseRequested] so the WorkInfo CANCELLED observer routes to
     * [ModelState.Paused] instead of treating this as a failure.
     */
    fun pauseDownload() {
        if (_state.value !is ModelState.Downloading) {
            Log.i(TAG, "pauseDownload: state=${_state.value::class.simpleName} — ignored")
            return
        }
        userPauseRequested = true
        WorkManager.getInstance(config.context).cancelUniqueWork(UNIQUE_WORK_NAME)
        // Optimistic UI update — the CANCELLED observer fires asynchronously
        // and would otherwise leave the spinner spinning for a beat.
        _state.value = ModelState.Paused(lastKnownProgress)
        Log.i(TAG, "pauseDownload: cancelled at ${lastKnownProgress}% — partial file kept")
    }

    /**
     * Resume a previously paused download. Re-enqueues the worker; the worker's
     * resumable streaming logic detects the partial file via `outputFile.exists()`
     * and asks the server for the remaining bytes with an HTTP `Range` header.
     */
    fun resumeDownload() {
        if (_state.value !is ModelState.Paused) {
            Log.i(TAG, "resumeDownload: state=${_state.value::class.simpleName} — ignored")
            return
        }
        scheduleDownload()
    }

    /**
     * Cancel a download outright — stops the worker, deletes the partial file,
     * clears the persisted ready flag, and resets state to [ModelState.Idle].
     * Use when the user actively gives up on the download (as opposed to
     * deferring it via [pauseDownload]).
     */
    fun cancelDownload() {
        val current = _state.value
        if (current !is ModelState.Downloading && current !is ModelState.Paused) {
            Log.i(TAG, "cancelDownload: state=${current::class.simpleName} — ignored")
            return
        }
        userPauseRequested = false  // ensure observer doesn't misread as pause
        WorkManager.getInstance(config.context).cancelUniqueWork(UNIQUE_WORK_NAME)
        findLocalModel()?.let { f ->
            if (f.delete()) Log.w(TAG, "cancelDownload: deleted partial file ${f.name}")
        }
        clearReadyFlag()
        lastKnownProgress = 0
        _state.value = ModelState.Idle
        Log.i(TAG, "cancelDownload: partial file removed, state reset to Idle")
    }

    private fun scheduleDownload() {
        val networkType = if (config.wifiOnlyModelDownload) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val providerKeys = config.modelProviders.map { it.toKey() }.toTypedArray()

        val workRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_PROVIDERS to providerKeys,
                    ModelDownloadWorker.KEY_BACKEND_URL to config.backendUrl,
                    ModelDownloadWorker.KEY_AUTH_TOKEN to config.authToken,
                    ModelDownloadWorker.KEY_HF_TOKEN to config.huggingFaceToken,
                    ModelDownloadWorker.KEY_HF_URL to config.huggingFaceModelUrl,
                    ModelDownloadWorker.KEY_MODEL_ID to config.selectedModelId,
                )
            )
            .addTag(DOWNLOAD_TAG)
            .build()

        WorkManager.getInstance(config.context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, workRequest)

        _state.value = ModelState.Downloading(progressPercent = 0)
        Log.i(
            TAG,
            "Model download scheduled — providers=${config.modelProviders.map { it::class.simpleName }}, " +
                "wifiOnly=${config.wifiOnlyModelDownload}, requiredNetworkType=$networkType",
        )
        logNetworkSnapshot("scheduleDownload")
        // No per-schedule observer here anymore — observation runs for the
        // lifetime of the manager (see [observeUniqueWork] called from init).
        // That fixes the "download running in background but UI shows Idle"
        // case where a fresh process or chat re-open lands on a manager that
        // never started observing because no one called scheduleDownload()
        // this session.
    }

    /**
     * Long-lived collector for the unique download work's [WorkInfo] flow.
     * Started once from [init] so the manager sees in-flight work regardless of
     * whether [scheduleDownload] has been called this process — critical for
     * the "screen timed out, came back, chat showed Download button" symptom
     * the user observed: WorkManager kept downloading correctly across the
     * screen-off / process-restart, but the manager wasn't watching, so the UI
     * defaulted to Idle until the user tapped Download (which would REPLACE the
     * running worker and re-attach an observer).
     *
     * Guards against demoting terminal states ([ModelState.Ready] and
     * [ModelState.LoadFailed]) so a stale `WorkInfo` from a previous successful
     * download — which `getWorkInfosForUniqueWorkFlow` can still return for a
     * short retention window — can't overwrite a freshly-reconciled Ready.
     */
    private fun observeUniqueWork() {
        scope.launch {
            WorkManager.getInstance(config.context)
                .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
                .collect { infoList ->
                    val info = infoList.firstOrNull() ?: return@collect
                    Log.i(TAG, "observeUniqueWork: state=${info.state} jobId=${info.id}")
                    // Once the model is genuinely loaded or we've explicitly
                    // marked it bad, ignore any further WorkInfo emissions.
                    val current = _state.value
                    if (current is ModelState.Ready && info.state != WorkInfo.State.RUNNING) {
                        return@collect
                    }
                    if (current is ModelState.LoadFailed) return@collect

                    when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                            // Show "preparing" so the user knows something is happening
                            // even before the worker actually starts (e.g. waiting for
                            // Wi-Fi). Don't overwrite a more specific state we already
                            // hold for this run.
                            if (current !is ModelState.Downloading && current !is ModelState.Paused) {
                                _state.value = ModelState.Downloading(progressPercent = -1)
                            }
                        }
                        WorkInfo.State.RUNNING -> {
                            val pct = info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                            val bytes = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_DOWNLOADED, 0L)
                            val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
                            lastKnownProgress = pct
                            _state.value = ModelState.Downloading(
                                progressPercent = pct,
                                bytesDownloaded = bytes,
                                totalBytes = total,
                            )
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val path = info.outputData.getString(ModelDownloadWorker.KEY_FILE_PATH)
                            val file = path?.let { File(it) }?.takeIf { it.exists() }
                            _state.value = if (file != null) {
                                Log.i(TAG, "Model ready at: ${file.absolutePath}")
                                persistReadyFlag(file)
                                ModelState.Ready(file)
                            } else {
                                clearReadyFlag()
                                ModelState.DownloadFailed("Model file missing after download completed")
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                                ?: "All providers failed"
                            Log.e(TAG, "Download failed: $error")
                            _state.value = ModelState.DownloadFailed(error)
                        }
                        WorkInfo.State.CANCELLED -> {
                            // Distinguish a user pause from a genuine cancellation. The
                            // pause flow cancels the unique work to stop network activity,
                            // but the partial file is preserved on disk and resumeDownload()
                            // picks it back up via HTTP Range.
                            if (userPauseRequested) {
                                userPauseRequested = false
                                Log.i(TAG, "Download paused at ${lastKnownProgress}%")
                                _state.value = ModelState.Paused(lastKnownProgress)
                            } else if (current !is ModelState.Ready) {
                                _state.value = ModelState.DownloadFailed("Download cancelled")
                            }
                        }
                    }
                }
        }
    }

    /**
     * Called when the inference engine fails to load the model file.
     *
     * Used to delete the file unconditionally, which destroyed the "asks to download
     * again after finishing" UX whenever the engine returned null transiently
     * (file still flushing, momentary I/O denial, fresh download race). Now the
     * decision is gated on a size heuristic:
     *
     *   - file size < the variant size floor → the file is definitely truncated;
     *     delete it, clear the prefs flag, and surface [ModelState.LoadFailed] so the
     *     UI re-prompts for a download.
     *   - file size ≥ the variant size floor → the file looks structurally complete;
     *     keep it on disk and surface [ModelState.LoadFailed] with a retry CTA. The
     *     prefs flag is left untouched so reconciliation on next launch can still
     *     try the engine again.
     *
     * Genuine SHA-256 corruption is caught by [verifyIntegrity] in the load path,
     * which uses [deleteModelAndReset] directly — no need to second-guess it here.
     */
    fun onModelLoadFailed(reason: String = "Model file failed to load") {
        // Guard against the "chat reopened mid-download" footgun. When a worker
        // is actively writing the .task file, a fresh ChatViewModel tries to
        // load it, the engine fails (file is partial), and the chat layer calls
        // onModelLoadFailed(). Without this guard we would:
        //   - flip state to LoadFailed (which observeUniqueWork then locks in,
        //     refusing any further WorkInfo updates), and
        //   - delete the in-flight file if it is below the size threshold,
        //     destroying the user's progress.
        // The right interpretation of "load failed while downloading" is "the
        // engine raced the worker" — not a real failure. Ignore it.
        val currentState = _state.value
        if (currentState is ModelState.Downloading || currentState is ModelState.Paused) {
            Log.w(
                TAG,
                "onModelLoadFailed: download in flight ($currentState) — ignoring '$reason'",
            )
            return
        }

        val file = findLocalModel()
        if (file != null) {
            val sizeMb = file.length() / 1_048_576
            if (file.length() < minValidModelSizeBytes()) {
                if (file.delete()) {
                    Log.w(TAG, "Deleted truncated model file ($sizeMb MB < min): ${file.name}")
                }
                clearReadyFlag()
            } else {
                Log.w(TAG, "Load failed but file size OK ($sizeMb MB) — keeping for retry: $reason")
            }
        }
        _state.value = ModelState.LoadFailed(reason)
    }

    /**
     * Explicit teardown for a confirmed-bad model: deletes the file, clears the
     * persisted ready flag, and surfaces [ModelState.LoadFailed]. Use this only
     * when corruption is positively confirmed (e.g. SHA-256 mismatch) or when
     * the user explicitly chooses "re-download" from the LoadFailed CTA.
     */
    fun deleteModelAndReset(reason: String = "Model file deleted by user action") {
        findLocalModel()?.let { file ->
            if (file.delete()) {
                Log.w(TAG, "Deleted model file: ${file.name}")
            }
        }
        clearReadyFlag()
        _state.value = ModelState.LoadFailed(reason)
    }

    /**
     * Verify SHA-256 integrity of the given model file.
     * @param expectedHash Expected hex digest, or null to skip verification.
     */
    fun verifyIntegrity(file: File, expectedHash: String?): Boolean {
        if (expectedHash == null) return true
        val actualHash = file.sha256()
        val ok = actualHash.equals(expectedHash, ignoreCase = true)
        if (!ok) Log.e(TAG, "Integrity check failed for ${file.name}: expected=$expectedHash actual=$actualHash")
        return ok
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Emit a single line describing the current `ConnectivityManager` state to
     * logcat. Called at every trigger / schedule boundary so QA can correlate
     * "download didn't start" reports with the active network type, transport
     * flags, and metering status at the moment the SDK saw the request.
     */
    private fun logNetworkSnapshot(stage: String) {
        val cm = config.context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "Network snapshot[$stage]: ConnectivityManager unavailable")
            return
        }
        val net = cm.activeNetwork
        if (net == null) {
            Log.w(TAG, "Network snapshot[$stage]: activeNetwork=null (no active connection)")
            return
        }
        val caps = cm.getNetworkCapabilities(net)
        if (caps == null) {
            Log.w(TAG, "Network snapshot[$stage]: capabilities=null for activeNetwork=${net.networkHandle}")
            return
        }
        Log.i(
            TAG,
            "Network snapshot[$stage]: activeNetwork=${net.networkHandle}, " +
                "transports=${transportSummary(caps)}, " +
                "notMetered=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}, " +
                "internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}, " +
                "validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}",
        )
    }

    private fun transportSummary(caps: NetworkCapabilities): String {
        val flags = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) flags += "CELLULAR"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) flags += "WIFI"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) flags += "ETHERNET"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) flags += "VPN"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) flags += "BLUETOOTH"
        return if (flags.isEmpty()) "[]" else flags.joinToString(prefix = "[", postfix = "]")
    }

    companion object {
        private const val TAG = "ModelManager"
        const val DOWNLOAD_TAG = "microcoaching_model_download"
        const val UNIQUE_WORK_NAME = "microcoaching_model_download"

        // Per-variant completeness floor now lives in ModelCatalog.minValidSizeBytes()
        // (see minValidModelSizeBytes()) — the old global 750 MB constant was tuned
        // for the 1B and wrongly rejected the ~250–300 MB 270M variants.

        private const val PREFS_NAME = "microcoaching_model_prefs"
        private const val KEY_MODEL_READY = "model_ready"
        private const val KEY_MODEL_PATH = "model_path"
    }
}
