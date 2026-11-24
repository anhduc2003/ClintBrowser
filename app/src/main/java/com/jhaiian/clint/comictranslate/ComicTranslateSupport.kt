package com.jhaiian.clint.comictranslate

import android.content.Context
import androidx.preference.PreferenceManager
import com.jhaiian.clint.BuildConfig
import com.jhaiian.clint.translation.ClintTranslations
import com.jhaiian.clint.translation.TranslationRepository

object ComicTranslateSupport {

    private const val PREF_AUTO_SITES = "comic_translate_auto_sites"

    val available: Boolean get() = !BuildConfig.IS_FOSS

    fun targetLanguage(context: Context): String {
        val saved = ClintTranslations.savedTarget(context)
        if (saved != null) return ClintTranslations.normalize(saved) ?: saved
        val user = ClintTranslations.userLanguage(context)
        return ClintTranslations.normalize(user) ?: "en"
    }

    fun saveTargetLanguage(context: Context, code: String) {
        ClintTranslations.saveTarget(context, code)
    }

    fun autoSites(context: Context): Set<String> =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(PREF_AUTO_SITES, null).orEmpty().toSet()

    fun isAutoSite(context: Context, url: String?): Boolean {
        val origin = ClintTranslations.originOf(url) ?: return false
        return origin in autoSites(context)
    }

    fun setAutoSite(context: Context, url: String?, enabled: Boolean): Boolean {
        val origin = ClintTranslations.originOf(url) ?: return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val sites = prefs.getStringSet(PREF_AUTO_SITES, null).orEmpty().toMutableSet()
        if (enabled) sites.add(origin) else sites.remove(origin)
        prefs.edit().putStringSet(PREF_AUTO_SITES, sites).apply()
        return true
    }

    suspend fun sourceLanguage(context: Context, hint: String?, script: ComicScript): String? {
        val normalizer = hint?.let { ClintTranslations.normalize(it) }
        val supported = TranslationRepository.registry(context)?.fromLanguages.orEmpty()
        val candidates = listOfNotNull(normalizer?.takeIf { script.matchesLanguage(it) }, script.defaultSourceLanguage)
        return candidates.firstOrNull { candidate -> supported.any { it.equals(candidate, ignoreCase = true) } }
    }

    suspend fun targetOptions(context: Context): List<String> =
        TranslationRepository.registry(context)?.toLanguages.orEmpty()
}
