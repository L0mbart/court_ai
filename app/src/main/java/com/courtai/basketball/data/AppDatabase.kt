package com.courtai.basketball.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * ============================================================================
 * AppDatabase.kt — database lokal Room (file courtai.db)
 * ============================================================================
 *
 * PERAN: satu database app; berisi tabel sessions.
 * ALUR: CourtAiApp.get() → singleton → sessionDao() untuk insert/query.
 *
 * Analogi: lemari arsip di HP; isinya catatan latihan.
 */
@Database(entities = [TrainingSession::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Ambil instance tunggal (thread-safe). */
        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "courtai.db"
                ).build().also { instance = it }
            }
        }
    }
}
