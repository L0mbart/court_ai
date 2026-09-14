package com.courtai.basketball.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.courtai.basketball.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================================================================
 * UpdateChecker.kt — cek & unduh versi baru aplikasi
 * ============================================================================
 *
 * PERAN FILE:
 * Tanya server “apakah ada APK lebih baru?”. Jika ya, tampilkan dialog,
 * unduh file, lalu buka installer Android.
 *
 * ALUR SINGKAT:
 * 1. GET /api/version → dapat versionCode, changelog, apkUrl.
 * 2. Bandingkan dengan BuildConfig.VERSION_CODE di HP.
 * 3. Jika lebih baru → dialog + notifikasi.
 * 4. User setuju → UpdateDownloadActivity unduh APK → install.
 */

/** Data versi yang dikirim server (JSON). */
data class RemoteVersion(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val forceUpdate: Boolean,
    val apkUrl: String?
)

/**
 * Objek utilitas untuk cek update (bukan Activity).
 * Dipanggil dari MainActivity saat buka / tombol Check Update.
 */
object UpdateChecker {
    /**
     * Ambil info versi dari server (jalan di thread IO).
     * @throws Exception jika server offline atau HTTP error
     */
    suspend fun fetchVersion(context: android.content.Context): RemoteVersion =
        withContext(Dispatchers.IO) {
            val base = ServerConfig.getBaseUrl(context)
            val conn = (URL("$base/api/version").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) {
                    throw IllegalStateException("Server HTTP ${conn.responseCode}")
                }
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                RemoteVersion(
                    versionCode = json.optInt("versionCode", 0),
                    versionName = json.optString("versionName", "?"),
                    changelog = json.optString("changelog", ""),
                    forceUpdate = json.optBoolean("forceUpdate", false),
                    apkUrl = json.optString("apkUrl", "")
                        .takeIf { it.isNotBlank() && it != "null" }
                )
            } finally {
                conn.disconnect()
            }
        }

    /** true jika versionCode remote lebih besar dari yang terpasang. */
    fun isNewer(remote: RemoteVersion): Boolean =
        remote.versionCode > BuildConfig.VERSION_CODE

    /** Tampilkan dialog update; forceUpdate = tidak bisa ditutup dengan “Nanti”. */
    fun showUpdateDialog(activity: Activity, remote: RemoteVersion) {
        if (remote.apkUrl.isNullOrBlank()) {
            Toast.makeText(
                activity,
                "Update available but APK not uploaded on server",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle("Update tersedia: ${remote.versionName}")
            .setMessage(
                "Ada versi baru CourtAI (build ${remote.versionCode}).\n\n" +
                    remote.changelog.ifBlank { "Perbaikan dan fitur baru." }
            )
            .setPositiveButton("Update sekarang") { _, _ ->
                activity.startActivity(
                    Intent(activity, UpdateDownloadActivity::class.java)
                        .putExtra(UpdateDownloadActivity.EXTRA_URL, remote.apkUrl)
                        .putExtra(UpdateDownloadActivity.EXTRA_VERSION, remote.versionName)
                )
            }
        if (!remote.forceUpdate) {
            builder.setNegativeButton("Nanti", null)
        } else {
            builder.setCancelable(false)
        }
        builder.show()
    }

    /**
     * Cek server lalu prompt jika ada update.
     * @param silentIfLatest true = diam saja kalau sudah terbaru
     * @param notify true = tampilkan juga notifikasi sistem
     */
    suspend fun checkAndPrompt(
        activity: Activity,
        silentIfLatest: Boolean = true,
        notify: Boolean = true
    ) {
        try {
            val remote = fetchVersion(activity)
            if (isNewer(remote)) {
                if (notify) {
                    UpdateNotifier.notifyUpdateAvailable(activity, remote)
                }
                withContext(Dispatchers.Main) { showUpdateDialog(activity, remote) }
            } else if (!silentIfLatest) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        "Sudah versi terbaru (${BuildConfig.VERSION_NAME})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            if (!silentIfLatest) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        "Tidak bisa hubungi server: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}

// ========== ACTIVITY PENGUNDUH APK ==========

/**
 * Layar singkat (tanpa UI rumit): unduh APK di background thread,
 * lalu buka Intent installer Android.
 */
class UpdateDownloadActivity : Activity() {
    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_VERSION = "version"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        val version = intent.getStringExtra(EXTRA_VERSION) ?: "update"
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        Toast.makeText(this, "Downloading $version...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                ensureInstallPermission()
                val file = downloadApk(url, version)
                runOnUiThread { installApk(file) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Download gagal: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    /** Android 8+: wajib izinkan “install unknown apps” untuk CourtAI. */
    private fun ensureInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
            throw IllegalStateException("Allow install unknown apps, then tap Update again")
        }
    }

    /** Unduh file APK ke cacheDir aplikasi. */
    private fun downloadApk(url: String, version: String): File {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val safe = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val out = File(cacheDir, "courtai-$safe.apk")
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(out).use { output ->
                    input.copyTo(output)
                }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    /** Buka installer sistem dengan FileProvider (aman, tidak pakai file:// mentah). */
    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }
}
