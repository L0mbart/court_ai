package com.courtai.basketball.tracking

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the densest orange basketball-like blob in a camera frame.
 */
class BallAnalyzer(
    private val onResult: (BallPoint?) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile var enabled: Boolean = false
    private var lastTs = 0L
    private var smoothX = -1f
    private var smoothY = -1f
    private var lostSince = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (!enabled) {
                onResult(null)
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastTs < 28) {
                return
            }
            lastTs = now

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

            val cx = (rSumX / rCount).toFloat() / width
            val cy = (rSumY / rCount).toFloat() / height
            val bw = (maxX - minX).toFloat() / width
            val bh = (maxY - minY).toFloat() / height
            val radius = max(bw, bh) / 2f
            val aspect = if (bh < 0.001f) 99f else bw / bh
            if (radius < 0.008f || radius > 0.38f || aspect < 0.35f || aspect > 2.8f) {
                emitLost(now)
                return
            }

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
            image.close()
        }
    }

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
            onResult(BallPoint(smoothX, smoothY, 0.05f, 0.2f))
        }
    }

    private fun isBasketballOrange(r: Int, g: Int, b: Int, y: Int): Boolean {
        if (r < 90) return false
        if (r < g) return false
        if (b > (r * 0.9f).toInt()) return false
        val maxc = max(r, max(g, b))
        val minc = min(r, min(g, b))
        if (maxc < 70) return false
        val sat = (maxc - minc).toFloat() / max(1, maxc)
        if (sat < 0.20f) return false
        // hue approx for orange
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
