package com.courtai.basketball.update

import android.content.Context
import com.courtai.basketball.auth.AuthSession
import com.courtai.basketball.data.TrainingSession
import com.courtai.basketball.update.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================================================================
 * SessionSync.kt — kirim hasil latihan HP → server dashboard
 * ============================================================================
 *
 * PERAN FILE:
 * Mengubah TrainingSession lokal menjadi JSON, lalu POST ke
 * /api/sessions/sync supaya muncul di web dashboard.
 *
 * ALUR SINGKAT:
 * 1. Ambil sesi dari Room (satu atau banyak).
 * 2. toJson() → tambah userId, activityType, skor, dll.
 * 3. POST ke server; gagal → lempar Exception (UI tampilkan Toast).
 *
 * Analogi: seperti mengirim rapor latihan ke guru (dashboard PC).
 */
object SessionSync {
    /**
     * Klasifikasikan jenis aktivitas dari drillId.
     * Berguna agar dashboard bisa filter “shoot” vs “dribble”.
     */
    fun activityType(drillId: String): String = when {
        drillId == "pound_the_rock" -> "dribble"
        drillId.contains("workout") -> "workout"
        drillId in listOf(
            "freestyle", "free_throws", "catch_shoot", "corner_threes",
            "speed_shooting", "around_world", "midrange_mix", "clutch_closes"
        ) -> "shoot"
        else -> "training"
    }

    /** Label ramah manusia untuk kartu di dashboard. */
    fun activityLabel(drillId: String, title: String): String = when (activityType(drillId)) {
        "dribble" -> "Dribble - Pound The Rock"
        "shoot" -> "Shoot - $title"
        "workout" -> "Workout - $title"
        else -> title
    }

    /** Kirim satu sesi (biasanya tepat setelah Finish di tracker). */
    suspend fun pushOne(context: Context, session: TrainingSession): Boolean =
        withContext(Dispatchers.IO) {
            val base = ServerConfig.getBaseUrl(context)
            val payload = JSONObject().put(
                "sessions",
                JSONArray().put(toJson(context, session))
            )
            post(base, payload)
            true
        }

    /**
     * Kirim banyak sesi sekaligus (tombol Sync di beranda).
     * @return jumlah sesi yang dikirim
     */
    suspend fun push(context: Context, sessions: List<TrainingSession>): Int =
        withContext(Dispatchers.IO) {
            if (sessions.isEmpty()) return@withContext 0
            val base = ServerConfig.getBaseUrl(context)
            val arr = JSONArray()
            sessions.forEach { arr.put(toJson(context, it)) }
            post(base, JSONObject().put("sessions", arr))
            sessions.size
        }

    // ========== BENTUK JSON UNTUK SERVER ==========

    /**
     * Satu objek JSON per sesi.
     * clientId unik = userId + id lokal + createdAt (hindari duplikat di server).
     */
    private fun toJson(context: Context, s: TrainingSession): JSONObject {
        val attempts = s.makes + s.misses
        val fg = if (attempts == 0) null else (s.makes * 100.0 / attempts)
        val type = activityType(s.drillId)
        val score = if (type == "dribble") {
            // Skor dribble sering tertanam di judul: "Pound The Rock (42 pts)"
            Regex("""\((\d+)\s*pts\)""").find(s.title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: s.makes
        } else null
        return JSONObject()
            .put("clientId", "${AuthSession.userId(context)}_${s.id}_${s.createdAt}")
            .put("userId", AuthSession.userId(context))
            .put("userName", AuthSession.displayName(context))
            .put("title", s.title)
            .put("drillId", s.drillId)
            .put("activityType", type)
            .put("activityLabel", activityLabel(s.drillId, s.title))
            .put("makes", s.makes)
            .put("misses", s.misses)
            .put("attempts", attempts)
            .put("fgPercent", if (fg == null) JSONObject.NULL else fg)
            .put("score", if (score == null) JSONObject.NULL else score)
            .put("durationMs", s.durationMs)
            .put("createdAt", s.createdAt)
            .put("deviceId", android.os.Build.MODEL)
            .put("source", "android")
    }

    /** POST JSON ke endpoint sync; lempar error jika HTTP bukan 2xx. */
    private fun post(base: String, body: JSONObject) {
        val conn = (URL("$base/api/sessions/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
