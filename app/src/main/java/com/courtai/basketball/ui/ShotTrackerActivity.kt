package com.courtai.basketball.ui

import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.courtai.basketball.CourtAiApp
import com.courtai.basketball.R
import com.courtai.basketball.camera.OverlayView
import com.courtai.basketball.data.DrillCatalog
import com.courtai.basketball.databinding.ActivityShotTrackerBinding
import com.courtai.basketball.tracking.BallAnalyzer
import com.courtai.basketball.tracking.ShotEvent
import com.courtai.basketball.tracking.ShotTrackerEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * ============================================================================
 * ShotTrackerActivity.kt — layar lacak tembakan (MAKE / MISS)
 * ============================================================================
 *
 * PERAN FILE:
 * Kamera + kotak rim yang bisa digeser. Saat Start, mesin mendeteksi
 * apakah bola masuk ring (MAKE) atau meleset (MISS). Bisa juga input manual.
 *
 * ALUR SINGKAT:
 * 1. User drag kotak oranye ke posisi ring di layar.
 * 2. Start → BallAnalyzer + ShotTrackerEngine aktif.
 * 3. MAKE/MISS otomatis atau tombol manual → HUD update + flash teks.
 * 4. Finish / waktu habis / target tercapai → simpan sesi.
 *
 * Analogi: kamera = mata; kotak rim = “lubang ring” virtual; engine = wasit.
 */
class ShotTrackerActivity : AppCompatActivity() {
    companion object {
        /** Extra Intent: id drill dari DrillCatalog (mis. "free_throws"). */
        const val EXTRA_DRILL_ID = "drill_id"
    }

    private lateinit var binding: ActivityShotTrackerBinding
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private val engine = ShotTrackerEngine()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var analyzer: BallAnalyzer? = null

    private var makes = 0
    private var misses = 0
    private var tracking = false
    private var startedAt = 0L
    /** Waktu terakumulasi saat pause (ms). */
    private var elapsedMs = 0L
    private var timerJob: Job? = null
    private lateinit var drillId: String
    private var useFrontCamera = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null

    // ========== SETUP LAYAR ==========

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        binding = ActivityShotTrackerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)

        setupFullscreenInsets()
        hideSystemBars()

        drillId = intent.getStringExtra(EXTRA_DRILL_ID) ?: "freestyle"
        val drill = DrillCatalog.byId(drillId)
        binding.tvDrillTitle.text = drill.title
        updateFlipLabel()

        binding.btnToggle.setOnClickListener { toggleTracking() }
        binding.btnMake.setOnClickListener { registerShot(ShotEvent.MAKE, manual = true) }
        binding.btnMiss.setOnClickListener { registerShot(ShotEvent.MISS, manual = true) }
        binding.btnFinish.setOnClickListener { finishSession() }
        binding.btnFlipCamera.setOnClickListener {
            useFrontCamera = !useFrontCamera
            updateFlipLabel()
            bindCamera()
            Toast.makeText(
                this,
                if (useFrontCamera) "Front camera" else "Back camera",
                Toast.LENGTH_SHORT
            ).show()
        }

        startCamera()
        updateHud()
    }

    private fun updateFlipLabel() {
        binding.btnFlipCamera.text = if (useFrontCamera) "Front" else "Back"
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun setupFullscreenInsets() {
        val topBase = binding.topBar.paddingTop
        val bottomBase = binding.bottomBar.paddingBottom
        val startBase = binding.bottomBar.paddingStart
        val endBase = binding.bottomBar.paddingEnd

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            val bottomInset = maxOf(bars.bottom, gestures.bottom / 3, dp(24))
            binding.topBar.updatePadding(top = topBase + bars.top)
            binding.bottomBar.updatePadding(
                left = startBase + bars.left,
                right = endBase + bars.right,
                bottom = bottomBase + bottomInset
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ========== KAMERA + DETEKSI TEMBAKAN ==========

    /**
     * Pipeline: frame → BallAnalyzer → map koordinat → overlay + ShotTrackerEngine.
     * Kotak rim dari OverlayView selalu disalin ke engine saat tracking.
     */
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            analyzer = BallAnalyzer { point ->
                val mapped = point?.let { p ->
                    com.courtai.basketball.camera.CameraCoordMapper.map(
                        p,
                        previewView.display?.rotation ?: 0,
                        useFrontCamera
                    )
                }
                runOnUiThread {
                    overlay.ball = mapped
                    if (tracking) {
                        val rim = overlay.rimNormalized()
                        engine.updateRim(rim.left, rim.top, rim.right, rim.bottom)
                        val event = engine.onBall(mapped)
                        if (event != null) registerShot(event, manual = false)
                    }
                }
            }
            analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer!!) }
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val analysis = analysisUseCase ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val selector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
            analyzer?.enabled = tracking
        } catch (e: Exception) {
            if (useFrontCamera) {
                useFrontCamera = false
                updateFlipLabel()
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                    Toast.makeText(this, "Front camera unavailable, using back", Toast.LENGTH_SHORT)
                        .show()
                } catch (_: Exception) {
                    Toast.makeText(this, "Camera error", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ========== START / PAUSE / TIMER ==========

    /**
     * Start: salin rim ke engine, mulai timer.
     * Pause: simpan elapsed, tampilkan lagi panduan drag rim.
     */
    private fun toggleTracking() {
        tracking = !tracking
        analyzer?.enabled = tracking
        overlay.showGuide = !tracking
        if (tracking) {
            engine.resetSession()
            val rim = overlay.rimNormalized()
            engine.updateRim(rim.left, rim.top, rim.right, rim.bottom)
            startedAt = SystemClock.elapsedRealtime()
            binding.btnToggle.text = getString(com.courtai.basketball.R.string.pause)
            binding.tvStatus.text = "Tracking… shoot toward the rim box"
            startTimer()
        } else {
            elapsedMs += SystemClock.elapsedRealtime() - startedAt
            binding.btnToggle.text = getString(com.courtai.basketball.R.string.start)
            binding.tvStatus.text = "Paused — drag rim if needed"
            timerJob?.cancel()
        }
    }

    /**
     * Update timer; otomatis selesai jika drill punya batas waktu
     * atau target jumlah MAKE.
     */
    private fun startTimer() {
        timerJob?.cancel()
        val drill = DrillCatalog.byId(drillId)
        timerJob = lifecycleScope.launch {
            while (isActive && tracking) {
                val live = elapsedMs + (SystemClock.elapsedRealtime() - startedAt)
                val sec = (live / 1000).toInt()
                binding.tvTimer.text = String.format("%02d:%02d", sec / 60, sec % 60)
                if (drill.timeLimitSec > 0 && sec >= drill.timeLimitSec) {
                    Toast.makeText(this@ShotTrackerActivity, "Time's up!", Toast.LENGTH_SHORT).show()
                    finishSession()
                    break
                }
                if (drill.targetMakes > 0 && makes >= drill.targetMakes) {
                    Toast.makeText(this@ShotTrackerActivity, "Target reached!", Toast.LENGTH_SHORT).show()
                    finishSession()
                    break
                }
                delay(200)
            }
        }
    }

    // ========== CATAT MAKE / MISS ==========

    /**
     * Tambah skor MAKE atau MISS.
     * @param manual true jika dari tombol; false jika dari auto-detect
     */
    private fun registerShot(event: ShotEvent, manual: Boolean) {
        when (event) {
            ShotEvent.MAKE -> makes++
            ShotEvent.MISS -> misses++
        }
        flash(event)
        updateHud()
        binding.tvStatus.text = if (manual) {
            "Manual ${event.name.lowercase()} recorded"
        } else {
            "Auto ${event.name.lowercase()} detected"
        }
    }

    private fun flash(event: ShotEvent) {
        val tv = binding.tvFlash
        tv.alpha = 1f
        tv.text = if (event == ShotEvent.MAKE) "MAKE" else "MISS"
        tv.setTextColor(
            Color.parseColor(if (event == ShotEvent.MAKE) "#2DD4A8" else "#FF4D4D")
        )
        ObjectAnimator.ofFloat(tv, View.ALPHA, 1f, 0f).setDuration(700).start()
    }

    private fun updateHud() {
        val attempts = makes + misses
        val fg = if (attempts == 0) 0 else makes * 100 / attempts
        binding.tvLiveMakes.text = "$makes MAKE"
        binding.tvLiveMisses.text = "$misses MISS"
        binding.tvLiveFg.text = "$fg%"
    }

    /** Simpan sesi ke Room, coba sync, lalu tutup Activity. */
    private fun finishSession() {
        if (tracking) {
            elapsedMs += SystemClock.elapsedRealtime() - startedAt
            tracking = false
            analyzer?.enabled = false
            timerJob?.cancel()
        }
        val duration = elapsedMs.coerceAtLeast(1000L)
        val drill = DrillCatalog.byId(drillId)
        val repo = (application as CourtAiApp).repository
        lifecycleScope.launch {
            val id = repo.save(drill.title, drill.id, makes, misses, duration)
            try {
                repo.getById(id)?.let {
                    com.courtai.basketball.update.SessionSync.pushOne(this@ShotTrackerActivity, it)
                }
            } catch (_: Exception) {
            }
            Toast.makeText(this@ShotTrackerActivity, "Session saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
