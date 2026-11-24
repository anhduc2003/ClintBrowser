package com.jhaiian.clint.comictranslate

import android.graphics.Bitmap
import android.graphics.Rect

enum class ComicScript(val defaultSourceLanguage: String) {
    LATIN("en"),
    KOREAN("ko"),
    JAPANESE("ja"),
    CHINESE("zh-Hans")
}

data class ComicOcrLine(val bounds: Rect, val text: String)

data class ComicTextBlock(
    val bounds: Rect,
    val original: String,
    val translation: String? = null
)

enum class ComicTranslationProvider { ON_DEVICE, ONLINE }

data class ComicTranslationPage(
    val bitmap: Bitmap,
    val blocks: List<ComicTextBlock>,
    val sourceLanguage: String,
    val targetLanguage: String,
    val provider: ComicTranslationProvider,
    val autoTranslateSite: Boolean
)

enum class ComicFailure {
    NO_TEXT,
    NO_SOURCE_LANGUAGE,
    SAME_LANGUAGE,
    MODEL_MISSING,
    CAPTURE_FAILED,
    TRANSLATION_FAILED
}

sealed interface ComicOverlayState {
    data class Analyzing(val bitmap: Bitmap?) : ComicOverlayState
    data class Downloading(val bitmap: Bitmap, val language: String, val bytes: Long) : ComicOverlayState
    data class Ready(val page: ComicTranslationPage) : ComicOverlayState
    data class Failed(
        val failure: ComicFailure,
        val bitmap: Bitmap?,
        val downloadBytes: Long = 0L,
        val sourceLanguage: String? = null
    ) : ComicOverlayState
}

sealed interface ComicTranslationOutcome {
    data class Success(val page: ComicTranslationPage) : ComicTranslationOutcome
    data class Failure(
        val reason: ComicFailure,
        val downloadBytes: Long = 0L,
        val sourceLanguage: String? = null
    ) : ComicTranslationOutcome
}

fun ComicScript.forLanguage(code: String?): ComicScript = when {
    code == null -> this
    code.startsWith("ko", ignoreCase = true) -> ComicScript.KOREAN
    code.startsWith("ja", ignoreCase = true) -> ComicScript.JAPANESE
    code.startsWith("zh", ignoreCase = true) -> ComicScript.CHINESE
    code.startsWith("vi", ignoreCase = true) ||
        code.startsWith("en", ignoreCase = true) ||
        code.startsWith("id", ignoreCase = true) -> ComicScript.LATIN
    else -> this
}

fun ComicScript.matchesLanguage(code: String?): Boolean {
    if (code == null) return false
    return when (this) {
        ComicScript.KOREAN -> code.startsWith("ko", ignoreCase = true)
        ComicScript.JAPANESE -> code.startsWith("ja", ignoreCase = true)
        ComicScript.CHINESE -> code.startsWith("zh", ignoreCase = true)
        ComicScript.LATIN -> !code.startsWith("ko", true) &&
            !code.startsWith("ja", true) &&
            !code.startsWith("zh", true)
    }
}
