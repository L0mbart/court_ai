package com.courtai.basketball.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.courtai.basketball.data.Drill
import com.courtai.basketball.data.DrillCatalog
import com.courtai.basketball.databinding.ActivityDrillsBinding
import com.courtai.basketball.databinding.ItemDrillBinding

/**
 * ============================================================================
 * DrillListActivity.kt — daftar latihan tembak (drill)
 * ============================================================================
 *
 * PERAN: tampilkan DrillCatalog; tap item → ShotTracker dengan drillId itu.
 * ALUR: pilih drill → izin kamera → mulai tracking MAKE/MISS.
 */
class DrillListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDrillsBinding
    private var pendingDrillId: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingDrillId?.let { openDrill(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDrillsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvDrills.layoutManager = LinearLayoutManager(this)
        binding.rvDrills.adapter = DrillAdapter(DrillCatalog.all) { drill ->
            ensureCamera(drill.id)
        }
    }

    /** Minta CAMERA dulu jika belum ada, baru buka tracker. */
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

    private class DrillAdapter(
        private val items: List<Drill>,
        private val onClick: (Drill) -> Unit
    ) : RecyclerView.Adapter<DrillAdapter.VH>() {
        class VH(val binding: ItemDrillBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemDrillBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.tvTitle.text = item.title
            holder.binding.tvDesc.text = item.description
            holder.binding.tvMeta.text = "${item.category} · ${item.meta}"
            holder.binding.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
