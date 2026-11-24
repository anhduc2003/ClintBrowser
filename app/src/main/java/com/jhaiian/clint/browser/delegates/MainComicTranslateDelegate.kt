package com.jhaiian.clint.browser.delegates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.jhaiian.clint.R
import com.jhaiian.clint.browser.MainActivity
import com.jhaiian.clint.browser.webview.ClintWebView
import com.jhaiian.clint.comictranslate.ComicFailure
import com.jhaiian.clint.comictranslate.ComicOverlayState
import com.jhaiian.clint.comictranslate.ComicTranslationEngine
import com.jhaiian.clint.comictranslate.ComicTranslationOutcome
import com.jhaiian.clint.comictranslate.ComicTranslationOverlay
import com.jhaiian.clint.comictranslate.ComicTranslateSupport
import com.jhaiian.clint.ui.showClintSnackbar
import com.jhaiian.clint.ui.theme.ClintComposeTheme
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

private const val AUTO_TRANSLATE_DELAY_MS = 1_200L
private const val CAPTURE_TIMEOUT_MS = 4_000L
private const val TRANSLATE_TIMEOUT_MS = 180_000L

internal fun MainActivity.onMenuComicTranslate() {
    if (!ComicTranslateSupport.available) {
        showClintSnackbar(message = getString(R.string.comic_translate_unavailable))
        return
    }
    if (overlayContent != null) return
    startComicTranslation()
}

internal fun MainActivity.maybeAutoTranslateComic(url: String?) {
    if (!ComicTranslateSupport.available) return
    if (url == null || !url.startsWith("http")) return
    if (!ComicTranslateSupport.isAutoSite(this, url)) return
    if (overlayContent != null) return
    lifecycleScope.launch {
        delay(AUTO_TRANSLATE_DELAY_MS)
        if (overlayContent != null) return@launch
        if (tabManager.activeTab?.webView?.url != url) return@launch
        startComicTranslation()
    }
}

private fun MainActivity.startComicTranslation() {
    val webView = tabManager.activeTab?.webView ?: return
    val pageUrl = webView.url?.takeIf { it.startsWith("http") } ?: return
    val pageLanguage = (webView as? ClintWebView)?.translator?.state?.value?.docLanguage
    val theme = prefs.getString("app_theme", "system") ?: "system"
    val overlayState = mutableStateOf<ComicOverlayState?>(null)
    var targetOptions by mutableStateOf<List<String>>(emptyList())
    var showOriginal by mutableStateOf(false)

    fun dismiss() {
        overlayContent = null
    }

    fun retranslate(bitmap: Bitmap, target: String) {
        overlayState.value = ComicOverlayState.Analyzing(bitmap)
        lifecycleScope.launch {
            overlayState.value = translateComicPage(bitmap, pageUrl, pageLanguage, target)
        }
    }

    fun captureAndTranslate() {
        overlayState.value = ComicOverlayState.Analyzing(overlayState.value?.bitmap())
        lifecycleScope.launch {
            val captured = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { captureContentBitmapAsync() }
            if (captured == null) {
                overlayState.value = ComicOverlayState.Failed(ComicFailure.CAPTURE_FAILED, null)
                return@launch
            }
            retranslate(captured, ComicTranslateSupport.targetLanguage(this@startComicTranslation))
        }
    }

    fun reloadTargetOptions() {
        lifecycleScope.launch {
            targetOptions = ComicTranslateSupport.targetOptions(this@startComicTranslation).sortedBy { it }
        }
    }

    overlayContent = {
        ClintComposeTheme(theme = theme) {
            val state = overlayState.value
            if (state != null) {
                ComicTranslationOverlay(
                    state = state,
                    showOriginal = showOriginal,
                    targetOptions = targetOptions,
                    onToggleOriginal = { showOriginal = !showOriginal },
                    onTargetSelected = { code ->
                        ComicTranslateSupport.saveTargetLanguage(this, code)
                        val bitmap = state.bitmap()
                        if (bitmap != null) retranslate(bitmap, code)
                    },
                    onToggleAutoSite = { enabled ->
                        ComicTranslateSupport.setAutoSite(this, pageUrl, enabled)
                        val current = overlayState.value
                        if (current is ComicOverlayState.Ready) {
                            overlayState.value = ComicOverlayState.Ready(current.page.copy(autoTranslateSite = enabled))
                        }
                    },
                    onDownloadModel = {
                        val failure = overlayState.value as? ComicOverlayState.Failed
                        val bitmap = failure?.bitmap
                        val source = failure?.sourceLanguage
                        if (bitmap != null && source != null) {
                            val target = ComicTranslateSupport.targetLanguage(this)
                            overlayState.value = ComicOverlayState.Downloading(bitmap, target, failure.downloadBytes)
                            lifecycleScope.launch {
                                val downloaded = ComicTranslationEngine.downloadModel(this@startComicTranslation, source, target)
                                overlayState.value = if (downloaded) {
                                    ComicOverlayState.Analyzing(bitmap)
                                } else {
                                    ComicOverlayState.Failed(ComicFailure.TRANSLATION_FAILED, bitmap, sourceLanguage = source)
                                }
                                if (downloaded) {
                                    overlayState.value = translateComicPage(bitmap, pageUrl, pageLanguage, target)
                                }
                            }
                        }
                    },
                    onRetry = {
                        val bitmap = overlayState.value?.bitmap()
                        if (bitmap == null) captureAndTranslate()
                        else retranslate(bitmap, ComicTranslateSupport.targetLanguage(this))
                    },
                    onDismiss = { dismiss() }
                )
            }
        }
    }

    overlayState.value = ComicOverlayState.Analyzing(null)
    reloadTargetOptions()
    captureAndTranslate()
}

private suspend fun MainActivity.translateComicPage(
    bitmap: Bitmap,
    pageUrl: String,
    pageLanguage: String?,
    target: String
): ComicOverlayState {
    val outcome = withTimeoutOrNull(TRANSLATE_TIMEOUT_MS) {
        runCatching {
            ComicTranslationEngine.translate(this@translateComicPage, bitmap, pageUrl, pageLanguage, target)
        }.getOrElse { ComicTranslationOutcome.Failure(ComicFailure.TRANSLATION_FAILED) }
    } ?: ComicTranslationOutcome.Failure(ComicFailure.TRANSLATION_FAILED)
    return when (outcome) {
        is ComicTranslationOutcome.Success -> ComicOverlayState.Ready(outcome.page)
        is ComicTranslationOutcome.Failure -> ComicOverlayState.Failed(
            failure = outcome.reason,
            bitmap = bitmap,
            downloadBytes = outcome.downloadBytes,
            sourceLanguage = outcome.sourceLanguage
        )
    }
}

private fun ComicOverlayState?.bitmap(): Bitmap? = when (this) {
    is ComicOverlayState.Analyzing -> bitmap
    is ComicOverlayState.Downloading -> bitmap
    is ComicOverlayState.Ready -> page.bitmap
    is ComicOverlayState.Failed -> bitmap
    null -> null
}

/**
 * Captures what the user is looking at, cropped to the composition root so the translated
 * boxes line up with the page underneath. PixelCopy grabs the GPU surface, which is the only
 * way to include a hardware-rendered WebView; the software draw is a fallback.
 */
private suspend fun MainActivity.captureContentBitmapAsync(): Bitmap? {
    val content = findViewById<View>(android.R.id.content) ?: return null
    val width = content.width
    val height = content.height
    if (width <= 0 || height <= 0) return null
    return withContext(Dispatchers.Main) {
        val root = content.rootView ?: content
        val windowBitmap = runCatching {
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return@withContext null
        val copied = suspendCancellableCoroutine<Boolean> { continuation ->
            runCatching {
                PixelCopy.request(window, windowBitmap, { result ->
                    if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
                }, Handler(Looper.getMainLooper()))
            }.onFailure { if (continuation.isActive) continuation.resume(false) }
        }
        val location = IntArray(2)
        content.getLocationInWindow(location)
        val cropped = if (copied) {
            runCatching {
                val left = location[0].coerceIn(0, (windowBitmap.width - 1).coerceAtLeast(0))
                val top = location[1].coerceIn(0, (windowBitmap.height - 1).coerceAtLeast(0))
                val cropWidth = minOf(width, windowBitmap.width - left)
                val cropHeight = minOf(height, windowBitmap.height - top)
                if (cropWidth <= 0 || cropHeight <= 0) null
                else Bitmap.createBitmap(windowBitmap, left, top, cropWidth, cropHeight)
            }.getOrNull()
        } else null
        if (cropped != null) {
            windowBitmap.recycle()
            cropped
        } else {
            windowBitmap.recycle()
            runCatching {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    content.draw(Canvas(bitmap))
                }
            }.getOrNull()
        }
    }
}
