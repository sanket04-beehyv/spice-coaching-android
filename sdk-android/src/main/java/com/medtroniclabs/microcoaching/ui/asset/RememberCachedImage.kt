package com.medtroniclabs.microcoaching.ui.asset

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.data.asset.AssetKind
import java.io.File

/**
 * Resolves a remote image to a locally-cached [File] via
 * [com.medtroniclabs.microcoaching.data.asset.AssetCache], so it renders offline
 * after the first online view. Feed the returned file to Coil
 * (`rememberAsyncImagePainter(model = file)`).
 *
 * @param key stable asset identity (media `object_name`, or the path of a
 *   presigned URL). Re-resolution is keyed on this, so a rotated signature still
 *   hits the same cache entry. Null/blank keys resolve to null.
 * @param fetchUrl supplies a fresh presigned URL on a cache miss while online.
 *
 * Emits null until resolved, and stays null on an offline miss — callers render
 * their placeholder in that case.
 */
@Composable
fun rememberCachedImageFile(
    key: String?,
    fetchUrl: suspend () -> String?,
): State<File?> = produceState<File?>(initialValue = null, key) {
    val k = key?.takeIf { it.isNotBlank() }
    value = if (k == null) {
        null
    } else {
        runCatching {
            MicroCoachingSDK.getInstance().assetCache.localFile(k, AssetKind.IMAGE, fetchUrl = fetchUrl)
        }.getOrNull()
    }
}

/**
 * Variant for an already-presigned image URL (e.g. a module thumbnail): keys on
 * the URL path, so a rotated signature still resolves to the same cached file.
 */
@Composable
fun rememberCachedImageFileForUrl(url: String?): State<File?> =
    produceState<File?>(initialValue = null, url) {
        value = url?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                MicroCoachingSDK.getInstance().assetCache.localFileForUrl(it, AssetKind.IMAGE)
            }.getOrNull()
        }
    }
