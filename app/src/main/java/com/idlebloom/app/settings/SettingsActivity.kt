package com.idlebloom.app.settings

import android.os.Bundle
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.app.AppCompatActivity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.lifecycle.lifecycleScope
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import com.idlebloom.app.R
import com.idlebloom.app.data.PhotoDiscoveryResult
import com.idlebloom.app.data.PhotoRepository
import com.idlebloom.app.data.SourceConfig
import com.idlebloom.app.databinding.ActivitySettingsBinding
import com.idlebloom.app.preview.PreviewSlideshowActivity
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private val photoRepository by lazy { PhotoRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appTitleTextView.text = getString(R.string.settings_title)
        installValidationResetHandlers()

        lifecycleScope.launch {
            populateFields(settingsStore.load())
        }

        binding.saveButton.setOnClickListener {
            saveCurrentSettings()
        }

        binding.testConnectionButton.setOnClickListener {
            testConnection()
        }

        binding.previewButton.setOnClickListener {
            launchPreview()
        }
    }

    private fun populateFields(config: SourceConfig) {
        binding.baseUrlEditText.setText(config.baseUrl)
        binding.usernameEditText.setText(config.username)
        binding.passwordEditText.setText(config.password)
        binding.remoteDirectoryEditText.setText(config.remoteDirectory)
        binding.intervalEditText.setText(config.slideIntervalSeconds.toString())
        binding.shuffleSwitch.isChecked = config.shuffleEnabled
    }

    private fun readConfigFromUi(): SourceConfig {
        val interval = binding.intervalEditText.text?.toString()?.toIntOrNull() ?: 20
        return SourceConfig(
            baseUrl = binding.baseUrlEditText.text?.toString().orEmpty(),
            username = binding.usernameEditText.text?.toString().orEmpty(),
            password = binding.passwordEditText.text?.toString().orEmpty(),
            remoteDirectory = binding.remoteDirectoryEditText.text?.toString().orEmpty().ifBlank { "/" },
            slideIntervalSeconds = interval,
            shuffleEnabled = binding.shuffleSwitch.isChecked
        )
    }

    private fun saveCurrentSettings() {
        val config = readConfigFromUi()
        if (!validate(config)) {
            return
        }

        lifecycleScope.launch {
            settingsStore.save(config)
            showStatus(
                message = getString(R.string.settings_saved),
                diagnostics = null
            )
            Snackbar.make(binding.root, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun testConnection() {
        val config = readConfigFromUi()
        if (!validate(config)) {
            return
        }

        lifecycleScope.launch {
            showStatus(
                message = getString(R.string.settings_testing),
                diagnostics = null
            )

            val result = photoRepository.refreshPhotos(config)
            val message = when {
                !result.isSuccess -> getString(
                    R.string.settings_test_error,
                    result.errorMessage ?: getString(R.string.error_unknown)
                )

                result.photos.isEmpty() -> getString(R.string.settings_test_no_photos)
                else -> getString(R.string.settings_test_success, result.photos.size)
            }

            showStatus(
                message = message,
                diagnostics = formatDiagnostics(result)
            )
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun launchPreview() {
        val config = readConfigFromUi()
        if (!validate(config)) {
            return
        }

        lifecycleScope.launch {
            settingsStore.save(config)
            showStatus(
                message = getString(R.string.settings_preview_launching),
                diagnostics = null
            )
            startActivity(PreviewSlideshowActivity.createIntent(this@SettingsActivity, config))
        }
    }

    private fun validate(config: SourceConfig): Boolean {
        clearErrors()

        var valid = true
        val normalizedBaseUrl = config.normalizedBaseUrl()
        val normalizedDirectory = config.normalizedRemoteDirectory()

        if (normalizedBaseUrl.isBlank()) {
            binding.baseUrlInputLayout.error = getString(R.string.settings_validation_base_url_required)
            valid = false
        } else if (normalizedBaseUrl.toHttpUrlOrNull() == null) {
            binding.baseUrlInputLayout.error = getString(R.string.settings_validation_base_url_invalid)
            valid = false
        }

        if (config.username.isBlank()) {
            binding.usernameInputLayout.error = getString(R.string.settings_validation_username_required)
            valid = false
        }

        if (config.remoteDirectory.isBlank()) {
            binding.remoteDirectoryInputLayout.error = getString(R.string.settings_validation_remote_directory_required)
            valid = false
        } else if (normalizedDirectory == "/") {
            binding.remoteDirectoryInputLayout.error = getString(R.string.settings_validation_remote_directory_invalid)
            valid = false
        }

        if (config.slideIntervalSeconds < 5) {
            binding.intervalInputLayout.error = getString(R.string.settings_validation_interval_invalid)
            valid = false
        }

        if (!valid) {
            showStatus(
                message = getString(R.string.settings_validation_fix_fields),
                diagnostics = null
            )
        }

        return valid
    }

    private fun clearErrors() {
        binding.baseUrlInputLayout.error = null
        binding.usernameInputLayout.error = null
        binding.passwordInputLayout.error = null
        binding.remoteDirectoryInputLayout.error = null
        binding.intervalInputLayout.error = null
    }

    private fun installValidationResetHandlers() {
        listOf(
            binding.baseUrlEditText to binding.baseUrlInputLayout,
            binding.usernameEditText to binding.usernameInputLayout,
            binding.passwordEditText to binding.passwordInputLayout,
            binding.remoteDirectoryEditText to binding.remoteDirectoryInputLayout,
            binding.intervalEditText to binding.intervalInputLayout
        ).forEach { (editText, inputLayout) ->
            editText.doAfterTextChanged {
                inputLayout.error = null
            }
        }
    }

    private fun showStatus(message: String, diagnostics: String?) {
        binding.statusTextView.text = message
        binding.diagnosticsTextView.isVisible = !diagnostics.isNullOrBlank()
        binding.diagnosticsTextView.text = diagnostics.orEmpty()
    }

    private fun formatDiagnostics(result: PhotoDiscoveryResult): String {
        if (result.attempts.isEmpty()) {
            return ""
        }

        val lines = mutableListOf<String>()
        val successfulAttempt = result.successfulAttempt
        if (successfulAttempt != null) {
            lines += getString(
                R.string.settings_diagnostics_header_success,
                successfulAttempt.requestVariant,
                successfulAttempt.url
            )
        } else {
            lines += getString(
                R.string.settings_diagnostics_header_failure,
                result.attempts.size
            )
        }

        result.attempts.forEachIndexed { index, attempt ->
            lines += getString(
                R.string.settings_diagnostics_attempt,
                index + 1,
                attempt.requestVariant,
                attempt.url,
                attempt.responseCode?.toString() ?: "network",
                attempt.detail
            )
        }
        return lines.joinToString("\n")
    }
}
