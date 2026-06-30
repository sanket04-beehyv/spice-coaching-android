package com.medtroniclabs.microcoaching.ui.document

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnErrorListener
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.medtroniclabs.microcoaching.R
import java.io.File

/**
 * In-app PDF preview backed by DImuthuUpe/AndroidPdfViewer (`PDFView`).
 *
 * The wrapped library handles the hard parts — vertical scroll, pinch-zoom,
 * double-tap zoom, fling, and page-jump — natively over `pdfium-android`.
 *
 * The PDF is supplied as an already-downloaded local [file] (resolved by
 * [DocumentPreviewActivity] via the durable `AssetCache`, so it works offline);
 * `PDFView` mmaps it directly. We add:
 *  - Initial page jump from [startPage] via `.defaultPage(startPage - 1)`.
 *  - Floating bottom-right control overlay: page indicator + up / down
 *    page navigation. Pinch + double-tap give the user zoom for free, so
 *    no zoom buttons in v1.
 *  - Failure path: surfaces "Could not open document" + an "Open in browser"
 *    CTA that fires [externalFallback].
 */
@Composable
internal fun PdfPagerScreen(
    file: File,
    startPage: Int?,
    externalFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var loadFailed by remember(file) { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(Color(0xFFEEEEEE))) {
        if (loadFailed) {
            PdfFailure(externalFallback, Modifier.align(Alignment.Center))
        } else {
            PdfBodyWithOverlay(
                file = file,
                startPage = startPage,
                onLoadError = { reason ->
                    Log.w(TAG, "PDFView load failed: $reason")
                    loadFailed = true
                },
            )
        }
    }
}

/**
 * Hosts the `PDFView` + the floating page-nav overlay. Page state is hoisted
 * here so both the AndroidView's `OnPageChangeListener` and the overlay
 * buttons can talk to it.
 */
@Composable
private fun PdfBodyWithOverlay(
    file: File,
    startPage: Int?,
    onLoadError: (Throwable) -> Unit,
) {
    // `pdfView` is captured into the overlay's `onClick` handlers so the
    // jump buttons can call `pdfView.jumpTo(...)`. Using a MutableState rather
    // than `remember { mutableStateOf(...) }` directly so the AndroidView's
    // factory hook can write into it.
    val pdfViewRef: MutableState<PDFView?> = remember { mutableStateOf(null) }
    var currentPage by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PDFView(ctx, null).also { pdfViewRef.value = it }
            },
            update = { view ->
                // Re-issuing .load() on every recomposition would thrash
                // pdfium. Only load once — when the file or startPage changes,
                // the parent re-creates this composable via produceState.
                if (view.tag == file.absolutePath) return@AndroidView
                view.tag = file.absolutePath

                view.fromFile(file)
                    .defaultPage(((startPage ?: 1) - 1).coerceAtLeast(0))
                    .enableSwipe(true)
                    .swipeHorizontal(false)
                    .enableDoubletap(true)
                    .spacing(8)
                    .enableAntialiasing(true)
                    .pageFitPolicy(com.github.barteksc.pdfviewer.util.FitPolicy.WIDTH)
                    // petretiandrea's listeners are *Kotlin* `interface` (not
                    // `fun interface`), so SAM lambdas don't compile — anonymous
                    // object literals are the explicit form.
                    .onLoad(object : OnLoadCompleteListener {
                        override fun loadComplete(totalPages: Int) {
                            pageCount = totalPages
                            Log.i(TAG, "PDFView loaded — pageCount=$totalPages startPage=$startPage")
                        }
                    })
                    .onPageChange(object : OnPageChangeListener {
                        override fun onPageChanged(page: Int, pageCount2: Int) {
                            currentPage = page
                            pageCount = pageCount2
                        }
                    })
                    .onError(object : OnErrorListener {
                        override fun onError(t: Throwable?) {
                            if (t != null) onLoadError(t)
                        }
                    })
                    .load()
            },
        )

        if (pageCount > 0) {
            PageNavOverlay(
                currentPage = currentPage,
                pageCount = pageCount,
                onPrev = {
                    val target = (currentPage - 1).coerceAtLeast(0)
                    pdfViewRef.value?.jumpTo(target, true)
                },
                onNext = {
                    val target = (currentPage + 1).coerceAtMost(pageCount - 1)
                    pdfViewRef.value?.jumpTo(target, true)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun PageNavOverlay(
    currentPage: Int,
    pageCount: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = onPrev,
                enabled = currentPage > 0,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.chat_source_page_prev),
                )
            }
            Text(
                // 1-indexed for the user — "47 / 168" reads more naturally than "46 / 168".
                text = "${currentPage + 1} / $pageCount",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            IconButton(
                onClick = onNext,
                enabled = currentPage < pageCount - 1,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.chat_source_page_next),
                )
            }
        }
    }
}

@Composable
private fun PdfFailure(externalFallback: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.chat_source_unavailable),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = externalFallback) {
            Text(stringResource(R.string.chat_source_open_externally))
        }
    }
}

private const val TAG = "PdfPagerScreen"
