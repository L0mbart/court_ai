package com.courtai.basketball.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: TrainingSession): Long

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TrainingSession>>

    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    suspend fun getAll(): List<TrainingSession>

    @Query("SELECT COALESCE(SUM(makes),0) FROM sessions")
    suspend fun totalMakes(): Int

    @Query("SELECT COALESCE(SUM(misses),0) FROM sessions")
    suspend fun totalMisses(): Int

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun sessionCount(): Int
}
