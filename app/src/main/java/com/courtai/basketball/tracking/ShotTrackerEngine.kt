package com.courtai\basketball.tracking

/**
 * ============================================================================
 * ShotTrackerEngine.kt — deteksi tembakan MAKE atau MISS
 * ============================================================================
 *
 * PERAN FILE:
 * Memakai posisi bola + kotak “rim” (lingkaran ring) di layar
 * untuk memutuskan: bola masuk (MAKE) atau meleset (MISS).
 *
 * ALUR SINGKAT (MAKE):
 * 1. Bola lewat DI ATAS kotak rim → “usaha tembak dimulai”.
 * 2. Bola turun dan masuk ke dalam kotak rim → “melewati ring”.
 * 3. Bola terus turun lewat tengah rim → MAKE!
 *
 * ALUR SINGKAT (MISS):
 * - Bola sudah di atas rim, lalu jatuh jauh di samping ring → MISS.
 *
 * Analogi: seperti wasit yang melihat bola lewat di atas ring,
 * lalu masuk lubang (MAKE) atau keluar ke samping (MISS).
 */

/** Satu titik bola di layar. Semua nilai 0..1 (normalisasi). */
data class BallPoint(
    val x: Float,
    val y: Float,
    val radius: Float,
    val conf: Float
)

/** Hasil deteksi otomatis: masuk atau meleset. */
enum class ShotEvent { MAKE, MISS }

/**
 * Mesin deteksi tembakan.
 * MAKE = bola naik di atas rim, lalu turun melewati kotak rim.
 */
class ShotTrackerEngine {
    private var lastEventAt = 0L
    /** Apakah bola sudah pernah di atas rim di usaha ini? */
    private var wasAboveRim = false
    /** Apakah bola sudah masuk kotak rim sambil turun? */
    private var enteredRim = false
    /** Berapa frame bola berada di dalam rim (lebih banyak = lebih yakin). */
    private var insideFrames = 0
    private var peakY = Float.MAX_VALUE
    private var prevY = -1f
    /** Jeda minimal antar MAKE/MISS supaya tidak double-count. */
    private var cooldownMs = 750L

    // Kotak rim dinormalisasi 0..1 (user bisa drag di OverlayView)
    var rimLeft = 0.35f
    var rimTop = 0.18f
    var rimRight = 0.65f
    var rimBottom = 0.30f

    /** Reset status usaha tembak (bukan reset skor UI). */
    fun resetSession() {
        wasAboveRim = false
        enteredRim = false
        insideFrames = 0
        peakY = Float.MAX_VALUE
        prevY = -1f
        lastEventAt = 0L
    }

    /**
     * Update posisi kotak rim dari overlay (setelah user drag).
     * Nilai dipaksa tetap valid (kanan > kiri, bawah > atas).
     */
    fun updateRim(left: Float, top: Float, right: Float, bottom: Float) {
        rimLeft = left.coerceIn(0f, 1f)
        rimTop = top.coerceIn(0f, 1f)
        rimRight = right.coerceIn(0f, 1f)
        rimBottom = bottom.coerceIn(0f, 1f)
        if (rimRight < rimLeft + 0.05f) rimRight = (rimLeft + 0.05f).coerceAtMost(1f)
        if (rimBottom < rimTop + 0.04f) rimBottom = (rimTop + 0.04f).coerceAtMost(1f)
    }

    // ========== INTI: BACA BOLA TIAP FRAME ==========

    /**
     * Dipanggil tiap frame saat tracking aktif.
     *
     * @param point posisi bola; null = sementara hilang
     * @return ShotEvent.MAKE / MISS, atau null kalau belum ada keputusan
     */
    fun onBall(point: BallPoint?, now: Long = System.currentTimeMillis()): ShotEvent? {
        // Bola hilang tapi sudah hampir masuk → anggap MAKE (kasus cepat lewat rim)
        if (point == null || point.conf < 0.12f) {
            if (wasAboveRim && enteredRim && insideFrames >= 2 && now - lastEventAt > 700) {
                lastEventAt = now
                clearAttempt()
                return ShotEvent.MAKE
            }
            return null
        }

        // Padding horizontal sedikit lebih longgar di atas rim
        val padX = (rimRight - rimLeft) * 0.08f
        val left = rimLeft - padX
        val right = rimRight + padX
        val midY = (rimTop + rimBottom) / 2f
        val inX = point.x in left..right
        val inRim = point.x in rimLeft..rimRight &&
            point.y >= rimTop && point.y <= rimBottom + 0.02f
        val descending = if (prevY < 0f) true else point.y > prevY + 0.0015f
        prevY = point.y

        // ========== LANGKAH 1: BOLA DI ATAS RIM ==========
        if (point.y < rimTop && inX) {
            wasAboveRim = true
            peakY = minOf(peakY, point.y)
        }

        // ========== LANGKAH 2: MASUK KOTAK RIM SAAT TURUN ==========
        if (wasAboveRim && inRim && descending) {
            enteredRim = true
            insideFrames++
        }

        // ========== LANGKAH 3: LEWAT TENGAH / BAWAH RIM → MAKE ==========
        if (wasAboveRim && enteredRim && descending) {
            val centered = point.x in (rimLeft - 0.02f)..(rimRight + 0.02f)
            val through = (point.y >= midY && insideFrames >= 2) ||
                (point.y > rimBottom && insideFrames >= 1) ||
                insideFrames >= 4
            if (through && centered && now - lastEventAt > cooldownMs) {
                lastEventAt = now
                clearAttempt()
                return ShotEvent.MAKE
            }
        }

        // ========== MISS: jatuh jauh di samping setelah sudah di atas rim ==========
        if (wasAboveRim && point.y > rimBottom + 0.12f &&
            (point.x < left - 0.06f || point.x > right + 0.06f)
        ) {
            if (now - lastEventAt > cooldownMs) {
                lastEventAt = now
                clearAttempt()
                return ShotEvent.MISS
            }
        }

        // ========== MISS: turun di luar jalur X tanpa pernah masuk rim ==========
        if (wasAboveRim && !enteredRim && point.y > rimBottom && !inX &&
            now - lastEventAt > 900
        ) {
            lastEventAt = now
            clearAttempt()
            return ShotEvent.MISS
        }

        return null
    }

    /** Bersihkan status usaha tembak setelah MAKE/MISS tercatat. */
    private fun clearAttempt() {
        wasAboveRim = false
        enteredRim = false
        insideFrames = 0
        peakY = Float.MAX_VALUE
        prevY = -1f
    }
}
