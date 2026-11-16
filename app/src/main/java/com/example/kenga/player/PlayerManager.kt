package com.example.kenga.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.kenga.data.local.VideoEntity
import java.io.File
import okhttp3.OkHttpClient

/** Result of resolving a [VideoEntity] to something ExoPlayer can play. */
sealed interface PlaybackResolution {
    /** Local media source ready to hand to `ExoPlayer.setMediaSource`. */
    data class Ready(val source: MediaSource) : PlaybackResolution

    /** No usable local file (not downloaded / FAILED / files deleted) — offline-first, no streaming. */
    data object MissingFiles : PlaybackResolution
}

/**
 * Media3 playback plumbing (Octo `SessionPlayerManager` port, minus session
 * timers — Kenga videos are reference material only).
 *
 * Local-first resolution for a downloaded video:
 *  1. Muxed progressive MP4 (`videos/<id>.mp4`) → single [ProgressiveMediaSource].
 *  2. Dual-track download (`videos/<id>.video` + sibling `<id>.audio`, paths per
 *     [com.example.kenga.downloader.VideoDownloadManager]) → [MergingMediaSource]
 *     (video + side-loaded audio).
 *  3. Neither present → [PlaybackResolution.MissingFiles]; the UI offers a
 *     retry-download action. Streaming the canonical YouTube URL is NOT
 *     supported by plain ExoPlayer — offline-first is the only playback path.
 */
class PlayerManager(context: Context, okHttpClient: OkHttpClient) {

    private val appContext: Context = context.applicationContext

    /** DefaultDataSource wraps OkHttp — file:// URIs go through FileDataSource, http through OkHttp. */
    private val dataSourceFactory = DefaultDataSource.Factory(
        appContext,
        OkHttpDataSource.Factory(okHttpClient).setUserAgent(USER_AGENT),
    )

    /** Resolves the best local playback source for [video]. Pure function, main-thread safe. */
    fun resolve(video: VideoEntity): PlaybackResolution {
        // 1) Muxed progressive MP4 — preferred (primary download strategy).
        val muxed = File(File(appContext.filesDir, "videos"), "${video.videoId}.mp4")
        if (muxed.exists() && muxed.length() > 0) {
            return PlaybackResolution.Ready(progressive(muxed))
        }

        // 2) Dual download: videoFilePath points at the video-only track; audio is derivable.
        val primary = video.videoFilePath?.let(::File)
        if (primary != null && primary.exists() && primary.length() > 0) {
            val audio = File(primary.parentFile, primary.nameWithoutExtension + AUDIO_SUFFIX)
            return if (audio.exists() && audio.length() > 0) {
                PlaybackResolution.Ready(MergingMediaSource(progressive(primary), progressive(audio)))
            } else {
                // Video-only file without audio — still playable (silent), better than nothing.
                PlaybackResolution.Ready(progressive(primary))
            }
        }

        return PlaybackResolution.MissingFiles
    }

    /**
     * Builds the player. Audio focus is handled by Media3 (`handleAudioFocus = true`),
     * so pausing on call/other-app audio is automatic. The caller owns the listener.
     */
    fun createPlayer(): ExoPlayer = ExoPlayer.Builder(appContext).build().apply {
        setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ true)
    }

    private fun progressive(file: File): ProgressiveMediaSource =
        ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)))

    private companion object {
        const val AUDIO_SUFFIX = ".audio"
        const val USER_AGENT = "Kenga/1.0 (Media3)"

        val AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    }
}

/** Convenience: prepares [player] with [source] and starts playback. */
fun ExoPlayer.prepareWith(source: MediaSource) {
    setMediaSource(source)
    prepare()
    playWhenReady = true
}

/** Player-lifecycle helper used by the detail-screen player pane. */
fun Player.togglePlayPause() {
    if (isPlaying) pause() else play()
}
