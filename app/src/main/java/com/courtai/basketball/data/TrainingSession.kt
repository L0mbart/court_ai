package com.courtai.basketball.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class TrainingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val drillId: String,
    val makes: Int,
    val misses: Int,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis()
) {
    val attempts: Int get() = makes + misses
    val fgPercent: Float
        get() = if (attempts == 0) 0f else makes * 100f / attempts
}
