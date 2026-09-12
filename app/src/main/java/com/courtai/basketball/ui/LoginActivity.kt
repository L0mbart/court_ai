package com.courtai.basketball.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.courtai.basketball.auth.AuthApi
import com.courtai.basketball.auth.AuthSession
import com.courtai.basketball.databinding.ActivityLoginBinding
import com.courtai.basketball.update.ServerConfig
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // If already logged in and biometric off, go home
        if (AuthSession.isLoggedIn(this) && !AuthSession.isBiometricEnabled(this)) {
            goHome()
            return
        }

        binding.switchBiometric.isChecked = AuthSession.isBiometricEnabled(this)
        updateBiometricButton()

        binding.btnLogin.setOnClickListener { doPasswordLogin() }
        binding.btnBiometric.setOnClickListener { promptBiometric() }
        binding.btnServer.setOnClickListener { editServer() }

        // Auto biometric unlock if session exists
        if (AuthSession.isLoggedIn(this) && AuthSession.isBiometricEnabled(this) && canUseBiometric()) {
            binding.tvStatus.text = "Gunakan biometrik untuk masuk sebagai ${AuthSession.displayName(this)}"
            promptBiometric()
        }
    }

    private fun updateBiometricButton() {
        val show = AuthSession.isLoggedIn(this) && canUseBiometric()
        binding.btnBiometric.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun canUseBiometric(): Boolean {
        val mgr = BiometricManager.from(this)
        return mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun doPasswordLogin() {
        val id = binding.etIdentifier.text?.toString().orEmpty()
        val pw = binding.etPassword.text?.toString().orEmpty()
        if (id.isBlank() || pw.isBlank()) {
            Toast.makeText(this, "Isi email/telepon dan password", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnLogin.isEnabled = false
        binding.tvStatus.text = "Menghubungkan ke server..."
        lifecycleScope.launch {
            try {
                val user = AuthApi.login(this@LoginActivity, id, pw)
                AuthSession.save(this@LoginActivity, user)
                AuthSession.setBiometricEnabled(this@LoginActivity, binding.switchBiometric.isChecked)
                Toast.makeText(this@LoginActivity, "Welcome, ${user.name}", Toast.LENGTH_SHORT).show()
                goHome()
            } catch (e: Exception) {
                binding.tvStatus.text = e.message ?: "Login gagal"
                Toast.makeText(this@LoginActivity, e.message ?: "Login gagal", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnLogin.isEnabled = true
                updateBiometricButton()
            }
        }
    }

    private fun promptBiometric() {
        if (!AuthSession.isLoggedIn(this)) {
            Toast.makeText(this, "Login password dulu sekali", Toast.LENGTH_SHORT).show()
            return
        }
        if (!canUseBiometric()) {
            Toast.makeText(this, "Biometrik tidak tersedia di device ini", Toast.LENGTH_SHORT).show()
            return
        }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    AuthSession.setBiometricEnabled(this@LoginActivity, true)
                    goHome()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    binding.tvStatus.text = errString.toString()
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("CourtAI Biometric Login")
                .setSubtitle("Masuk sebagai ${AuthSession.displayName(this)}")
                .setNegativeButtonText("Pakai password")
                .build()
        )
    }

    private fun editServer() {
        val input = EditText(this).apply {
            setText(ServerConfig.getBaseUrl(this@LoginActivity))
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Server URL")
            .setMessage("IP PC dashboard, contoh http://192.168.1.10:8080")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                ServerConfig.setBaseUrl(this, input.text.toString())
                Toast.makeText(this, ServerConfig.getBaseUrl(this), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
