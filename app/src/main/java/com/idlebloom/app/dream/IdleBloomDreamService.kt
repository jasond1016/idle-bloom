package com.idlebloom.app.dream

import android.service.dreams.DreamService
import android.view.LayoutInflater
import com.idlebloom.app.R
import com.idlebloom.app.databinding.DreamContentBinding
import com.idlebloom.app.settings.SettingsStore
import com.idlebloom.app.slideshow.SlideshowPlaybackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class IdleBloomDreamService : DreamService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsStore by lazy { SettingsStore(applicationContext) }

    private lateinit var binding: DreamContentBinding
    private lateinit var playbackController: SlideshowPlaybackController
    private var startJob: Job? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setInteractive(false)
        setFullscreen(true)
        setScreenBright(true)

        binding = DreamContentBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        playbackController = SlideshowPlaybackController(
            context = this,
            scope = scope,
            binding = binding
        )
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()

        startJob?.cancel()
        startJob = scope.launch {
            val config = settingsStore.load()
            try {
                playbackController.start(config)
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            }
        }
    }

    override fun onDreamingStopped() {
        startJob?.cancel()
        startJob = null
        playbackController.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        startJob?.cancel()
        startJob = null
        scope.cancel()
        playbackController.stop()
        super.onDetachedFromWindow()
    }
}
