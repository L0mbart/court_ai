package com.courtai.basketball.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.courtai.basketball.R
import com.courtai.basketball.data.WorkoutCatalog
import com.courtai.basketball.databinding.ActivityWorkoutBinding
import com.google.android.material.button.MaterialButton

/**
 * ============================================================================
 * WorkoutActivity.kt — daftar paket latihan (beberapa drill berurutan)
 * ============================================================================
 *
 * PERAN: tampilkan WorkoutCatalog; Start membuka drill pertama di ShotTracker.
 * ALUR: pilih paket → pastikan izin kamera → ShotTrackerActivity.
 */
class WorkoutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWorkoutBinding
    private var pendingDrillId: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingDrillId?.let { openDrill(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Bangun kartu UI secara kode (bukan XML item) untuk tiap paket
        WorkoutCatalog.all.forEach { plan ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(40, 40, 40, 40)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 28
                layoutParams = lp
            }

            card.addView(TextView(this).apply {
                text = plan.title
                setTextColor(ContextCompat.getColor(this@WorkoutActivity, R.color.court_cream))
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = plan.description
                setTextColor(0xFF9BB0C9.toInt())
                textSize = 13f
                setPadding(0, 8, 0, 16)
            })
            card.addView(TextView(this).apply {
                text = plan.blocks.joinToString(" → ") { it.title }
                setTextColor(ContextCompat.getColor(this@WorkoutActivity, R.color.court_amber))
                textSize = 12f
                setPadding(0, 0, 0, 20)
            })

            val first = plan.blocks.first()
            card.addView(MaterialButton(this).apply {
                text = "Start: ${first.title}"
                setBackgroundColor(ContextCompat.getColor(this@WorkoutActivity, R.color.court_orange))
                setOnClickListener { ensureCamera(first.id) }
            })

            binding.workoutContainer.addView(card)
        }
    }

    private fun ensureCamera(drillId: String) {
        pendingDrillId = drillId
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (ok) openDrill(drillId) else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun openDrill(drillId: String) {
        startActivity(
            Intent(this, ShotTrackerActivity::class.java)
                .putExtra(ShotTrackerActivity.EXTRA_DRILL_ID, drillId)
        )
    }
}
