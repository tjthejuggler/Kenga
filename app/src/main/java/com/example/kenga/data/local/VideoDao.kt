package com.example.kenga.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(video: VideoEntity)

    @Update
    suspend fun update(video: VideoEntity)

    @Query("SELECT * FROM videos ORDER BY addedAt DESC, videoId")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos ORDER BY addedAt DESC, videoId")
    suspend fun getAllOnce(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE videoId = :videoId")
    suspend fun get(videoId: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE videoId = :videoId")
    fun observe(videoId: String): Flow<VideoEntity?>

    @Query("DELETE FROM videos WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    /** Phase 4 analysis pipeline state stamps. */
    @Query("UPDATE videos SET analysisState = :state, analyzedAt = :ts WHERE videoId = :videoId")
    suspend fun updateAnalysisState(videoId: String, state: AnalysisState, ts: Long?)

    @Query("UPDATE videos SET transcriptText = :text, transcriptSource = :source WHERE videoId = :videoId")
    suspend fun updateTranscript(videoId: String, text: String, source: TranscriptSource)

    /** Persists the final Phase 4 analysis result atomically. */
    @Query(
        "UPDATE videos SET muscleGroups = :muscleCsv, exercises = :exercisesJson, " +
            "analysisState = 'DONE', analyzedAt = :ts WHERE videoId = :videoId"
    )
    suspend fun updateAnalysisResult(
        videoId: String,
        muscleCsv: String,
        exercisesJson: String,
        ts: Long,
    )
}
