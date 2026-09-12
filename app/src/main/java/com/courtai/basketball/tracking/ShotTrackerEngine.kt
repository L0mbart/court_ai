package com.courtai.basketball.tracking

data class BallPoint(val x: Float, val y: Float, val radius: Float, val conf: Float)

enum class ShotEvent { MAKE, MISS }

/**
 * MAKE when ball goes above rim then passes down through the rim box.
 */
class ShotTrackerEngine {
    private var lastEventAt = 0L
    private var wasAboveRim = false
    private var enteredRim = false
    private var insideFrames = 0
    private var peakY = Float.MAX_VALUE
    private var prevY = -1f
    private var cooldownMs = 750L

    var rimLeft = 0.35f
    var rimTop = 0.18f
    var rimRight = 0.65f
    var rimBottom = 0.30f

    fun resetSession() {
        wasAboveRim = false
        enteredRim = false
        insideFrames = 0
        peakY = Float.MAX_VALUE
        prevY = -1f
        lastEventAt = 0L
    }

    fun updateRim(left: Float, top: Float, right: Float, bottom: Float) {
        rimLeft = left.coerceIn(0f, 1f)
        rimTop = top.coerceIn(0f, 1f)
        rimRight = right.coerceIn(0f, 1f)
        rimBottom = bottom.coerceIn(0f, 1f)
        if (rimRight < rimLeft + 0.05f) rimRight = (rimLeft + 0.05f).coerceAtMost(1f)
        if (rimBottom < rimTop + 0.04f) rimBottom = (rimTop + 0.04f).coerceAtMost(1f)
    }

    fun onBall(point: BallPoint?, now: Long = System.currentTimeMillis()): ShotEvent? {
        if (point == null || point.conf < 0.12f) {
            if (wasAboveRim && enteredRim && insideFrames >= 2 && now - lastEventAt > 700) {
                lastEventAt = now
                clearAttempt()
                return ShotEvent.MAKE
            }
            return null
        }

        val padX = (rimRight - rimLeft) * 0.08f
        val left = rimLeft - padX
        val right = rimRight + padX
        val midY = (rimTop + rimBottom) / 2f
        val inX = point.x in left..right
        val inRim = point.x in rimLeft..rimRight &&
            point.y >= rimTop && point.y <= rimBottom + 0.02f
        val descending = if (prevY < 0f) true else point.y > prevY + 0.0015f
        prevY = point.y

        if (point.y < rimTop && inX) {
            wasAboveRim = true
            peakY = minOf(peakY, point.y)
        }

        if (wasAboveRim && inRim && descending) {
            enteredRim = true
            insideFrames++
        }

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

        if (wasAboveRim && point.y > rimBottom + 0.12f &&
            (point.x < left - 0.06f || point.x > right + 0.06f)
        ) {
            if (now - lastEventAt > cooldownMs) {
                lastEventAt = now
                clearAttempt()
                return ShotEvent.MISS
            }
        }

        if (wasAboveRim && !enteredRim && point.y > rimBottom && !inX &&
            now - lastEventAt > 900
        ) {
            lastEventAt = now
            clearAttempt()
            return ShotEvent.MISS
        }

        return null
    }

    private fun clearAttempt() {
        wasAboveRim = false
        enteredRim = false
        insideFrames = 0
        peakY = Float.MAX_VALUE
        prevY = -1f
    }
}
