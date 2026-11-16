package com.example.kenga.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Library-video lifecycle. Download leg (Phase 3): PENDING_DOWNLOAD →
 * DOWNLOADING → DOWNLOADED (or FAILED). Analysis leg (Phase 4) then uses
 * PENDING → RUNNING → DONE / FAILED on top of a DOWNLOADED row.
 */
enum class AnalysisState { NONE, PENDING_DOWNLOAD, DOWNLOADING, DOWNLOADED, PENDING, RUNNING, DONE, FAILED }

/** How the stored transcript text was obtained (Phase 4; NONE = not fetched yet). */
enum class TranscriptSource { NONE, CAPTIONS, NEWPIPE_SUBTITLES, TITLE_DESCRIPTION }

/** A library entry (plans/kenga_architecture.md §5). PK is the YouTube videoId. */
@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val videoId: String,
    val url: String, // canonical https://www.youtube.com/watch?v=<id>
    val title: String,
    val channel: String,
    val durationMs: Long = 0L,
    val thumbnailPath: String? = null, // cached hqdefault.jpg, null until fetched
    val videoFilePath: String? = null, // app-private file, null while not downloaded
    val downloadedAt: Long? = null, // epoch millis of last successful download
    val addedAt: Long = System.currentTimeMillis(), // epoch millis of share/insert (library sort key)
    val analysisState: AnalysisState = AnalysisState.NONE,
    val analyzedAt: Long? = null, // epoch millis of last completed/failed analysis
    val muscleGroups: String = "", // CSV of canonical muscle-group ids
    val exercises: String = "[]", // JSON array of detected exercises (§4 schema)
    val transcriptText: String = "", // full transcript text (Phase 4, v2)
    val transcriptSource: TranscriptSource = TranscriptSource.NONE, // v2
)
