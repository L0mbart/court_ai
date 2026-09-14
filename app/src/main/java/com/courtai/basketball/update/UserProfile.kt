package com.courtai.basketball.update

import android.content.Context
import android.content.SharedPreferences
import java.text.Normalizer

/**
 * ============================================================================
 * UserProfile.kt — nama pemain lokal (legacy / helper dashboard)
 * ============================================================================
 *
 * PERAN: simpan display name & buat userId slug dari nama
 * (contoh "User A" → "user-a"). AuthSession kini jadi sumber utama login;
 * file ini tetap berguna untuk ID tracking berbasis nama.
 */
object UserProfile {
    private const val PREFS = "courtai_user"
    private const val KEY_NAME = "display_name"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasName(context: Context): Boolean =
        !prefs(context).getString(KEY_NAME, null).isNullOrBlank()

    fun getDisplayName(context: Context): String =
        prefs(context).getString(KEY_NAME, null)?.trim()?.takeIf { it.isNotBlank() } ?: ""

    /** ID web/dashboard — dibuat dari nama (stabil selama nama sama). */
    fun getUserId(context: Context): String {
        val name = getDisplayName(context)
        return if (name.isBlank()) "belum-set-nama" else slugify(name)
    }

    fun setDisplayName(context: Context, name: String): String {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        require(clean.isNotBlank()) { "Nama tidak boleh kosong" }
        require(clean.length in 2..32) { "Nama 2-32 karakter" }
        prefs(context).edit().putString(KEY_NAME, clean).apply()
        return clean
    }

    /** Ubah nama jadi slug aman URL: huruf kecil, spasi → strip. */
    fun slugify(name: String): String {
        val normalized = Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        val slug = normalized
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifBlank { "player" }.take(40)
    }
}
