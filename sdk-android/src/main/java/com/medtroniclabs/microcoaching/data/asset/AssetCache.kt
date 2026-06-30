package com.medtroniclabs.microcoaching.data.asset

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.db.dao.CachedAssetDao
import com.medtroniclabs.microcoaching.data.db.entity.CachedAssetEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Asset categories the cache stores. Drives only the on-disk sub-directory today. */
enum class AssetKind { IMAGE, VIDEO, DOCUMENT }

/**
 * Thrown by [AssetCache.localFile] when the device has insufficient disk space
 * to write the downloaded asset. Callers should surface a storage-specific
 * error message rather than a generic "unavailable" one.
 */
class InsufficientStorageException(cause: IOException) : Exception("Insufficient storage to cache asset", cause)

/**
 * Reusable offline cache for remote assets fetched from short-lived presigned
 * URLs (module thumbnails, lesson-card images now; video / PDF later).
 *
 * Keyed by a **stable asset identity** (a media `object_name`, a source-document
 * `storage_path`, or the path component of a presigned URL) — never the full
 * presigned URL, whose signature query rotates per fetch. The same asset
 * referenced by two entities therefore resolves to one cached file (dedup).
 *
 * Flow (see [localFile]): cache hit → return the local [File]; offline miss →
 * null (caller shows its placeholder); online miss → fetch the URL, download,
 * store, and return. Storage is bounded by [maxBytes] via LRU eviction on
 * `last_access_at`.
 */
class AssetCache(
    private val context: Context,
    private val dao: CachedAssetDao,
    private val scope: CoroutineScope,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    /**
     * Plain client for downloading self-authorizing presigned URLs. Deliberately
     * does NOT carry the API auth interceptor — the asset host (S3) differs from
     * the API host and the URL already embeds its signature.
     */
    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val cacheDir: File by lazy {
        File(context.filesDir, "asset_cache").apply { mkdirs() }
    }

    /** Per-key locks so concurrent requests for the same asset download once. */
    private val keyLocks = mutableMapOf<String, Mutex>()
    private val keyLocksGuard = Mutex()

    /**
     * Resolve [key] to a local [File], downloading via [fetchUrl] on first online
     * view. Returns null on an offline miss or any download failure.
     *
     * @param key stable asset identity (object_name / storage_path / URL path).
     * @param onProgress optional download-progress callback `(bytesDownloaded, totalBytes)`,
     *   invoked (throttled) only while actually downloading. `totalBytes <= 0` means the
     *   server didn't send a Content-Length → caller should show an indeterminate bar.
     *   Not called on a cache hit. Default null = no progress (existing callers unaffected).
     * @param fetchUrl supplies a fresh presigned URL; only called on a cache miss
     *   while online. May itself return null (e.g. resolver failed).
     */
    suspend fun localFile(
        key: String,
        kind: AssetKind = AssetKind.IMAGE,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
        fetchUrl: suspend () -> String?,
    ): File? {
        if (key.isBlank()) {
            Log.w(TAG, "localFile called with blank key — nothing to resolve")
            return null
        }
        val keyHash = sha256(key)
        Log.d(TAG, "localFile(kind=$kind key=$key)")

        cachedFile(keyHash)?.let {
            Log.d(TAG, "cache HIT key=$key -> ${it.name}")
            return it
        }

        // Serialize same-key work so duplicate views don't double-download.
        lockFor(keyHash).withLock {
            // Re-check inside the lock — another caller may have just finished.
            cachedFile(keyHash)?.let { return it }

            if (!isOnline()) {
                Log.d(TAG, "offline miss for key=$key")
                return null
            }
            val url = runCatching { fetchUrl() }
                .onFailure { Log.w(TAG, "fetchUrl threw for key=$key: ${it.message}", it) }
                .getOrNull()
            if (url.isNullOrBlank()) {
                Log.w(TAG, "no URL resolved for key=$key — cannot cache (fetchUrl returned null/blank)")
                return null
            }
            Log.d(TAG, "resolved url for key=$key host=${url.toHttpUrlOrNull()?.host} path=${url.toHttpUrlOrNull()?.encodedPath}")
            return downloadAndStore(keyHash, key, kind, url, onProgress)
        }
    }

    /**
     * Convenience for already-presigned URLs (thumbnails / documents): keys on
     * the URL **path** so a rotated signature still hits the same entry.
     */
    suspend fun localFileForUrl(url: String?, kind: AssetKind = AssetKind.IMAGE): File? {
        val key = stableKeyForUrl(url) ?: return null
        return localFile(key, kind, fetchUrl = { url })
    }

    /**
     * True when [key] is already cached on disk — a cheap read-only peek (no
     * download, no LRU touch). Used to show a "view" vs "download" affordance.
     */
    suspend fun isCached(key: String): Boolean {
        if (key.isBlank()) return false
        val row = dao.get(sha256(key)) ?: return false
        return File(row.localPath).exists()
    }

    /** Stable key from a URL: scheme+host+path, dropping the volatile query. */
    fun stableKeyForUrl(url: String?): String? {
        val http = url?.toHttpUrlOrNull() ?: return null
        return "${http.scheme}://${http.host}${http.encodedPath}"
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private suspend fun cachedFile(keyHash: String): File? {
        val row = dao.get(keyHash) ?: return null
        val file = File(row.localPath)
        if (!file.exists()) {
            // Row without a backing file (e.g. cleared cacheDir) — drop it.
            dao.delete(keyHash)
            return null
        }
        scope.launch { runCatching { dao.touch(keyHash, System.currentTimeMillis()) } }
        return file
    }

    private suspend fun downloadAndStore(
        keyHash: String,
        key: String,
        kind: AssetKind,
        url: String,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, kind.name.lowercase()).apply { mkdirs() }
        val target = File(dir, keyHash)
        val tmp = File(dir, "$keyHash.tmp")
        try {
            val request = Request.Builder().url(url).get().build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(
                        TAG,
                        "download failed code=${response.code} key=$key " +
                            "body=${response.body?.string()?.take(200)}",
                    )
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                if (onProgress == null) {
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
                } else {
                    copyWithProgress(body.byteStream(), tmp, body.contentLength(), onProgress)
                }
                val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                dao.upsert(
                    CachedAssetEntity(
                        keyHash = keyHash,
                        assetKey = key,
                        kind = kind.name,
                        localPath = target.absolutePath,
                        bytes = target.length(),
                        mime = mime,
                    ),
                )
                Log.d(TAG, "cached key=$key bytes=${target.length()} -> ${target.name}")
            }
            evictIfNeeded()
            target
        } catch (e: IOException) {
            runCatching { tmp.delete() }
            if (e.isStorageFull()) {
                Log.w(TAG, "insufficient storage for key=$key — cannot cache asset")
                throw InsufficientStorageException(e)
            }
            Log.w(TAG, "download IO error for key=$key: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "download error for key=$key: ${e.message}", e)
            runCatching { tmp.delete() }
            null
        }
    }

    /**
     * Streaming copy that reports throttled progress (every percent change, or
     * every [PROGRESS_EMIT_INTERVAL_MS], whichever first — mirrors
     * [com.medtroniclabs.microcoaching.ai.model.ModelDownloadWorker]). [total] is
     * the Content-Length (≤ 0 = unknown → caller shows an indeterminate bar).
     */
    private fun copyWithProgress(
        input: InputStream,
        target: File,
        total: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        input.use { ins ->
            target.outputStream().use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                var downloaded = 0L
                var lastEmitMs = 0L
                var lastPercent = -1
                onProgress(0L, total)
                while (ins.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    val percent = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1
                    val percentChanged = percent != -1 && percent != lastPercent
                    val intervalElapsed = (now - lastEmitMs) >= PROGRESS_EMIT_INTERVAL_MS
                    if (percentChanged || intervalElapsed) {
                        lastEmitMs = now
                        lastPercent = percent
                        onProgress(downloaded, total)
                    }
                }
                out.flush()
                onProgress(downloaded, total)
            }
        }
    }

    /** Trim to [maxBytes] by deleting least-recently-accessed unpinned entries. */
    private suspend fun evictIfNeeded() {
        var total = dao.totalBytes() ?: return
        if (total <= maxBytes) return
        // Evict in small batches until under budget (or nothing left to evict).
        while (total > maxBytes) {
            val victims = dao.oldestUnpinned(EVICTION_BATCH)
            if (victims.isEmpty()) break
            for (victim in victims) {
                runCatching { File(victim.localPath).delete() }
                dao.delete(victim.keyHash)
                total -= victim.bytes
                if (total <= maxBytes) break
            }
        }
        Log.d(TAG, "eviction complete: total=$total budget=$maxBytes")
    }

    private suspend fun lockFor(keyHash: String): Mutex = keyLocksGuard.withLock {
        keyLocks.getOrPut(keyHash) { Mutex() }
    }

    private fun isOnline(): Boolean =
        runCatching { MicroCoachingSDK.getInstance().isNetworkAvailable() }.getOrDefault(false)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "AssetCache"
        private const val DEFAULT_MAX_BYTES = 150L * 1024 * 1024 // ~150 MB
        private const val EVICTION_BATCH = 16
        private const val PROGRESS_EMIT_INTERVAL_MS = 200L
    }
}

/**
 * True when this [IOException] indicates the device is out of disk space.
 * Checks errno 28 (ENOSPC) via the cause chain first; falls back to message
 * string matching for cases where the errno isn't wrapped in an [ErrnoException].
 */
private fun IOException.isStorageFull(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is ErrnoException && t.errno == OsConstants.ENOSPC) return true
        t = t.cause
    }
    val msg = message?.lowercase() ?: return false
    return "enospc" in msg || "no space left" in msg || "disk full" in msg || "insufficient storage" in msg
}
