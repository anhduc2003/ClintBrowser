package com.jhaiian.clint.comictranslate

import com.jhaiian.clint.downloads.ClintDownloadManager
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Translates the text read from a comic page when the on-device model is not available.
 *
 * Three engines are tried in order and the first one that returns every line wins, so a
 * single endpoint being down, rate limiting us, or refusing the device's address does not
 * end the page in "Không dịch được trang này". When all three fail the reason from each one
 * is kept, which is what the failure card shows under the message.
 */
internal object NetworkTranslator {

    sealed interface Result {
        data class Success(val texts: List<String>) : Result
        data class Failure(val reason: String) : Result
    }

    private sealed interface Attempt {
        data class Done(val texts: List<String>) : Attempt
        data class Error(val message: String) : Attempt
    }

    private const val GOOGLE_BATCH_ENDPOINT = "https://clients5.google.com/translate_a/t"
    private const val GOOGLE_GTX_ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val MY_MEMORY_ENDPOINT = "https://api.mymemory.translated.net/get"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val MAX_QUERY_CHARS = 1200
    private const val MAX_TEXTS_PER_REQUEST = 12
    private const val MY_MEMORY_MAX_CHARS = 480
    private const val TOTAL_BUDGET_MS = 90_000L
    private const val REQUEST_TIMEOUT_SECONDS = 20L

    private val client by lazy {
        ClintDownloadManager.httpClient.newBuilder()
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    suspend fun translate(from: String, to: String, texts: List<String>): Result =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext Result.Success(emptyList())
            val payload = texts.filter { it.isNotBlank() }
            if (payload.isEmpty()) return@withContext Result.Success(texts)
            val source = endpointLanguage(from)
            val target = endpointLanguage(to)
            val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
            val engines = listOf<Pair<String, () -> Attempt>>(
                "google" to { googleBatch(source, target, payload, deadline) },
                "gtx" to { googleGtx(source, target, payload, deadline) },
                "mymemory" to { myMemory(source, target, payload, deadline) }
            )
            val errors = ArrayList<String>(engines.size)
            for ((name, engine) in engines) {
                when (val attempt = engine()) {
                    is Attempt.Done -> return@withContext Result.Success(restore(texts, attempt.texts))
                    is Attempt.Error -> errors += "$name ${attempt.message}"
                }
            }
            Result.Failure(errors.joinToString("; "))
        }

    private fun googleBatch(from: String, to: String, texts: List<String>, deadline: Long): Attempt {
        val results = ArrayList<String>(texts.size)
        for (batch in chunk(texts)) {
            if (System.currentTimeMillis() > deadline) return Attempt.Error("deadline")
            val url = buildString {
                append(GOOGLE_BATCH_ENDPOINT)
                append("?client=dict-chrome-ex")
                append("&sl=").append(from)
                append("&tl=").append(to)
                batch.forEach { append("&q=").append(encode(it)) }
            }
            val done = when (val attempt = fetch(url, deadline, ::googleBatchLines)) {
                is Attempt.Error -> return attempt
                is Attempt.Done -> attempt
            }
            if (done.texts.size != batch.size) {
                return Attempt.Error("mismatch ${done.texts.size}/${batch.size}")
            }
            results += done.texts
        }
        return Attempt.Done(results)
    }

    private fun googleGtx(from: String, to: String, texts: List<String>, deadline: Long): Attempt {
        val results = ArrayList<String>(texts.size)
        for (batch in chunk(texts)) {
            if (System.currentTimeMillis() > deadline) return Attempt.Error("deadline")
            val url = buildString {
                append(GOOGLE_GTX_ENDPOINT)
                append("?client=gtx&dt=t")
                append("&sl=").append(from)
                append("&tl=").append(to)
                append("&q=").append(encode(batch.joinToString("\n")))
            }
            val done = when (val attempt = fetch(url, deadline, ::gtxLines)) {
                is Attempt.Error -> return attempt
                is Attempt.Done -> attempt
            }
            if (done.texts.size != batch.size) {
                return Attempt.Error("mismatch ${done.texts.size}/${batch.size}")
            }
            results += done.texts
        }
        return Attempt.Done(results)
    }

    private fun myMemory(from: String, to: String, texts: List<String>, deadline: Long): Attempt {
        val results = ArrayList<String>(texts.size)
        var translated = 0
        var lastError = "no answer"
        val pair = encode("$from|$to")
        for (text in texts) {
            if (System.currentTimeMillis() > deadline) break
            val url = buildString {
                append(MY_MEMORY_ENDPOINT)
                append("?q=").append(encode(text.take(MY_MEMORY_MAX_CHARS)))
                append("&langpair=").append(pair)
            }
            when (val attempt = fetch(url, deadline, ::memoryLine)) {
                is Attempt.Error -> lastError = attempt.message
                is Attempt.Done -> {
                    val line = attempt.texts.firstOrNull().orEmpty().trim()
                    if (line.isNotEmpty()) {
                        translated++
                        results += line
                    } else {
                        results += text
                    }
                }
            }
        }
        return if (translated == 0) Attempt.Error(lastError) else Attempt.Done(results)
    }

    private fun fetch(url: String, deadline: Long, parse: (String) -> List<String>): Attempt {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L) return Attempt.Error("deadline")
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Attempt.Error("HTTP ${response.code}")
                else Attempt.Done(parse(response.body.string()))
            }
        } catch (t: Throwable) {
            Attempt.Error(describe(t))
        }
    }

    private fun googleBatchLines(body: String): List<String> {
        val array = JSONArray(body)
        val lines = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is String -> lines += item
                is JSONArray -> lines += item.optString(0)
                else -> lines += ""
            }
        }
        return lines
    }

    /**
     * The gtx endpoint answers one joined string: every newline separated line comes back as
     * its own segment, so joining the segment texts restores the line breaks we sent.
     */
    private fun gtxLines(body: String): List<String> {
        val segments = JSONArray(body).optJSONArray(0) ?: throw IllegalStateException("no segments")
        val joined = buildString {
            for (index in 0 until segments.length()) {
                append(segments.optJSONArray(index)?.optString(0).orEmpty())
            }
        }
        return joined.trimEnd('\n').split('\n')
    }

    private fun memoryLine(body: String): List<String> {
        val json = JSONObject(body)
        if (json.optBoolean("quotaFinished")) throw IllegalStateException("quota finished")
        val line = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
        if (line.isBlank()) {
            val details = json.optString("responseDetails").take(60)
            throw IllegalStateException(details.ifBlank { "empty" })
        }
        return listOf(unescape(line))
    }

    private fun restore(texts: List<String>, translated: List<String>): List<String> {
        val result = ArrayList<String>(texts.size)
        var cursor = 0
        texts.forEach { text ->
            if (text.isBlank()) {
                result += ""
            } else {
                result += translated.getOrNull(cursor).orEmpty()
                cursor++
            }
        }
        return result
    }

    private fun chunk(texts: List<String>): List<List<String>> {
        val chunks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var length = 0
        texts.forEach { text ->
            val full = current.size >= MAX_TEXTS_PER_REQUEST || length + text.length > MAX_QUERY_CHARS
            if (current.isNotEmpty() && full) {
                chunks += current
                current = ArrayList()
                length = 0
            }
            current += text
            length += text.length + 1
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    private fun unescape(text: String): String = text
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun describe(t: Throwable): String {
        val name = t.javaClass.simpleName.ifBlank { "error" }
        val message = t.message?.replace('\n', ' ')?.take(70)
        return if (message.isNullOrBlank()) name else "$name $message"
    }

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun endpointLanguage(code: String): String = when {
        code.startsWith("zh-hans", ignoreCase = true) || code.equals("zh", ignoreCase = true) -> "zh-CN"
        code.startsWith("zh-hant", ignoreCase = true) || code.equals("zh-tw", ignoreCase = true) -> "zh-TW"
        code.equals("nb", ignoreCase = true) -> "no"
        code.equals("he", ignoreCase = true) -> "iw"
        code.equals("id", ignoreCase = true) -> "id"
        else -> code.substringBefore('-').ifBlank { "en" }
    }
}
