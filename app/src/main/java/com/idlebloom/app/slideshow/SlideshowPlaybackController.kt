package com.idlebloom.app.slideshow

import android.content.Context
import androidx.core.view.isVisible
import coil.ImageLoader
import coil.load
import coil.request.ImageRequest
import com.idlebloom.app.R
import com.idlebloom.app.data.PhotoRepository
import com.idlebloom.app.data.RemotePhoto
import com.idlebloom.app.data.SourceConfig
import com.idlebloom.app.databinding.DreamContentBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SlideshowPlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val binding: DreamContentBinding,
    private val photoRepository: PhotoRepository = PhotoRepository(context.applicationContext)
) {

    private var playbackJob: Job? = null
    private var refreshJob: Job? = null
    private var transientStatusJob: Job? = null
    private var imageLoader: ImageLoader? = null

    private var sourcePhotos: List<RemotePhoto> = emptyList()
    private var activePlaylist: List<RemotePhoto> = emptyList()
    private var currentIndex: Int = -1
    private var currentConfig: SourceConfig? = null

    fun start(config: SourceConfig) {
        stop()
        currentConfig = config

        if (!config.isReady()) {
            showStatus(
                message = context.getString(R.string.dream_status_missing_config),
                loading = false,
                keepCaption = false
            )
            return
        }

        imageLoader = AuthenticatedImageLoaderFactory.create(context, config)
        refreshFromCacheThenRemote(config)
    }

    fun stop() {
        transientStatusJob?.cancel()
        transientStatusJob = null
        refreshJob?.cancel()
        refreshJob = null
        playbackJob?.cancel()
        playbackJob = null
        imageLoader?.shutdown()
        imageLoader = null
        sourcePhotos = emptyList()
        activePlaylist = emptyList()
        currentIndex = -1
    }

    fun showNextPhoto(): Boolean {
        val config = currentConfig ?: return false
        if (activePlaylist.isEmpty()) return false

        if (currentIndex == -1) {
            displayPhotoAt(index = 0)
        } else {
            showNextPhotoInternal(config)
        }
        restartPlaybackLoop(config)
        return true
    }

    fun showPreviousPhoto(): Boolean {
        val config = currentConfig ?: return false
        if (activePlaylist.isEmpty()) return false

        val previousIndex = when {
            currentIndex == -1 -> activePlaylist.lastIndex
            currentIndex > 0 -> currentIndex - 1
            config.shuffleEnabled && sourcePhotos.isNotEmpty() -> {
                activePlaylist = sourcePhotos.shuffled()
                activePlaylist.lastIndex
            }
            else -> activePlaylist.lastIndex
        }

        displayPhotoAt(previousIndex)
        restartPlaybackLoop(config)
        return true
    }

    private fun refreshFromCacheThenRemote(config: SourceConfig) {
        refreshJob = scope.launch {
            val cachedIndex = photoRepository.loadCachedPhotos(config)
            if (cachedIndex != null) {
                applyPlaylist(
                    photos = cachedIndex.photos,
                    shuffle = config.shuffleEnabled,
                    restartFromBeginning = true
                )
                startPlaybackLoopIfNeeded(config)

                val ageMinutes = TimeUnit.MILLISECONDS.toMinutes(
                    (System.currentTimeMillis() - cachedIndex.savedAtEpochMillis).coerceAtLeast(0L)
                )
                showTemporaryStatus(
                    context.getString(
                        R.string.dream_status_using_cached,
                        cachedIndex.photos.size,
                        ageMinutes
                    )
                )
            } else {
                showStatus(
                    message = context.getString(R.string.dream_status_loading),
                    loading = true,
                    keepCaption = false
                )
            }

            try {
                val discoveryResult = photoRepository.refreshPhotos(config)
                if (!discoveryResult.isSuccess) {
                    if (activePlaylist.isEmpty()) {
                        showStatus(
                            message = context.getString(
                                R.string.dream_status_error,
                                discoveryResult.errorMessage ?: context.getString(R.string.error_unknown)
                            ),
                            loading = false,
                            keepCaption = false
                        )
                    } else {
                        showTemporaryStatus(
                            context.getString(
                                R.string.dream_status_refresh_failed,
                                discoveryResult.errorMessage ?: context.getString(R.string.error_unknown)
                            )
                        )
                    }
                    return@launch
                }

                if (discoveryResult.photos.isEmpty()) {
                    if (activePlaylist.isEmpty()) {
                        showStatus(
                            message = context.getString(R.string.dream_status_no_photos),
                            loading = false,
                            keepCaption = false
                        )
                    } else {
                        showTemporaryStatus(context.getString(R.string.dream_status_remote_empty))
                    }
                    return@launch
                }

                val hadPlaylist = activePlaylist.isNotEmpty()
                applyPlaylist(
                    photos = discoveryResult.photos,
                    shuffle = config.shuffleEnabled,
                    restartFromBeginning = !hadPlaylist || sourcePhotos != discoveryResult.photos
                )
                startPlaybackLoopIfNeeded(config)

                if (hadPlaylist) {
                    showTemporaryStatus(
                        context.getString(
                            R.string.dream_status_refreshed,
                            discoveryResult.photos.size
                        )
                    )
                }
            } catch (cancellationException: CancellationException) {
                throw cancellationException
            } catch (t: Throwable) {
                if (activePlaylist.isEmpty()) {
                    showStatus(
                        message = context.getString(
                            R.string.dream_status_error,
                            t.message ?: context.getString(R.string.error_unknown)
                        ),
                        loading = false,
                        keepCaption = false
                    )
                } else {
                    showTemporaryStatus(
                        context.getString(
                            R.string.dream_status_refresh_failed,
                            t.message ?: context.getString(R.string.error_unknown)
                        )
                    )
                }
            }
        }
    }

    private fun startPlaybackLoopIfNeeded(config: SourceConfig) {
        if (playbackJob?.isActive == true) {
            return
        }

        playbackJob = scope.launch {
            if (activePlaylist.isEmpty()) {
                showStatus(
                    message = context.getString(R.string.dream_status_no_photos),
                    loading = false,
                    keepCaption = false
                )
                return@launch
            }

            if (currentIndex == -1) {
                displayPhotoAt(index = 0)
            }

            while (isActive) {
                delay(config.intervalMillis())
                if (activePlaylist.isEmpty()) {
                    showStatus(
                        message = context.getString(R.string.dream_status_no_photos),
                        loading = false,
                        keepCaption = false
                    )
                    return@launch
                }
                showNextPhotoInternal(config)
            }
        }
    }

    private fun applyPlaylist(
        photos: List<RemotePhoto>,
        shuffle: Boolean,
        restartFromBeginning: Boolean
    ) {
        val currentPhotoUrl = activePlaylist
            .getOrNull(currentIndex)
            ?.url

        sourcePhotos = photos.distinctBy { it.url }
        activePlaylist = if (shuffle) sourcePhotos.shuffled() else sourcePhotos
        currentIndex = when {
            activePlaylist.isEmpty() -> -1
            restartFromBeginning -> -1
            currentPhotoUrl == null -> -1
            else -> activePlaylist.indexOfFirst { it.url == currentPhotoUrl }
                .takeIf { it >= 0 }
                ?: -1
        }
    }

    private fun restartPlaybackLoop(config: SourceConfig) {
        playbackJob?.cancel()
        playbackJob = null
        startPlaybackLoopIfNeeded(config)
    }

    private fun showNextPhotoInternal(config: SourceConfig) {
        val nextIndex = when {
            currentIndex == -1 -> 0
            currentIndex + 1 < activePlaylist.size -> currentIndex + 1
            config.shuffleEnabled && sourcePhotos.isNotEmpty() -> {
                activePlaylist = sourcePhotos.shuffled()
                0
            }
            else -> 0
        }
        displayPhotoAt(nextIndex)
    }

    private fun displayPhotoAt(index: Int) {
        val photo = activePlaylist.getOrNull(index) ?: return
        currentIndex = index
        binding.captionTextView.text = photo.name
        binding.captionTextView.isVisible = true
        displayPhoto(photo)
        prefetchNextPhoto(index)
    }

    private fun displayPhoto(photo: RemotePhoto) {
        binding.photoImageView.load(photo.url, imageLoader ?: return) {
            placeholder(R.drawable.dream_placeholder)
            error(R.drawable.dream_placeholder)
            crossfade(true)
            listener(
                onSuccess = { _, _ ->
                    binding.progressBar.isVisible = false
                    binding.statusTextView.isVisible = false
                },
                onError = { _, _ ->
                    binding.progressBar.isVisible = false
                    showTemporaryStatus(
                        context.getString(
                            R.string.dream_status_image_error,
                            photo.name
                        )
                    )
                }
            )
        }
    }

    private fun prefetchNextPhoto(currentIndex: Int) {
        val loader = imageLoader ?: return
        if (activePlaylist.isEmpty()) return

        val nextPhoto = when {
            currentIndex + 1 < activePlaylist.size -> activePlaylist[currentIndex + 1]
            currentConfig?.shuffleEnabled == true && sourcePhotos.isNotEmpty() -> sourcePhotos.first()
            else -> activePlaylist.firstOrNull()
        } ?: return

        val request = ImageRequest.Builder(context)
            .data(nextPhoto.url)
            .build()
        loader.enqueue(request)
    }

    private fun showStatus(message: String, loading: Boolean, keepCaption: Boolean) {
        transientStatusJob?.cancel()
        binding.progressBar.isVisible = loading
        binding.statusTextView.text = message
        binding.statusTextView.isVisible = true
        if (!keepCaption) {
            binding.captionTextView.isVisible = false
        }
    }

    private fun showTemporaryStatus(message: String) {
        transientStatusJob?.cancel()
        binding.progressBar.isVisible = false
        binding.statusTextView.text = message
        binding.statusTextView.isVisible = true
        transientStatusJob = scope.launch {
            delay(4_000L)
            binding.statusTextView.isVisible = false
        }
    }
}
