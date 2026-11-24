package com.jhaiian.clint.mediacapture.download

import com.jhaiian.clint.downloads.ClintDownloadManager
import okhttp3.Request

object DirectTrackResolver {

    private const val CHUNK_BYTES = 8L * 1024L * 1024L
    private const val MIN_CHUNKED_BYTES = 16L * 1024L * 1024L

    fun fetch(
        url: String,
        pageUrl: String,
        userAgent: String,
        extraHeaders: Map<String, String>,
        kind: TrackKind
    ): ResolvedTrack {
        val total = contentLength(url, pageUrl, userAgent, extraHeaders)
        val segments = if (total != null && total > MIN_CHUNKED_BYTES) {
            val chunked = ArrayList<SegmentSpec>()
            var offset = 0L
            var sequence = 0L
            while (offset < total) {
                val length = minOf(CHUNK_BYTES, total - offset)
                chunked += SegmentSpec(
                    url = url,
                    byteRangeOffset = offset,
                    byteRangeLength = length,
                    sequenceNumber = sequence
                )
                offset += length
                sequence++
            }
            chunked
        } else {
            listOf(SegmentSpec(url = url))
        }
        return ResolvedTrack(
            kind = kind,
            initSegment = null,
            segments = segments,
            containerHintExtension = if (kind == TrackKind.AUDIO) "m4a" else "mp4"
        )
    }

    private fun contentLength(
        url: String,
        pageUrl: String,
        userAgent: String,
        extraHeaders: Map<String, String>
    ): Long? {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
        StreamRequestHeaders.apply(builder, url, pageUrl, "", userAgent, extraHeaders)
        return runCatching {
            ClintDownloadManager.httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
            }
        }.getOrNull()
    }
}
