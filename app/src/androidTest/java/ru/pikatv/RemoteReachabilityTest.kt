package ru.pikatv

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.domain.*
import ru.pikatv.feature.feed.FeedScreen
import ru.pikatv.feature.reader.*

/** Every visible action must be reachable with the arrows alone, whatever the column width wraps. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RemoteReachabilityTest {
    @get:Rule val compose = createComposeRule()
    private val reactions = listOf(Reaction(15, "❤️", 9), Reaction(26, "💩", 4, canVote = false)) + (1..9).map { Reaction(it, "😄", it) }
    private val post = Post("1", "https://example.invalid/p", "Пост", rating = Rating(123), reactions = reactions, canReact = true)
    private val directions = listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)

    @Before fun remoteMode() = InstrumentationRegistry.getInstrumentation().setInTouchMode(false)

    private fun focusedNode() = compose.onAllNodes(isFocused()).fetchSemanticsNodes().first()

    /** Presses every arrow from every element the arrows have reached so far, starting at [start]. */
    private fun reachable(start: SemanticsNodeInteraction): Set<String> {
        val visited = mutableSetOf<Int>()
        val labels = mutableSetOf<String>()
        val queue = ArrayDeque<SemanticsNodeInteraction>()
        fun visit(node: SemanticsNode) {
            labels += node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "блок"
            if (visited.add(node.id)) queue.addLast(compose.onNode(SemanticsMatcher("node ${node.id}") { it.id == node.id }))
        }
        start.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        visit(focusedNode())
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            directions.forEach { key ->
                node.performSemanticsAction(SemanticsActions.RequestFocus)
                compose.waitForIdle()
                compose.onNode(isFocused()).performKeyInput { pressKey(key) }
                compose.waitForIdle()
                visit(focusedNode())
            }
        }
        return labels
    }

    private fun assertFeedActions(width: Float, scale: Float) {
        var preferences by mutableStateOf(ReaderPreferences(columnWidth = width, textScale = scale))
        compose.setContent {
            FeedScreen(listOf(post), "Горячее", loading = false, error = null, hasMore = false, collapsedIds = emptySet(), bookmarkedIds = emptySet(), onMenu = {},
                onOpenPost = {}, onComments = {}, onToggleBookmark = {}, onToggleCollapse = {}, onLoadMore = {}, onRetry = {}, preferences = preferences)
        }
        val seen = reachable(compose.onNode(hasText("Пост") and hasClickAction()))
        val expected = listOf("▲", "▼", "Комментарии 0", "☆ В избранное", "Свернуть", "+ Эмодзи", "❤️ 9", "😄 9")
        assertTrue("width $width scale $scale reached $seen", seen.containsAll(expected))
        assertFalse("A counter is not a button: $seen", "💩 4" in seen)
    }

    @Test fun feedActionsAt40Percent() = assertFeedActions(.4f, 1f)
    @Test fun feedActionsAt40PercentAndDoubleText() = assertFeedActions(.4f, 2f)
    @Test fun feedActionsAt60Percent() = assertFeedActions(.6f, 1f)
    @Test fun feedActionsAt100Percent() = assertFeedActions(1f, 1f)

    @Test fun postActionsInANarrowColumn() {
        val blocks = post.copy(blocks = listOf(ContentBlock.Text("t", "Текст")))
        compose.setContent {
            ReaderScreen(blocks, ReaderPreferences(columnWidth = .4f), collapsed = false, bookmarked = false, onBack = {}, onComments = {}, onToggleBookmark = {}, onToggleCollapse = {},
                onTextScaleChange = {}, onImage = {}, onVideo = {})
        }
        val seen = reachable(compose.onNode(hasText("← Назад") and hasClickAction()))
        assertTrue("reached $seen", seen.containsAll(listOf("▲", "▼", "Комментарии 0", "☆ В избранное", "Свернуть", "А−", "А+", "+ Эмодзи", "😄 9")))
    }

    @Test fun videoButtonStaysBesideAWidePosterInANarrowColumn() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                ReadingArea(Modifier.width(356.dp)) {
                    BlockContent(ContentBlock.Video("v", "https://example.invalid/v.mp4", posterUrl = "https://example.invalid/v.jpg", aspectRatio = 2.4f, title = "Видео"), 1f, {}, {})
                }
            }
        }
        val action = compose.onNode(hasText("▶  Видео") and hasClickAction())
        action.assertIsDisplayed()
        assertTrue(action.getBoundsInRoot().right - action.getBoundsInRoot().left > 20.dp)
    }

    @Test fun pressesInADialogKeepTheScreenAwake() {
        lateinit var root: View
        val presses = mutableIntStateOf(0)
        val focus = FocusRequester()
        var clicks = 0
        compose.setContent {
            root = LocalView.current
            KeepScreenAwake(presses, millis = 1_500)
            CompositionLocalProvider(LocalRemotePresses provides presses) {
                Box(Modifier.fillMaxSize().onPreviewKeyEvent { presses.intValue++; false }) { Text("Чтение") }
                TrackedDialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    TvAction("Действие", { clicks++ }, Modifier.focusRequester(focus))
                }
            }
        }
        compose.waitUntil(5_000) { root.keepScreenOn }
        compose.mainClock.autoAdvance = false
        repeat(6) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.mainClock.advanceTimeBy(500)
        }
        compose.mainClock.autoAdvance = true
        assertTrue("clicks=$clicks presses=${presses.intValue}", clicks >= 1 && presses.intValue >= 12)
        assertTrue(root.keepScreenOn)
    }
}
