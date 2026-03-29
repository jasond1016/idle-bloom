package com.idlebloom.app.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.idlebloom.app.R
import com.idlebloom.app.data.SourceConfig
import com.idlebloom.app.data.WebDavPhotoSource
import com.idlebloom.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private val photoSource by lazy { WebDavPhotoSource() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appTitleTextView.text = getString(R.string.settings_title)

        lifecycleScope.launch {
            populateFields(settingsStore.load())
        }

        binding.saveButton.setOnClickListener {
            lifecycleScope.launch {
                settingsStore.save(readConfigFromUi())
                binding.statusTextView.text = getString(R.string.settings_saved)
                Snackbar.make(binding.root, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
            }
        }

        binding.testConnectionButton.setOnClickListener {
            lifecycleScope.launch {
                val config = readConfigFromUi()
                binding.statusTextView.text = getString(R.string.settings_testing)

                try {
                    val photos = photoSource.listPhotos(config)
                    val message = if (photos.isEmpty()) {
                        getString(R.string.settings_test_no_photos)
                    } else {
                        getString(R.string.settings_test_success, photos.size)
                    }
                    binding.statusTextView.text = message
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                } catch (t: Throwable) {
                    val message = getString(R.string.settings_test_error, t.message ?: "unknown error")
                    binding.statusTextView.text = message
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                }
            }
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
}
