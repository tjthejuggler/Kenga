package com.example.kenga.youtube

import com.example.kenga.youtube.YoutubeUrlParser.canonicalWatchUrl
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * YouTube metadata via the public oEmbed endpoint (Phase 2 scope):
 * `https://www.youtube.com/oembed?url=<watch>&format=json` → title + channel.
 *
 * Deliberately does NOT pull in NewPipeExtractor — that arrives with the
 * Phase 3 download pipeline (which needs stream lists + duration). oEmbed is
 * lightweight, keyless and enough for the Library card metadata at this stage.
 *
 * Blocking HTTP — every public function must be invoked from
 * [kotlinx.coroutines.Dispatchers.IO].
 */
class YoutubeMetaFetcher(private val client: OkHttpClient) {

    /** What the Library UI needs from an oEmbed lookup. */
    @Serializable
    data class VideoMeta(
        val videoId: String,
        val title: String,
        val channel: String,
        val canonicalUrl: String,
    )

    /** oEmbed response document (subset — unknown fields ignored). */
    @Serializable
    private data class OEmbedResponse(
        val title: String? = null,
        val author_name: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetches title + channel for [videoId].
     *
     * @throws IOException on network failure or non-200 response — callers treat
     *   metadata as best-effort (the row persists regardless).
     */
    suspend fun fetch(videoId: String): VideoMeta = withContext(Dispatchers.IO) {
        val watchUrl = canonicalWatchUrl(videoId)
        val request = Request.Builder()
            .url("https://www.youtube.com/oembed?url=${java.net.URLEncoder.encode(watchUrl, "UTF-8")}&format=json")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("oEmbed HTTP ${response.code} for $videoId")
            }
            val body = response.body?.string().orEmpty()
            val doc = json.decodeFromString<OEmbedResponse>(body)
            VideoMeta(
                videoId = videoId,
                title = doc.title.orEmpty().ifBlank { videoId },
                channel = doc.author_name.orEmpty(),
                canonicalUrl = watchUrl,
            )
        }
    }

    /** [VideoMeta] plus the raw extractor result — used by the downloader + TranscriptFetcher. */
    data class FullInfo(val meta: VideoMeta, val streamInfo: StreamInfo)

    /**
     * Full watch-page fetch via NewPipeExtractor (Octo pattern): metadata,
     * duration and the stream/subtitle lists in one shot. Blocking —
     * invoke from Dispatchers.IO.
     *
     * @throws IOException on network failure
     * @throws org.schabi.newpipe.extractor.exceptions.ExtractionException on layout breakage
     */
    fun fetchFull(videoId: String): FullInfo {
        val url = canonicalWatchUrl(videoId)
        val service: StreamingService = ServiceList.YouTube
        val info: StreamInfo = StreamInfo.getInfo(service, url)
        return FullInfo(
            meta = VideoMeta(
                videoId = videoId,
                title = info.name.orEmpty().ifBlank { videoId },
                channel = info.uploaderName.orEmpty(),
                canonicalUrl = url,
            ),
            streamInfo = info,
        )
    }
}
