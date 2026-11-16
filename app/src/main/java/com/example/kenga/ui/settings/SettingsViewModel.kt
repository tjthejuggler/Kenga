package com.example.kenga.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.kenga.KengaApplication
import com.example.kenga.data.settings.SettingsRepository
import com.example.kenga.llm.LlmClient
import com.example.kenga.work.ReanalyzeLibraryWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings presentation state (Phase 4): editable LLM form + Test-LLM
 * round-trip + the "Reanalyse all" library action (Octo port, Kenga scope).
 */
class SettingsViewModel(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val llmClient: LlmClient,
    private val tailClient: com.example.kenga.ipc.TailClient,
    private val videoRepository: com.example.kenga.data.repository.VideoRepository,
    private val database: com.example.kenga.data.local.KengaDatabase,
    private val tailExerciseIngestor: com.example.kenga.llm.TailExerciseIngestor,
) : ViewModel() {

    /** Result of the "Test LLM" round-trip. */
    sealed interface TestLlmState {
        data object Idle : TestLlmState
        data object Running : TestLlmState
        data class Success(val reply: String) : TestLlmState
        data class Failure(val message: String) : TestLlmState
    }

    // ── Form fields (pre-filled from DataStore once, then user-owned until Save) ──
    val baseUrl = MutableStateFlow(SettingsRepository.DEFAULT_LLM_BASE_URL)
    val apiKey = MutableStateFlow("")
    val model = MutableStateFlow(SettingsRepository.DEFAULT_LLM_MODEL)

    private val _testLlm = MutableStateFlow<TestLlmState>(TestLlmState.Idle)
    val testLlm: StateFlow<TestLlmState> = _testLlm.asStateFlow()

    val showPassword = MutableStateFlow(false)

    /** Persist the three LLM fields to DataStore. */
    fun save(onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsRepository.setLlmBaseUrl(baseUrl.value.trim())
            settingsRepository.setLlmApiKey(apiKey.value.trim())
            settingsRepository.setLlmModel(model.value.trim())
            onSaved()
        }
    }

    /** Tiny "reply OK" round-trip against the endpoint as currently typed. */
    fun testLlm() {
        if (_testLlm.value == TestLlmState.Running) return
        if (apiKey.value.isBlank()) {
            _testLlm.value = TestLlmState.Failure(MESSAGE_NEEDS_KEY)
            return
        }
        _testLlm.value = TestLlmState.Running
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    llmClient.complete(
                        endpoint = LlmClient.Endpoint(
                            baseUrl = baseUrl.value.trim().ifBlank { SettingsRepository.DEFAULT_LLM_BASE_URL },
                            apiKey = apiKey.value.trim(),
                            model = model.value.trim().ifBlank { SettingsRepository.DEFAULT_LLM_MODEL },
                        ),
                        userMessage = "Say OK",
                    )
                }
            }
            _testLlm.value = result.fold(
                onSuccess = { TestLlmState.Success(it.take(60)) },
                onFailure = { TestLlmState.Failure(it.message ?: "Unknown error") },
            )
        }
    }

    // ── "Reanalyse all" + "Reassociate muscle groups" ──

    private val _reanalyzeStatus = MutableStateFlow<String?>(null)
    /** One-line feedback for the reanalyse button ("queued" / progress / error). */
    val reanalyzeStatus: StateFlow<String?> = _reanalyzeStatus.asStateFlow()

    private val _reassociateStatus = MutableStateFlow<String?>(null)
    /** One-line feedback for the catalog re-association button. */
    val reassociateStatus: StateFlow<String?> = _reassociateStatus.asStateFlow()

    /**
     * Re-runs the LLM (or heuristics) muscle-group association over every
     * machine-mapped catalog exercise — body-weight habits included — under
     * the multi-group schema. Rows the user mapped by hand are never touched.
     */
    fun reassociateGroups() {
        if (_reassociateStatus.value == STATUS_REASSOCIATE_RUNNING) return
        viewModelScope.launch {
            val key = settingsRepository.settings.first().llmApiKey
            if (key.isBlank()) {
                _reassociateStatus.value = MESSAGE_NEEDS_KEY
                return@launch
            }
            _reassociateStatus.value = STATUS_REASSOCIATE_RUNNING
            val updated = withContext(Dispatchers.IO) {
                runCatching { tailExerciseIngestor.reclassify() }.getOrDefault(-1)
            }
            _reassociateStatus.value = if (updated >= 0) {
                STATUS_REASSOCIATE_DONE + " ($updated)"
            } else {
                STATUS_REASSOCIATE_FAILED
            }
        }
    }

    /**
     * Enqueues the unique [ReanalyzeLibraryWorker] — force re-runs transcript +
     * muscle-group/exercise analysis for every library video in the background.
     * REPLACE (not KEEP): a previously failed/stuck chain under the same unique
     * name must never silently swallow a fresh user request.
     */
    fun reanalyzeLibrary() {
        viewModelScope.launch {
            val key = settingsRepository.settings.first().llmApiKey
            if (key.isBlank()) {
                _reanalyzeStatus.value = MESSAGE_NEEDS_KEY
                return@launch
            }
            val request = OneTimeWorkRequestBuilder<ReanalyzeLibraryWorker>().build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                ReanalyzeLibraryWorker.UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            _reanalyzeStatus.value = STATUS_REANALYZE_QUEUED
        }
    }

    /**
     * Live status from WorkManager: reflects real worker progress
     * ("analysing 2/4") and terminal outcomes. Observed from init so a fresh
     * ViewModel resumes a running reanalyse rather than showing stale "idle".
     */
    private fun observeReanalyzeWork() {
        viewModelScope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(ReanalyzeLibraryWorker.UNIQUE_NAME)
                .collect { infos ->
                    val info = infos.firstOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.ENQUEUED ->
                            _reanalyzeStatus.value = STATUS_REANALYZE_QUEUED
                        WorkInfo.State.RUNNING -> {
                            val done = info.progress.getInt(ReanalyzeLibraryWorker.PROGRESS_DONE, -1)
                            val total = info.progress.getInt(ReanalyzeLibraryWorker.PROGRESS_TOTAL, -1)
                            _reanalyzeStatus.value =
                                if (done >= 0 && total > 0) "analyzing $done/$total" else "analyzing…"
                        }
                        WorkInfo.State.FAILED ->
                            _reanalyzeStatus.value = STATUS_REANALYZE_FAILED
                        WorkInfo.State.SUCCEEDED ->
                            _reanalyzeStatus.value = STATUS_REANALYZE_DONE
                        else -> Unit
                    }
                }
        }
    }

    // ── Storage section (Phase 10): export/import + stats ──

    data class StorageStats(val videoCount: Int, val downloadedCount: Int, val videoBytes: Long)

    val storageStats: StateFlow<StorageStats> = videoRepository.library
        .map { rows ->
            StorageStats(
                videoCount = rows.size,
                downloadedCount = rows.count { it.videoFilePath != null },
                videoBytes = rows.sumOf { row ->
                    row.videoFilePath?.let { path ->
                        java.io.File(path).takeIf { it.exists() }?.length() ?: 0L
                    } ?: 0L
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageStats(0, 0, 0L))

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    /** Writes the library JSON document to the SAF-created [uri]. */
    fun exportLibrary(uri: android.net.Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = videoRepository.exportLibraryJson()
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray())
                    } ?: error("cannot open output stream")
                }
            }
            _backupStatus.value = result.fold(
                onSuccess = { STATUS_EXPORT_OK },
                onFailure = { STATUS_EXPORT_FAILED + (it.message.orEmpty()) },
            )
        }
    }

    /** Merges the library JSON at [uri] by videoId; imported rows re-download. */
    fun importLibrary(uri: android.net.Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("cannot open input stream")
                    videoRepository.importLibraryJson(json)
                }
            }
            _backupStatus.value = result.fold(
                onSuccess = { res ->
                    // Kick the (unique-KEEP) downloader for each imported row.
                    res.imported.forEach { com.example.kenga.work.DownloadVideoWorker.enqueue(appContext, it) }
                    STATUS_IMPORT_OK + " ${res.imported.size} added, ${res.skippedExisting} skipped"
                },
                onFailure = { STATUS_IMPORT_FAILED + (it.message.orEmpty()) },
            )
        }
    }

    // ── Tail section (Phase 6, §6.3) ──

    /** null = probing. installed+weightsV3 drives the status line. */
    private val _tailAvailability =
        MutableStateFlow<com.example.kenga.ipc.TailClient.TailAvailability?>(null)
    val tailAvailability: StateFlow<com.example.kenga.ipc.TailClient.TailAvailability?> =
        _tailAvailability.asStateFlow()

    val weightUnit: StateFlow<String> = settingsRepository.weightUnit
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_WEIGHT_UNIT)

    // ── Body-weight habits (v2 READ): habit → Kenga exercise mapping ──

    /** v2 habits with type + sub-category names (null = probing). */
    private val _v2Habits =
        MutableStateFlow<List<com.example.kenga.ipc.TailClient.TailV2Habit>?>(null)
    val v2Habits: StateFlow<List<com.example.kenga.ipc.TailClient.TailV2Habit>?> =
        _v2Habits.asStateFlow()

    val bodyweightMap: StateFlow<Map<String, String>> = settingsRepository.bodyweightHabitMap
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** "" clears the mapping. */
    fun setBodyweightHabit(habitName: String, exerciseName: String) {
        viewModelScope.launch {
            settingsRepository.setBodyweightHabit(habitName, exerciseName)
            // Seed the (possibly new) mapped name + its Tail sub-category
            // variants into the catalog and let the LLM associate them.
            seedBodyweightCatalog()
        }
    }

    /** Catalog rows keyed by lowercase exercise name (for association display). */
    val exerciseCatalog: StateFlow<Map<String, com.example.kenga.data.local.ExerciseEntity>> =
        database.exerciseDao().observeAll()
            .map { rows -> rows.associateBy { it.name } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Manual muscle-group edit for a body-weight association (base name or a
     * "Name (Subtype)" variant). An exercise may carry SEVERAL groups — the
     * column is a CSV of canonical ids, primary first; an empty list means
     * "None" (row excluded from the body figure). User edits are written as
     * MANUAL so the LLM ingest never overwrites them.
     */
    fun setAssociationGroups(displayName: String, groupIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val key = displayName.trim().lowercase()
            if (key.isEmpty()) return@launch
            val dao = database.exerciseDao()
            val existing = dao.find(key)
            if (groupIds.isEmpty() && existing == null) return@launch
            dao.upsert(
                (existing ?: com.example.kenga.data.local.ExerciseEntity(
                    name = key,
                    muscleGroupId = "",
                    source = com.example.kenga.data.local.ExerciseSource.MANUAL,
                    lastUsedAt = System.currentTimeMillis(),
                    aliases = displayName.trim(),
                )).copy(
                    muscleGroupId = groupIds.distinct().joinToString(","),
                    source = com.example.kenga.data.local.ExerciseSource.MANUAL,
                ),
            )
        }
    }

    /** Ensure mapped exercises + Tail sub-type variants exist, then LLM-associate blanks. */
    private fun seedBodyweightCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val bwMap = settingsRepository.bodyweightHabitMap.first()
                    .filterValues { it.isNotBlank() }
                if (bwMap.isEmpty()) return@runCatching
                val subtypesByHabit = _v2Habits.value.orEmpty()
                    .filter { it.habitType == "subtyped" }
                    .associate { it.habitName to it.subtypeNames }
                bwMap.forEach { (habit, exerciseName) ->
                    tailExerciseIngestor.ensureCatalogEntry(exerciseName)
                    subtypesByHabit[habit].orEmpty().forEach {
                        tailExerciseIngestor.ensureCatalogEntry("$exerciseName ($it)")
                    }
                }
                tailExerciseIngestor.ingestMissing()
            }
        }
    }

    private val _tailSyncStatus = MutableStateFlow<String?>(null)
    val tailSyncStatus: StateFlow<String?> = _tailSyncStatus.asStateFlow()

    private fun loadTailStatus() {
        viewModelScope.launch {
            _tailAvailability.value = withContext(Dispatchers.IO) { tailClient.queryAvailability() }
            if (_tailAvailability.value?.installed == true) {
                _v2Habits.value = withContext(Dispatchers.IO) { tailClient.queryV2Habits() }
            }
            // v2 subtypes are now known — seed mapped names + variants and
            // let the LLM classify any blanks so the associations show up.
            seedBodyweightCatalog()
        }
    }

    fun setWeightUnit(unit: String) {
        viewModelScope.launch { settingsRepository.setWeightUnit(unit) }
    }

    /** "Sync now": force a flush + snapshot pull with live result feedback. */
    fun syncTailNow() {
        val wm = WorkManager.getInstance(appContext)
        wm.enqueueUniqueWork(
            com.example.kenga.work.TailSyncWorker.UNIQUE_ONESHOT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<com.example.kenga.work.TailSyncWorker>().build(),
        )
        _tailSyncStatus.value = STATUS_TAIL_QUEUED
    }

    private fun observeTailSyncWork() {
        viewModelScope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(com.example.kenga.work.TailSyncWorker.UNIQUE_ONESHOT)
                .collect { infos ->
                    val info = infos.firstOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.ENQUEUED -> _tailSyncStatus.value = STATUS_TAIL_QUEUED
                        WorkInfo.State.RUNNING -> _tailSyncStatus.value = STATUS_TAIL_RUNNING
                        WorkInfo.State.SUCCEEDED -> _tailSyncStatus.value = STATUS_TAIL_DONE
                        WorkInfo.State.FAILED -> _tailSyncStatus.value = STATUS_TAIL_FAILED
                        else -> Unit
                    }
                }
        }
    }

    // Placed at the END of the class: this init block launches coroutines that
    // assign MutableStateFlow properties declared below. Kotlin initializes
    // properties in declaration order, so an init block at the top of the class
    // could run (and resume on Dispatchers.Main.immediate) before those flows
    // exist, causing an NPE. Keeping it last guarantees every property is ready.
    init {
        viewModelScope.launch {
            val current = settingsRepository.settings.first()
            baseUrl.value = current.llmBaseUrl
            apiKey.value = current.llmApiKey
            model.value = current.llmModel
        }
        observeReanalyzeWork()
        loadTailStatus()
        observeTailSyncWork()
    }

    companion object {
        const val STATUS_EXPORT_OK = "library exported ✓"
        const val STATUS_EXPORT_FAILED = "export failed"
        const val STATUS_IMPORT_OK = "import done —"
        const val STATUS_IMPORT_FAILED = "import failed"
        const val STATUS_TAIL_QUEUED = "queued"
        const val STATUS_TAIL_RUNNING = "syncing…"
        const val STATUS_TAIL_DONE = "synced ✓"
        const val STATUS_TAIL_FAILED = "sync failed — will retry"
        const val MESSAGE_NEEDS_KEY = "enter an API key first"
        const val STATUS_REANALYZE_QUEUED = "queued"
        const val STATUS_REANALYZE_DONE = "done"
        const val STATUS_REANALYZE_FAILED = "failed — see logs (tag ReanalyzeLibraryWorker)"

        const val STATUS_REASSOCIATE_RUNNING = "reassoc-running"
        const val STATUS_REASSOCIATE_DONE = "done"
        const val STATUS_REASSOCIATE_FAILED = "failed — see logs (tag TailExerciseIngestor)"

        /** Factory pattern consistent with VideoDetailViewModel. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KengaApplication
                SettingsViewModel(
                    appContext = app.applicationContext,
                    settingsRepository = app.appContainer.settingsRepository,
                    llmClient = app.appContainer.llmClient,
                    tailClient = app.appContainer.tailClient,
                    videoRepository = app.appContainer.videoRepository,
                    database = app.appContainer.database,
                    tailExerciseIngestor = app.appContainer.tailExerciseIngestor,
                )
            }
        }
    }
}
