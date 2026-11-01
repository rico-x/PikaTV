package ru.pikatv

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.domain.ContentBlock
import ru.pikatv.domain.Post
import java.io.File

/**
 * Opt-in real path: `-e pikatvVideoPost <story URL with a Pikabu video that has sound>`.
 * Real MainActivity and site adapter, the post's video action, the app's own Dialog and a real Home press.
 * Emulator audio output is a stub, so "no sound" here means no started audio player of this app in `dumpsys audio`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LivePikabuVideoTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val timeLabel = SemanticsMatcher("playback time") { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { Regex("\\d+:\\d\\d / .+").matches(it.text) }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes().decodeToString() }

    /** Works while the activity is stopped: compose-test APIs are not used here. */
    private fun appSoundStarted() = shell("dumpsys audio").lineSequence().any { "u/pid:${Process.myUid()}/" in it && "state:started" in it }

    private fun playbackSeconds(): Int? = compose.onAllNodes(timeLabel).fetchSemanticsNodes().firstOrNull()
        ?.config?.get(SemanticsProperties.Text)?.first()?.text?.substringBefore(" /")?.split(':')?.fold(0) { total, part -> total * 60 + part.toInt() }

    private fun awaitLifecycle(state: Lifecycle.State) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            var current = Lifecycle.State.INITIALIZED
            instrumentation.runOnMainSync { current = compose.activity.lifecycle.currentState }
            if (current == state) return
            Thread.sleep(200)
        }
        fail("Activity did not reach $state")
    }

    private fun screenshot(name: String) = instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
        File(instrumentation.targetContext.filesDir, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun realVideoPlaysStopsOnHomeResumesAndReturnsToReading() {
        val url = InstrumentationRegistry.getArguments().getString("pikatvVideoPost")
        assumeTrue("Set -e pikatvVideoPost <story URL> to run", url != null)
        instrumentation.setInTouchMode(false)
        compose.waitForIdle()
        lateinit var model: PikaViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[PikaViewModel::class.java] }
        val post = Post(Regex("_(\\d+)$").find(url!!)!!.groupValues[1], url, "Пост с видео")
        compose.runOnIdle { model.openPost(post) }
        compose.waitUntil(30_000) { !model.state.value.postLoading }
        assertNull(model.state.value.postError)
        val video = model.state.value.currentPost!!.blocks.filterIsInstance<ContentBlock.Video>().first()
        assertTrue("Adapter link: ${video.url}", video.url.orEmpty().endsWith(".mp4"))

        val action = hasText("▶", substring = true) and hasClickAction()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(action)
        // A post may hold several videos; the first one is enough here.
        compose.onAllNodes(action)[0].performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onAllNodes(action)[0].performKeyInput { pressKey(Key.Enter) }

        compose.waitUntil(30_000) { (playbackSeconds() ?: 0) >= 3 }
        compose.onNodeWithText("Не удалось", substring = true).assertDoesNotExist()
        assertTrue("Sound starts with the video", appSoundStarted())
        screenshot("video-playing")

        val beforeHome = playbackSeconds()!!
        shell("input keyevent KEYCODE_HOME")
        awaitLifecycle(Lifecycle.State.CREATED)
        Thread.sleep(2_000)
        assertFalse("No background sound after Home", appSoundStarted())

        shell("am start -n ${compose.activity.packageName}/.MainActivity --activity-reorder-to-front")
        awaitLifecycle(Lifecycle.State.RESUMED)
        compose.waitUntil(30_000) { (playbackSeconds() ?: -1) > beforeHome }
        assertTrue("Sound resumes in the foreground", appSoundStarted())
        screenshot("video-after-home")

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodes(timeLabel).fetchSemanticsNodes().isEmpty() }
        Thread.sleep(1_000)
        assertFalse("Closing the player stops sound", appSoundStarted())
        compose.onAllNodes(action)[0].assertExists()
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        screenshot("video-closed")
    }
}
