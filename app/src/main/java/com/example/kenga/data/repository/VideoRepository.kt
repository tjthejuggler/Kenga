package com.example.kenga.data.repository

import com.example.kenga.data.local.AnalysisState
import com.example.kenga.data.local.VideoDao
import com.example.kenga.data.local.VideoEntity
import com.example.kenga.downloader.VideoDownloadManager
import com.example.kenga.youtube.YoutubeMetaFetcher
import com.example.kenga.youtube.YoutubeUrlParser
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Library façade (Octo `VideoRepository` pattern, Phase 2 scope): DB rows +
 * file cleanup around [VideoDao].
 *
 * Hard requirement inherited from Octo (docs/ARCHITECTURE.md §4.2): the
 * canonical-URL row is ALWAYS persisted — even when metadata fetch fails the
 * library still shows the video (title falls back to the video id).
 *
 * Export/import JSON, downloads and the analysis pipeline arrive in later
 * phases and will attach here.
 */
class VideoRepository(
    private val videoDao: VideoDao,
    private val metaFetcher: YoutubeMetaFetcher,
    private val downloadManager: VideoDownloadManager? = null,
) {

    /** Library list, newest additions first (sort handled by the DAO query). */
    val library: Flow<List<VideoEntity>> = videoDao.observeAll()

    /**
     * Parses [text] (may contain prose around the URL — the share-sheet path),
     * best-effort fetches oEmbed metadata, and upserts a PENDING_DOWNLOAD row.
     * A duplicate share returns the existing row untouched (except a FAILED
     * analysis is reset to PENDING_DOWNLOAD for re-processing).
     *
     * @return the persisted row, or null when no YouTube video URL was found.
     */
    suspend fun addSharedUrl(text: String): VideoEntity? {
        val parsed = YoutubeUrlParser.parse(text) ?: return null
        val existing = videoDao.get(parsed.videoId)

        val row = existing?.copy(
            analysisState = if (existing.analysisState == AnalysisState.FAILED) {
                AnalysisState.PENDING_DOWNLOAD
            } else {
                existing.analysisState
            },
        ) ?: run {
            // Metadata is best-effort: oEmbed failure keeps the video id as title.
            val meta = runCatching { metaFetcher.fetch(parsed.videoId) }.getOrNull()
            VideoEntity(
                videoId = parsed.videoId,
                url = parsed.canonicalUrl,
                title = meta?.title?.ifBlank { parsed.videoId } ?: parsed.videoId,
                channel = meta?.channel.orEmpty(),
                analysisState = AnalysisState.PENDING_DOWNLOAD,
            )
        }

        videoDao.upsert(row)
        return row
    }

    /** Refreshes oEmbed metadata (title/channel) for an existing row. */
    suspend fun refreshMetadata(videoId: String): Boolean = withContext(Dispatchers.IO) {
        val row = videoDao.get(videoId) ?: return@withContext false
        try {
            val meta = metaFetcher.fetch(videoId)
            videoDao.upsert(
                row.copy(
                    title = meta.title.ifBlank { row.title },
                    channel = meta.channel.ifBlank { row.channel },
                ),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Updates the analysis state stamp on a row (Phase 4 pipeline hook). */
    suspend fun updateAnalysisState(videoId: String, state: AnalysisState, analyzedAt: Long? = null) {
        val row = videoDao.get(videoId) ?: return
        videoDao.upsert(row.copy(analysisState = state, analyzedAt = analyzedAt ?: row.analyzedAt))
    }

    /** Deletes any downloaded/cached files + the DB row (muxed/dual/audio/thumbnail). */
    suspend fun deleteVideo(videoId: String) = withContext(Dispatchers.IO) {
        val row = videoDao.get(videoId)
        row?.videoFilePath?.let { path ->
            File(path).delete()
            // Dual-stream download: audio is the derivable `.audio` sibling.
            File(path).let { File(it.parentFile, it.nameWithoutExtension + ".audio") }.delete()
        }
        row?.thumbnailPath?.let { File(it).delete() }
        downloadManager?.deleteFiles(videoId)
        videoDao.delete(videoId)
    }

    /** Single-row access for the detail screen. */
    suspend fun get(videoId: String): VideoEntity? = videoDao.get(videoId)

    fun observe(videoId: String): Flow<VideoEntity?> = videoDao.observe(videoId)

    // ── Phase 10: video-detail editing (§7.2) ──

    /** Rewrites the muscle-group CSV on a row (manual multi-select edit). */
    suspend fun updateMuscleGroups(videoId: String, csv: String) {
        val row = videoDao.get(videoId) ?: return
        videoDao.upsert(row.copy(muscleGroups = csv))
    }

    /** Rewrites the raw exercises JSON on a row (manual rename edit). */
    suspend fun updateExercisesJson(videoId: String, json: String) {
        val row = videoDao.get(videoId) ?: return
        videoDao.upsert(row.copy(exercises = json))
    }

    /**
     * Renames an exercise (case-insensitive) inside the exercises JSON of one
     * video or every video that mentions it. Returns the number of rows whose
     * JSON actually changed. Also the detail screen's primary edit path.
     */
    suspend fun renameExerciseInVideo(videoId: String, oldName: String, newName: String, allVideos: Boolean): Int {
        val oldKey = oldName.trim().lowercase()
        val newDisplay = newName.trim()
        if (oldKey.isEmpty() || newDisplay.isEmpty() || oldKey == newDisplay.lowercase()) return 0
        val rows = if (allVideos) videoDao.getAllOnce() else listOfNotNull(videoDao.get(videoId))
        var changed = 0
        for (row in rows) {
            val rewritten = rewriteExerciseName(row.exercises, oldKey, newDisplay) ?: continue
            videoDao.upsert(row.copy(exercises = rewritten))
            changed++
        }
        return changed
    }

    /** Returns a new JSON string with every `name` matching [oldKey] replaced, or null if unchanged. */
    private fun rewriteExerciseName(exercisesJson: String, oldKey: String, newDisplay: String): String? {
        var changed = false
        val element = runCatching { exportJson.parseToJsonElement(exercisesJson) }.getOrNull() ?: return null
        val array = element as? kotlinx.serialization.json.JsonArray ?: return null
        val out = kotlinx.serialization.json.buildJsonArray {
            for (entry in array) {
                val obj = entry as? kotlinx.serialization.json.JsonObject ?: continue
                val name = obj["name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                if (name != null && name.trim().lowercase() == oldKey) {
                    changed = true
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put("name", kotlinx.serialization.json.JsonPrimitive(newDisplay))
                            obj["muscle_groups"]?.let { put("muscle_groups", it) }
                            obj["muscle_group"]?.let { put("muscle_group", it) }
                            obj["confidence"]?.let { put("confidence", it) }
                        },
                    )
                } else {
                    add(entry)
                }
            }
        }
        return if (changed) out.toString() else null
    }

    // ── Phase 10: library export / import (Octo Phase 8 pattern) ──

    private val exportJson = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Serializable export row (videoId/url/title/channel/muscleGroups/exercises/addedAt). */
    @kotlinx.serialization.Serializable
    data class LibraryExportEntry(
        val videoId: String,
        val url: String = "",
        val title: String = "",
        val channel: String = "",
        val muscleGroups: String = "",
        val exercises: String = "[]",
        val addedAt: Long = 0L,
    )

    @kotlinx.serialization.Serializable
    data class LibraryExport(val videos: List<LibraryExportEntry>)

    data class LibraryImportResult(val imported: List<String>, val skippedExisting: Int)

    /** Serializes every library row to a JSON document (SAF export payload). */
    suspend fun exportLibraryJson(): String {
        val entries = videoDao.getAllOnce().map { row ->
            LibraryExportEntry(
                videoId = row.videoId,
                url = row.url.ifBlank { YoutubeUrlParser.canonicalWatchUrl(row.videoId) },
                title = row.title,
                channel = row.channel,
                muscleGroups = row.muscleGroups,
                exercises = row.exercises,
                addedAt = row.addedAt,
            )
        }
        return exportJson.encodeToString(LibraryExport.serializer(), LibraryExport(entries))
    }

    /**
     * Merges an exported document into the library by videoId (existing rows are
     * never clobbered). Imported rows start PENDING_DOWNLOAD so the downloader
     * re-fetches them; the caller re-kicks the worker for each imported id.
     * @throws kotlinx.serialization.SerializationException on malformed input
     */
    suspend fun importLibraryJson(json: String): LibraryImportResult {
        val document = exportJson.decodeFromString(LibraryExport.serializer(), json)
        val imported = mutableListOf<String>()
        var skipped = 0
        for (entry in document.videos) {
            val id = entry.videoId.trim()
            if (id.isEmpty()) continue
            if (videoDao.get(id) != null) {
                skipped++
                continue
            }
            videoDao.upsert(
                VideoEntity(
                    videoId = id,
                    url = entry.url.ifBlank { YoutubeUrlParser.canonicalWatchUrl(id) },
                    title = entry.title.ifBlank { id },
                    channel = entry.channel,
                    muscleGroups = entry.muscleGroups,
                    exercises = entry.exercises.ifBlank { "[]" },
                    addedAt = if (entry.addedAt > 0) entry.addedAt else System.currentTimeMillis(),
                    analysisState = AnalysisState.PENDING_DOWNLOAD,
                ),
            )
            imported += id
        }
        return LibraryImportResult(imported = imported, skippedExisting = skipped)
    }
}
