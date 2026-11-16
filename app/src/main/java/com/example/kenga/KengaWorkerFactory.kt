package com.example.kenga

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.kenga.work.DownloadVideoWorker
import com.example.kenga.work.ReanalyzeLibraryWorker
import com.example.kenga.work.TailSyncWorker

/**
 * Manual-DI bridge so [androidx.work.CoroutineWorker]s receive dependencies
 * from [KengaApplication.AppContainer] (WorkManager's default reflective
 * factory has no constructor to call).
 *
 * Phase 3: DownloadVideoWorker. Later phases register ReanalyzeLibraryWorker
 * and TailSyncWorker here.
 */
class KengaWorkerFactory(private val container: KengaApplication.AppContainer) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        DownloadVideoWorker::class.java.name ->
            DownloadVideoWorker(appContext, workerParameters)

        ReanalyzeLibraryWorker::class.java.name ->
            ReanalyzeLibraryWorker(appContext, workerParameters)

        TailSyncWorker::class.java.name ->
            TailSyncWorker(
                appContext = appContext,
                params = workerParameters,
                tailClient = container.tailClient,
                setLogDao = container.database.setLogDao(),
                weightsHabitResolver = container.weightsHabitResolver,
                tailSynchronizer = container.tailSynchronizer,
            )

        else -> null // fall back to default reflective instantiation
    }
}
