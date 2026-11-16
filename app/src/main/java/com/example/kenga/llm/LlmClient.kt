package com.example.kenga.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Canonical muscle-group id list shared with the LLM prompt (plans §3/§4).
 * Kept as strings so the llm package does not depend on the Room layer; the
 * pipeline maps them via [com.example.kenga.data.local.MuscleGroup.fromId].
 */
object MuscleGroupIds {
    val CANONICAL_IDS: List<String> = listOf(
        "neck", "shoulders", "chest", "upper_back", "lower_back", "core", "obliques",
        "lats", "hips_glutes", "groin_adductors", "quads", "hamstrings", "calves",
        "ankles_feet", "wrists_forearms", "biceps_triceps",
    )
}

/**
 * Provider-agnostic OpenAI-compatible chat-completions client (Octo `LlmClient`
 * port, Kenga §4 schema): POST {baseUrl}/chat/completions,
 * `response_format {"type":"json_object"}`, temperature 0.2, kotlinx-serialization
 * validation with unknown muscle-group ids dropped.
 */
class LlmClient(private val okHttpClient: OkHttpClient) {

    /** Endpoint credentials from [com.example.kenga.data.settings.KengaSettings]. */
    data class Endpoint(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    /**
     * One detected exercise (§4 schema rows). An exercise can train several
     * muscle groups — [muscleGroups] lists them PRIMARY MOVER FIRST. The old
     * single-id field stays as a computed property so heuristic callers and
     * tests keep working.
     */
    @Serializable
    data class DetectedExercise(
        val name: String,
        val muscleGroups: List<String> = emptyList(),
        val confidence: Double = 0.0,
    ) {
        /** Primary (first) muscle-group id, or "" when unclassified. */
        val muscleGroup: String get() = muscleGroups.firstOrNull().orEmpty()
    }

    /** Validated model answer (§4 schema). */
    @Serializable
    data class ExerciseAnalysis(
        val muscleGroups: List<String> = emptyList(),
        val exercises: List<DetectedExercise> = emptyList(),
        val confidence: Double = 0.0,
        val notes: String = "",
    )

    /** Thrown on HTTP/network/parse failure — the pipeline maps this to AnalysisState.FAILED. */
    class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Classifies [transcript] into muscle-group ids + exercise names. Short input →
     * one call; input over [CHUNK_CHAR_LIMIT] → chunked calls, results unioned:
     * muscles preserve first-seen order; exercises dedupe case-insensitively keeping
     * the highest confidence per name.
     *
     * @param isTitleDescriptionOnly true when [transcript] is title+description, not
     *        a real transcript — the system prompt is adjusted accordingly.
     * @param knownExerciseNames the user's own exercise names (Tail read-back,
     *        Phase 6) — passed as preferred naming hints; may be empty.
     * @param framesBase64 optional ordered video stills (base64 JPEG) appended as
     *        vision content parts when the endpoint is multimodal-capable.
     */
    fun classifyExercises(
        endpoint: Endpoint,
        transcript: String,
        isTitleDescriptionOnly: Boolean,
        knownExerciseNames: List<String> = emptyList(),
        framesBase64: List<String> = emptyList(),
    ): ExerciseAnalysis {
        require(endpoint.baseUrl.isNotBlank()) { "LLM base URL is blank" }
        require(endpoint.model.isNotBlank()) { "LLM model is blank" }

        // Frames only make sense on a single call (short transcript) — a chunked
        // text run repeats the frames pointlessly, so they are sampled once.
        val chunks = chunk(transcript, CHUNK_CHAR_LIMIT)
        if (chunks.size == 1) {
            return classifyChunk(
                endpoint = endpoint,
                chunkText = chunks[0],
                isTitleDescriptionOnly = isTitleDescriptionOnly,
                knownExerciseNames = knownExerciseNames,
                framesBase64 = framesBase64,
                chunkIndex = null,
                chunkCount = 1,
            )
        }

        val mergedMuscles = LinkedHashSet<String>()
        val exercisesByCiName = LinkedHashMap<String, DetectedExercise>()
        var confidenceSum = 0.0
        val notes = StringBuilder()
        chunks.forEachIndexed { index, chunkText ->
            val part = classifyChunk(
                endpoint = endpoint,
                chunkText = chunkText,
                isTitleDescriptionOnly = isTitleDescriptionOnly,
                knownExerciseNames = knownExerciseNames,
                framesBase64 = emptyList(),
                chunkIndex = index,
                chunkCount = chunks.size,
            )
            mergedMuscles += part.muscleGroups
            part.exercises.forEach { exercise ->
                val key = exercise.name.lowercase().trim()
                val existing = exercisesByCiName[key]
                if (existing == null || exercise.confidence > existing.confidence) {
                    exercisesByCiName[key] = exercise
                }
            }
            confidenceSum += part.confidence
            if (part.notes.isNotBlank()) {
                if (notes.isNotEmpty()) notes.append("; ")
                notes.append(part.notes)
            }
        }
        return ExerciseAnalysis(
            muscleGroups = mergedMuscles.toList(),
            exercises = exercisesByCiName.values.toList(),
            confidence = confidenceSum / chunks.size,
            notes = notes.toString(),
        )
    }

    /** Tiny round-trip used by Settings "Test LLM" — returns the raw assistant text. */
    fun complete(endpoint: Endpoint, userMessage: String): String {
        val body = buildChatBody(
            endpoint = endpoint,
            system = "You are a helpful assistant. Reply with exactly: OK",
            userContent = textContent(userMessage),
        )
        val content = extractMessageContent(postChatCompletions(endpoint, body))
        return content?.takeIf { it.isNotBlank() } ?: throw LlmException("Empty completion response")
    }

    // ─────────────────────────────────────────────────────────────────────────────

    private fun classifyChunk(
        endpoint: Endpoint,
        chunkText: String,
        isTitleDescriptionOnly: Boolean,
        knownExerciseNames: List<String>,
        framesBase64: List<String>,
        chunkIndex: Int?,
        chunkCount: Int,
    ): ExerciseAnalysis {
        val system = buildSystemPrompt(isTitleDescriptionOnly, chunkIndex, chunkCount)
        val user = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put(
                    "text",
                    buildUserText(chunkText, isTitleDescriptionOnly, knownExerciseNames),
                )
            }
            )
            framesBase64.forEachIndexed { index, b64 ->
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Frame ${index + 1}/${framesBase64.size}:")
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", "data:image/jpeg;base64,$b64")
                    }
                })
            }
        }
        val responseContent = extractMessageContent(
            postChatCompletions(endpoint, buildChatBody(endpoint, system, user)),
        )
        return parseAnalysis(responseContent)
    }

    private fun buildUserText(
        chunkText: String,
        isTitleDescriptionOnly: Boolean,
        knownExerciseNames: List<String>,
    ): String = buildString {
        if (isTitleDescriptionOnly) {
            append("Video title, description and tags:\n")
        } else {
            append("Video transcript:\n")
        }
        append(chunkText)
        if (knownExerciseNames.isNotEmpty()) {
            append("\n\nThe user already tracks these exercise names in their training log. ")
            append("When an exercise in the video matches one of them, prefer the user's exact naming:\n")
            append(knownExerciseNames.take(200).joinToString(", "))
        }
    }

    private fun buildSystemPrompt(
        isTitleDescriptionOnly: Boolean,
        chunkIndex: Int?,
        chunkCount: Int,
    ): String = buildString {
        append("You are a strength-training assistant. ")
        if (isTitleDescriptionOnly) {
            append("No transcript is available — only the video title, description and tags. ")
            append("Recall matters more than precision here: list EVERY muscle group and ")
            append("exercise the video plausibly demonstrates, inferring from the drill type ")
            append("named in the title. ")
        } else {
            append("Given the transcript of a weight-lifting video, list EVERY muscle group ")
            append("that is trained and the specific exercises performed or demonstrated — ")
            append("including brief mentions and partial demonstrations. ")
        }
        append("\n\nRules:\n")
        append("- muscle_group ids must come ONLY from this canonical list:\n  ")
        append(MuscleGroupIds.CANONICAL_IDS.joinToString(", "))
        append("\n- Exercise names use natural Title Case (e.g. \"Bench Press\", \"Romanian ")
        append("Deadlift\"); prefer the user's own naming when provided.\n")
        append("- An exercise typically trains SEVERAL muscle groups — list EVERY group it ")
        append("meaningfully works in that exercise's muscle_groups array, PRIMARY mover FIRST ")
        append("(e.g. a push-up → [\"chest\", \"shoulders\", \"biceps_triceps\"]). Never squeeze an ")
        append("exercise down to a single group when synergists are clearly involved — this ")
        append("matters most for body-weight drills (pull-ups, dips, squats, push-ups, planks, lunges).\n")
        append("- Ignore pleasantries and chit-chat — but never drop an actual exercise mention.\n")
        if (chunkIndex != null) {
            append("- This is part ${chunkIndex + 1} of $chunkCount of a longer transcript — ")
            append("classify this part on its own; other chunks are merged elsewhere.\n")
        }
        append("- Respond with JSON only, matching this schema:\n")
        append(
            """  {"muscle_groups": ["id", ...], "exercises": [{"name": "...", "muscle_groups": ["primary_id", "secondary_id", ...], "confidence": 0.0-1.0}], """ +
                """"confidence": 0.0-1.0, "notes": "brief optional rationale"}""",
        )
    }

    private fun textContent(text: String) = buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
        })
    }

    /** Body with a pre-built (possibly multimodal) user content array. */
    private fun buildChatBody(endpoint: Endpoint, system: String, userContent: kotlinx.serialization.json.JsonArray): String {
        val payload = buildJsonObject {
            put("model", endpoint.model)
            put("temperature", 0.2)
            putJsonObject("response_format") { put("type", "json_object") }
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userContent)
                })
            })
        }
        return payload.toString()
    }

    private fun postChatCompletions(endpoint: Endpoint, body: String): String {
        val url = endpoint.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        // LLM calls must not inherit the downloader's unbounded call timeout.
        val client = okHttpClient.newBuilder()
            .callTimeout(LLM_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LlmException("LLM HTTP ${response.code}: ${responseBody.take(300)}")
                }
                return responseBody
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("LLM request failed: ${e.message}", e)
        }
    }

    /** Pulls `choices[0].message.content` out of the raw completion JSON. */
    private fun extractMessageContent(rawResponseBody: String): String? = try {
        val root = json.parseToJsonElement(rawResponseBody).jsonObject
        val first = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        first["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    /**
     * Decodes + validates the model's JSON answer (§4): unknown muscle-group ids
     * are dropped from both lists; exercise names are trimmed and deduped
     * case-insensitively; each exercise carries its canonical muscle_groups
     * array (primary first). The single-id `muscle_group` field of the old
     * schema is still accepted for models that answer it (dropped only when
     * non-canonical).
     */
    private fun parseAnalysis(content: String?): ExerciseAnalysis {
        if (content.isNullOrBlank()) throw LlmException("LLM returned no content")
        val raw = parseJsonContent(content)
        val muscleIds = raw["muscle_groups"]?.jsonArray
            ?.mapNotNull { element ->
                runCatching { element.jsonPrimitive.content }.getOrNull()
            }
            .orEmpty()
            .filter { it in MuscleGroupIds.CANONICAL_IDS } // drop hallucinated ids
            .distinct()

        val seenNames = HashSet<String>()
        val exercises = raw["exercises"]?.jsonArray
            ?.mapNotNull { element ->
                runCatching {
                    val obj = element.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (name.isEmpty()) return@runCatching null
                    val groups = buildList {
                        obj["muscle_groups"]?.jsonArray?.forEach { g ->
                            runCatching { add(g.jsonPrimitive.content.trim()) }
                        }
                        // Legacy single-id field: only when the array is absent.
                        if (isEmpty()) {
                            obj["muscle_group"]?.jsonPrimitive?.contentOrNull?.let { add(it.trim()) }
                        }
                    }.filter { it in MuscleGroupIds.CANONICAL_IDS } // drop hallucinated ids
                        .distinct()
                    if (groups.isEmpty()) return@runCatching null
                    val confidence = obj["confidence"]?.jsonPrimitive?.doubleOrNull
                        ?.coerceIn(0.0, 1.0) ?: 0.0
                    DetectedExercise(name = name, muscleGroups = groups, confidence = confidence)
                }.getOrNull()
            }
            .orEmpty()
            .filter { seenNames.add(it.name.lowercase()) } // case-insensitive dedupe

        val confidence = raw["confidence"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 1.0) ?: 0.0
        val notes = raw["notes"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return ExerciseAnalysis(
            muscleGroups = muscleIds,
            exercises = exercises,
            confidence = confidence,
            notes = notes,
        )
    }

    private fun parseJsonContent(content: String): kotlinx.serialization.json.JsonObject {
        val cleaned = content
            .trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            json.parseToJsonElement(cleaned).jsonObject
        } catch (e: Exception) {
            throw LlmException("LLM response is not valid JSON: ${content.take(200)}", e)
        }
    }

    /** Splits into paragraph-aware chunks no longer than [limit] chars. */
    internal fun chunk(text: String, limit: Int = CHUNK_CHAR_LIMIT): List<String> {
        if (text.length <= limit) return listOf(text)
        val chunks = ArrayList<String>()
        var cursor = 0
        while (cursor < text.length) {
            var end = minOf(cursor + limit, text.length)
            if (end < text.length) {
                // Prefer breaking on a paragraph, then a line, then any whitespace.
                val lastParagraph = text.lastIndexOf("\n\n", end)
                val lastLine = text.lastIndexOf('\n', end)
                val lastSpace = text.lastIndexOf(' ', end)
                end = when {
                    lastParagraph > cursor + limit / 2 -> lastParagraph + 2
                    lastLine > cursor + limit / 2 -> lastLine + 1
                    lastSpace > cursor + limit / 2 -> lastSpace + 1
                    else -> end
                }
            }
            chunks += text.substring(cursor, end).trim()
            cursor = end
        }
        return chunks.filter { it.isNotEmpty() }.ifEmpty { listOf(text.take(limit)) }
    }

    companion object {
        /** ~24k chars per request (Kenga Phase 4 task spec). */
        const val CHUNK_CHAR_LIMIT = 24_000
        const val LLM_CALL_TIMEOUT_SECONDS = 120L
    }
}
