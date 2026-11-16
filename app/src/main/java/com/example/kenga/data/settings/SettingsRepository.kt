package com.example.kenga.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Immutable snapshot of user settings — exposed as a Flow by [SettingsRepository]. */
data class KengaSettings(
    val llmBaseUrl: String,
    val llmApiKey: String,
    val llmModel: String,
    /** Tail body-weight habit name → Kenga exercise name (e.g. "Pull-ups" → "Pull-Up"). */
    val bodyweightHabitMap: Map<String, String>,
    /** "kg" or "lb". */
    val weightUnit: String,
)

/**
 * DataStore-backed settings (plans/kenga_architecture.md §6.3): provider-agnostic
 * OpenAI-compatible LLM endpoint (defaults to z.ai) + the muscle-group → Tail
 * weights-habit mapping + display weight unit.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val WEIGHT_UNIT = stringPreferencesKey("weight_unit")

        /** Single JSON blob: Tail body-weight habit → Kenga exercise name. */
        val BODYWEIGHT_HABIT_MAP = stringPreferencesKey("bodyweight_habit_map")

        /**
         * One-time marker: machine-mapped catalog rows were re-classified into
         * the multi-muscle-group schema (TailSynchronizer upgrade pass).
         */
        val CATALOG_MULTI_GROUP_MIGRATED = booleanPreferencesKey("catalog_multi_group_migrated")
    }

    val settings: Flow<KengaSettings> = dataStore.data.map { prefs ->
        KengaSettings(
            // Old builds shipped the pay-as-you-go URL; a GLM Coding Plan key
            // against it fails with an "insufficient balance" error — migrate.
            llmBaseUrl = (prefs[Keys.LLM_BASE_URL] ?: DEFAULT_LLM_BASE_URL)
                .takeIf { it != LEGACY_LLM_BASE_URL } ?: DEFAULT_LLM_BASE_URL,
            llmApiKey = prefs[Keys.LLM_API_KEY] ?: "",
            llmModel = prefs[Keys.LLM_MODEL] ?: DEFAULT_LLM_MODEL,
            bodyweightHabitMap = decodeBodyweightMap(
                prefs[Keys.BODYWEIGHT_HABIT_MAP],
            ),
            weightUnit = prefs[Keys.WEIGHT_UNIT] ?: DEFAULT_WEIGHT_UNIT,
        )
    }

    val llmBaseUrl: Flow<String> = settings.map { it.llmBaseUrl }
    val llmApiKey: Flow<String> = settings.map { it.llmApiKey }
    val llmModel: Flow<String> = settings.map { it.llmModel }
    val bodyweightHabitMap: Flow<Map<String, String>> = settings.map { it.bodyweightHabitMap }
    val weightUnit: Flow<String> = settings.map { it.weightUnit }

    /** True once the one-time multi-group catalog re-classification has run. */
    val catalogMultiGroupMigrated: Flow<Boolean> =
        dataStore.data.map { it[Keys.CATALOG_MULTI_GROUP_MIGRATED] ?: false }

    suspend fun setCatalogMultiGroupMigrated() =
        dataStore.edit { it[Keys.CATALOG_MULTI_GROUP_MIGRATED] = true }

    suspend fun setLlmBaseUrl(value: String) = dataStore.edit { it[Keys.LLM_BASE_URL] = value }
    suspend fun setLlmApiKey(value: String) = dataStore.edit { it[Keys.LLM_API_KEY] = value }
    suspend fun setLlmModel(value: String) = dataStore.edit { it[Keys.LLM_MODEL] = value }

    /** "" clears the mapping (body-weight habit no longer an exercise). */
    suspend fun setBodyweightHabit(habitName: String, exerciseName: String) {
        val current = bodyweightHabitMap.first().toMutableMap()
        if (exerciseName.isBlank()) current.remove(habitName) else current[habitName] = exerciseName.trim()
        dataStore.edit { it[Keys.BODYWEIGHT_HABIT_MAP] = encodeBodyweightMap(current) }
    }

    suspend fun setWeightUnit(value: String) =
        dataStore.edit { it[Keys.WEIGHT_UNIT] = if (value == "lb") "lb" else "kg" }

    private val bodyweightMapSerializer = MapSerializer(String.serializer(), String.serializer())

    private fun encodeBodyweightMap(map: Map<String, String>): String =
        Json.encodeToString(bodyweightMapSerializer, map)

    private fun decodeBodyweightMap(raw: String?): Map<String, String> =
        raw?.let { json ->
            runCatching { Json.decodeFromString(bodyweightMapSerializer, json) }
                .getOrDefault(emptyMap())
        }.orEmpty()

    companion object {
        /** GLM Coding Plan endpoint (same as Tail): subscription keys only work here. */
        const val DEFAULT_LLM_BASE_URL = "https://api.z.ai/api/coding/paas/v4"

        /** Pre-coding-plan default — silently upgraded in [settings]. */
        const val LEGACY_LLM_BASE_URL = "https://api.z.ai/api/paas/v4"
        const val DEFAULT_LLM_MODEL = "glm-4.6"
        const val DEFAULT_WEIGHT_UNIT = "kg"
    }
}
