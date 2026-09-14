package com.courtai.basketball

import android.app.Application
import com.courtai.basketball.data.AppDatabase
import com.courtai.basketball.data.SessionRepository
import com.courtai.basketball.update.UpdateNotifier

/**
 * ============================================================================
 * CourtAiApp.kt — “kantor pusat” aplikasi saat pertama dijalankan
 * ============================================================================
 *
 * PERAN FILE:
 * Kelas Application Android: hidup selama app terbuka.
 * Di sini kita siapkan database + repository sekali saja,
 * supaya semua Activity bisa pakai lewat (application as CourtAiApp).
 *
 * ALUR SINGKAT:
 * 1. OS buat CourtAiApp → onCreate().
 * 2. Buka Room database "courtai.db".
 * 3. Buat SessionRepository (pintu masuk simpan/baca sesi).
 * 4. Siapkan channel notifikasi update.
 */
class CourtAiApp : Application() {
    /** Akses data latihan; diisi di onCreate, lalu dibaca Activity. */
    lateinit var repository: SessionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = SessionRepository(db.sessionDao())
        UpdateNotifier.ensureChannel(this)
    }
}
