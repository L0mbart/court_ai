package com.courtai.basketball.data

class SessionRepository(private val dao: SessionDao) {
    fun observeSessions() = dao.observeAll()

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
