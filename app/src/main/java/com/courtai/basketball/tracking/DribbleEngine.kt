package com.courtai.basketball.tracking

/**
 * Detects basketball dribbles from vertical bounce cycles.
 * Tuned for fewer false positives + smoother motion.
 */
class DribbleEngine {
    private var lastDribbleAt = 0L
    private var goingDown = false
    private var peakY = 1f
    private var valleyY = 0f
    private var armed = false
    private var smoothY = -1f
    private var prevY = -1f
    private var downFrames = 0
    private var upFrames = 0

    var dribbles = 0
        private set
    var leftCount = 0
        private set
    var rightCount = 0
        private set
    var combo = 0
        private set
    var bestCombo = 0
        private set
    var score = 0
        private set
    var lastSide: Side = Side.CENTER
        private set

    enum class Side { LEFT, RIGHT, CENTER }

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

    fun addManualDribble(x: Float = 0.5f, now: Long = System.currentTimeMillis()) {
        registerDribble(x, 0.08f, now)
    }

    fun onBall(point: BallPoint?, now: Long = System.currentTimeMillis()): Boolean {
        if (point == null || point.conf < 0.22f) {
            if (combo > 0 && now - lastDribbleAt > 1400) combo = 0
            return false
        }

        // Heavy Y smoothing
        smoothY = if (smoothY < 0f) point.y else smoothY * 0.78f + point.y * 0.22f
        if (prevY < 0f) {
            prevY = smoothY
            return false
        }
        val dy = smoothY - prevY
        prevY = smoothY

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
            upFrames++
            downFrames = 0
            if (upFrames >= 3 && goingDown) {
                val amplitude = valleyY - peakY
                val minAmp = 0.055f
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
            downFrames = maxOf(0, downFrames - 1)
            upFrames = maxOf(0, upFrames - 1)
        }

        if (combo > 0 && now - lastDribbleAt > 1500) combo = 0
        return false
    }

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

        var points = 1
        if (amplitude >= 0.10f) points++
        if (combo >= 5) points++
        if (combo >= 10) points++
        score += points
    }
}
