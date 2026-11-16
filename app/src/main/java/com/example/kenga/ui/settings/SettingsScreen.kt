package com.example.kenga.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kenga.R
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.ui.components.GlassCard
import com.example.kenga.ui.components.KengaHeader

/**
 * Settings (Phase 4): LLM endpoint form (base URL / API key / model, z.ai
 * defaults) + Save + inline "Test LLM" round-trip + "Reanalyse all" library
 * action with live worker progress. Tail mapping (Phase 6) and storage/about
 * (Phase 10) sections are marked TODO below.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val testState by viewModel.testLlm.collectAsState()
    val showPassword by viewModel.showPassword.collectAsState()
    val baseUrl by viewModel.baseUrl.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val model by viewModel.model.collectAsState()
    val reanalyzeStatus by viewModel.reanalyzeStatus.collectAsState()
    val reassociateStatus by viewModel.reassociateStatus.collectAsState()
    val storageStats by viewModel.storageStats.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    var saveFlash by remember { mutableStateOf(false) }

    // Phase 10: SAF CreateDocument / OpenDocument — export writes the library
    // JSON, import merges by videoId (imported rows re-download via worker).
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.exportLibrary(uri)
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importLibrary(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        KengaHeader(
            title = stringResource(R.string.settings_title),
            modifier = Modifier.padding(top = 8.dp),
        )

        // ── LLM section (Octo port) ──
        GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_llm_section),
                    style = MaterialTheme.typography.titleMedium,
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { viewModel.baseUrl.value = it },
                    label = { Text(stringResource(R.string.settings_llm_base_url)) },
                    supportingText = {
                        Text(stringResource(R.string.settings_llm_base_url_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { viewModel.apiKey.value = it },
                    label = { Text(stringResource(R.string.settings_llm_api_key)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { viewModel.showPassword.value = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (showPassword) {
                                    stringResource(R.string.settings_hide)
                                } else {
                                    stringResource(R.string.settings_show)
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { viewModel.model.value = it },
                    label = { Text(stringResource(R.string.settings_llm_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { viewModel.save { saveFlash = true } }) {
                        Text(stringResource(R.string.settings_save))
                    }
                    OutlinedButton(onClick = { viewModel.testLlm() }) {
                        Text(stringResource(R.string.settings_test_llm))
                    }
                    if (saveFlash) {
                        Text(
                            text = stringResource(R.string.settings_saved),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                TestLlmResult(testState)
            }
        }

        // ── Analysis: reanalyse the whole library ──
        GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_reanalyze_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_reanalyze_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { viewModel.reanalyzeLibrary() }) {
                    Text(stringResource(R.string.settings_reanalyze_button))
                }
                Text(
                    text = stringResource(R.string.settings_reassociate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { viewModel.reassociateGroups() }) {
                    Text(stringResource(R.string.settings_reassociate_button))
                }
                when (val reassociate = reassociateStatus) {
                    null -> Unit
                    SettingsViewModel.MESSAGE_NEEDS_KEY -> Text(
                        text = stringResource(R.string.settings_reanalyze_needs_key),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SettingsViewModel.STATUS_REASSOCIATE_RUNNING -> Text(
                        text = stringResource(R.string.settings_reassociate_running),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SettingsViewModel.STATUS_REASSOCIATE_FAILED -> Text(
                        text = stringResource(R.string.settings_reassociate_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text(
                        text = if (reassociate.startsWith(SettingsViewModel.STATUS_REASSOCIATE_DONE)) {
                            stringResource(R.string.settings_reassociate_done)
                        } else {
                            reassociate
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                when (reanalyzeStatus) {
                    null -> Unit
                    SettingsViewModel.MESSAGE_NEEDS_KEY -> Text(
                        text = stringResource(R.string.settings_reanalyze_needs_key),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SettingsViewModel.STATUS_REANALYZE_QUEUED -> Text(
                        text = stringResource(R.string.settings_reanalyze_queued),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SettingsViewModel.STATUS_REANALYZE_DONE -> Text(
                        text = stringResource(R.string.settings_reanalyze_done),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> Text(
                        text = reanalyzeStatus.orEmpty(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        TailSection(viewModel)

        // ── Storage: stats + export/import library JSON (Phase 10) ──
        GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_storage_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                val mb = storageStats.videoBytes / (1024.0 * 1024.0)
                Text(
                    text = stringResource(
                        R.string.settings_storage_stats,
                        storageStats.videoCount,
                        storageStats.downloadedCount,
                        if (mb >= 1.0) "%.1f MB".format(mb) else "${storageStats.videoBytes / 1024} KB",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("kenga-library.json") }) {
                        Text(stringResource(R.string.settings_export))
                    }
                    OutlinedButton(onClick = {
                        importLauncher.launch(arrayOf("application/json"))
                    }) {
                        Text(stringResource(R.string.settings_import))
                    }
                }
                backupStatus?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_storage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_about_version),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun TailSection(viewModel: SettingsViewModel) {
    val availability by viewModel.tailAvailability.collectAsState()
    val unit by viewModel.weightUnit.collectAsState()
    val syncStatus by viewModel.tailSyncStatus.collectAsState()

    GlassCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_tail_section),
                style = MaterialTheme.typography.titleMedium,
            )

            // Status line: installed? weights_v3?
            val statusText = when {
                availability == null -> stringResource(R.string.settings_tail_checking)
                availability!!.installed && availability!!.weightsV3 ->
                    stringResource(R.string.settings_tail_connected)
                availability!!.installed ->
                    stringResource(R.string.settings_tail_no_v3)
                else -> stringResource(R.string.settings_tail_absent)
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    availability?.installed == true && availability?.weightsV3 == true ->
                        MaterialTheme.colorScheme.primary
                    availability?.installed == true ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
            )

            if (availability?.installed != true) {
                Text(
                    text = stringResource(R.string.settings_tail_offline_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // kg/lb unit toggle.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_tail_unit),
                    style = MaterialTheme.typography.bodyMedium,
                )
                listOf("kg", "lb").forEach { u ->
                    FilterChip(
                        selected = unit == u,
                        onClick = { viewModel.setWeightUnit(u) },
                        label = { Text(u) },
                    )
                }
            }

            BodyweightHabitSection(viewModel)

            BodyweightAssociationsSection(viewModel)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { viewModel.syncTailNow() }) {
                    Text(stringResource(R.string.settings_tail_sync_now))
                }
                syncStatus?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * Body-weight habits (§6.2 v2): every Tail counter/subtyped habit can be
 * mapped to a Kenga exercise name. Sub-category rows become "Exercise
 * (Sub-category)" catalog entries; the LLM associates them (and the base
 * names) with muscle groups. Fully user-editable; blank = not an exercise.
 */
@Composable
private fun BodyweightHabitSection(viewModel: SettingsViewModel) {
    val v2Habits by viewModel.v2Habits.collectAsState()
    val bwMap by viewModel.bodyweightMap.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    val candidates = v2Habits.orEmpty()
        .filter { it.habitType == "counter" || it.habitType == "subtyped" }

    // Always include mapped habits (even when Tail is unreachable), plus all
    // Tail counter/subtyped habits when the v2 list loaded.
    val rows = (
        bwMap.keys +
            candidates.map { it.habitName }
        ).distinct().sorted()

    if (rows.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_tail_bodyweight_section),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.settings_tail_bodyweight_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val mappedCount = bwMap.size
        OutlinedButton(onClick = { showDialog = true }) {
            Text(
                stringResource(
                    R.string.settings_tail_bodyweight_manage,
                    mappedCount,
                ),
            )
        }
    }

    if (showDialog) {
        BodyweightHabitPickerDialog(
            rows = rows,
            candidates = candidates,
            bwMap = bwMap,
            onValueChange = { habit, value -> viewModel.setBodyweightHabit(habit, value) },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * Searchable popup: with dozens of Tail habits the inline settings list was
 * unusable — the dialog filters habits by name (mapping shown as the row's
 * text-field value) and edits are written through immediately.
 */
@Composable
private fun BodyweightHabitPickerDialog(
    rows: List<String>,
    candidates: List<com.example.kenga.ipc.TailClient.TailV2Habit>,
    bwMap: Map<String, String>,
    onValueChange: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = if (query.isBlank()) {
        rows
    } else {
        rows.filter {
            it.contains(query, ignoreCase = true) ||
                bwMap[it].orEmpty().contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_tail_bodyweight_section)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.settings_tail_bodyweight_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (filtered.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_tail_bodyweight_no_match),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    filtered.forEach { habit ->
                        val subtypes =
                            candidates.firstOrNull { it.habitName == habit }?.subtypeNames.orEmpty()
                        BodyweightHabitRow(
                            habitName = habit,
                            subtypes = subtypes,
                            value = bwMap[habit].orEmpty(),
                            onValueChange = { onValueChange(habit, it) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
        },
    )
}

@Composable
private fun BodyweightHabitRow(
    habitName: String,
    subtypes: List<String>,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(habitName) },
            placeholder = { Text(stringResource(R.string.settings_tail_bodyweight_placeholder)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (subtypes.isNotEmpty() && value.isNotBlank()) {
            Text(
                text = stringResource(R.string.settings_tail_bodyweight_subtypes, subtypes.joinToString(", ")),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Mapped body-weight habits with their LLM-determined muscle-group
 * associations, each manually editable. Sub-categories reported by Tail's
 * v2 habit list get their own "Exercise (Subtype)" catalog row and dropdown,
 * because variants can work different muscles (chin-up vs pull-up).
 */
@Composable
private fun BodyweightAssociationsSection(viewModel: SettingsViewModel) {
    val bwMap by viewModel.bodyweightMap.collectAsState()
    val v2Habits by viewModel.v2Habits.collectAsState()
    val catalog by viewModel.exerciseCatalog.collectAsState()
    val mapped = bwMap.filterValues { it.isNotBlank() }.entries.sortedBy { it.key }
    if (mapped.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.settings_tail_bodyweight_associations),
            style = MaterialTheme.typography.titleSmall,
        )
        mapped.forEach { (habit, exercise) ->
            AssociationGroupRow(
                label = "$habit → $exercise",
                groups = catalog[exercise.trim().lowercase()]?.muscleGroupIds
                    .orEmpty().mapNotNull(MuscleGroup::fromId),
                onSelect = { selected ->
                    viewModel.setAssociationGroups(exercise, selected.map { it.id })
                },
            )
            val subtypes = v2Habits.orEmpty()
                .firstOrNull { it.habitName == habit }?.subtypeNames.orEmpty()
            subtypes.forEach { subtype ->
                val variant = "$exercise ($subtype)"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Spacer(Modifier.width(16.dp))
                    AssociationGroupRow(
                        label = variant,
                        groups = catalog[variant.trim().lowercase()]?.muscleGroupIds
                            .orEmpty().mapNotNull(MuscleGroup::fromId),
                        onSelect = { selected ->
                            viewModel.setAssociationGroups(variant, selected.map { it.id })
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * One association's muscle-group mapping, MULTI-SELECT: an exercise can train
 * several groups (chips in the dialog), the CSV summary shows them all, and
 * an empty selection means "None" (excluded from the body figure).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssociationGroupRow(
    label: String,
    groups: List<MuscleGroup>,
    onSelect: (List<MuscleGroup>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val current = if (groups.isEmpty()) {
        stringResource(R.string.settings_muscle_group_none)
    } else {
        groups.joinToString(", ") { it.displayName }
    }
    OutlinedButton(
        onClick = { pickerOpen = true },
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = "$label · $current",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
        )
    }
    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text(label) },
            text = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MuscleGroup.entries.forEach { group ->
                        FilterChip(
                            selected = group in groups,
                            onClick = {
                                onSelect(
                                    if (group in groups) groups - group else groups + group,
                                )
                            },
                            label = {
                                Text(group.displayName, style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) {
                    Text(stringResource(R.string.settings_done))
                }
            },
        )
    }
}

@Composable
private fun TestLlmResult(state: SettingsViewModel.TestLlmState) {
    when (state) {
        is SettingsViewModel.TestLlmState.Idle -> Unit

        is SettingsViewModel.TestLlmState.Running -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_test_running))
            }
        }

        is SettingsViewModel.TestLlmState.Success -> Text(
            text = stringResource(R.string.settings_test_ok, state.reply),
            color = MaterialTheme.colorScheme.primary,
        )

        is SettingsViewModel.TestLlmState.Failure -> Text(
            text = stringResource(R.string.settings_test_failed, state.message),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
