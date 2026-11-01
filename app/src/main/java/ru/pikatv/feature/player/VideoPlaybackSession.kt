package ru.pikatv.feature.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.net.URI

/** One overlay owns one player. Android WebView/Pikabu cookies are never supplied to media requests. */
@UnstableApi
internal class VideoPlaybackSession(private val context: Context, rawUrl: String?) {
    private val mediaUrl = rawUrl?.trim()?.takeIf { value ->
        runCatching { URI(value).let { it.scheme in listOf("http", "https") && !it.host.isNullOrBlank() && it.rawUserInfo == null } }.getOrDefault(false)
    }
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var error by mutableStateOf(if (mediaUrl == null) "У этого видео нет доступной ссылки для воспроизведения." else null)
        private set
    var playbackState by mutableStateOf(Player.STATE_IDLE)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var playbackRequested by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf<Long?>(null)
        private set
    val canRetry: Boolean get() = mediaUrl != null
    private var savedPosition = 0L
    private var playWhenResumed = true
    private var closed = false

    fun start() {
        if (closed || mediaUrl == null || player != null) return
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("PikaTV/0.1 AndroidTV")
            .setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(false)
        // A dedicated transport with no account Cookie/Authorization headers; no ad SDK or HTML player.
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        val created = try { ExoPlayer.Builder(context).setMediaSourceFactory(sources).build() }
        catch (_: RuntimeException) { error = "Не удалось запустить нативный плеер. Можно закрыть видео и продолжить чтение."; return }
        player = created
        created.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        created.setHandleAudioBecomingNoisy(true)
        created.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { updatePosition() }
            override fun onPlaybackStateChanged(state: Int) { playbackState = state }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(failure: PlaybackException) {
                // Do not expose signed URLs, request headers or server bodies in the UI/logs.
                error = "Не удалось воспроизвести эту ссылку. Источник может быть недоступен или не поддерживаться (код ${failure.errorCode})."
                isPlaying = false
            }
        })
        error = null
        try {
            created.setMediaItem(MediaItem.fromUri(mediaUrl), savedPosition)
            created.prepare()
            created.playWhenReady = playWhenResumed
            updatePosition()
        } catch (_: RuntimeException) {
            // A URL may need an optional streaming module (HLS/DASH) that this basic build lacks.
            stop()
            error = "Этот источник пока не поддерживается нативным плеером. Можно закрыть видео и продолжить чтение."
        }
    }

    fun pauseForLifecycle() {
        player?.let { playWhenResumed = it.playWhenReady; it.pause(); updatePosition() }
    }

    fun resumeForLifecycle() { player?.playWhenReady = playWhenResumed; updatePosition() }

    fun stop() {
        player?.let { savedPosition = it.currentPosition.coerceAtLeast(0); it.release() }
        player = null
        isPlaying = false
        playbackRequested = false
    }

    fun close() { closed = true; stop() }

    fun togglePlayback() {
        player?.let {
            if (it.playbackState == Player.STATE_ENDED) {
                it.seekTo(0)
                playWhenResumed = true
            } else playWhenResumed = !it.playWhenReady
            it.playWhenReady = playWhenResumed
            updatePosition()
        }
    }

    fun seekBy(deltaMs: Long) {
        player?.let {
            val end = it.duration.takeIf { duration -> duration != C.TIME_UNSET && duration >= 0 } ?: Long.MAX_VALUE
            it.seekTo((it.currentPosition + deltaMs).coerceIn(0, end))
            updatePosition()
        }
    }

    fun retry() {
        if (!canRetry || closed) return
        stop()
        playWhenResumed = true
        start()
    }

    fun updatePosition() {
        player?.let {
            positionMs = it.currentPosition.coerceAtLeast(0)
            durationMs = it.duration.takeIf { duration -> duration != C.TIME_UNSET && duration >= 0 }
            isPlaying = it.isPlaying
            playbackState = it.playbackState
            playbackRequested = it.playWhenReady && it.playbackState != Player.STATE_ENDED
        }
    }
}
