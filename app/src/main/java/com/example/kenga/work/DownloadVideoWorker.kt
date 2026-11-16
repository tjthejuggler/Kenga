package com.example.kenga.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.kenga.KengaApplication
import com.example.kenga.data.local.AnalysisState
import java.util.concurrent.TimeUnit

/**
 * Resolves streams → downloads video (+audio) + thumbnail → persists file
 * paths, duration and refreshed metadata on the row (Octo `DownloadVideoWorker`
 * port; the transcript/analysis trigger is Kenga Phase 4 and intentionally
 * absent here).
 *
 * Progress (bytes/total) is published via WorkManager setProgress so the
 * Library UI can render a live percentage. Any failure after [MAX_ATTEMPTS]
 * runs leaves the row FAILED **but keeps the URL row** so the library still
 * shows the video.
 */
class DownloadVideoWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val videoId = inputData.requireVideoId()
        val container = (applicationContext.applicationContext as KengaApplication).appContainer
        val dao = container.database.videoDao()
        val existing = dao.get(videoId) ?: return Result.failure() // deleted mid-flight

        dao.upsert(existing.copy(analysisState = AnalysisState.DOWNLOADING))
        try {
            val result = container.videoDownloadManager.download(videoId) { read, total ->
                setProgressAsync(
                    workDataOf(
                        PROGRESS_BYTES to read,
                        PROGRESS_TOTAL to total,
                    ),
                )
            }

            // Re-read: user could have deleted the row while bytes were flowing.
            val current = dao.get(videoId) ?: return Result.failure()

            val localPath: String
            val thumbPath: String?
            when (val outcome = result.outcome) {
                is com.example.kenga.downloader.VideoDownloadManager.Outcome.Muxed -> {
                    localPath = outcome.file.absolutePath
                    thumbPath = outcome.thumbnailFile?.absolutePath ?: current.thumbnailPath
                }

                is com.example.kenga.downloader.VideoDownloadManager.Outcome.Dual -> {
                    localPath = outcome.videoFile.absolutePath // audio derivable (.audio sibling)
                    thumbPath = outcome.thumbnailFile?.absolutePath ?: current.thumbnailPath
                }
            }

            dao.upsert(
                current.copy(
                    title = result.title.ifBlank { current.title },
                    channel = result.channel.ifBlank { current.channel },
                    durationMs = if (result.durationMs > 0) result.durationMs else current.durationMs,
                    videoFilePath = localPath,
                    thumbnailPath = thumbPath,
                    downloadedAt = System.currentTimeMillis(),
                    analysisState = AnalysisState.DOWNLOADED,
                ),
            )

            // Phase 4 trigger (Octo chaining pattern): transcript + muscle/exercise
            // analysis runs right after a successful download. Fire-and-forget —
            // never fails the download result; ensureAnalyzed collapses all
            // errors into AnalysisState.FAILED on the row.
            try {
                val outcome = container.exerciseAnalysisPipeline.ensureAnalyzed(videoId)
                android.util.Log.i("DownloadVideoWorker", "Post-download analysis: $outcome")
            } catch (t: Throwable) {
                android.util.Log.w("DownloadVideoWorker", "Post-download analysis trigger failed", t)
            }
            return Result.success()
        } catch (t: Throwable) {
            // Row must survive regardless of outcome — re-read so we never clobber
            // metadata updated by a concurrent retry.
            val row = dao.get(videoId)
            if (row != null) {
                dao.upsert(
                    row.copy(
                        analysisState = AnalysisState.FAILED,
                        // keep videoFilePath only if a previous download succeeded
                        videoFilePath = row.downloadedAt?.let { row.videoFilePath },
                    ),
                )
            }
            return if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun Data.requireVideoId(): String =
        getString(KEY_VIDEO_ID) ?: error("DownloadVideoWorker requires $KEY_VIDEO_ID")

    companion object {
        const val KEY_VIDEO_ID = "videoId"
        const val PROGRESS_BYTES = "progressBytes"
        const val PROGRESS_TOTAL = "progressTotal"
        const val MAX_ATTEMPTS = 3

        /** Unique-work name prefix; full name is "$UNIQUE_WORK_NAME_PREFIX:$videoId". */
        const val UNIQUE_WORK_NAME_PREFIX = "download_video"

        fun uniqueWorkName(videoId: String) = "$UNIQUE_WORK_NAME_PREFIX:$videoId"

        /** Enqueue helper — unique per videoId, KEEP so re-shares don't restart running work. */
        fun enqueue(context: Context, videoId: String) {
            val request = OneTimeWorkRequestBuilder<DownloadVideoWorker>()
                .setInputData(workDataOf(KEY_VIDEO_ID to videoId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(UNIQUE_WORK_NAME_PREFIX)
                .addTag(videoId) // lets UI match WorkInfos to rows without inputData
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(videoId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
