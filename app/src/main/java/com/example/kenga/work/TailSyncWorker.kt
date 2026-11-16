package com.example.kenga.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.kenga.data.local.SetLogEntity
import com.example.kenga.data.local.TailSyncState
import com.example.kenga.ipc.TailClient
import java.util.concurrent.TimeUnit

/**
 * Tail sync (§6.2): flushes local [SetLogEntity] rows stuck in PENDING to Tail
 * (v6 broadcast), then pulls a fresh `/v3/weights*` snapshot. Runs periodically,
 * on app start, and on demand after each successful log.
 *
 * Semantics:
 *  - SENT → row marked SENT (idempotent-ish: only PENDING rows are retried).
 *  - NO_TAIL → row stays PENDING and the worker returns [Result.retry] so
 *    WorkManager's backoff re-attempts when Tail returns.
 *  - unmapped muscle group (no habit in settings) → row marked OFFLINE.
 */
import com.example.kenga.data.local.SetLogDao

class TailSyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val tailClient: TailClient,
    private val setLogDao: SetLogDao,
    private val weightsHabitResolver: com.example.kenga.data.repository.WeightsHabitResolver,
    private val tailSynchronizer: com.example.kenga.data.repository.TailSynchronizer? = null,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dao = setLogDao

        var tailAbsent = false
        val pending = dao.bySyncState(TailSyncState.PENDING)
        for (log in pending) {
            // Per-exercise habit resolution (PB mirror / sidecar), no settings map.
            val habit = weightsHabitResolver.resolve(log.exerciseName)
            if (habit.isBlank()) {
                dao.update(log.copy(tailSyncState = TailSyncState.OFFLINE))
                continue
            }
            when (tailClient.logWeightsEntry(habit, log.exerciseName, log.weightGrams, log.reps, log.machine, log.date)) {
                TailClient.SendResult.SENT ->
                    dao.update(log.copy(tailSyncState = TailSyncState.SENT))
                TailClient.SendResult.NO_TAIL -> {
                    tailAbsent = true
                    Log.i(TAG, "Tail absent — set log ${log.id} stays PENDING")
                }
                TailClient.SendResult.ERROR -> Unit // stays PENDING, retried next run
            }
        }

        // Full READ sync: weights snapshot + body-weight habit entries +
        // LLM/heuristic muscle-group ingest for new Tail exercise names.
        // Falls back to the plain weights snapshot when the synchronizer is
        // not injected (defensive).
        val snapshot = if (tailSynchronizer != null) {
            tailSynchronizer.fullRefresh()
        } else {
            tailClient.refreshWeightsSnapshot() != null
        }
        Log.i(
            TAG,
            "Sync done: flushed=%d tailAbsent=%s snapshot=%s"
                .format(pending.size, tailAbsent, snapshot ?: "unreachable"),
        )

        // Snapshot rows feed Kenga's served scene figure (set counts) — ping
        // Tail so the combined-scene widget re-pulls promptly. Unconditional
        // on a reachable sync: Tail-side changes (body-weight habits done
        // today) recolor the figure even when nothing was flushed locally;
        // ensureFresh's fingerprint check makes the no-change ping cheap.
        if (snapshot != null) {
            runCatching {
                com.example.kenga.widget.FigureSnapshotProvider.refreshAndPing(applicationContext)
            }
            runCatching {
                com.example.kenga.widget.KengaWidgetProvider.requestUpdate(applicationContext)
            }
        }

        return if (tailAbsent) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "TailSyncWorker"
        const val UNIQUE_ONESHOT = "tail_sync_once"
        const val UNIQUE_PERIODIC = "tail_sync_periodic"

        /** On-demand / app-start / after-log flush + snapshot pull (KEEP). */
        fun enqueueSyncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<TailSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONESHOT,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /** Background safety net: every 6h flush + snapshot refresh. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<TailSyncWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
