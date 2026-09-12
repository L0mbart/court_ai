package com.courtai.basketball
import android.app.Application
import com.courtai.basketball.data.AppDatabase
import com.courtai.basketball.data.SessionRepository
import com.courtai.basketball.update.UpdateNotifier
class CourtAiApp : Application() {
    lateinit var repository: SessionRepository
        private set
    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = SessionRepository(db.sessionDao())
        UpdateNotifier.ensureChannel(this)
    }
}
