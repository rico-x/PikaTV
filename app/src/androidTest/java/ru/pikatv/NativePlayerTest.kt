package ru.pikatv

import android.content.Context
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.domain.ContentBlock
import ru.pikatv.feature.player.NativeVideoPlayer
import ru.pikatv.feature.player.NativeVideoPlayerContent
import ru.pikatv.feature.player.VideoPlaybackSession
import java.io.FileInputStream

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NativePlayerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun missingUrlShowsDarkNativeErrorAndClosesWithBack() {
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            Text("Место чтения сохранено")
            if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)) {
                NativeVideoPlayer(ContentBlock.Video("missing", null)) { open = false }
            }
        }
        compose.onNodeWithText("У этого видео нет доступной ссылки для воспроизведения.").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Закрыть видео").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("native-video-player").assertDoesNotExist()
        compose.onNodeWithText("Место чтения сохранено").assertIsDisplayed()
    }

    @Test fun networkFailureCanRetryAndCloseWithDpad() {
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            if (open) NativeVideoPlayer(ContentBlock.Video("unreachable", "https://127.0.0.1:1/video.mp4")) { open = false }
            else Text("Чтение продолжается")
        }
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Не удалось воспроизвести эту ссылку.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Повторить видео").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Повторить видео").performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(25_000) { compose.onAllNodesWithText("Не удалось воспроизвести эту ссылку.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Закрыть видео").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Закрыть видео").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Чтение продолжается").assertIsDisplayed()
    }

    @Test fun lifecyclePauseStopAndCloseReleaseTheActualNativePlayer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val session = VideoPlaybackSession(context, "https://127.0.0.1:1/video.mp4")
            try {
                session.start()
                assertNotNull(session.player)
                assertTrue(session.player!!.playWhenReady)
                session.pauseForLifecycle()
                assertFalse(session.player!!.playWhenReady)
                session.stop()
                assertNull(session.player)
                session.start()
                assertNotNull(session.player)
                assertTrue(session.player!!.playWhenReady)
                session.close()
                assertNull(session.player)
                session.start()
                assertNull(session.player)
            } finally { session.close() }
        }
    }

    @Test fun actualActivityLifecyclePausesReleasesAndRecreatesTheOverlayPlayer() {
        lateinit var session: VideoPlaybackSession
        compose.setContent {
            NativeVideoPlayerContent(ContentBlock.Video("activity-lifecycle", "https://127.0.0.1:1/video.mp4"), {}) { session = it }
        }
        compose.waitForIdle()
        val original = session.player
        assertNotNull(original)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertNotNull(session.player)
            assertFalse(session.player!!.playWhenReady)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { assertNull(session.player) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertNotNull(session.player)
            assertTrue(original !== session.player)
            assertTrue(session.player!!.playWhenReady)
        }
    }

    @Test fun privilegedOrCredentialBearingUrlsNeverCreateAPlayer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listOf(null, "", "javascript:alert(1)", "file:///sdcard/private.mp4", "content://private/video", "https://user:secret@example.invalid/video.mp4").forEach { url ->
                val session = VideoPlaybackSession(context, url)
                session.start()
                assertNull(session.player)
                assertFalse(session.canRetry)
                assertNotNull(session.error)
                session.close()
            }
        }
    }

    /** Opt-in public media only: pass -e pikatvVideo <https MP4 URL>; no account/session needed. */
    @Test fun suppliedPublicMp4PlaysPausesAndClosesWithRemote() {
        val url = InstrumentationRegistry.getArguments().getString("pikatvVideo")
        assumeTrue("Set -e pikatvVideo <https MP4 URL> for the real playback check", url != null)
        lateinit var liveSession: VideoPlaybackSession
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            if (open) NativeVideoPlayerContent(ContentBlock.Video("live-native", url), { open = false }) { liveSession = it }
            else Text("Чтение после видео")
        }
        compose.waitUntil(45_000) { liveSession.playbackState == Player.STATE_READY && liveSession.isPlaying }
        fun positionLabel() = compose.onNode(hasText(" / ", substring = true)).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString { it.text }.substringBefore(" / ")
        compose.waitUntil(10_000) { positionLabel() != "0:00" }
        compose.onNodeWithText("Пауза").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Пауза").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Продолжить").assertIsDisplayed()
        val pausedPosition = positionLabel()
        android.os.SystemClock.sleep(1_200)
        compose.waitForIdle()
        assertEquals(pausedPosition, positionLabel())
        capturePlayer("mp4-paused")
        compose.onNodeWithText("Продолжить").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Продолжить").performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Пауза").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("+10 сек").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("+10 сек").performKeyInput { pressKey(Key.Enter) }
        // The short demo reaches its end with playWhenReady=true. One OK must replay immediately.
        compose.waitUntil(15_000) { liveSession.playbackState == Player.STATE_ENDED }
        compose.onNodeWithText("Продолжить").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Продолжить").performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(10_000) { liveSession.isPlaying && liveSession.positionMs in 1 until (liveSession.durationMs ?: Long.MAX_VALUE) }
        compose.onNodeWithText("Закрыть видео").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Закрыть видео").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Чтение после видео").assertIsDisplayed()
    }

    /** Optional negative media input; it is handed straight to Media3, never to a WebView. */
    @Test fun suppliedHtmlUrlFailsNativelyAndKeepsCloseFocused() {
        val url = InstrumentationRegistry.getArguments().getString("pikatvUnsupportedVideo")
        assumeTrue("Set -e pikatvUnsupportedVideo <https HTML URL> for the unsupported input check", url != null)
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            if (open) NativeVideoPlayer(ContentBlock.Video("html-native", url)) { open = false }
            else Text("Чтение после неподдержанного видео")
        }
        compose.waitUntil(45_000) { compose.onAllNodesWithText("Не удалось воспроизвести эту ссылку.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Закрыть видео").assertIsFocused()
        capturePlayer("html-error")
        compose.onNodeWithText("Закрыть видео").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Чтение после неподдержанного видео").assertIsDisplayed()
    }

    private fun capturePlayer(name: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /data/local/tmp/pikatv-player-$name.png").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
    }
}
