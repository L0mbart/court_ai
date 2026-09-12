package com.courtai.basketball.camera

import android.view.Surface
import com.courtai.basketball.tracking.BallPoint

object CameraCoordMapper {
    /**
     * Maps ImageAnalysis normalized coords into PreviewView portrait space.
     * Front camera is mirrored to match the selfie preview.
     */
    fun map(point: BallPoint, displayRotation: Int, useFrontCamera: Boolean): BallPoint {
        val rotated = when (displayRotation) {
            Surface.ROTATION_0 -> point.copy(x = point.y, y = 1f - point.x)
            Surface.ROTATION_90 -> point
            Surface.ROTATION_180 -> point.copy(x = 1f - point.y, y = point.x)
            Surface.ROTATION_270 -> point.copy(x = 1f - point.x, y = 1f - point.y)
            else -> point.copy(x = point.y, y = 1f - point.x)
        }
        return if (useFrontCamera) {
            rotated.copy(x = 1f - rotated.x)
        } else {
            rotated
        }
    }
}
