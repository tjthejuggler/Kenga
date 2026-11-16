package com.example.kenga

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Configuration
import com.example.kenga.data.local.AnalysisState
import com.example.kenga.data.local.DatabaseProvider
import com.example.kenga.data.local.KengaDatabase
import com.example.kenga.data.repository.ExerciseRepository
import com.example.kenga.data.repository.PbRepository
import com.example.kenga.data.repository.VideoRepository
import com.example.kenga.data.settings.SettingsRepository
import com.example.kenga.data.repository.TailSynchronizer
import com.example.kenga.ipc.TailClient
import com.example.kenga.llm.TailExerciseIngestor
import com.example.kenga.downloader.VideoDownloadManager
import com.example.kenga.llm.ExerciseAnalysisPipeline
import com.example.kenga.llm.LlmClient
import com.example.kenga.network.OkHttpDownloaderBridge
import com.example.kenga.network.kengaOkHttpClient
import com.example.kenga.player.PlayerManager
import com.example.kenga.youtube.TranscriptFetcher
import com.example.kenga.youtube.YoutubeMetaFetcher
import com.example.kenga.work.DownloadVideoWorker
import com.example.kenga.work.TailSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe

private val Context.kengaDataStore by preferencesDataStore(name = "kenga_settings")

/**
 * Manual dependency graph (no Hilt — Octo pattern).
 * Activities and workers reach dependencies via `application.appContainer`.
 */
class KengaApplication : Application(), Configuration.Provider {

    lateinit var appContainer: AppContainer
        private set

    /** Application-wide scope for fire-and-forget work (outlives any screen). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        // Octo Phase 8 pattern: after process death, rows can be stranded in
        // PENDING_DOWNLOAD/DOWNLOADING with no live worker. Unique-KEEP enqueue
        // makes the re-kick idempotent.
        applicationScope.launch { recoverAfterProcessDeath() }
        // Phase 6: flush PENDING Tail logs + pull a fresh weights snapshot on
        // start, plus a periodic safety net (both KEEP → idempotent).
        TailSyncWorker.enqueueSyncNow(this)
        TailSyncWorker.schedulePeriodic(this)
    }

    private suspend fun recoverAfterProcessDeath() {
        runCatching {
            val dao = appContainer.database.videoDao()
            val rows = dao.observeAll().firstOrNull().orEmpty()

            // Re-kick orphaned downloads (unique-KEEP → idempotent).
            rows.filter {
                it.analysisState == AnalysisState.PENDING_DOWNLOAD ||
                    it.analysisState == AnalysisState.DOWNLOADING
            }.forEach { DownloadVideoWorker.enqueue(this, it.videoId) }

            // Clear stale ANALYZING rows: a row stuck in RUNNING/PENDING analysis
            // after process death has no live worker — reset to DOWNLOADED so the
            // reanalyse path (or a fresh share) can pick it up again.
            rows.filter { it.analysisState == AnalysisState.RUNNING }
                .forEach { dao.updateAnalysisState(it.videoId, AnalysisState.DOWNLOADED, null) }
        }
    }

    /** WorkManager needs on-demand initialization to see [appContainer] (worker DI). */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(KengaWorkerFactory(appContainer))
            .build()

    /** Simple hand-rolled DI container (Wags-style). */
    class AppContainer(context: Context) {
        val appContext: Context = context.applicationContext
        val okHttpClient by lazy { kengaOkHttpClient() }

        init {
            // NewPipeExtractor global init — once per process, before first extractor use.
            NewPipe.init(
                OkHttpDownloaderBridge(
                    OkHttpDownloaderBridge.newExtractorClient(kengaOkHttpClient()),
                ),
            )
        }

        val database: KengaDatabase = DatabaseProvider.get(context)
        val settingsRepository = SettingsRepository(context.kengaDataStore)

        // Phase 2: share-in & library.
        val youtubeMetaFetcher by lazy { YoutubeMetaFetcher(okHttpClient) }

        // Phase 3: download pipeline + player.
        val videoDownloadManager by lazy {
            VideoDownloadManager(
                context = appContext,
                okHttpClient = okHttpClient,
            )
        }
        val playerManager by lazy { PlayerManager(appContext, okHttpClient) }

        // Phase 4: transcript + LLM analysis pipeline.
        val transcriptFetcher by lazy {
            TranscriptFetcher(okHttpClient, youtubeMetaFetcher)
        }
        val llmClient by lazy { LlmClient(okHttpClient) }
        val exerciseAnalysisPipeline by lazy {
            ExerciseAnalysisPipeline(
                db = database,
                transcriptFetcher = transcriptFetcher,
                llmClient = llmClient,
                settingsRepository = settingsRepository,
                // Phase 6: LLM prompts prefer the user's Tail exercise naming.
                knownExerciseNamesProvider = { exerciseRepository.tailExerciseNames() },
            )
        }

        val videoRepository by lazy {
            VideoRepository(
                videoDao = database.videoDao(),
                metaFetcher = youtubeMetaFetcher,
                downloadManager = videoDownloadManager,
            )
        }

        // Auto weights-habit resolution (PB mirror / per-set sidecar) — the
        // replacement for the removed per-muscle-group Settings pickers.
        val weightsHabitResolver by lazy {
            com.example.kenga.data.repository.WeightsHabitResolver(
                pbDao = database.pbDao(),
                tailWeightSetDao = database.tailWeightSetDao(),
                tailSnapshotDao = database.tailSnapshotDao(),
            )
        }

        // Phase 7: set-log write path (Room truth + Tail broadcast, §7.3).
        val setLogRepository by lazy {
            com.example.kenga.data.repository.SetLogRepository(
                appContext = appContext,
                setLogDao = database.setLogDao(),
                tailClient = tailClient,
                exerciseRepository = exerciseRepository,
                weightsHabitResolver = weightsHabitResolver,
            )
        }

        // Phase 6: Tail integration (§6.2) + PB engine.
        val tailClient by lazy { TailClient(appContext, database) }
        val exerciseRepository by lazy { ExerciseRepository(database.exerciseDao()) }

        /** LLM/heuristic muscle-group association for Tail-pulled exercise names. */
        val tailExerciseIngestor by lazy {
            TailExerciseIngestor(
                db = database,
                llmClient = llmClient,
                settingsRepository = settingsRepository,
            )
        }

        /** One-call Tail READ sync: weights snapshot + body-weight habits + ingest. */
        val tailSynchronizer by lazy {
            TailSynchronizer(
                tailClient = tailClient,
                db = database,
                settingsRepository = settingsRepository,
                ingestor = tailExerciseIngestor,
            )
        }

        val pbRepository by lazy {
            PbRepository(
                pbDao = database.pbDao(),
                setLogDao = database.setLogDao(),
                tailSnapshotDao = database.tailSnapshotDao(),
                exerciseDao = database.exerciseDao(),
                tailHabitEntryDao = database.tailHabitEntryDao(),
                settingsRepository = settingsRepository,
                tailWeightSetDao = database.tailWeightSetDao(),
            )
        }
    }
}
