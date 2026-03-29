package com.idlebloom.app.dream

import android.service.dreams.DreamService
import android.view.LayoutInflater
import androidx.core.view.isVisible
import coil.ImageLoader
import coil.load
import com.idlebloom.app.R
import com.idlebloom.app.data.SourceConfig
import com.idlebloom.app.data.WebDavPhotoSource
import com.idlebloom.app.databinding.DreamContentBinding
import com.idlebloom.app.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient

class IdleBloomDreamService : DreamService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsStore by lazy { SettingsStore(applicationContext) }
    private val photoSource by lazy { WebDavPhotoSource() }

    private lateinit var binding: DreamContentBinding
    private var slideshowJob: Job? = null
    private var imageLoader: ImageLoader? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setInteractive(false)
        setFullscreen(true)
        setScreenBright(true)

        binding = DreamContentBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()

        slideshowJob?.cancel()
        slideshowJob = scope.launch {
            val config = settingsStore.load()
            if (!config.isReady()) {
                showStatus(getString(R.string.dream_status_missing_config))
                return@launch
            }

            imageLoader = buildImageLoader(config)
            showLoadingStatus(getString(R.string.dream_status_loading))

            try {
                val photos = photoSource.listPhotos(config)
                if (photos.isEmpty()) {
                    showStatus(getString(R.string.dream_status_no_photos))
                    return@launch
                }

                binding.progressBar.isVisible = false
                binding.statusTextView.isVisible = false

                var playlist = if (config.shuffleEnabled) photos.shuffled() else photos
                var index = 0

                while (true) {
                    if (playlist.isEmpty()) {
                        showStatus(getString(R.string.dream_status_no_photos))
                        return@launch
                    }

                    if (index >= playlist.size) {
                        index = 0
                        if (config.shuffleEnabled) {
                            playlist = playlist.shuffled()
                        }
                    }

                    val photo = playlist[index]
                    binding.captionTextView.text = photo.name
                    binding.captionTextView.isVisible = true
                    binding.photoImageView.load(photo.url, imageLoader ?: return@launch) {
                        crossfade(true)
                        placeholder(R.drawable.dream_placeholder)
                        error(R.drawable.dream_placeholder)
                    }

                    index += 1
                    delay(config.intervalMillis())
                }
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (t: Throwable) {
                showStatus(getString(R.string.dream_status_error, t.message ?: "unknown error"))
            }
        }
    }

    override fun onDreamingStopped() {
        slideshowJob?.cancel()
        slideshowJob = null
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        imageLoader?.shutdown()
        super.onDetachedFromWindow()
    }

    private fun showStatus(message: String) {
        binding.progressBar.isVisible = false
        binding.statusTextView.text = message
        binding.statusTextView.isVisible = true
        binding.captionTextView.isVisible = false
    }

    private fun showLoadingStatus(message: String) {
        binding.progressBar.isVisible = true
        binding.statusTextView.text = message
        binding.statusTextView.isVisible = true
        binding.captionTextView.isVisible = false
    }

    private fun buildImageLoader(config: SourceConfig): ImageLoader {
        val authHeader = Credentials.basic(config.username, config.password)
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", authHeader)
                    .build()
                chain.proceed(request)
            })
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .crossfade(true)
            .build()
    }
}
