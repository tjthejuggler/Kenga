package com.example.kenga.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.kenga.KengaApplication
import com.example.kenga.llm.ExerciseAnalysisPipeline

/**
 * "Reanalyse all" (Settings action; Octo `ReanalyzeLibraryWorker` port):
 * force re-runs the full analysis pipeline — transcript + muscle groups AND
 * exercises — for every library video, sequentially on a background worker so
 * the app can be closed mid-run without corrupting state (each video's persist
 * is transactional; RUNNING markers keep re-entry safe).
 *
 * Standard (Context, WorkerParameters) constructor pulling deps from
 * [KengaApplication.appContainer] — identical to [DownloadVideoWorker].
 *
 * Progress is reported as "done/total" via setProgress for the Settings UI.
 */
class ReanalyzeLibraryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val pipeline
        get() = (applicationContext.applicationContext as KengaApplication)
            .appContainer.exerciseAnalysisPipeline

    override suspend fun doWork(): Result {
        val videoIds = pipeline.allVideoIds()
        if (videoIds.isEmpty()) return Result.success()

        Log.i(TAG, "Reanalyse library: ${videoIds.size} videos")
        var analyzed = 0
        videoIds.forEachIndexed { index, videoId ->
            // force = true: even DONE rows are redone with the current settings.
            val outcome = pipeline.ensureAnalyzed(videoId, force = true)
            Log.i(TAG, "Reanalyse [$videoId] -> $outcome")
            if (outcome == ExerciseAnalysisPipeline.Outcome.ANALYZED) analyzed++
            setProgress(
                workDataOf(
                    PROGRESS_DONE to index + 1,
                    PROGRESS_TOTAL to videoIds.size,
                )
            )
        }
        Log.i(TAG, "Reanalyse library finished: $analyzed/${videoIds.size} analyzed")
        return Result.success()
    }

    companion object {
        const val TAG = "ReanalyzeLibraryWorker"
        const val UNIQUE_NAME = "kenga_reanalyze_library"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
    }
}
