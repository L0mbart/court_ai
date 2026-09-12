package com.courtai.basketball.auth

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

data class LoggedInUser(
    val id: String,
    val name: String,
    val email: String,
    val phone: String,
    val token: String
)

object AuthSession {
    private const val PREFS = "courtai_auth"
    private const val KEY_JSON = "user_json"
    private const val KEY_BIOMETRIC = "biometric_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLoggedIn(context: Context): Boolean = current(context) != null

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

    fun save(context: Context, user: LoggedInUser) {
        val o = JSONObject()
            .put("id", user.id)
            .put("name", user.name)
            .put("email", user.email)
            .put("phone", user.phone)
            .put("token", user.token)
        prefs(context).edit().putString(KEY_JSON, o.toString()).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_JSON).apply()
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
    }

    fun isBiometricEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BIOMETRIC, false) && isLoggedIn(context)

    fun displayName(context: Context): String = current(context)?.name ?: "Guest"

    fun userId(context: Context): String = current(context)?.id ?: "guest"
}
