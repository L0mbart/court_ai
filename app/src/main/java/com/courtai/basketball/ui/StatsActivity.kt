package com.courtai.basketball.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.courtai.basketball.CourtAiApp
import com.courtai.basketball.data.TrainingSession
import com.courtai.basketball.databinding.ActivityStatsBinding
import com.courtai.basketball.databinding.ItemSessionBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStatsBinding
    private val adapter = SessionAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvSessions.layoutManager = LinearLayoutManager(this)
        binding.rvSessions.adapter = adapter

        val repo = (application as CourtAiApp).repository
        lifecycleScope.launch {
            val s = repo.summary()
            binding.tvSessions.text = s.sessions.toString()
            binding.tvTotalMakes.text = s.makes.toString()
            binding.tvTotalFg.text = s.fgPercent?.let { String.format("%.0f%%", it) } ?: "—"
        }
        lifecycleScope.launch {
            repo.observeSessions().collectLatest { list ->
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                adapter.submit(list)
            }
        }
    }

    private class SessionAdapter : RecyclerView.Adapter<SessionAdapter.VH>() {
        private var items: List<TrainingSession> = emptyList()
        private val fmt = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())

        fun submit(data: List<TrainingSession>) {
            items = data
            notifyDataSetChanged()
        }

        class VH(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            val mins = (s.durationMs / 1000 / 60).toInt()
            val secs = ((s.durationMs / 1000) % 60).toInt()
            holder.binding.tvSessionTitle.text = s.title
            holder.binding.tvSessionMeta.text =
                "${fmt.format(Date(s.createdAt))} · ${s.makes}/${s.attempts} · " +
                    String.format("%.0f%% FG", s.fgPercent) +
                    " · ${mins}m ${secs}s"
        }

        override fun getItemCount() = items.size
    }
}
