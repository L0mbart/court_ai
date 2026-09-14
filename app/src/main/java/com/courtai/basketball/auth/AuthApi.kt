package com.courtai.basketball.auth

import android.content.Context
import com.courtai.basketball.update.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================================================================
 * AuthApi.kt — komunikasi login ke server
 * ============================================================================
 *
 * PERAN FILE:
 * Mengirim email/telepon + password ke API, lalu menerima data user + token.
 * Tidak menyimpan apa-apa — penyimpanan ada di AuthSession.
 *
 * ALUR SINGKAT:
 * 1. Bangun JSON { identifier, password }.
 * 2. POST ke /api/auth/login.
 * 3. Jika sukses → LoggedInUser; jika gagal → Exception dengan pesan server.
 */
object AuthApi {
    /**
     * Login ke dashboard server.
     *
     * @param identifier email atau nomor telepon
     * @param password kata sandi
     * @return user yang berhasil masuk (lalu biasanya AuthSession.save)
     */
    suspend fun login(context: Context, identifier: String, password: String): LoggedInUser =
        withContext(Dispatchers.IO) {
            val base = ServerConfig.getBaseUrl(context)
            val body = JSONObject()
                .put("identifier", identifier.trim())
                .put("password", password)
                .toString()
            val conn = (URL("$base/api/auth/login").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 12000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.readText().orEmpty()
                if (code !in 200..299) {
                    val detail = try {
                        JSONObject(text).optString("detail", text)
                    } catch (_: Exception) {
                        text.ifBlank { "Login gagal ($code)" }
                    }
                    throw IllegalStateException(detail)
                }
                val json = JSONObject(text)
                val user = json.getJSONObject("user")
                LoggedInUser(
                    id = user.getString("id"),
                    name = user.getString("name"),
                    email = user.optString("email"),
                    phone = user.optString("phone"),
                    token = json.optString("token")
                )
            } finally {
                conn.disconnect()
            }
        }
}
