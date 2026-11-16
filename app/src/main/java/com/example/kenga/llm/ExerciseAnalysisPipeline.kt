package com.example.kenga.llm

import android.util.Log
import androidx.room.withTransaction
import com.example.kenga.data.local.AnalysisState
import com.example.kenga.data.local.KengaDatabase
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.data.local.TranscriptSource
import com.example.kenga.data.settings.KengaSettings
import com.example.kenga.data.settings.SettingsRepository
import com.example.kenga.youtube.TranscriptFetcher
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Phase 4 orchestration (plans §4; replaces Octo's BodyPartAnalysisPipeline):
 * for one video —
 *
 * 1. fetch + persist the transcript if the row doesn't have one
 *    ([TranscriptFetcher] three-tier fallback chain)
 * 2. classify into muscle-group ids AND exercise names: LLM when an API key is
 *    configured, else [LlmHeuristics]
 * 3. validate (unknown muscle ids dropped; exercise names trimmed, deduped ci;
 *    exercise with invalid muscle_group dropped) and persist muscleGroups CSV +
 *    exercises JSON on the row
 * 4. mark AnalysisState.DONE (or FAILED with transcript left null when unobtainable)
 *
 * `ensureAnalyzed` is idempotent (no-op when already DONE) and re-entrant-safe
 * via the RUNNING marker — a second trigger while RUNNING returns without
 * duplicating work (Octo pattern).
 */
class ExerciseAnalysisPipeline(
    private val db: KengaDatabase,
    private val transcriptFetcher: TranscriptFetcher,
    private val llmClient: LlmClient,
    private val settingsRepository: SettingsRepository,
    /** Known exercise names from Tail (wired in Phase 6; empty for now). */
    private val knownExerciseNamesProvider: suspend () -> List<String> = { emptyList() },
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Outcome for logging / UI toasts. */
    enum class Outcome { ANALYZED, ALREADY_DONE, SKIPPED_RUNNING, NO_TRANSCRIPT, FAILED }

    /**
     * Runs the full pipeline for [videoId] on [Dispatchers.IO].
     * Never throws — all failures collapse into `FAILED` state on the row.
     */
    suspend fun ensureAnalyzed(videoId: String, force: Boolean = false): Outcome =
        withContext(Dispatchers.IO) {
            val dao = db.videoDao()
            val row = dao.get(videoId) ?: return@withContext Outcome.FAILED

            if (!force && row.analysisState == AnalysisState.DONE) {
                return@withContext Outcome.ALREADY_DONE
            }
            // force=true (Reanalyse library) pushes through a stale RUNNING marker
            // left by a process-killed run; only opportunistic triggers respect
            // the lock — a video is never analyzed twice concurrently.
            if (!force && row.analysisState == AnalysisState.RUNNING) {
                return@withContext Outcome.SKIPPED_RUNNING
            }

            dao.updateAnalysisState(videoId, AnalysisState.RUNNING, now())
            try {
                // ── 1. transcript ──
                var transcript = row.transcriptText
                var source: TranscriptSource = row.transcriptSource
                if (transcript.isBlank() || source == TranscriptSource.NONE) {
                    val fetched = transcriptFetcher.fetch(videoId)
                    if (fetched == null) {
                        dao.updateAnalysisState(videoId, AnalysisState.FAILED, now())
                        return@withContext Outcome.NO_TRANSCRIPT
                    }
                    transcript = fetched.text
                    source = fetched.source
                    dao.updateTranscript(videoId, transcript, source)
                }

                // ── 2. classification (LLM if configured, else heuristics) ──
                val settings: KengaSettings = settingsRepository.settings.first()
                val llmConfigured = settings.llmApiKey.isNotBlank()
                val endpoint = if (llmConfigured) {
                    LlmClient.Endpoint(
                        baseUrl = settings.llmBaseUrl.ifBlank { SettingsRepository.DEFAULT_LLM_BASE_URL },
                        apiKey = settings.llmApiKey,
                        model = settings.llmModel.ifBlank { SettingsRepository.DEFAULT_LLM_MODEL },
                    )
                } else {
                    null
                }

                // Known-exercise naming hints (Tail read-back; Phase 6 wires this).
                val knownNames = runCatching { knownExerciseNamesProvider() }.getOrDefault(emptyList())

                // Multimodal support: frames from the downloaded file ride along
                // on the single-call path only (kept whatever Octo does).
                val frames = if (endpoint != null) {
                    row.videoFilePath?.let { File(it) }
                        ?.takeIf { it.isFile }
                        ?.let { VideoFrameExtractor.extractBase64Frames(it) }
                        .orEmpty()
                } else {
                    emptyList()
                }

                val analysis = if (endpoint != null) {
                    llmClient.classifyExercises(
                        endpoint = endpoint,
                        transcript = transcript,
                        isTitleDescriptionOnly = source == TranscriptSource.TITLE_DESCRIPTION,
                        knownExerciseNames = knownNames,
                        framesBase64 = frames,
                    )
                } else {
                    LlmHeuristics.classify(transcript)
                }

                // ── 3. validation — canonical muscle ids only; exercises with no
                // canonical muscle_groups are dropped, but their muscles, if
                // separately listed, stay. An exercise may carry SEVERAL groups
                // (primary first) — all of them are credited.
                val muscles = analysis.muscleGroups
                    .filter { MuscleGroup.fromId(it) != null }
                    .toCollection(LinkedHashSet())
                analysis.exercises.forEach { exercise ->
                    muscles += exercise.muscleGroups.filter { MuscleGroup.fromId(it) != null }
                }

                // ── 4. atomic persist + state flip ──
                val muscleCsv = muscles.joinToString(",")
                val exercisesJson = json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(DetectedExerciseDto.serializer()),
                    analysis.exercises
                        .filter { exercise -> exercise.muscleGroups.any { MuscleGroup.fromId(it) != null } }
                        .map { exercise ->
                            DetectedExerciseDto(
                                name = exercise.name,
                                muscle_groups = exercise.muscleGroups
                                    .filter { MuscleGroup.fromId(it) != null },
                                confidence = exercise.confidence,
                            )
                        },
                )
                db.withTransaction {
                    dao.updateAnalysisResult(videoId, muscleCsv, exercisesJson, now())
                }
                Log.i(
                    TAG,
                    "Analyzed %s: %d muscle groups, %d exercises via %s".format(
                        videoId,
                        muscles.size,
                        analysis.exercises.size,
                        if (llmConfigured) "llm" else "heuristic",
                    ),
                )
                Outcome.ANALYZED
            } catch (t: Throwable) {
                Log.w(TAG, "Analysis failed for $videoId", t)
                try {
                    dao.updateAnalysisState(videoId, AnalysisState.FAILED, now())
                } catch (_: Exception) {
                    // row deleted mid-flight — nothing to mark
                }
                Outcome.FAILED
            }
        }

    /** All library video ids (newest first) — the reanalyse worker's job list. */
    suspend fun allVideoIds(): List<String> = withContext(Dispatchers.IO) {
        db.videoDao().getAllOnce().map { it.videoId }
    }

    /**
     * Row-level JSON DTO persisted in VideoEntity.exercises — one exercise with
     * ALL its canonical muscle-group ids, primary mover first.
     */
    @kotlinx.serialization.Serializable
    data class DetectedExerciseDto(
        val name: String,
        val muscle_groups: List<String> = emptyList(),
        val confidence: Double,
    )

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val TAG = "ExercisePipeline"
    }
}
