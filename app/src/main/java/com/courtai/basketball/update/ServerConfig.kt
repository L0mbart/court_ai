package com.courtai.basketball.update

import android.content.Context
import android.content.SharedPreferences

/**
 * ============================================================================
 * ServerConfig.kt — alamat dasar server dashboard
 * ============================================================================
 *
 * PERAN: simpan URL PC server (login, sync, update).
 * Default 10.0.2.2 = localhost PC dari emulator Android.
 * Di HP fisik: ganti ke IP LAN, contoh http://192.168.1.10:8080
 */
object ServerConfig {
    private const val PREFS = "courtai_server"
    private const val KEY_URL = "base_url"

    /** Default: PC localhost via Android emulator. Ganti di HP nyata ke IP LAN. */
    const val DEFAULT_URL = "http://10.0.2.2:8080"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_URL, DEFAULT_URL)?.trimEnd('/') ?: DEFAULT_URL

    fun setBaseUrl(context: Context, url: String) {
        val clean = url.trim().trimEnd('/')
        prefs(context).edit().putString(KEY_URL, clean).apply()
    }
}
