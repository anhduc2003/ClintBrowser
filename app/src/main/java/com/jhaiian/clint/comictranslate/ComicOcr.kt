package com.jhaiian.clint.comictranslate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

internal object ComicOcr {

    private const val MIN_UPSCALE_WIDTH = 1100

    fun recognizerFor(script: ComicScript): TextRecognizer = when (script) {
        ComicScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        ComicScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        ComicScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        ComicScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(context: Context, bitmap: Bitmap, script: ComicScript): List<ComicOcrLine> {
        val scaled = upscale(bitmap)
        val recognizer = recognizerFor(script)
        return try {
            val text = withContext(Dispatchers.Default) { process(recognizer, scaled) }
            val scaleX = bitmap.width.toFloat() / scaled.width
            val scaleY = bitmap.height.toFloat() / scaled.height
            text.textBlocks.flatMap { block -> block.lines.mapNotNull { line -> toLine(line, scaleX, scaleY) } }
        } finally {
            recognizer.close()
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private suspend fun process(recognizer: TextRecognizer, bitmap: Bitmap): Text =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text -> if (continuation.isActive) continuation.resume(text) }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
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
