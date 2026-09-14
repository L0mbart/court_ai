package com.courtai.basketball.tracking

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

/**
 * ============================================================================
 * BallAnalyzer.kt — “mata” yang mencari bola basket di kamera
 * ============================================================================
 *
 * PERAN FILE:
 * Setiap frame kamera masuk ke sini. Kita cari gumpalan warna oranye
 * (seperti bola basket), lalu kirim posisi bola ke layar / engine lain.
 *
 * ALUR SINGKAT:
 * 1. Kamera kirim 1 gambar (ImageProxy).
 * 2. Scan piksel → cari yang “oranye bola basket”.
 * 3. Kumpulkan di grid kasar → pilih area paling padat.
 * 4. Scan ulang lebih detail di sekitar area itu → hitung pusat bola.
 * 5. Haluskan gerakan (smoothing) supaya lingkaran di layar tidak loncat-loncat.
 * 6. Panggil onResult(BallPoint) atau null kalau bola hilang.
 *
 * Analogi: seperti mata manusia yang mencari benda oranye di lapangan,
 * lalu bilang “bola ada di sini” ke teman yang menggambar di layar.
 */

/**
 * Kelas pencari bola di setiap frame kamera.
 *
 * @param onResult fungsi yang dipanggil tiap kali hasil siap.
 *                 BallPoint = bola ketemu; null = bola belum / hilang.
 */
class BallAnalyzer(
    private val onResult: (BallPoint?) -> Unit
) : ImageAnalysis.Analyzer {

    /** true = sedang menganalisis; false = diam (hemat baterai/CPU). */
    @Volatile var enabled: Boolean = false

    /** Waktu frame terakhir diproses — dipakai untuk batasi frekuensi (~28 ms). */
    private var lastTs = 0L

    /** Posisi X/Y yang sudah dihaluskan (0..1). -1 = belum pernah ketemu. */
    private var smoothX = -1f
    private var smoothY = -1f

    /** Kapan bola mulai “hilang” — tunggu sebentar sebelum benar-benar null. */
    private var lostSince = 0L

    // ========== ANALISIS SATU FRAME KAMERA ==========

    /**
     * Dipanggil CameraX untuk setiap gambar baru.
     * Di sini kita “membaca” warna piksel dan mencari bola.
     */
    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled) {
                onResult(null)
                return
            }
            val now = System.currentTimeMillis()
            // Jangan proses terlalu sering — hemat tenaga HP
            if (now - lastTs < 28) {
                return
            }
            lastTs = now

            // Format YUV = cara kamera menyimpan warna (Y=terang, U/V=warna)
            if (image.format != ImageFormat.YUV_420_888) {
                onResult(null)
                return
            }

            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val yRow = yPlane.rowStride
            val yPix = yPlane.pixelStride
            val uRow = uPlane.rowStride
            val uPix = uPlane.pixelStride
            val vRow = vPlane.rowStride
            val vPix = vPlane.pixelStride

            // ========== PAS 1: SCAN KASAR (GRID) ==========
            // Ibarat membagi layar jadi kotak-kotak kecil, lalu hitung
            // berapa banyak “titik oranye” di tiap kotak.
            val step = max(3, min(width, height) / 100)
            val gw = max(8, width / 24)
            val gh = max(8, height / 24)
            val grid = FloatArray(gw * gh)
            val gxSum = FloatArray(gw * gh)
            val gySum = FloatArray(gw * gh)

            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val yIndex = y * yRow + x * yPix
                    if (yIndex >= yBuf.capacity()) {
                        x += step
                        continue
                    }
                    val Y = yBuf.get(yIndex).toInt() and 0xFF
                    val ux = x / 2
                    val uy = y / 2
                    val uIndex = uy * uRow + ux * uPix
                    val vIndex = uy * vRow + ux * vPix
                    if (uIndex >= uBuf.capacity() || vIndex >= vBuf.capacity()) {
                        x += step
                        continue
                    }
                    // U/V diubah jadi RGB supaya mudah cek “oranye atau bukan”
                    val U = (uBuf.get(uIndex).toInt() and 0xFF) - 128
                    val V = (vBuf.get(vIndex).toInt() and 0xFF) - 128
                    val r = (Y + 1.370705f * V).toInt().coerceIn(0, 255)
                    val g = (Y - 0.337633f * U - 0.698001f * V).toInt().coerceIn(0, 255)
                    val b = (Y + 1.732446f * U).toInt().coerceIn(0, 255)

                    if (isBasketballOrange(r, g, b, Y)) {
                        val cx = ((x.toFloat() / width) * gw).toInt().coerceIn(0, gw - 1)
                        val cy = ((y.toFloat() / height) * gh).toInt().coerceIn(0, gh - 1)
                        val gi = cy * gw + cx
                        grid[gi] += 1f
                        gxSum[gi] += x.toFloat()
                        gySum[gi] += y.toFloat()
                    }
                    x += step
                }
                y += step
            }

            // Cari kotak dengan paling banyak piksel oranye
            var best = -1f
            var bestI = -1
            for (i in grid.indices) {
                if (grid[i] > best) {
                    best = grid[i]
                    bestI = i
                }
            }
            if (best < 3f || bestI < 0) {
                emitLost(now)
                return
            }

            // Gabungkan tetangga kotak terbaik → perkiraan pusat kasar
            val cellX = bestI % gw
            val cellY = bestI / gw
            var sumX = 0f
            var sumY = 0f
            var count = 0f
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val nx = cellX + dx
                    val ny = cellY + dy
                    if (nx !in 0 until gw || ny !in 0 until gh) continue
                    val gi = ny * gw + nx
                    if (grid[gi] <= 0f) continue
                    sumX += gxSum[gi]
                    sumY += gySum[gi]
                    count += grid[gi]
                }
            }
            if (count < 4f) {
                emitLost(now)
                return
            }

            // ========== PAS 2: SCAN HALUS DI SEKITAR PUSAT ==========
            // Seperti zoom-in: hanya periksa lingkaran di sekitar lokasi kasar.
            val roughX = sumX / count
            val roughY = sumY / count
            val searchR = max(24f, min(width, height) * 0.18f)
            var rSumX = 0.0
            var rSumY = 0.0
            var rCount = 0
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            val fine = max(2, step / 2)
            val x0 = max(0, (roughX - searchR).toInt())
            val x1 = min(width - 1, (roughX + searchR).toInt())
            val y0 = max(0, (roughY - searchR).toInt())
            val y1 = min(height - 1, (roughY + searchR).toInt())

            var yy = y0
            while (yy <= y1) {
                var xx = x0
                while (xx <= x1) {
                    val yIndex = yy * yRow + xx * yPix
                    if (yIndex < yBuf.capacity()) {
                        val Y = yBuf.get(yIndex).toInt() and 0xFF
                        val uIndex = (yy / 2) * uRow + (xx / 2) * uPix
                        val vIndex = (yy / 2) * vRow + (xx / 2) * vPix
                        if (uIndex < uBuf.capacity() && vIndex < vBuf.capacity()) {
                            val U = (uBuf.get(uIndex).toInt() and 0xFF) - 128
                            val V = (vBuf.get(vIndex).toInt() and 0xFF) - 128
                            val r = (Y + 1.370705f * V).toInt().coerceIn(0, 255)
                            val g = (Y - 0.337633f * U - 0.698001f * V).toInt().coerceIn(0, 255)
                            val b = (Y + 1.732446f * U).toInt().coerceIn(0, 255)
                            if (isBasketballOrange(r, g, b, Y)) {
                                rSumX += xx
                                rSumY += yy
                                rCount++
                                if (xx < minX) minX = xx
                                if (yy < minY) minY = yy
                                if (xx > maxX) maxX = xx
                                if (yy > maxY) maxY = yy
                            }
                        }
                    }
                    xx += fine
                }
                yy += fine
            }

            if (rCount < 5) {
                emitLost(now)
                return
            }

            // ========== VALIDASI BENTUK BOLA ==========
            // Pusat dinormalisasi 0..1 (kiri-atas = 0, kanan-bawah = 1)
            val cx = (rSumX / rCount).toFloat() / width
            val cy = (rSumY / rCount).toFloat() / height
            val bw = (maxX - minX).toFloat() / width
            val bh = (maxY - minY).toFloat() / height
            val radius = max(bw, bh) / 2f
            val aspect = if (bh < 0.001f) 99f else bw / bh
            // Buang yang terlalu kecil/besar atau bentuknya aneh (bukan bulat)
            if (radius < 0.008f || radius > 0.38f || aspect < 0.35f || aspect > 2.8f) {
                emitLost(now)
                return
            }

            // ========== SMOOTHING: gerakan lebih lembut ==========
            // Gabungkan posisi lama + baru (seperti bola bergeser pelan, tidak teleport)
            lostSince = 0L
            val a = 0.22f
            if (smoothX < 0f) {
                smoothX = cx
                smoothY = cy
            } else {
                smoothX = smoothX * (1 - a) + cx * a
                smoothY = smoothY * (1 - a) + cy * a
            }
            val conf = min(1f, rCount / 70f)
            onResult(BallPoint(smoothX, smoothY, radius, conf))
        } finally {
            // WAJIB: tutup image supaya CameraX bisa kasih frame berikutnya
            image.close()
        }
    }

    // ========== BOLA HILANG SEMENTARA ==========

    /**
     * Dipanggil saat frame ini tidak menemukan bola yang valid.
     * Tidak langsung null: tahan posisi terakhir ~160 ms supaya tidak kedip.
     */
    private fun emitLost(now: Long) {
        if (smoothX < 0f) {
            onResult(null)
            return
        }
        if (lostSince == 0L) lostSince = now
        if (now - lostSince > 160) {
            smoothX = -1f
            smoothY = -1f
            onResult(null)
        } else {
            // Masih “memegang” posisi lama dengan confidence rendah
            onResult(BallPoint(smoothX, smoothY, 0.05f, 0.2f))
        }
    }

    // ========== CEK WARNA ORANYE BOLA BASKET ==========

    /**
     * Apakah piksel ini mirip warna bola basket?
     *
     * Syarat sederhana:
     * - Merah (R) cukup kuat dan lebih besar dari hijau (G)
     * - Biru (B) tidak terlalu tinggi (bukan ungu/pink)
     * - Saturasi cukup (bukan abu-abu)
     * - Hue (warna) di kisaran oranye (~4°–58°)
     * - Tidak terlalu gelap / terlalu terang
     */
    private fun isBasketballOrange(r: Int, g: Int, b: Int, y: Int): Boolean {
        if (r < 90) return false
        if (r < g) return false
        if (b > (r * 0.9f).toInt()) return false
        val maxc = max(r, max(g, b))
        val minc = min(r, min(g, b))
        if (maxc < 70) return false
        val sat = (maxc - minc).toFloat() / max(1, maxc)
        if (sat < 0.20f) return false
        // Perkiraan hue (sudut warna di lingkaran pelangi)
        val d = (maxc - minc).toFloat().coerceAtLeast(1f)
        var hue = when (maxc) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        hue *= 60f
        if (hue < 0) hue += 360f
        if (hue < 4f || hue > 58f) return false
        if (y < 30 || y > 245) return false
        return true
    }
}
