package com.courtai.basketball.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * TrainingSession.kt — satu baris catatan latihan di database
 * ============================================================================
 *
 * PERAN: model Room untuk tabel "sessions".
 * Field makes/misses juga dipakai dribble (makes = jumlah dribble).
 */
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
    /** Field goal % = MAKE / attempts * 100. */
    val fgPercent: Float
        get() = if (attempts == 0) 0f else makes * 100f / attempts
}
