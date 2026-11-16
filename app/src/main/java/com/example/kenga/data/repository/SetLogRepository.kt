package com.example.kenga.data.repository

import android.content.Context
import android.util.Log
import com.example.kenga.data.local.SetLogDao
import com.example.kenga.data.local.SetLogEntity
import com.example.kenga.data.local.TailSyncState
import com.example.kenga.ipc.TailClient
import com.example.kenga.work.TailSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * One set to log (WeightsLogDialog output, §7.3).
 */
data class LogDraft(
    val muscleGroupId: String,
    val exerciseName: String,
    val weightGrams: Int,
    val reps: Int,
    val machine: Boolean,
)

/**
 * Set-log write path (§6.2/§7.3): Room row is the local truth; the Tail v6
 * broadcast is attempted immediately on insert and retried by [TailSyncWorker]
 * when it cannot go through.
 *
 * Sync semantics:
 *  - mapped group + broadcast SENT → row marked [TailSyncState.SENT];
 *  - NO_TAIL / ERROR / broadcast failure → row stays PENDING and the worker
 *    is enqueued (KEEP) to retry with backoff;
 *  - unmapped group ("" habit in settings) → row marked OFFLINE (local-only).
 *
 * Delete removes the row and, when it had reached Tail (SENT), sends a
 * negative-reps correction so Tail's day aggregate is compensated. Undo
 * re-inserts the row and re-broadcasts it like a fresh log.
 */
class SetLogRepository(
    private val appContext: Context,
    private val setLogDao: SetLogDao,
    private val tailClient: TailClient,
    private val exerciseRepository: ExerciseRepository,
    private val weightsHabitResolver: WeightsHabitResolver,
) {

    /**
     * Inserts the set, attempts the immediate Tail broadcast, and returns the
     * new row id. The row lands in whatever sync state the broadcast dictates
     * (see class docs).
     */
    suspend fun insert(draft: LogDraft): Long = withContext(Dispatchers.IO) {
        // Habit resolved from where this exercise was logged before (PB mirror
        // / per-set sidecar), not from a per-group Settings mapping.
        val habit = weightsHabitResolver.resolve(draft.exerciseName)
        val now = System.currentTimeMillis()
        val entity = SetLogEntity(
            ts = now,
            muscleGroupId = draft.muscleGroupId,
            exerciseName = draft.exerciseName,
            weightGrams = draft.weightGrams,
            reps = draft.reps,
            machine = draft.machine,
            date = LocalDate.now().toString(),
        )
        val state = if (habit.isBlank()) {
            TailSyncState.OFFLINE
        } else {
            when (tailClient.logWeightsEntry(
                habit, draft.exerciseName, draft.weightGrams, draft.reps, draft.machine, entity.date,
            )) {
                TailClient.SendResult.SENT -> TailSyncState.SENT
                TailClient.SendResult.NO_TAIL -> {
                    TailSyncWorker.enqueueSyncNow(appContext)
                    TailSyncState.PENDING
                }
                TailClient.SendResult.ERROR -> {
                    TailSyncWorker.enqueueSyncNow(appContext)
                    TailSyncState.PENDING
                }
            }
        }
        val id = setLogDao.insert(entity.copy(tailSyncState = state))
        // Keep the autocomplete catalog fresh with the just-used name.
        runCatching { exerciseRepository.upsertManual(draft.exerciseName, draft.muscleGroupId) }
        // Tail combined-scene widget: re-render + ping at the data-write
        // point (MainActivity resume alone misses log-then-go-home flows).
        runCatching {
            com.example.kenga.widget.FigureSnapshotProvider.refreshAndPing(appContext)
        }
        Log.i(
            TAG,
            "Set logged id=%d ex=%s %dg x%d machine=%s habit=%s state=%s"
                .format(id, draft.exerciseName, draft.weightGrams, draft.reps, draft.machine, habit, state),
        )
        id
    }

    /**
     * Deletes a row, sending a compensating negative-reps correction to Tail
     * when the row had been SENT. Returns the removed row (for the undo
     * Snackbar) or null when it no longer exists.
     */
    suspend fun delete(id: Long): SetLogEntity? = withContext(Dispatchers.IO) {
        val row = setLogDao.get(id) ?: return@withContext null
        setLogDao.delete(id)
        if (row.tailSyncState == TailSyncState.SENT) {
            val habit = weightsHabitResolver.resolve(row.exerciseName)
            if (habit.isNotBlank()) {
                tailClient.sendCorrection(
                    habitName = habit,
                    exerciseName = row.exerciseName,
                    repsToRemove = row.reps,
                    machine = row.machine,
                    date = row.date,
                )
            }
        }
        Log.i(TAG, "Set deleted id=%d (was %s)".format(id, row.tailSyncState))
        runCatching {
            com.example.kenga.widget.FigureSnapshotProvider.refreshAndPing(appContext)
        }
        row
    }

    /**
     * Undo path (§7.3): re-inserts a previously deleted row and re-broadcasts
     * it exactly like a fresh log (the delete may have compensated Tail, so a
     * plain positive entry restores it; a never-SENT row simply retries).
     */
    suspend fun restore(row: SetLogEntity): Long = withContext(Dispatchers.IO) {
        insert(
            LogDraft(
                muscleGroupId = row.muscleGroupId,
                exerciseName = row.exerciseName,
                weightGrams = row.weightGrams,
                reps = row.reps,
                machine = row.machine,
            ),
        )
    }

    /** Today's rows (Log tab day list), oldest first. */
    fun observeByDate(date: String): Flow<List<SetLogEntity>> = setLogDao.observeByDate(date)

    private companion object {
        const val TAG = "SetLogRepository"
    }
}
