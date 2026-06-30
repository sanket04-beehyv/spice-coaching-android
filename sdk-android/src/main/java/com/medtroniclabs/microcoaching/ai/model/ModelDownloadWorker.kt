package com.medtroniclabs.microcoaching.ai.model

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import com.medtroniclabs.microcoaching.BuildConfig
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that downloads the on-device Gemma model, trying each configured
 * [ModelProvider] in order and falling back to the next on failure.
 *
 * **Provider fallback order** (default): Backend → HuggingFace → Kaggle
 *
 * Features:
 *   - Sequential fallback: moves to next provider if the current one fails
 *   - Resumable: uses HTTP `Range` requests if a partial file already exists
 *   - Progress: reports 0–99 via [KEY_PROGRESS] (plus [KEY_BYTES_DOWNLOADED] / [KEY_TOTAL_BYTES])
 *     while streaming; 100 on success
 *   - Foreground service: shows a persistent system notification via [ModelDownloadNotifier]
 *     so the OS keeps the download alive even when the host app is minimized or killed
 *   - Survives process death — WorkManager re-enqueues on restart
 *
 * On success, [KEY_FILE_PATH] in output data holds the absolute path to the model file.
 * [ModelManager] observes this worker's [WorkInfo][androidx.work.WorkInfo] to update [ModelState].
 *
 * Backend endpoint: `{backendUrl}/api/v1/models/gemma/download`
 * HuggingFace endpoint: the selected [ModelCatalog] variant's `downloadUrl`
 * (overridable via [MicroCoachingConfig.huggingFaceModelUrl]).
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(progress = 0, bytesDownloaded = 0L, totalBytes = 0L)

    override suspend fun doWork(): Result {
        // Promote to foreground service before any I/O so the OS keeps us alive
        // when the host app is backgrounded or killed. If POST_NOTIFICATIONS was
        // denied the call can fail — we log and continue: the download still works,
        // just without the elevated priority.
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.w(TAG, "setForeground at start failed: ${it.message}") }

        val providers = inputData.getStringArray(KEY_PROVIDERS)
            ?.mapNotNull { ModelProvider.fromKey(it) }
            ?.ifEmpty { ModelProvider.DEFAULT_ORDER }
            ?: ModelProvider.DEFAULT_ORDER

        // Resolve the selected model variant — single source of truth for the
        // download URL, on-disk filename, expected size (size floor), and whether
        // an access token is required. Falls back to the catalog default.
        val variant = inputData.getString(KEY_MODEL_ID)
            ?.let { ModelCatalog.byId(it) }
            ?: ModelCatalog.default()

        Log.i(TAG, "doWork start — providers=${providers.map { it::class.simpleName }}, model=${variant.id} (${variant.fileName})")
        logNetworkSnapshot("doWork")

        val outputDir = applicationContext.getExternalFilesDir(null)
            ?: run {
                Log.e(TAG, "External storage unavailable — failing")
                return Result.failure(workDataOf(KEY_ERROR to "External storage unavailable"))
            }

        val errors = mutableListOf<String>()

        for (provider in providers) {
            val providerName = provider::class.simpleName ?: "Unknown"
            Log.i(TAG, "Attempting download from provider: $providerName")

            val outcome = when (provider) {
                ModelProvider.Backend -> tryBackend(outputDir, variant)
                ModelProvider.HuggingFace -> tryHuggingFace(outputDir, variant)
                ModelProvider.Kaggle -> {
                    Log.w(TAG, "Kaggle provider not yet implemented — skipping")
                    DownloadOutcome.Failure("Kaggle provider not yet supported")
                }
            }

            when (outcome) {
                is DownloadOutcome.Success -> {
                    Log.i(TAG, "Download complete via $providerName → ${outcome.file.name} (${outcome.file.length() / 1_048_576} MB)")
                    // Persist the ready flag before returning so ModelManager.reconcileReadyState()
                    // on the next process start can emit Ready immediately even if the WorkInfo
                    // SUCCEEDED event was never observed by a live ModelManager (e.g. worker
                    // finished while the app was killed).
                    applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_MODEL_READY, true)
                        .putString(KEY_MODEL_PATH, outcome.file.absolutePath)
                        .apply()
                    return Result.success(
                        workDataOf(
                            KEY_PROGRESS to 100,
                            KEY_FILE_PATH to outcome.file.absolutePath,
                        )
                    )
                }
                is DownloadOutcome.Failure -> {
                    val msg = "[$providerName] ${outcome.reason}"
                    Log.w(TAG, "$msg — trying next provider")
                    errors += msg
                }
            }
        }

        val combinedError = errors.joinToString(" | ")
        Log.e(TAG, "All providers failed: $combinedError")
        return Result.failure(workDataOf(KEY_ERROR to combinedError))
    }

    // ── Backend provider ──────────────────────────────────────────────────────

    private suspend fun tryBackend(outputDir: File, variant: ModelVariant): DownloadOutcome {
        val backendUrl = inputData.getString(KEY_BACKEND_URL)
        if (backendUrl.isNullOrBlank()) {
            return DownloadOutcome.Failure("backendUrl not configured — set via Builder.backendUrl()")
        }

        val authToken = inputData.getString(KEY_AUTH_TOKEN) ?: ""
        val downloadUrl = "${backendUrl.trimEnd('/')}/api/v1/models/gemma/download"
        // Write to the variant's filename so on-disk resolution (ModelManager /
        // InferenceRouter, which match by exact fileName) finds it regardless of
        // which provider served the bytes.
        val outputFile = File(outputDir, variant.fileName)

        val headers = buildMap<String, String> {
            if (authToken.isNotBlank()) put("Authorization", "Bearer $authToken")
        }

        return streamDownload(
            url = downloadUrl,
            headers = headers,
            outputFile = outputFile,
            minValidBytes = ModelCatalog.minValidSizeBytes(variant),
        )
    }

    // ── HuggingFace provider ──────────────────────────────────────────────────

    private suspend fun tryHuggingFace(outputDir: File, variant: ModelVariant): DownloadOutcome {
        // The variant's catalog URL is authoritative; KEY_HF_URL is an optional
        // host override for a file not in the allowlist.
        val hfUrl = inputData.getString(KEY_HF_URL)
            ?.takeIf { it.isNotBlank() }
            ?: variant.downloadUrl
        val hfToken = inputData.getString(KEY_HF_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: ModelProvider.DEFAULT_HF_TOKEN

        // On-disk filename always comes from the selected variant so resolution
        // (which matches by exact fileName) is deterministic.
        val outputFile = File(outputDir, variant.fileName)

        if (hfToken.isBlank() && variant.requiresAccessToken) {
            Log.w(TAG, "[HF] token=<BLANK> but model '${variant.id}' is gated — download will fail")
        } else if (BuildConfig.DEBUG) {
            Log.d(TAG, "[HF] token=${hfToken.take(1)}…${hfToken.takeLast(1)} (len=${hfToken.length})")
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[HF] url=$hfUrl")
            Log.d(TAG, "[HF] outputFile=${outputFile.absolutePath}")
        }

        val headers = buildMap<String, String> {
            if (hfToken.isNotBlank()) put("Authorization", "Bearer $hfToken")
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "[HF] Authorization header present=${headers.containsKey("Authorization")}")
        }

        return streamDownload(
            url = hfUrl,
            headers = headers,
            outputFile = outputFile,
            minValidBytes = ModelCatalog.minValidSizeBytes(variant),
        )
    }

    // ── Shared streaming download ─────────────────────────────────────────────

    /**
     * Streams [url] to [outputFile], resuming from existing bytes if the server supports Range.
     * Reports progress (0–99) via WorkManager [setProgress].
     */
    private suspend fun streamDownload(
        url: String,
        headers: Map<String, String>,
        outputFile: File,
        minValidBytes: Long,
    ): DownloadOutcome = runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()

        // Resume from partial file
        val existingBytes = if (outputFile.exists()) outputFile.length() else 0L
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()

        // Always close the response to avoid OkHttp connection leaks on early returns.
        response.use { resp ->
            val isPartialContent = resp.code == 206
            Log.d(TAG, "streamDownload → HTTP ${resp.code} ${resp.message} | url=$url")

            if (resp.code == 416) {
                // Range not satisfiable — server rejected our resume offset.
                // If the local file is already ≥ the variant's size floor it is almost
                // certainly the complete model; treat it as a successful download so
                // inference can proceed. If smaller, it is a corrupt partial — delete it
                // so the next attempt starts from zero.
                val localSize = outputFile.length()
                return@runCatching if (localSize >= minValidBytes) {
                    Log.i(TAG, "HTTP 416 — local file is ${localSize / 1_048_576} MB, treating as already complete")
                    DownloadOutcome.Success(outputFile)
                } else {
                    outputFile.delete()
                    Log.w(TAG, "HTTP 416 — partial file deleted (${localSize / 1_048_576} MB), retry to start fresh")
                    DownloadOutcome.Failure("HTTP 416: partial file removed — retry to restart download")
                }
            }

            if (!resp.isSuccessful && !isPartialContent) {
                if (BuildConfig.DEBUG) {
                    val errorBody = resp.body?.string()?.take(500) ?: "<no body>"
                    Log.e(TAG, "streamDownload failed | HTTP ${resp.code} | body=$errorBody | headers=${resp.headers}")
                } else {
                    Log.e(TAG, "streamDownload failed | HTTP ${resp.code}: ${resp.message}")
                }
                return@runCatching DownloadOutcome.Failure("HTTP ${resp.code}: ${resp.message}")
            }

            val body = resp.body
                ?: return@runCatching DownloadOutcome.Failure("Empty response body")

            val contentLength = body.contentLength()
            val totalBytes = if (contentLength > 0L) contentLength + existingBytes else 0L
            val appendToFile = existingBytes > 0L && isPartialContent

            if (existingBytes > 0L) {
                Log.i(TAG, "Resuming download from byte $existingBytes (${existingBytes / 1_048_576} MB already present)")
            }

            body.byteStream().use { input ->
                FileOutputStream(outputFile, appendToFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalRead = existingBytes

                    // Throttle progress emission: every percent change, or every
                    // PROGRESS_EMIT_INTERVAL_MS, whichever comes first. Without throttling
                    // each 8 KB iteration would spam setForeground and saturate the
                    // notification system.
                    var lastEmitMs = 0L
                    var lastEmittedPercent = -1

                    // First emit happens once we already have a stream open so the
                    // notification flips from the initial "0 MB" to a real number quickly.
                    emitProgress(
                        percent = if (totalBytes > 0L) {
                            ((totalRead * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        } else 0,
                        bytesDownloaded = totalRead,
                        totalBytes = totalBytes,
                    )
                    lastEmitMs = System.currentTimeMillis()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        val percent = if (totalBytes > 0L) {
                            ((totalRead * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        } else {
                            -1
                        }
                        val percentChanged = percent != -1 && percent != lastEmittedPercent
                        val intervalElapsed = (now - lastEmitMs) >= PROGRESS_EMIT_INTERVAL_MS
                        if (percentChanged || intervalElapsed) {
                            lastEmitMs = now
                            lastEmittedPercent = percent
                            emitProgress(
                                percent = percent.coerceAtLeast(0),
                                bytesDownloaded = totalRead,
                                totalBytes = totalBytes,
                            )
                        }
                    }
                    output.flush()
                }
            }

            // Verify the download is complete. HuggingFace often uses chunked transfer
            // (no Content-Length), so the loop above may exit on a dropped connection with
            // a partial file. Treat anything below the minimum valid size as a failure
            // and delete it so the next attempt starts fresh.
            val finalSize = outputFile.length()
            if (totalBytes > 0L && finalSize < totalBytes) {
                outputFile.delete()
                return@runCatching DownloadOutcome.Failure(
                    "Incomplete download: received $finalSize of $totalBytes bytes — file deleted"
                )
            }
            if (totalBytes == 0L && finalSize < minValidBytes) {
                outputFile.delete()
                return@runCatching DownloadOutcome.Failure(
                    "Download too small (${finalSize / 1_048_576} MB < ${minValidBytes / 1_048_576} MB minimum) — file deleted, retry required"
                )
            }

            DownloadOutcome.Success(outputFile)
        }
    }.getOrElse { cause ->
        Log.e(TAG, "streamDownload error for $url: ${cause.message}")
        DownloadOutcome.Failure(cause.message ?: "Unknown error")
    }

    // ── Foreground service + progress emission ────────────────────────────────

    /**
     * Builds the [ForegroundInfo] used both at worker start and on every
     * throttled progress update. On API ≥ Q the service is typed as
     * `DATA_SYNC`; on older releases the type argument is omitted (the OS
     * doesn't require it pre-Q).
     */
    private fun buildForegroundInfo(
        progress: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
    ): ForegroundInfo {
        val notification = ModelDownloadNotifier.buildNotification(
            context = applicationContext,
            progress = progress,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                ModelDownloadNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(ModelDownloadNotifier.NOTIFICATION_ID, notification)
        }
    }

    /**
     * Emits a progress update through both channels:
     *   - [setProgress] so [ModelManager]'s WorkInfo observer can update the in-app UI.
     *   - [setForeground] so the system notification reflects the latest MB / percent.
     *
     * Wrapped in [runCatching] because [setForeground] can throw on Android 13+
     * if POST_NOTIFICATIONS was denied; the underlying download must continue
     * regardless so we never propagate that failure.
     */
    private suspend fun emitProgress(
        percent: Int,
        bytesDownloaded: Long,
        totalBytes: Long,
    ) {
        setProgress(
            workDataOf(
                KEY_PROGRESS to percent,
                KEY_BYTES_DOWNLOADED to bytesDownloaded,
                KEY_TOTAL_BYTES to totalBytes,
            )
        )
        runCatching {
            setForeground(buildForegroundInfo(percent, bytesDownloaded, totalBytes))
        }.onFailure {
            Log.w(TAG, "setForeground progress update failed: ${it.message}")
        }
    }

    // ── Internal result type ──────────────────────────────────────────────────

    private sealed class DownloadOutcome {
        data class Success(val file: File) : DownloadOutcome()
        data class Failure(val reason: String) : DownloadOutcome()
    }

    /**
     * Same shape as `ModelManager.logNetworkSnapshot` but lives here so the
     * worker can describe its own view of the network. Important for the
     * "WorkManager satisfied its constraint but the worker still can't reach
     * the network" debugging story — the worker's view is what actually
     * matters for HTTP success.
     */
    private fun logNetworkSnapshot(stage: String) {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "Network snapshot[$stage]: ConnectivityManager unavailable")
            return
        }
        val net = cm.activeNetwork
        if (net == null) {
            Log.w(TAG, "Network snapshot[$stage]: activeNetwork=null")
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
        private const val TAG = "ModelDownloadWorker"
        private const val BUFFER_SIZE = 8 * 1024  // 8 KB
        // The "is this download complete?" floor is now per-variant
        // (ModelCatalog.minValidSizeBytes), passed into streamDownload — the old
        // global 750 MB constant was tuned for the 1B and rejected the 270M.

        // ── WorkManager input keys ────────────────────────────────────────────
        /** String array of provider keys — see [ModelProvider.toKey]. */
        const val KEY_PROVIDERS = "providers"
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_HF_URL = "hf_url"
        const val KEY_HF_TOKEN = "hf_token"
        /** [ModelCatalog] variant id selected by the host (see [MicroCoachingConfig.selectedModelId]). */
        const val KEY_MODEL_ID = "model_id"

        // ── WorkManager progress / output keys ────────────────────────────────
        /** Int 0–100. Reports 100 only on [androidx.work.WorkInfo.State.SUCCEEDED]. */
        const val KEY_PROGRESS = "progress"
        /** Long. Bytes received so far. Present during RUNNING. */
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        /** Long. Total bytes expected, or 0 if unknown (chunked transfer). Present during RUNNING. */
        const val KEY_TOTAL_BYTES = "total_bytes"
        /** Absolute path to the downloaded model file. Present only on success. */
        const val KEY_FILE_PATH = "file_path"
        /** Human-readable error string. Present only on failure. */
        const val KEY_ERROR = "error"

        /** Minimum gap between two progress emits, ms. Keeps notification updates ≤ 2 Hz. */
        private const val PROGRESS_EMIT_INTERVAL_MS = 500L

        // Mirrors ModelManager constants — kept here so the worker can persist the
        // ready flag without needing a back-reference to the manager instance.
        private const val PREFS_NAME = "microcoaching_model_prefs"
        private const val KEY_MODEL_READY = "model_ready"
        private const val KEY_MODEL_PATH = "model_path"
    }
}
