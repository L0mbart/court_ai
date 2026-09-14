package com.courtai.basketball.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.courtai.basketball.BuildConfig
import com.courtai.basketball.CourtAiApp
import com.courtai.basketball.auth.AuthSession
import com.courtai.basketball.databinding.ActivityMainBinding
import com.courtai.basketball.update.RemoteVersion
import com.courtai.basketball.update.ServerConfig
import com.courtai.basketball.update.SessionSync
import com.courtai.basketball.update.UpdateChecker
import kotlinx.coroutines.launch

/**
 * ============================================================================
 * MainActivity.kt — layar beranda (menu utama) CourtAI
 * ============================================================================
 *
 * PERAN FILE:
 * Pintu masuk setelah login. Menampilkan profil, ringkasan skor,
 * dan tombol menuju fitur: shot tracker, dribble, drill, workout, stats.
 *
 * ALUR SINGKAT:
 * 1. Cek sudah login? Kalau belum → LoginActivity.
 * 2. Tampilkan nama pemain + ringkasan MAKE / attempts / FG%.
 * 3. Tombol fitur → minta izin kamera (jika perlu) → buka Activity terkait.
 * 4. Tombol sync / update / server → hubungi PC dashboard.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    /** Aksi yang ditunda sampai izin kamera dikabulkan. */
    private var pendingAction: (() -> Unit)? = null

    // ========== LAUNCHER IZIN ==========

    /** Meminta izin CAMERA; jika OK, jalankan pendingAction. */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingAction?.invoke()
        pendingAction = null
    }

    /** Setelah izin notifikasi (Android 13+), cek update diam-diam. */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        lifecycleScope.launch {
            UpdateChecker.checkAndPrompt(this@MainActivity, silentIfLatest = true)
        }
    }

    // ========== SIKLUS HIDUP LAYAR ==========

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Belum login → jangan tampilkan beranda
        if (!AuthSession.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindProfile()

        // ========== TOMBOL MENU ==========
        binding.btnLogout.setOnClickListener { confirmLogout() }

        binding.btnStartTracker.setOnClickListener {
            withCamera {
                startActivity(
                    Intent(this, ShotTrackerActivity::class.java)
                        .putExtra(ShotTrackerActivity.EXTRA_DRILL_ID, "freestyle")
                )
            }
        }
        binding.btnDribble.setOnClickListener {
            withCamera {
                startActivity(Intent(this, DribbleActivity::class.java))
            }
        }
        binding.btnDrills.setOnClickListener {
            startActivity(Intent(this, DrillListActivity::class.java))
        }
        binding.btnWorkouts.setOnClickListener {
            startActivity(Intent(this, WorkoutActivity::class.java))
        }
        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        binding.btnCheckUpdate.setOnClickListener {
            lifecycleScope.launch {
                UpdateChecker.checkAndPrompt(this@MainActivity, silentIfLatest = false)
            }
        }
        binding.btnSync.setOnClickListener { syncSessions() }
        binding.btnServer.setOnClickListener { editServerUrl() }

        // Dari notifikasi update? Buka dialog; kalau tidak, cek update biasa
        if (!handleUpdateIntent(intent)) {
            ensureNotificationPermissionThenCheck()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!AuthSession.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        bindProfile()
        refreshSummary()
    }

    // ========== UPDATE & NOTIFIKASI ==========

    /** Minta izin notifikasi dulu (API 33+), lalu cek versi di server. */
    private fun ensureNotificationPermissionThenCheck() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        lifecycleScope.launch {
            UpdateChecker.checkAndPrompt(this@MainActivity, silentIfLatest = true)
        }
    }

    /**
     * Dipanggil saat user tap notifikasi “ada update”.
     * Extra Intent berisi info versi dari UpdateNotifier.
     * @return true jika Intent memang untuk membuka dialog update
     */
    private fun handleUpdateIntent(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra("open_update", false) != true) return false
        val url = intent.getStringExtra("update_url") ?: return false
        val version = intent.getStringExtra("update_version") ?: "update"
        val changelog = intent.getStringExtra("update_changelog").orEmpty()
        val force = intent.getBooleanExtra("update_force", false)
        val code = intent.getIntExtra("update_code", 0)
        val remote = RemoteVersion(code, version, changelog, force, url)
        UpdateChecker.showUpdateDialog(this, remote)
        return true
    }

    // ========== PROFIL & LOGOUT ==========

    /** Isi teks salam + nama + ID/kontak dari AuthSession. */
    private fun bindProfile() {
        val user = AuthSession.current(this) ?: return
        binding.tvGreeting.text = "Let's go, ${user.name.split(" ").first()}"
        binding.tvPlayerName.text = user.name
        val contact = when {
            user.email.isNotBlank() -> user.email
            user.phone.isNotBlank() -> user.phone
            else -> "—"
        }
        binding.tvPlayerId.text = "ID: ${user.id}  |  $contact  |  v${BuildConfig.VERSION_NAME}"
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Keluar dari akun ${AuthSession.displayName(this)}?")
            .setPositiveButton("Logout") { _, _ ->
                AuthSession.clear(this)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Ambil ringkasan dari database lokal (Room) dan tampilkan di kartu. */
    private fun refreshSummary() {
        val repo = (application as CourtAiApp).repository
        lifecycleScope.launch {
            val s = repo.summary()
            binding.tvMakes.text = s.makes.toString()
            binding.tvAttempts.text = s.attempts.toString()
            binding.tvFg.text = s.fgPercent?.let { String.format("%.0f%%", it) } ?: "—"
        }
    }

    // ========== SYNC & SERVER ==========

    /** Kirim semua sesi latihan lokal ke server dashboard. */
    private fun syncSessions() {
        val repo = (application as CourtAiApp).repository
        lifecycleScope.launch {
            try {
                val n = SessionSync.push(this@MainActivity, repo.getAll())
                Toast.makeText(
                    this@MainActivity,
                    "Synced $n sesi (${AuthSession.displayName(this@MainActivity)})",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Sync gagal: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Dialog ubah alamat PC server (contoh: http://192.168.1.10:8080). */
    private fun editServerUrl() {
        val input = EditText(this).apply {
            setText(ServerConfig.getBaseUrl(this@MainActivity))
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Server Dashboard")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                ServerConfig.setBaseUrl(this, input.text.toString())
                Toast.makeText(this, ServerConfig.getBaseUrl(this), Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    UpdateChecker.checkAndPrompt(this@MainActivity, silentIfLatest = true)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Jalankan [action] hanya jika izin kamera sudah ada.
     * Kalau belum, simpan action lalu minta izin dulu.
     */
    private fun withCamera(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) action()
        else {
            pendingAction = action
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}
