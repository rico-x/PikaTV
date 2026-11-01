package ru.pikatv.feature.player

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import ru.pikatv.domain.ContentBlock
import ru.pikatv.feature.reader.TvAction
import ru.pikatv.feature.reader.TvBackground
import ru.pikatv.feature.reader.TvMuted
import ru.pikatv.feature.reader.TvSurface
import ru.pikatv.feature.reader.TvText
import java.util.Locale

/** Native overlay. The caller keeps the reading screen composed beneath it and dismisses onClose. */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NativeVideoPlayer(video: ContentBlock.Video, onClose: () -> Unit) {
    NativeVideoPlayerContent(video, onClose)
}

/** Internal session hook lets instrumentation verify the actual activity lifecycle wiring. */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeVideoPlayerContent(
    video: ContentBlock.Video,
    onClose: () -> Unit,
    onSessionCreated: (VideoPlaybackSession) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session = remember(video.id, video.url) { VideoPlaybackSession(context.applicationContext, video.url) }
    val closeFocus = remember { FocusRequester() }
    var surface by remember { mutableStateOf<PlayerView?>(null) }
    fun close() { session.close(); onClose() }
    BackHandler(onBack = ::close)
    // Error/retry replaces controls; return focus to a stable action instead of the removed button.
    LaunchedEffect(session, session.error) { closeFocus.requestFocus() }
    DisposableEffect(session, lifecycle) {
        onSessionCreated(session)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> session.start()
                Lifecycle.Event.ON_RESUME -> session.resumeForLifecycle()
                Lifecycle.Event.ON_PAUSE -> session.pauseForLifecycle()
                Lifecycle.Event.ON_STOP -> session.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) session.start()
        onDispose { lifecycle.removeObserver(observer); surface?.player = null; session.close() }
    }
    LaunchedEffect(session) {
        while (true) { session.updatePosition(); delay(500) }
    }
    Column(Modifier.fillMaxSize().background(TvBackground).testTag("native-video-player")
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.MediaPlayPause -> { session.togglePlayback(); true }
                Key.MediaRewind -> { session.seekBy(-10_000); true }
                Key.MediaFastForward -> { session.seekBy(10_000); true }
                else -> false
            }
        }.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f).background(TvSurface), contentAlignment = Alignment.Center) {
            if (session.error == null) AndroidView(
                factory = { viewContext -> PlayerView(viewContext).apply {
                    useController = false
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    setShutterBackgroundColor(android.graphics.Color.rgb(16, 25, 22))
                    setEnableComposeSurfaceSyncWorkaround(true)
                    surface = this
                } },
                update = { it.player = session.player; it.keepScreenOn = session.isPlaying },
                onRelease = { it.player = null; it.keepScreenOn = false; if (surface === it) surface = null },
                modifier = Modifier.fillMaxSize(),
            )
            when {
                session.error != null -> Text(session.error!!, color = TvText, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(16.dp))
                session.playbackState == Player.STATE_BUFFERING || session.playbackState == Player.STATE_IDLE ->
                    Text("Загрузка видео…", color = TvText, fontSize = 15.sp, modifier = Modifier.background(TvSurface).padding(8.dp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("${timeLabel(session.positionMs)} / ${session.durationMs?.let(::timeLabel) ?: "—"}", color = TvMuted, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
            TvAction("Закрыть видео", ::close, Modifier.focusRequester(closeFocus))
            if (session.error == null) {
                // Buffering is still a play request; it must not be labelled as a paused video.
                TvAction(if (session.playbackRequested) "Пауза" else "Продолжить", session::togglePlayback)
                TvAction("−10 сек", { session.seekBy(-10_000) })
                TvAction("+10 сек", { session.seekBy(10_000) })
            } else if (session.canRetry) TvAction("Повторить видео", session::retry)
        }
    }
}

private fun timeLabel(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (totalSeconds >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", totalSeconds / 3_600, totalSeconds / 60 % 60, totalSeconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}
