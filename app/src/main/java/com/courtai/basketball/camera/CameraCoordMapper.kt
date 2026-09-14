package com.courtai.basketball.camera

import android.view.Surface
import com.courtai.basketball.tracking.BallPoint

/**
 * ============================================================================
 * CameraCoordMapper.kt — penerjemah koordinat kamera → layar
 * ============================================================================
 *
 * PERAN FILE:
 * BallAnalyzer melihat gambar “mentah” dari sensor kamera.
 * PreviewView menampilkan gambar yang sudah diputar / dicerminkan.
 * File ini menyelaraskan keduanya agar lingkaran bola pas di layar.
 *
 * ALUR SINGKAT:
 * 1. Ambil BallPoint dari ImageAnalysis (koordinat sensor).
 * 2. Putar sesuai rotasi layar HP (0° / 90° / 180° / 270°).
 * 3. Jika kamera depan: cerminkan X (seperti mirror selfie).
 *
 * Analogi: peta kota vs peta yang diputar — titik yang sama
 * harus digeser supaya cocok dengan gambar yang kamu lihat.
 */
object CameraCoordMapper {
    /**
     * Ubah koordinat analisis menjadi ruang PreviewView potret.
     *
     * @param point posisi dari BallAnalyzer (0..1)
     * @param displayRotation Surface.ROTATION_* dari display
     * @param useFrontCamera true = mirror horizontal (selfie)
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
