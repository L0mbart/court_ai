package com.courtai.basketball.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.courtai.basketball.R
import com.courtai.basketball.ui.MainActivity

/**
 * ============================================================================
 * UpdateNotifier.kt — notifikasi sistem “ada update”
 * ============================================================================
 *
 * PERAN: buat channel notifikasi + tampilkan alert saat versi baru tersedia.
 * Tap notifikasi → MainActivity dengan extra open_update (buka dialog update).
 */
object UpdateNotifier {
    const val CHANNEL_ID = "courtai_updates"
    private const val NOTIF_ID = 1103
    private const val PREFS = "courtai_update"
    private const val KEY_LAST_NOTIFIED = "last_notified_code"

    /** Buat channel (wajib Android 8+). Dipanggil dari CourtAiApp. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CourtAI Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifikasi saat ada versi aplikasi baru"
            enableVibration(true)
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * Tampilkan notifikasi sekali per versionCode
     * (supaya tidak spam untuk versi yang sama).
     */
    fun notifyUpdateAvailable(context: Context, remote: RemoteVersion) {
        ensureChannel(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getInt(KEY_LAST_NOTIFIED, 0)
        if (last >= remote.versionCode) return

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_update", true)
            putExtra("update_url", remote.apkUrl)
            putExtra("update_version", remote.versionName)
            putExtra("update_changelog", remote.changelog)
            putExtra("update_force", remote.forceUpdate)
            putExtra("update_code", remote.versionCode)
        }
        val pending = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = remote.changelog.ifBlank {
            "Versi ${remote.versionName} siap diunduh."
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Update CourtAI ${remote.versionName}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
            prefs.edit().putInt(KEY_LAST_NOTIFIED, remote.versionCode).apply()
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS belum diberikan
        }
    }
}
