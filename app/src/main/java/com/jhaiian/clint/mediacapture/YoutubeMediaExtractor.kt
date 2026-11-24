package com.jhaiian.clint.mediacapture

import android.net.Uri
import com.jhaiian.clint.downloads.ClintDownloadManager
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

sealed interface YoutubeExtraction {
    data class Success(val title: String, val media: List<DetectedMedia>) : YoutubeExtraction
    data class Failure(val reason: String) : YoutubeExtraction
}

sealed interface YoutubeDialogState {
    data object None : YoutubeDialogState
    data object Loading : YoutubeDialogState
    data object Ready : YoutubeDialogState
    data class Failed(val reason: String) : YoutubeDialogState
}

object YoutubeMediaExtractor {

    const val AUDIO_GROUP = "youtube-audio"
    const val DIRECT_FORMAT = "DIRECT"

    private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

    private data class YoutubeClient(
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val deviceMake: String,
        val deviceModel: String,
        val osName: String,
        val osVersion: String,
        val androidSdkVersion: Int? = null
    )

    private val CLIENTS = listOf(
        YoutubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            deviceMake = "Oculus",
            deviceModel = "Quest 5",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32
        ),
        YoutubeClient(
            clientName = "IOS",
            clientVersion = "20.10.4",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.2.22D82"
        )
    )
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private val YOUTUBE_DOMAINS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")

    private fun isYouTubeUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = uri.host?.lowercase() ?: return false
        return YOUTUBE_DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    fun videoIdFrom(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!isYouTubeUrl(url)) return null
        val host = uri.host?.lowercase().orEmpty()
        uri.getQueryParameter("v")?.trim()?.takeIf { VIDEO_ID.matches(it) }?.let { return it }
        val path = uri.pathSegments.orEmpty().filter { it.isNotBlank() }
        if (host == "youtu.be" || host.endsWith(".youtu.be")) {
            return path.firstOrNull()?.takeIf { VIDEO_ID.matches(it) }
        }
        val marker = path.indexOfFirst { it == "shorts" || it == "embed" || it == "live" || it == "v" }
        if (marker < 0) return null
        return path.getOrNull(marker + 1)?.takeIf { VIDEO_ID.matches(it) }
    }

    suspend fun extract(pageUrl: String): YoutubeExtraction = withContext(Dispatchers.IO) {
        val videoId = videoIdFrom(pageUrl)
            ?: return@withContext YoutubeExtraction.Failure("This link does not point at a single YouTube video")
        var failure = "Could not reach YouTube"
        for (client in CLIENTS) {
            val player = try {
                requestPlayer(client, videoId)
            } catch (e: IOException) {
                failure = e.message ?: failure
                continue
            } catch (_: JSONException) {
                failure = "YouTube sent an unexpected response"
                continue
            }
            val status = player.optJSONObject("playabilityStatus")
            val statusName = status?.optString("status").orEmpty()
            if (statusName != "OK") {
                failure = playabilityReason(status, statusName)
                continue
            }
            val media = try {
                buildMedia(pageUrl, videoId, player, client.userAgent)
            } catch (_: JSONException) {
                emptyList()
            }
            if (media.isEmpty()) {
                failure = "YouTube did not offer a downloadable stream"
                continue
            }
            val title = player.optJSONObject("videoDetails")?.optString("title").orEmpty().trim()
            return@withContext YoutubeExtraction.Success(title.ifBlank { videoId }, media)
        }
        YoutubeExtraction.Failure(failure)
    }

    private fun requestPlayer(client: YoutubeClient, videoId: String): JSONObject {
        val clientContext = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", client.clientVersion)
            .put("deviceMake", client.deviceMake)
            .put("deviceModel", client.deviceModel)
            .put("osName", client.osName)
            .put("osVersion", client.osVersion)
            .put("hl", "en")
            .put("gl", "US")
        client.androidSdkVersion?.let { clientContext.put("androidSdkVersion", it) }
        val payload = JSONObject()
            .put("context", JSONObject().put("client", clientContext))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val request = Request.Builder()
            .url(PLAYER_ENDPOINT)
            .header("User-Agent", client.userAgent)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        ClintDownloadManager.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("YouTube returned HTTP ${response.code}")
            }
            return JSONObject(response.body.string())
        }
    }

    private fun playabilityReason(status: JSONObject?, statusName: String): String {
        val reported = status?.optString("reason")?.takeIf { it.isNotBlank() }
        if (reported != null && reported.contains("not a bot", ignoreCase = true)) {
            return "YouTube asked every client to confirm this is not a bot. Try again in a few minutes or on another network"
        }
        reported?.let { return it }
        val screen = status?.optJSONObject("errorScreen")
        val renderer = screen?.optJSONObject("playerErrorMessageRenderer")
        renderer?.optJSONObject("reason")?.optString("simpleText")?.takeIf { it.isNotBlank() }?.let { return it }
        renderer?.optJSONObject("subreason")?.optString("simpleText")?.takeIf { it.isNotBlank() }?.let { return it }
        return when (statusName) {
            "LOGIN_REQUIRED" -> "YouTube wants a signed-in session for this video"
            "UNPLAYABLE", "ERROR" -> "YouTube marked this video as unavailable"
            "" -> "YouTube did not return a playable video"
            else -> "YouTube reported $statusName"
        }
    }

    private fun buildMedia(
        pageUrl: String,
        videoId: String,
        player: JSONObject,
        userAgent: String
    ): List<DetectedMedia> {
        val details = player.optJSONObject("videoDetails")
        val duration = details?.optString("lengthSeconds")?.toDoubleOrNull()?.takeIf { it > 0.0 }
        val streaming = player.optJSONObject("streamingData") ?: return emptyList()
        val now = System.currentTimeMillis()
        val headers = mapOf("User-Agent" to userAgent, "Referer" to pageUrl)

        val hls = streaming.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
        if (hls != null && details?.optBoolean("isLive") == true) {
            return listOf(
                DetectedMedia(
                    id = "youtube-$videoId-live",
                    url = hls,
                    kind = MediaKind.VIDEO,
                    format = "HLS",
                    mimeType = "application/vnd.apple.mpegurl",
                    label = details.optString("title").takeIf { it.isNotBlank() },
                    durationSeconds = null,
                    isLive = true,
                    hasAudio = true,
                    groupUrl = pageUrl,
                    pageUrl = pageUrl,
                    detectedAtMillis = now,
                    requestHeaders = headers
                )
            )
        }

        val media = mutableListOf<DetectedMedia>()
        streaming.optJSONArray("formats")?.forEachObject { format ->
            if (!isDownloadable(format, video = true)) return@forEachObject
            val itag = format.optInt("itag")
            media += directMedia(format, videoId, itag, MediaKind.VIDEO, pageUrl, duration, now, headers).copy(
                hasAudio = true,
                groupUrl = null,
                audioGroupRef = null
            )
        }
        bestAudioFormat(streaming.optJSONArray("adaptiveFormats"))?.let { format ->
            val itag = format.optInt("itag")
            media += directMedia(format, videoId, itag, MediaKind.AUDIO, pageUrl, duration, now, headers).copy(
                groupUrl = pageUrl,
                mediaGroupId = AUDIO_GROUP,
                isDefaultTrack = true,
                label = audioLabel(format)
            )
        }
        streaming.optJSONArray("adaptiveFormats")?.forEachObject { format ->
            if (!isDownloadable(format, video = true)) return@forEachObject
            val itag = format.optInt("itag")
            media += directMedia(format, videoId, itag, MediaKind.VIDEO, pageUrl, duration, now, headers).copy(
                hasAudio = false,
                groupUrl = pageUrl,
                audioGroupRef = AUDIO_GROUP
            )
        }
        return media
    }

    private fun directMedia(
        format: JSONObject,
        videoId: String,
        itag: Int,
        kind: MediaKind,
        pageUrl: String,
        duration: Double?,
        now: Long,
        headers: Map<String, String>
    ): DetectedMedia {
        val mimeType = format.optString("mimeType").takeIf { it.isNotBlank() }
        return DetectedMedia(
            id = "youtube-$videoId-$itag",
            url = format.optString("url"),
            kind = kind,
            format = DIRECT_FORMAT,
            mimeType = mimeType,
            label = if (kind == MediaKind.AUDIO) audioLabel(format) else qualityLabel(format),
            width = format.optInt("width").takeIf { it > 0 },
            height = format.optInt("height").takeIf { it > 0 },
            bandwidthBitsPerSec = format.optLong("bitrate").takeIf { it > 0 },
            durationSeconds = duration,
            sizeBytes = format.optString("contentLength").toLongOrNull()?.takeIf { it > 0 },
            isLive = false,
            hasAudio = false,
            groupUrl = pageUrl,
            pageUrl = pageUrl,
            detectedAtMillis = now,
            requestHeaders = headers,
            representationId = "itag:$itag",
            codecs = codecsOf(mimeType),
            frameRate = format.optDouble("fps").takeIf { it > 0.0 },
            channels = format.optInt("audioChannels").takeIf { it > 0 }
        )
    }

    private fun bestAudioFormat(formats: JSONArray?): JSONObject? {
        var best: JSONObject? = null
        var bestBitrate = Long.MIN_VALUE
        formats?.forEachObject { format ->
            if (!isDownloadable(format, video = false)) return@forEachObject
            val bitrate = audioBitrate(format)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                best = format
            }
        }
        return best
    }

    private fun isDownloadable(format: JSONObject, video: Boolean): Boolean {
        val mimeType = format.optString("mimeType")
        if (!mimeType.startsWith(if (video) "video/mp4" else "audio/mp4")) return false
        if (format.optString("url").isBlank()) return false
        val codecs = codecsOf(mimeType).orEmpty().lowercase()
        return if (video) codecs.contains("avc1") else codecs.contains("mp4a")
    }

    private fun codecsOf(mimeType: String?): String? =
        mimeType
            ?.substringAfter("codecs=\"", "")
            ?.substringBefore('"')
            ?.takeIf { it.isNotBlank() }

    private fun qualityLabel(format: JSONObject): String? {
        val label = format.optString("qualityLabel").takeIf { it.isNotBlank() }
        val fps = format.optInt("fps")
        return if (label != null && fps >= 50 && !label.contains("$fps")) "$label$fps" else label
    }

    private fun audioLabel(format: JSONObject): String {
        val kbps = (audioBitrate(format) / 1000L).takeIf { it > 0 } ?: 128L
        return "AAC $kbps kbps"
    }

    private fun audioBitrate(format: JSONObject): Long =
        format.optLong("averageBitrate").takeIf { it > 0 }
            ?: format.optLong("bitrate").takeIf { it > 0 }
            ?: 0L

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(block)
        }
    }
}
