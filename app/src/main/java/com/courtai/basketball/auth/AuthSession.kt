package com.courtai.basketball.auth

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * ============================================================================
 * AuthSession.kt — “dompet” sesi login di HP
 * ============================================================================
 *
 * PERAN FILE:
 * Menyimpan data user yang sudah login di SharedPreferences
 * (memori kecil yang tetap ada setelah app ditutup).
 *
 * ALUR SINGKAT:
 * 1. Login sukses → save(user).
 * 2. MainActivity cek isLoggedIn() / current().
 * 3. Logout → clear().
 * 4. Opsional: flag biometrik (sidik jari / wajah) untuk buka cepat.
 */

/** Data user yang sedang login (dari server). */
data class LoggedInUser(
    val id: String,
    val name: String,
    val email: String,
    val phone: String,
    val token: String
)

/**
 * Penyimpanan lokal sesi auth.
 * Analogi: kartu anggota di dompet — bisa dibaca, diganti, atau dibuang.
 */
object AuthSession {
    private const val PREFS = "courtai_auth"
    private const val KEY_JSON = "user_json"
    private const val KEY_BIOMETRIC = "biometric_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** true jika ada data user tersimpan. */
    fun isLoggedIn(context: Context): Boolean = current(context) != null

    /** Baca user dari prefs; null jika kosong / JSON rusak. */
    fun current(context: Context): LoggedInUser? {
        val raw = prefs(context).getString(KEY_JSON, null) ?: return null
        return try {
            val o = JSONObject(raw)
            LoggedInUser(
                id = o.getString("id"),
                name = o.getString("name"),
                email = o.optString("email"),
                phone = o.optString("phone"),
                token = o.optString("token")
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Simpan user setelah login berhasil. */
    fun save(context: Context, user: LoggedInUser) {
        val o = JSONObject()
            .put("id", user.id)
            .put("name", user.name)
            .put("email", user.email)
            .put("phone", user.phone)
            .put("token", user.token)
        prefs(context).edit().putString(KEY_JSON, o.toString()).apply()
    }

    /** Hapus sesi (logout). */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_JSON).apply()
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    /** Biometrik aktif hanya jika flag ON dan masih ada user tersimpan. */
    fun isBiometricEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BIOMETRIC, false) && isLoggedIn(context)

    fun displayName(context: Context): String = current(context)?.name ?: "Guest"

    fun userId(context: Context): String = current(context)?.id ?: "guest"
}
