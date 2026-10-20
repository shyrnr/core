package com.maxrave.data.mediaservice

import com.maxrave.common.Config
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.common.QUALITY
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericPlaybackParameters
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.extension.now
import com.maxrave.domain.extension.toGenericMediaItem
import com.maxrave.domain.extension.toSongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.player.MediaPlayerListener
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.domain.utils.toTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSURL

/** A small iOS handler: the app's queue stays in Kotlin and AVPlayer renders its current item. */
internal class IosMediaServiceHandlerImpl(
    private val dataStoreManager: DataStoreManager,
    private val streamRepository: StreamRepository,
    private val coroutineScope: CoroutineScope,
) : MediaPlayerHandler,
    MediaPlayerListener {
    // AVPlayer needs an Apple-playable audio format. Keep the shared preference untouched while
    // asking the existing stream repository for its AAC quality path.
    private val appleAudioDataStoreManager =
        object : DataStoreManager by dataStoreManager {
            override val quality: Flow<String> = flowOf(QUALITY.items.last().toString())
        }

    override val player: MediaPlayerInterface =
        IosAvPlayerAdapter(coroutineScope, ::resolveStreamUrl)

    private val _simpleMediaState = MutableStateFlow<SimpleMediaState>(SimpleMediaState.Initial)
    override val simpleMediaState: StateFlow<SimpleMediaState> = _simpleMediaState.asStateFlow()

    private val _nowPlaying = MutableStateFlow<GenericMediaItem?>(player.currentMediaItem)
    override val nowPlaying: StateFlow<GenericMediaItem?> = _nowPlaying.asStateFlow()

    private val _queueData = MutableStateFlow(QueueData())
    override val queueData: StateFlow<QueueData?> = _queueData.asStateFlow()

    private val _controlState = MutableStateFlow(initialControlState())
    override val controlState: StateFlow<ControlState> = _controlState.asStateFlow()

    private val _nowPlayingState = MutableStateFlow(NowPlayingTrackState.initial())
    override val nowPlayingState: StateFlow<NowPlayingTrackState> = _nowPlayingState.asStateFlow()

    private val _sleepTimerState = MutableStateFlow(SleepTimerState(isDone = false, timeRemaining = 0))
    override val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

    private val _skipSegments = MutableStateFlow<List<SponsorSkipSegments>?>(null)
    override val skipSegments: StateFlow<List<SponsorSkipSegments>?> = _skipSegments.asStateFlow()

    private val _format = MutableStateFlow<com.maxrave.domain.data.entities.NewFormatEntity?>(null)
    override val format: StateFlow<com.maxrave.domain.data.entities.NewFormatEntity?> = _format.asStateFlow()

    private val _currentSongIndex = MutableStateFlow(player.currentMediaItemIndex)
    override val currentSongIndex: StateFlow<Int> = _currentSongIndex.asStateFlow()

    private val _castState = MutableStateFlow(GenericCastState.NOT_CASTING)
    override val castState: StateFlow<GenericCastState> = _castState.asStateFlow()

    override var onUpdateNotification: (List<GenericCommandButton>) -> Unit = {}
    override var pushPlayerError: (PlayerError) -> Unit = {}
    override var showToast: (ToastType) -> Unit = {}

    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var released = false

    init {
        player.addListener(this)
        startProgressUpdate()
    }

    private fun initialControlState() =
        ControlState(
            isPlaying = player.isPlaying,
            isShuffle = player.shuffleModeEnabled,
            repeatState = RepeatState.None,
            isLiked = false,
            isNextAvailable = player.hasNextMediaItem(),
            isPreviousAvailable = player.hasPreviousMediaItem(),
            isCrossfading = false,
            volume = player.volume,
        )

    private suspend fun resolveStreamUrl(mediaItem: GenericMediaItem): String? {
        mediaItem.uri
            ?.takeIf { it != mediaItem.mediaId && it.isDirectMediaUrl() }
            ?.let { return it }

        val videoId = videoIdForStream(mediaItem)
        if (videoId.isBlank()) return null

        val cached = streamRepository.getNewFormat(videoId).firstOrNull()
        val cachedAudioUrl = cached?.audioUrl
        if (
            cached != null &&
            cached.expiredTime > now() &&
            !cachedAudioUrl.isNullOrBlank() &&
            cached.isApplePlayableAudio() &&
            streamRepository.is403Url(cachedAudioUrl).firstOrNull() == false
        ) {
            return cachedAudioUrl
        }

        return streamRepository
            .getStream(
                dataStoreManager = appleAudioDataStoreManager,
                videoId = videoId,
                isDownloading = false,
                isVideo = false,
            ).lastOrNull()
    }

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        when (playerEvent) {
            PlayerEvent.PlayPause -> if (player.playWhenReady) player.pause() else player.play()
            PlayerEvent.Backward -> player.seekBack()
            PlayerEvent.Forward -> player.seekForward()
            PlayerEvent.Stop -> player.stop()
            PlayerEvent.Next -> player.seekToNext()
            PlayerEvent.Previous -> player.seekToPrevious()
            PlayerEvent.SkipToPrevious -> player.seekToPreviousMediaItem()
            PlayerEvent.Shuffle -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            PlayerEvent.Repeat -> {
                player.repeatMode =
                    when (player.repeatMode) {
                        PlayerConstants.REPEAT_MODE_OFF -> PlayerConstants.REPEAT_MODE_ALL
                        PlayerConstants.REPEAT_MODE_ALL -> PlayerConstants.REPEAT_MODE_ONE
                        else -> PlayerConstants.REPEAT_MODE_OFF
                    }
            }
            is PlayerEvent.UpdateProgress -> {
                val progress = playerEvent.newProgress.coerceIn(0f, 100f)
                player.seekTo((player.duration * progress / 100f).toLong())
            }
            is PlayerEvent.UpdateVolume -> player.volume = playerEvent.newVolume.coerceIn(0f, 1f)
            PlayerEvent.ToggleLike -> like(!controlState.value.isLiked)
        }
        refreshPlayerState()
    }

    override fun toggleRadio() = Unit

    override fun toggleLike() = like(!controlState.value.isLiked)

    override fun like(liked: Boolean) {
        _controlState.value = _controlState.value.copy(isLiked = liked)
    }

    override fun resetSongAndQueue() = reset()

    override fun sleepStart(minutes: Int) {
        sleepTimerJob?.cancel()
        _sleepTimerState.value = SleepTimerState(isDone = false, timeRemaining = minutes.coerceAtLeast(0))
        sleepTimerJob =
            coroutineScope.launch {
                delay(minutes.coerceAtLeast(0).toLong() * 60_000L)
                player.pause()
                _sleepTimerState.value = SleepTimerState(isDone = true, timeRemaining = 0)
            }
    }

    override fun sleepStop() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerState.value = SleepTimerState(isDone = false, timeRemaining = 0)
    }

    override fun removeMediaItem(position: Int) {
        player.removeMediaItem(position)
        val tracks = _queueData.value.data.listTracks
        if (position in tracks.indices) {
            _queueData.value = _queueData.value.copy(data = _queueData.value.data.copy(listTracks = tracks.toMutableList().apply { removeAt(position) }))
        }
        refreshPlayerState()
    }

    override fun addMediaItem(
        mediaItem: GenericMediaItem,
        playWhenReady: Boolean,
    ) {
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = playWhenReady
        refreshPlayerState()
    }

    override fun clearMediaItems() {
        player.clearMediaItems()
        refreshPlayerState()
    }

    override fun addMediaItemList(mediaItemList: List<GenericMediaItem>) {
        mediaItemList.forEach { player.addMediaItem(it) }
        refreshPlayerState()
    }

    override fun playMediaItemInMediaSource(index: Int) {
        player.seekTo(index, 0L)
        player.prepare()
        player.playWhenReady = true
    }

    override fun currentSongIndex(): Int = player.currentMediaItemIndex

    override fun currentOrderIndex(): Int {
        val mediaId = player.currentMediaItem?.mediaId?.removePrefix(MERGING_DATA_TYPE.VIDEO) ?: return -1
        return _queueData.value.data.listTracks.indexOfFirst { it.videoId == mediaId }
    }

    override suspend fun swap(
        from: Int,
        to: Int,
    ) {
        moveQueueItem(from, to)
    }

    override fun resetCrossfade() {
        _controlState.value = _controlState.value.copy(isCrossfading = false)
    }

    override fun shufflePlaylist(randomTrackIndex: Int) {
        val tracks = _queueData.value.data.listTracks
        if (tracks.isEmpty()) return
        val shuffled = tracks.shuffled()
        _queueData.value = _queueData.value.copy(data = _queueData.value.data.copy(listTracks = shuffled))
        coroutineScope.launch { load(index = shuffled.indexOfFirst { it.videoId == tracks.getOrNull(randomTrackIndex)?.videoId }) }
    }

    override fun loadMore() = Unit

    override fun getRelated(videoId: String) = Unit

    override fun setQueueData(queueData: QueueData.Data) {
        _queueData.value = _queueData.value.copy(data = queueData)
    }

    override fun getCurrentMediaItem(): GenericMediaItem? = player.currentMediaItem

    override suspend fun moveItemUp(position: Int) = moveQueueItem(position, position - 1)

    override suspend fun moveItemDown(position: Int) = moveQueueItem(position, position + 1)

    override suspend fun moveItemToPlayNext(position: Int) {
        val current = currentOrderIndex()
        if (current < 0 || position == current || position == current + 1) return
        moveQueueItem(position, if (position < current) current else current + 1)
    }

    private fun moveQueueItem(from: Int, to: Int) {
        val tracks = _queueData.value.data.listTracks.toMutableList()
        if (from !in tracks.indices || to !in tracks.indices || from == to) return
        player.moveMediaItem(from, to)
        tracks.add(to, tracks.removeAt(from))
        _queueData.value = _queueData.value.copy(data = _queueData.value.data.copy(listTracks = tracks))
        refreshPlayerState()
    }

    override fun addFirstMediaItemToIndex(
        mediaItem: GenericMediaItem?,
        index: Int,
    ) {
        if (mediaItem == null) return
        player.addMediaItem(index.coerceIn(0, player.mediaItemCount), mediaItem)
        refreshPlayerState()
    }

    override fun reset() {
        player.clearMediaItems()
        _queueData.value = QueueData()
        _nowPlayingState.value = NowPlayingTrackState.initial()
        _simpleMediaState.value = SimpleMediaState.Initial
        refreshPlayerState()
    }

    override suspend fun load(
        downloaded: Int,
        index: Int?,
    ) {
        loadQueueAt(index, playWhenReady = true)
    }

    override suspend fun loadMoreCatalog(
        listTrack: ArrayList<Track>,
        isAddToQueue: Boolean,
    ) {
        val existingIds = _queueData.value.data.listTracks.mapTo(mutableSetOf()) { it.videoId }
        val added = listTrack.filterNot { it.videoId in existingIds }
        if (added.isEmpty()) return
        _queueData.value =
            _queueData.value.copy(
                queueState = QueueData.StateSource.STATE_INITIALIZED,
                data = _queueData.value.data.copy(listTracks = _queueData.value.data.listTracks + added),
            )
        added.forEach { player.addMediaItem(it.toGenericMediaItem()) }
        refreshPlayerState()
    }

    override suspend fun updateCatalog(
        downloaded: Int,
        index: Int?,
    ): Boolean {
        if (_queueData.value.data.listTracks.isEmpty()) return false
        loadQueueAt(index, playWhenReady = true)
        return true
    }

    override fun addQueueToPlayer() {
        coroutineScope.launch { load() }
    }

    override fun loadPlaylistOrAlbum(index: Int?) {
        coroutineScope.launch { load(index = index) }
    }

    override fun setCurrentSongIndex(index: Int) {
        player.seekTo(index, 0L)
    }

    override suspend fun playNext(track: Track) {
        val tracks = _queueData.value.data.listTracks.toMutableList()
        val position = (player.currentMediaItemIndex + 1).coerceIn(0, tracks.size)
        tracks.add(position, track)
        player.addMediaItem(position, track.toGenericMediaItem())
        _queueData.value = _queueData.value.copy(data = _queueData.value.data.copy(listTracks = tracks))
        refreshPlayerState()
    }

    override suspend fun <T> loadMediaItem(
        anyTrack: T,
        type: String,
        index: Int?,
    ) {
        val track =
            when (anyTrack) {
                is Track -> anyTrack
                is SongEntity -> anyTrack.toTrack()
                else -> return
            }

        if (type == Config.SONG_CLICK || type == Config.VIDEO_CLICK || type == Config.SHARE) {
            _queueData.value = QueueData(data = QueueData.Data(listTracks = listOf(track), firstPlayedTrack = track))
        } else if (_queueData.value.data.listTracks.isEmpty()) {
            _queueData.value = QueueData(data = QueueData.Data(listTracks = listOf(track), firstPlayedTrack = track))
        }

        val queueTracks = _queueData.value.data.listTracks
        val selectedIndex = index?.takeIf { it in queueTracks.indices }
            ?: queueTracks.indexOfFirst { it.videoId == track.videoId }.takeIf { it >= 0 }
            ?: 0
        loadQueueAt(selectedIndex, playWhenReady = type != Config.RECOVER_TRACK_QUEUE)
    }

    private fun loadQueueAt(
        index: Int?,
        playWhenReady: Boolean,
    ) {
        val tracks = _queueData.value.data.listTracks
        if (tracks.isEmpty()) return
        val requestedIndex = index?.takeIf { it in tracks.indices }
        val firstPlayedIndex = _queueData.value.data.firstPlayedTrack?.let { first -> tracks.indexOfFirst { it.videoId == first.videoId } }
        val selectedIndex = requestedIndex ?: firstPlayedIndex?.takeIf { it >= 0 } ?: 0

        player.clearMediaItems()
        tracks.forEach { player.addMediaItem(it.toGenericMediaItem()) }
        player.seekTo(selectedIndex, 0L)
        player.prepare()
        player.playWhenReady = playWhenReady
        _queueData.value = _queueData.value.copy(queueState = QueueData.StateSource.STATE_INITIALIZED)
        refreshPlayerState()
    }

    override fun getPlayerDuration(): Long = player.duration

    override fun getProgress(): Long = player.currentPosition

    override fun startProgressUpdate() {
        if (progressJob?.isActive == true || released) return
        progressJob =
            coroutineScope.launch(Dispatchers.Main) {
                while (true) {
                    refreshPlayerState()
                    delay(250L)
                }
            }
    }

    override fun stopProgressUpdate() {
        progressJob?.cancel()
        progressJob = null
        refreshPlayerState()
    }

    override fun startBufferedUpdate() = Unit

    override fun stopBufferedUpdate() = Unit

    override fun mayBeNormalizeVolume() = Unit

    override fun mayBeSaveRecentSong(runBlocking: Boolean) = Unit

    override fun mayBeSavePlaybackState() = Unit

    override fun mayBeRestoreQueue() = Unit

    override suspend fun restoreQueueAndPlay(): Boolean = false

    override fun shouldReleaseOnTaskRemoved(): Boolean = false

    override fun release() {
        if (released) return
        released = true
        progressJob?.cancel()
        progressJob = null
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        player.removeListener(this)
        player.release()
        _nowPlaying.value = null
        _nowPlayingState.value = NowPlayingTrackState.initial()
        _simpleMediaState.value = SimpleMediaState.Initial
    }

    override fun onPlaybackStateChanged(playbackState: Int) = refreshPlayerState()

    override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPlayerState()

    override fun onMediaItemTransition(
        mediaItem: GenericMediaItem?,
        reason: Int,
    ) = refreshPlayerState()

    override fun onTimelineChanged(
        list: List<GenericMediaItem>,
        reason: String,
    ) = refreshPlayerState()

    override fun onPlayerError(error: PlayerError) {
        pushPlayerError(error)
        refreshPlayerState()
    }

    private fun refreshPlayerState() {
        if (released) return
        val item = player.currentMediaItem
        _nowPlaying.value = item
        _currentSongIndex.value = player.currentMediaItemIndex

        if (item == null) {
            _nowPlayingState.value = NowPlayingTrackState.initial()
            _simpleMediaState.value = SimpleMediaState.Initial
        } else {
            val videoId = videoIdForStream(item)
            val track = _queueData.value.data.listTracks.firstOrNull { it.videoId == videoId }
            _nowPlayingState.value = NowPlayingTrackState(item, track, track?.toSongEntity() ?: item.toSongEntity())
            _simpleMediaState.value =
                when (player.playbackState) {
                    PlayerConstants.STATE_BUFFERING -> SimpleMediaState.Loading(player.bufferedPercentage, player.duration)
                    PlayerConstants.STATE_ENDED -> SimpleMediaState.Ended
                    PlayerConstants.STATE_READY -> if (player.isPlaying) SimpleMediaState.Progress(player.currentPosition) else SimpleMediaState.Ready(player.duration)
                    else -> SimpleMediaState.Initial
                }
        }

        _controlState.value =
            _controlState.value.copy(
                isPlaying = player.isPlaying,
                isShuffle = player.shuffleModeEnabled,
                repeatState = repeatState(player.repeatMode),
                isNextAvailable = player.hasNextMediaItem(),
                isPreviousAvailable = player.hasPreviousMediaItem(),
                volume = player.volume,
            )
    }

    private fun repeatState(mode: Int): RepeatState =
        when (mode) {
            PlayerConstants.REPEAT_MODE_ONE -> RepeatState.One
            PlayerConstants.REPEAT_MODE_ALL -> RepeatState.All
            else -> RepeatState.None
        }
}

internal class IosAvPlayerAdapter(
    private val coroutineScope: CoroutineScope,
    private val resolveStreamUrl: suspend (GenericMediaItem) -> String?,
) : MediaPlayerInterface {
    private val avPlayer = AVPlayer()
    private val queue = mutableListOf<GenericMediaItem>()
    private val listeners = linkedSetOf<MediaPlayerListener>()
    private var currentIndex = -1
    private var prepared = false
    private var released = false
    private var pendingPositionMs = 0L
    private var loadedItem: GenericMediaItem? = null
    private var resolvingItem: GenericMediaItem? = null
    private var resolutionGeneration = 0
    private var resolutionJob: Job? = null
    private var audioSessionActivated = false

    override val isPlaying: Boolean get() = avPlayer.rate > 0f
    override val currentPosition: Long
        get() {
            val seconds = avPlayer.currentTime().seconds
            return if (seconds.isFinite() && seconds >= 0.0) (seconds * 1000.0).toLong() else pendingPositionMs
        }
    override val duration: Long
        get() {
            val seconds = avPlayer.currentItem?.duration?.seconds ?: return 0L
            return if (seconds.isFinite() && seconds >= 0.0) (seconds * 1000.0).toLong() else 0L
        }
    override val bufferedPosition: Long get() = currentPosition
    override val bufferedPercentage: Int get() = if (playbackState == PlayerConstants.STATE_READY) 100 else 0
    override val currentMediaItem: GenericMediaItem? get() = queue.getOrNull(currentIndex)
    override val currentMediaItemIndex: Int get() = currentIndex
    override val mediaItemCount: Int get() = queue.size
    override val contentPosition: Long get() = currentPosition
    override val playbackState: Int
        get() {
            val nativeItem = avPlayer.currentItem
            if (nativeItem == null) {
                return if (resolvingItem != null) PlayerConstants.STATE_BUFFERING else PlayerConstants.STATE_IDLE
            }
            return when (nativeItem.status) {
                AVPlayerItemStatusReadyToPlay -> PlayerConstants.STATE_READY
                AVPlayerItemStatusFailed -> PlayerConstants.STATE_IDLE
                else -> PlayerConstants.STATE_BUFFERING
            }
        }

    override var shuffleModeEnabled: Boolean = false
        set(value) {
            field = value
            notifyListeners { it.onShuffleModeEnabledChanged(value, getCurrentMediaTimeLine()) }
        }

    override var repeatMode: Int = PlayerConstants.REPEAT_MODE_OFF
    override var playWhenReady: Boolean = false
        set(value) {
            field = value
            if (value) {
                activateAudioSession()
                if (!prepared) prepare() else prepareCurrentItem()
                if (loadedItem == currentMediaItem) avPlayer.play()
            } else {
                avPlayer.pause()
                notifyListeners { it.onIsPlayingChanged(false) }
            }
        }

    override var playbackParameters: GenericPlaybackParameters = GenericPlaybackParameters.DEFAULT
    override val audioSessionId: Int = PlayerConstants.AUDIO_SESSION_ID_UNSET

    override var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolume()
            notifyListeners { it.onVolumeChanged(field) }
        }

    override var sleepFadeFactor: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolume()
        }

    override var crossfadeSuppressed: Boolean = false
    override var albumTrackIds: Set<String> = emptySet()
    override var skipSilenceEnabled: Boolean = false

    private fun activateAudioSession() {
        if (audioSessionActivated) return
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
        audioSessionActivated = true
    }

    private fun applyVolume() {
        avPlayer.volume = volume * sleepFadeFactor
    }

    override fun play() {
        playWhenReady = true
    }

    override fun pause() {
        playWhenReady = false
    }

    override fun stop() {
        pause()
        seekTo(0L)
    }

    override fun seekTo(positionMs: Long) {
        pendingPositionMs = positionMs.coerceAtLeast(0L)
        if (avPlayer.currentItem != null) {
            avPlayer.seekToTime(CMTimeMakeWithSeconds(pendingPositionMs / 1000.0, 1000))
        }
        notifyListeners { it.onSeeked(pendingPositionMs) }
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (mediaItemIndex !in queue.indices) return
        val changed = currentIndex != mediaItemIndex
        currentIndex = mediaItemIndex
        pendingPositionMs = positionMs.coerceAtLeast(0L)
        if (changed) {
            loadedItem = null
            resolvingItem = null
            if (prepared) prepareCurrentItem()
            notifyListeners { it.onMediaItemTransition(currentMediaItem, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_SEEK) }
        } else {
            seekTo(pendingPositionMs)
        }
    }

    override fun seekBack() = seekTo((currentPosition - SEEK_INTERVAL_MS).coerceAtLeast(0L))

    override fun seekForward() = seekTo((currentPosition + SEEK_INTERVAL_MS).let { if (duration > 0) it.coerceAtMost(duration) else it })

    override fun seekToNext() {
        if (shuffleModeEnabled && queue.size > 1) {
            queue.indices.filter { it != currentIndex }.randomOrNull()?.let { seekTo(it, 0L) }
        } else if (currentIndex + 1 in queue.indices) {
            seekTo(currentIndex + 1, 0L)
        } else if (repeatMode == PlayerConstants.REPEAT_MODE_ALL && queue.isNotEmpty()) {
            seekTo(0, 0L)
        }
    }

    override fun seekToPrevious() {
        if (currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
            seekTo(0L)
        } else {
            seekToPreviousMediaItem()
        }
    }

    override fun seekToPreviousMediaItem() {
        if (shuffleModeEnabled && queue.size > 1) {
            queue.indices.filter { it != currentIndex }.randomOrNull()?.let { seekTo(it, 0L) }
        } else if (currentIndex > 0) {
            seekTo(currentIndex - 1, 0L)
        } else if (repeatMode == PlayerConstants.REPEAT_MODE_ALL && queue.isNotEmpty()) {
            seekTo(queue.lastIndex, 0L)
        }
    }

    override fun prepare() {
        if (released) return
        prepared = true
        prepareCurrentItem()
    }

    private fun prepareCurrentItem() {
        val mediaItem = currentMediaItem ?: return
        if (released) return
        if (loadedItem == mediaItem && avPlayer.currentItem != null) {
            if (playWhenReady) avPlayer.play()
            return
        }
        if (resolvingItem == mediaItem && resolutionJob?.isActive == true) return

        resolutionJob?.cancel()
        avPlayer.pause()
        avPlayer.replaceCurrentItemWithPlayerItem(null)
        loadedItem = null
        resolvingItem = mediaItem
        val generation = ++resolutionGeneration
        notifyListeners {
            it.onIsLoadingChanged(true)
            it.onPlaybackStateChanged(PlayerConstants.STATE_BUFFERING)
        }
        resolutionJob =
            coroutineScope.launch {
                val url =
                    try {
                        resolveStreamUrl(mediaItem)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        withContext(Dispatchers.Main) {
                            if (generation == resolutionGeneration && !released) {
                                resolvingItem = null
                                notifyResolutionError(error.message ?: "Could not resolve audio stream")
                            }
                        }
                        return@launch
                    }

                withContext(Dispatchers.Main) {
                    if (released || generation != resolutionGeneration) return@withContext
                    if (url.isNullOrBlank()) {
                        resolvingItem = null
                        notifyResolutionError("No playable audio stream was returned")
                        return@withContext
                    }
                    val nativeUrl = NSURL.URLWithString(url)
                    if (nativeUrl == null) {
                        resolvingItem = null
                        notifyResolutionError("The resolved audio URL is invalid")
                        return@withContext
                    }
                    avPlayer.replaceCurrentItemWithPlayerItem(AVPlayerItem(uRL = nativeUrl))
                    loadedItem = mediaItem
                    resolvingItem = null
                    if (pendingPositionMs > 0L) {
                        avPlayer.seekToTime(CMTimeMakeWithSeconds(pendingPositionMs / 1000.0, 1000))
                    }
                    if (playWhenReady) avPlayer.play()
                    notifyListeners {
                        it.onIsLoadingChanged(false)
                        it.onPlaybackStateChanged(playbackState)
                        it.onMediaItemTransition(currentMediaItem, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                        it.onIsPlayingChanged(isPlaying)
                    }
                }
            }
    }

    private fun notifyResolutionError(message: String) {
        notifyListeners {
            it.onIsLoadingChanged(false)
            it.onPlaybackStateChanged(PlayerConstants.STATE_IDLE)
            it.onPlayerError(
                PlayerError(
                    errorCode = PlayerConstants.ERROR_CODE_TIMEOUT,
                    errorCodeName = "IOS_STREAM_RESOLUTION_FAILED",
                    message = message,
                ),
            )
        }
    }

    override fun setMediaItem(mediaItem: GenericMediaItem) {
        clearMediaItems()
        queue.add(mediaItem)
        currentIndex = 0
        notifyTimelineChanged()
    }

    override fun addMediaItem(mediaItem: GenericMediaItem) {
        addMediaItem(queue.size, mediaItem)
    }

    override fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        val insertAt = index.coerceIn(0, queue.size)
        queue.add(insertAt, mediaItem)
        if (currentIndex < 0) currentIndex = 0 else if (insertAt <= currentIndex) currentIndex++
        notifyTimelineChanged()
    }

    override fun removeMediaItem(index: Int) {
        if (index !in queue.indices) return
        val wasCurrent = index == currentIndex
        queue.removeAt(index)
        when {
            queue.isEmpty() -> clearMediaItems()
            index < currentIndex -> currentIndex--
            wasCurrent -> currentIndex = index.coerceAtMost(queue.lastIndex)
        }
        if (wasCurrent && queue.isNotEmpty() && prepared) {
            loadedItem = null
            prepareCurrentItem()
        }
        notifyTimelineChanged()
        notifyListeners { it.onMediaItemTransition(currentMediaItem, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
    }

    override fun removeMediaItems(
        fromIndex: Int,
        toIndex: Int,
        onRemoved: (removedIds: List<String>) -> Unit,
    ) {
        if (fromIndex < 0 || toIndex > queue.size || fromIndex >= toIndex) {
            onRemoved(emptyList())
            return
        }
        val removed = queue.subList(fromIndex, toIndex).map { it.mediaId }
        val oldCurrentIndex = currentIndex
        val currentRemoved = oldCurrentIndex in fromIndex until toIndex
        queue.subList(fromIndex, toIndex).clear()
        if (queue.isEmpty()) {
            clearMediaItems()
            onRemoved(removed)
            return
        }
        currentIndex =
            when {
                oldCurrentIndex < 0 -> 0
                currentRemoved -> fromIndex.coerceAtMost(queue.lastIndex)
                oldCurrentIndex >= toIndex -> oldCurrentIndex - (toIndex - fromIndex)
                else -> oldCurrentIndex
            }
        if (currentRemoved) {
            loadedItem = null
            if (prepared) prepareCurrentItem()
        }
        notifyTimelineChanged()
        onRemoved(removed)
    }

    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        val oldCurrentIndex = currentIndex
        queue.add(toIndex, queue.removeAt(fromIndex))
        currentIndex =
            when {
                oldCurrentIndex < 0 -> -1
                oldCurrentIndex == fromIndex -> toIndex
                fromIndex < oldCurrentIndex && toIndex >= oldCurrentIndex -> oldCurrentIndex - 1
                fromIndex > oldCurrentIndex && toIndex <= oldCurrentIndex -> oldCurrentIndex + 1
                else -> oldCurrentIndex
            }
        notifyTimelineChanged()
    }

    override fun moveShuffledItem(
        fromShuffledIndex: Int,
        toShuffledIndex: Int,
    ) = moveMediaItem(fromShuffledIndex, toShuffledIndex)

    override fun clearMediaItems() {
        resolutionGeneration++
        resolutionJob?.cancel()
        resolutionJob = null
        resolvingItem = null
        loadedItem = null
        prepared = false
        playWhenReady = false
        avPlayer.replaceCurrentItemWithPlayerItem(null)
        queue.clear()
        currentIndex = -1
        pendingPositionMs = 0L
        notifyTimelineChanged()
        notifyListeners { it.onMediaItemTransition(null, PlayerConstants.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
    }

    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {
        if (index !in queue.indices) return
        queue[index] = mediaItem
        if (index == currentIndex) {
            loadedItem = null
            if (prepared) prepareCurrentItem()
        }
        notifyTimelineChanged()
    }

    override fun getMediaItemAt(index: Int): GenericMediaItem? = queue.getOrNull(index)

    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> = queue.toList()

    override fun getUnshuffledIndex(shuffledIndex: Int): Int = shuffledIndex.takeIf { it in queue.indices } ?: -1

    override fun hasNextMediaItem(): Boolean =
        (shuffleModeEnabled && queue.size > 1) || currentIndex + 1 in queue.indices || (repeatMode == PlayerConstants.REPEAT_MODE_ALL && queue.isNotEmpty())

    override fun hasPreviousMediaItem(): Boolean =
        (shuffleModeEnabled && queue.size > 1) || currentIndex > 0 || (repeatMode == PlayerConstants.REPEAT_MODE_ALL && queue.isNotEmpty())

    override fun addListener(listener: MediaPlayerListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: MediaPlayerListener) {
        listeners.remove(listener)
    }

    override fun release() {
        if (released) return
        released = true
        resolutionGeneration++
        resolutionJob?.cancel()
        resolutionJob = null
        playWhenReady = false
        avPlayer.pause()
        avPlayer.replaceCurrentItemWithPlayerItem(null)
        if (audioSessionActivated) {
            AVAudioSession.sharedInstance().setActive(false, error = null)
            audioSessionActivated = false
        }
        queue.clear()
        currentIndex = -1
        listeners.clear()
    }

    private fun notifyTimelineChanged() {
        notifyListeners { it.onTimelineChanged(getCurrentMediaTimeLine(), "ios-queue") }
    }

    private inline fun notifyListeners(block: (MediaPlayerListener) -> Unit) {
        listeners.toList().forEach(block)
    }

}

internal fun videoIdForStream(mediaItem: GenericMediaItem): String =
    mediaItem.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)

private fun String.isDirectMediaUrl(): Boolean =
    startsWith("https://", ignoreCase = true) ||
        startsWith("http://", ignoreCase = true) ||
        startsWith("file://", ignoreCase = true)

private fun NewFormatEntity.isApplePlayableAudio(): Boolean {
    val mime = mimeType.orEmpty().lowercase()
    val url = audioUrl.orEmpty().substringBefore('?').lowercase()
    return mime.contains("audio/mp4") ||
        mime.contains("audio/aac") ||
        mime.contains("audio/mpeg") ||
        mime.contains("mpegurl") ||
        url.endsWith(".m3u8") ||
        url.endsWith(".m4a") ||
        url.endsWith(".mp3")
}

private const val SEEK_INTERVAL_MS = 10_000L
private const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
