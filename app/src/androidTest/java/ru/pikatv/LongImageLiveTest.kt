package ru.pikatv

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.domain.ContentBlock
import ru.pikatv.feature.images.ImageViewer
import java.io.File

/** Opt-in public read: `-e pikatvImage <https image URL>`. Without it the test is skipped. */
@RunWith(AndroidJUnit4::class)
class LongImageLiveTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realImageOpensAtWidthAndScrollsWithRemote() {
        val url = InstrumentationRegistry.getArguments().getString("pikatvImage")
        assumeTrue("Set -e pikatvImage <https URL> to run", url != null)
        compose.setContent { ImageViewer(ContentBlock.Image("live", url!!)) {} }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // A TV is never in touch mode; the instrumented activity starts in it, so focus is set explicitly.
        instrumentation.setInTouchMode(false)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        // OK opens the toolbar, whose first action switches to width mode.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("По ширине").fetchSemanticsNodes().isNotEmpty() }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(30_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Не удалось открыть", substring = true).assertDoesNotExist()
        fun offset() = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = offset()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.waitUntil(5_000) { offset() > before }
        // Save the displayed image for visual inspection.
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
            File(instrumentation.targetContext.filesDir, "live-width.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
