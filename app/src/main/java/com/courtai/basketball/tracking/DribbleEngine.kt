package com.courtai.basketball.tracking

/**
 * ============================================================================
 * DribbleEngine.kt — penghitung dribble dari gerakan naik-turun bola
 * ============================================================================
 *
 * PERAN FILE:
 * Menerima posisi bola (BallPoint) terus-menerus, lalu mendeteksi
 * “satu dribble” = satu siklus bounce (bola turun lalu naik lagi).
 *
 * ALUR SINGKAT:
 * 1. Haluskan posisi Y (atas-bawah) supaya getaran kecil diabaikan.
 * 2. Deteksi bola sedang TURUN (Y membesar) → catat puncak & lembah.
 * 3. Deteksi bola mulai NAIK lagi → ukur amplitudo (seberapa besar bounce).
 * 4. Jika bounce cukup besar & jeda cukup lama → hitung 1 dribble + skor.
 *
 * Analogi mudah:
 * Bayangkan bola dipantulkan. Setiap kali “turun ke lantai lalu naik”,
 * itu satu dribble — seperti detak jantung naik-turun pada grafik.
 */

/**
 * Mesin penghitung dribble dari siklus bounce vertikal.
 * Disetel agar jarang salah hitung + gerakan lebih mulus.
 */
class DribbleEngine {
    /** Waktu dribble terakhir (ms) — mencegah double-count terlalu cepat. */
    private var lastDribbleAt = 0L

    /** true = sedang fase turun (menuju lantai). */
    private var goingDown = false

    /** Y tertinggi (paling atas layar = nilai kecil) di awal turun. */
    private var peakY = 1f

    /** Y terendah (paling bawah) selama fase turun. */
    private var valleyY = 0f

    /** true = siap menghitung dribble saat bola naik lagi. */
    private var armed = false

    /** Y yang sudah dihaluskan; prevY = Y frame sebelumnya. */
    private var smoothY = -1f
    private var prevY = -1f

    /** Berapa frame berturut-turut turun / naik (filter noise). */
    private var downFrames = 0
    private var upFrames = 0

    // ========== STATISTIK SESI ==========

    /** Total dribble di sesi ini. */
    var dribbles = 0
        private set
    var leftCount = 0
        private set
    var rightCount = 0
        private set
    /** Combo = dribble beruntun tanpa jeda terlalu lama. */
    var combo = 0
        private set
    var bestCombo = 0
        private set
    /** Skor: dribble biasa + bonus amplitudo & combo. */
    var score = 0
        private set
    var lastSide: Side = Side.CENTER
        private set

    /** Sisi layar tempat bola saat dribble tercatat. */
    enum class Side { LEFT, RIGHT, CENTER }

    // ========== RESET & INPUT MANUAL ==========

    /** Mulai sesi baru — semua angka & status gerakan dikosongkan. */
    fun reset() {
        dribbles = 0
        leftCount = 0
        rightCount = 0
        combo = 0
        bestCombo = 0
        score = 0
        lastDribbleAt = 0L
        goingDown = false
        peakY = 1f
        valleyY = 0f
        armed = false
        lastSide = Side.CENTER
        smoothY = -1f
        prevY = -1f
        downFrames = 0
        upFrames = 0
    }

    /**
     * Tambah 1 dribble manual (tombol di UI) kalau auto-detect gagal.
     * @param x posisi horizontal 0..1 (kiri=0, kanan=1)
     */
    fun addManualDribble(x: Float = 0.5f, now: Long = System.currentTimeMillis()) {
        registerDribble(x, 0.08f, now)
    }

    // ========== INTI: BACA POSISI BOLA TIAP FRAME ==========

    /**
     * Dipanggil setiap kali BallAnalyzer menemukan (atau kehilangan) bola.
     *
     * @param point posisi bola; null / conf rendah = bola tidak jelas
     * @return true jika frame ini baru saja mencatat 1 dribble
     */
    fun onBall(point: BallPoint?, now: Long = System.currentTimeMillis()): Boolean {
        if (point == null || point.conf < 0.22f) {
            // Combo putus kalau lama tanpa dribble
            if (combo > 0 && now - lastDribbleAt > 1400) combo = 0
            return false
        }

        // Haluskan Y kuat-kuat — getaran kecil kamera jangan dianggap bounce
        smoothY = if (smoothY < 0f) point.y else smoothY * 0.78f + point.y * 0.22f
        if (prevY < 0f) {
            prevY = smoothY
            return false
        }
        val dy = smoothY - prevY
        prevY = smoothY

        // ========== FASE TURUN (bola ke lantai) ==========
        // Catatan: di layar, Y membesar = ke BAWAH
        if (dy > 0.0055f) {
            downFrames++
            upFrames = 0
            if (downFrames >= 3) {
                if (!goingDown) {
                    peakY = smoothY - dy * 3
                    goingDown = true
                    armed = true
                    valleyY = smoothY
                }
                valleyY = maxOf(valleyY, smoothY)
            }
        } else if (dy < -0.0055f) {
            // ========== FASE NAIK (bola memantul naik) ==========
            upFrames++
            downFrames = 0
            if (upFrames >= 3 && goingDown) {
                val amplitude = valleyY - peakY
                val minAmp = 0.055f
                // Bounce besar boleh jeda lebih pendek antar-hitung
                val minGap = if (amplitude > 0.10f) 220L else 280L
                if (armed && amplitude >= minAmp && now - lastDribbleAt >= minGap) {
                    registerDribble(point.x, amplitude, now)
                    goingDown = false
                    armed = false
                    valleyY = 0f
                    peakY = smoothY
                    upFrames = 0
                    downFrames = 0
                    return true
                }
                goingDown = false
                armed = false
                valleyY = 0f
                peakY = smoothY
                upFrames = 0
            }
        } else {
            // Gerakan hampir diam — pelan-pelan turunkan counter
            downFrames = maxOf(0, downFrames - 1)
            upFrames = maxOf(0, upFrames - 1)
        }

        if (combo > 0 && now - lastDribbleAt > 1500) combo = 0
        return false
    }

    // ========== CATAT SATU DRIBBLE ==========

    /**
     * Menambah skor & statistik setelah satu bounce valid terdeteksi.
     *
     * @param x posisi horizontal untuk tentukan LEFT / RIGHT / CENTER
     * @param amplitude seberapa “dalam” pantulan (lebih besar = bonus poin)
     */
    private fun registerDribble(x: Float, amplitude: Float, now: Long) {
        dribbles++
        combo++
        if (combo > bestCombo) bestCombo = combo
        lastDribbleAt = now

        lastSide = when {
            x < 0.40f -> Side.LEFT
            x > 0.60f -> Side.RIGHT
            else -> Side.CENTER
        }
        when (lastSide) {
            Side.LEFT -> leftCount++
            Side.RIGHT -> rightCount++
            Side.CENTER -> Unit
        }

        // Poin dasar 1; bonus bounce besar & combo panjang
        var points = 1
        if (amplitude >= 0.10f) points++
        if (combo >= 5) points++
        if (combo >= 10) points++
        score += points
    }
}
