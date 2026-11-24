package com.jhaiian.clint.comictranslate

import com.jhaiian.clint.downloads.ClintDownloadManager
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

/**
 * Translates the text read from a comic page with Google's public translate endpoint.
 * On-device models stay the first choice when their language pack is installed, but a
 * page with no pack installed, or a pack that will not load, still gets translated.
 */
internal object NetworkTranslator {

    private const val ENDPOINT = "https://clients5.google.com/translate_a/t"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val MAX_QUERY_CHARS = 1200
    private const val MAX_TEXTS_PER_REQUEST = 12

    suspend fun translate(from: String, to: String, texts: List<String>): List<String>? = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val source = endpointLanguage(from)
        val target = endpointLanguage(to)
        val results = ArrayList<String>(texts.size)
        var index = 0
        while (index < texts.size) {
            val batch = ArrayList<String>()
            var length = 0
            while (index < texts.size && batch.size < MAX_TEXTS_PER_REQUEST) {
                val text = texts[index]
                if (batch.isNotEmpty() && length + text.length > MAX_QUERY_CHARS) break
                batch += text
                length += text.length
                index++
            }
            val translated = request(source, target, batch) ?: return@withContext null
            if (translated.size != batch.size) return@withContext null
            results += translated
        }
        results.takeIf { it.size == texts.size }
    }

    private fun request(from: String, to: String, texts: List<String>): List<String>? {
        val url = buildString {
            append(ENDPOINT)
            append("?client=dict-chrome-ex")
            append("&sl=").append(from)
            append("&tl=").append(to)
            texts.forEach { text ->
                append("&q=").append(URLEncoder.encode(text, "UTF-8"))
            }
        }
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return runCatching {
            ClintDownloadManager.httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val array = JSONArray(response.body.string())
                buildList<String> {
                    for (index in 0 until array.length()) {
                        when (val item = array.opt(index)) {
                            is String -> add(item)
                            is JSONArray -> add(item.optString(0))
                            else -> add("")
                        }
                    }
                }
            }
        }.getOrNull()
    }

    private fun endpointLanguage(code: String): String = when {
        code.startsWith("zh-hans", ignoreCase = true) || code.equals("zh", ignoreCase = true) -> "zh-CN"
        code.startsWith("zh-hant", ignoreCase = true) || code.equals("zh-tw", ignoreCase = true) -> "zh-TW"
        code.equals("nb", ignoreCase = true) -> "no"
        code.equals("he", ignoreCase = true) -> "iw"
        code.equals("id", ignoreCase = true) -> "id"
        else -> code.substringBefore('-').ifBlank { "en" }
    }
}
