package com.courtai.basketball.data

/**
 * ============================================================================
 * SessionRepository.kt — pintu masuk simpan/baca sesi latihan
 * ============================================================================
 *
 * PERAN: membungkus SessionDao supaya UI tidak langsung bicara ke Room.
 * ALUR: Activity panggil save/summary → repository → DAO → database.
 */
class SessionRepository(private val dao: SessionDao) {
    /** Aliran daftar sesi (update otomatis saat data berubah). */
    fun observeSessions() = dao.observeAll()

    /**
     * Simpan satu sesi baru.
     * @return id baris yang baru dibuat di tabel sessions
     */
    suspend fun save(
        title: String,
        drillId: String,
        makes: Int,
        misses: Int,
        durationMs: Long
    ): Long {
        return dao.insert(
            TrainingSession(
                title = title,
                drillId = drillId,
                makes = makes,
                misses = misses,
                durationMs = durationMs
            )
        )
    }

    suspend fun getAll() = dao.getAll()

    suspend fun getById(id: Long) = dao.getAll().firstOrNull { it.id == id }

    /** Agregat untuk kartu di beranda / stats. */
    suspend fun summary(): Summary {
        val makes = dao.totalMakes()
        val misses = dao.totalMisses()
        val sessions = dao.sessionCount()
        val attempts = makes + misses
        val fg = if (attempts == 0) null else makes * 100f / attempts
        return Summary(sessions, makes, misses, attempts, fg)
    }

    data class Summary(
        val sessions: Int,
        val makes: Int,
        val misses: Int,
        val attempts: Int,
        val fgPercent: Float?
    )
}
