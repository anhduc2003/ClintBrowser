package com.jhaiian.clint.comictranslate

import android.content.Context
import android.graphics.Bitmap
import com.jhaiian.clint.translation.ClintTranslations
import com.jhaiian.clint.translation.TranslationEngine
import com.jhaiian.clint.translation.TranslationRepository

internal object ComicTranslationEngine {

    suspend fun translate(
        context: Context,
        bitmap: Bitmap,
        pageUrl: String,
        pageLanguage: String?,
        targetLanguage: String
    ): ComicTranslationOutcome {
        val target = ClintTranslations.normalize(targetLanguage) ?: targetLanguage
        val hinted = ComicScript.LATIN.forLanguage(pageLanguage)
        var script = hinted
        var lines = ComicOcr.recognize(context, bitmap, hinted)
        if (lines.isEmpty() && hinted != ComicScript.LATIN) {
            lines = ComicOcr.recognize(context, bitmap, ComicScript.LATIN)
            if (lines.isNotEmpty()) script = ComicScript.LATIN
        }
        val blocks = groupComicLines(lines)
        if (blocks.isEmpty()) return ComicTranslationOutcome.Failure(ComicFailure.NO_TEXT)

        val source = ComicTranslateSupport.sourceLanguage(context, pageLanguage, script)
            ?: return ComicTranslationOutcome.Failure(ComicFailure.NO_SOURCE_LANGUAGE)
        if (ClintTranslations.sameLanguage(source, target)) {
            return ComicTranslationOutcome.Failure(ComicFailure.SAME_LANGUAGE, sourceLanguage = source)
        }
        val missingBytes = TranslationRepository.missingSize(context, source, target)
        if (missingBytes != null && missingBytes == 0L) {
            val onDevice = translateOnDevice(context, source, target, blocks)
            if (onDevice != null) {
                return ComicTranslationOutcome.Success(
                    page(context, bitmap, pageUrl, onDevice, source, target, ComicTranslationProvider.ON_DEVICE)
                )
            }
        }

        val online = NetworkTranslator.translate(source, target, blocks.map { it.original })
        if (online != null) {
            val translated = blocks.mapIndexedNotNull { index, block ->
                online.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }?.let { block.copy(translation = it) }
            }
            if (translated.isNotEmpty()) {
                return ComicTranslationOutcome.Success(
                    page(context, bitmap, pageUrl, translated, source, target, ComicTranslationProvider.ONLINE)
                )
            }
        }

        if (missingBytes != null && missingBytes > 0L) {
            return ComicTranslationOutcome.Failure(
                ComicFailure.MODEL_MISSING,
                downloadBytes = missingBytes,
                sourceLanguage = source
            )
        }
        return ComicTranslationOutcome.Failure(ComicFailure.TRANSLATION_FAILED, sourceLanguage = source)
    }

    private fun page(
        context: Context,
        bitmap: Bitmap,
        pageUrl: String,
        blocks: List<ComicTextBlock>,
        source: String,
        target: String,
        provider: ComicTranslationProvider
    ) = ComicTranslationPage(
        bitmap = bitmap,
        blocks = blocks,
        sourceLanguage = source,
        targetLanguage = target,
        provider = provider,
        autoTranslateSite = ComicTranslateSupport.isAutoSite(context, pageUrl)
    )

    suspend fun downloadModel(context: Context, from: String, to: String): Boolean =
        TranslationEngine.prepare(context, from, to)

    private suspend fun translateOnDevice(
        context: Context,
        from: String,
        to: String,
        blocks: List<ComicTextBlock>
    ): List<ComicTextBlock>? {
        if (!TranslationEngine.prepare(context, from, to)) return null
        val results = TranslationEngine.translate(context, from, to, blocks.map { it.original }, false) ?: return null
        val translated = blocks.mapIndexedNotNull { index, block ->
            val text = results.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            block.copy(translation = text)
        }
        return translated.takeIf { it.isNotEmpty() }
    }
}
