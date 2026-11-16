package com.example.kenga.work

import android.content.Context
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Maps live WorkManager progress of [DownloadVideoWorker] into percentages for
 * the Library / detail UI. `-1` = indeterminate (server omitted content-length);
 * the key is absent when no work is running for a video.
 */
object DownloadProgress {

    /** videoId → percent (0..100, or -1 when indeterminate) for every RUNNING/ENQUEUED download. */
    fun observeAll(context: Context): Flow<Map<String, Int>> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosByTagFlow(DownloadVideoWorker.UNIQUE_WORK_NAME_PREFIX)
            .map { infos ->
                infos.filter {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }.associate { info -> videoIdOf(info) to percent(info.progress) }
            }

    /** Percent flow for a single video, or null when no live work exists. */
    fun observe(context: Context, videoId: String): Flow<Int?> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(DownloadVideoWorker.uniqueWorkName(videoId))
            .map { infos ->
                infos.firstOrNull()
                    ?.takeIf { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                    ?.let { percent(it.progress) }
            }

    /** The worker tags itself with the raw videoId (see enqueue). */
    private fun videoIdOf(info: WorkInfo): String =
        info.tags.firstOrNull { it != DownloadVideoWorker.UNIQUE_WORK_NAME_PREFIX }
            ?: info.id.toString()

    private fun percent(data: Data): Int {
        val bytes = data.getLong(DownloadVideoWorker.PROGRESS_BYTES, -1L)
        val total = data.getLong(DownloadVideoWorker.PROGRESS_TOTAL, -1L)
        return if (bytes >= 0 && total > 0) {
            ((bytes * 100) / total).toInt().coerceIn(0, 100)
        } else {
            -1 // indeterminate
        }
    }
}
