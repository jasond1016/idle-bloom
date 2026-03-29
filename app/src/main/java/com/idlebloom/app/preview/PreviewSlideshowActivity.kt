package com.idlebloom.app.preview

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.idlebloom.app.R
import com.idlebloom.app.data.SourceConfig
import com.idlebloom.app.databinding.DreamContentBinding
import com.idlebloom.app.slideshow.SlideshowPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PreviewSlideshowActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var binding: DreamContentBinding
    private lateinit var playbackController: SlideshowPlaybackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = DreamContentBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        binding.modeBadgeTextView.isVisible = true
        binding.modeBadgeTextView.text = getString(R.string.preview_mode_badge)

        playbackController = SlideshowPlaybackController(
            context = this,
            scope = scope,
            binding = binding
        )
    }

    override fun onStart() {
        super.onStart()
        playbackController.start(readConfigFromIntent())
    }

    override fun onStop() {
        playbackController.stop()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> playbackController.showPreviousPhoto()
            KeyEvent.KEYCODE_DPAD_RIGHT -> playbackController.showNextPhoto()
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun readConfigFromIntent(): SourceConfig {
        return SourceConfig(
            baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty(),
            username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
            password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
            remoteDirectory = intent.getStringExtra(EXTRA_REMOTE_DIRECTORY).orEmpty(),
            slideIntervalSeconds = intent.getIntExtra(EXTRA_SLIDE_INTERVAL_SECONDS, 20),
            shuffleEnabled = intent.getBooleanExtra(EXTRA_SHUFFLE_ENABLED, true)
        )
    }

    companion object {
        private const val EXTRA_BASE_URL = "extra_base_url"
        private const val EXTRA_USERNAME = "extra_username"
        private const val EXTRA_PASSWORD = "extra_password"
        private const val EXTRA_REMOTE_DIRECTORY = "extra_remote_directory"
        private const val EXTRA_SLIDE_INTERVAL_SECONDS = "extra_slide_interval_seconds"
        private const val EXTRA_SHUFFLE_ENABLED = "extra_shuffle_enabled"

        fun createIntent(context: Context, config: SourceConfig): Intent {
            return Intent(context, PreviewSlideshowActivity::class.java)
                .putExtra(EXTRA_BASE_URL, config.baseUrl)
                .putExtra(EXTRA_USERNAME, config.username)
                .putExtra(EXTRA_PASSWORD, config.password)
                .putExtra(EXTRA_REMOTE_DIRECTORY, config.remoteDirectory)
                .putExtra(EXTRA_SLIDE_INTERVAL_SECONDS, config.slideIntervalSeconds)
                .putExtra(EXTRA_SHUFFLE_ENABLED, config.shuffleEnabled)
        }
    }
}
