package com.jhaiian.clint.comictranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal object ComicOcr {

    private const val MIN_UPSCALE_WIDTH = 1100

    private val clients = HashMap<ComicScript, TextRecognizer>()

    suspend fun recognize(context: Context, bitmap: Bitmap, script: ComicScript): List<ComicOcrLine> {
        val scaled = upscale(bitmap)
        return try {
            val text = detect(script, scaled)
            val scaleX = bitmap.width.toFloat() / scaled.width
            val scaleY = bitmap.height.toFloat() / scaled.height
            text.textBlocks.flatMap { block -> block.lines.mapNotNull { line -> toLine(line, scaleX, scaleY) } }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /**
     * ML Kit keeps the delegate behind a recognizer in its component registry, and closing a
     * client releases it for every client built from the same options. The next getClient()
     * then reads a component that is no longer registered and throws a NullPointerException
     * from inside ML Kit, which is why one client per script is created once and kept for the
     * life of the process. A page that fails anyway gets a single retry on a client built from
     * a fresh options object, never on a closed one.
     */
    private suspend fun detect(script: ComicScript, bitmap: Bitmap): Text {
        runCatching { process(client(script), bitmap) }.getOrNull()?.let { return it }
        return process(replacement(script), bitmap)
    }

    @Synchronized
    private fun client(script: ComicScript): TextRecognizer =
        clients.getOrPut(script) { TextRecognition.getClient(optionsFor(script)) }

    @Synchronized
    private fun replacement(script: ComicScript): TextRecognizer =
        TextRecognition.getClient(optionsFor(script)).also { clients[script] = it }

    private fun optionsFor(script: ComicScript): TextRecognizerOptionsInterface = when (script) {
        ComicScript.LATIN -> TextRecognizerOptions.Builder().build()
        ComicScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
        ComicScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
        ComicScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
    }

    private suspend fun process(recognizer: TextRecognizer, bitmap: Bitmap): Text =
        withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { text -> if (continuation.isActive) continuation.resume(text) }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    }
            }
        }

    private fun toLine(line: Text.Line, scaleX: Float, scaleY: Float): ComicOcrLine? {
        val box = line.boundingBox ?: return null
        val text = line.text.trim()
        if (text.isEmpty()) return null
        val scaled = Rect(
            (box.left * scaleX).toInt(),
            (box.top * scaleY).toInt(),
            (box.right * scaleX).toInt(),
            (box.bottom * scaleY).toInt()
        )
        if (scaled.width() <= 0 || scaled.height() <= 0) return null
        return ComicOcrLine(bounds = scaled, text = text)
    }

    private fun upscale(bitmap: Bitmap): Bitmap {
        if (bitmap.width >= MIN_UPSCALE_WIDTH) return bitmap
        val factor = MIN_UPSCALE_WIDTH.toFloat() / bitmap.width
        val height = (bitmap.height * factor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, MIN_UPSCALE_WIDTH, height, true)
    }
}
