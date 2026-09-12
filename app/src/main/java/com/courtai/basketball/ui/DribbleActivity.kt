package com.courtai.basketball.ui

import android.animation.ObjectAnimator
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import com.courtai.basketball.databinding.ActivityDribbleBinding
import com.courtai.basketball.tracking.BallAnalyzer
import com.courtai.basketball.tracking.DribbleEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class DribbleActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_DURATION_SEC = "duration_sec"
        private const val DEFAULT_DURATION = 30
    }

    private lateinit var binding: ActivityDribbleBinding
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private val engine = DribbleEngine()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var analyzer: BallAnalyzer? = null

    private var tracking = false
    private var startedAt = 0L
    private var timerJob: Job? = null
    private var durationSec = DEFAULT_DURATION
    private var useFrontCamera = true
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null

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

        binding = ActivityDribbleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        overlay.showRim = false
        overlay.showGuide = false

        durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, DEFAULT_DURATION)
        setupFullscreenInsets()
        hideSystemBars()
        updateHud()
        updateFlipLabel()

        binding.btnToggle.setOnClickListener { toggleTracking() }
        binding.btnManual.setOnClickListener {
            if (!tracking) {
                Toast.makeText(this, "Press Start first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            engine.addManualDribble(overlay.ball?.x ?: 0.5f)
            onDribbleCounted()
        }
        binding.btnFinish.setOnClickListener { finishSession() }
        binding.btnFlipCamera.setOnClickListener {
            useFrontCamera = !useFrontCamera
            updateFlipLabel()
            bindCamera()
            val label = if (useFrontCamera) "Front camera" else "Back camera"
            Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }

        startCamera()
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
                    if (tracking && engine.onBall(mapped)) {
                        onDribbleCounted()
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
            // Fallback if front camera unavailable
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

    private fun toggleTracking() {
        tracking = !tracking
        analyzer?.enabled = tracking
        if (tracking) {
            engine.reset()
            startedAt = SystemClock.elapsedRealtime()
            binding.btnToggle.text = getString(R.string.pause)
            binding.tvStatus.text = "Pound it! Face the front camera with the ball in frame."
            updateHud()
            startTimer()
        } else {
            binding.btnToggle.text = getString(R.string.start)
            binding.tvStatus.text = "Paused"
            timerJob?.cancel()
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive && tracking) {
                val live = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                val left = (durationSec - live).coerceAtLeast(0)
                binding.tvTimer.text = String.format("0:%02d\nTIME", left)
                if (left <= 0) {
                    Toast.makeText(this@DribbleActivity, "Time's up!", Toast.LENGTH_SHORT).show()
                    finishSession()
                    break
                }
                delay(200)
            }
        }
    }

    private fun onDribbleCounted() {
        updateHud()
        val gain = when {
            engine.combo >= 10 -> 3
            engine.combo >= 5 -> 2
            else -> 1
        }
        flash("+$gain")
        vibrateLight()
    }

    private fun flash(text: String) {
        val tv = binding.tvFlash
        tv.text = text
        tv.alpha = 1f
        ObjectAnimator.ofFloat(tv, View.ALPHA, 1f, 0f).setDuration(450).start()
    }

    private fun vibrateLight() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = getSystemService(VibratorManager::class.java)
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(25)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun updateHud() {
        binding.tvScore.text = engine.score.toString()
        binding.tvDribbles.text = "${engine.dribbles}\nDRIBBLES"
        binding.tvCombo.text = "${engine.combo}\nCOMBO"
        val side = when (engine.lastSide) {
            DribbleEngine.Side.LEFT -> "LEFT"
            DribbleEngine.Side.RIGHT -> "RIGHT"
            DribbleEngine.Side.CENTER -> "—"
        }
        binding.tvSide.text = "$side\nL${engine.leftCount}/R${engine.rightCount}"
        if (!tracking) {
            binding.tvTimer.text = String.format("0:%02d\nTIME", durationSec)
        }
    }

    private fun finishSession() {
        if (tracking) {
            tracking = false
            analyzer?.enabled = false
            timerJob?.cancel()
        }
        val duration = if (startedAt == 0L) {
            1000L
        } else {
            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1000L)
        }
        val repo = (application as CourtAiApp).repository
        lifecycleScope.launch {
            val id = repo.save(
                title = "Pound The Rock (${engine.score} pts)",
                drillId = "pound_the_rock",
                makes = engine.dribbles,
                misses = 0,
                durationMs = duration
            )
            try {
                repo.getById(id)?.let {
                    com.courtai.basketball.update.SessionSync.pushOne(this@DribbleActivity, it)
                }
            } catch (_: Exception) {
            }
            Toast.makeText(
                this@DribbleActivity,
                "Saved: ${engine.dribbles} dribbles · ${engine.score} pts",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
