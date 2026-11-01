package ru.pikatv

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.domain.Author
import ru.pikatv.domain.Comment
import ru.pikatv.domain.ContentBlock
import ru.pikatv.domain.Post
import ru.pikatv.domain.Rating
import ru.pikatv.domain.ReaderPreferences
import ru.pikatv.feature.comments.CommentsScreen
import ru.pikatv.feature.feed.FeedScreen
import ru.pikatv.feature.reader.ReaderScreen

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderPreferencesUiTest {
    @get:Rule val compose = createComposeRule()
    private val post = Post("tags", "https://example.invalid/tags", "Пост с тегами",
        blocks = listOf(ContentBlock.Text("body", "Основной текст")),
        tags = listOf("Первый", "Второй", "Третий", "Четвёртый", "Пятый"))

    private fun show(preferences: ReaderPreferences, initialBlockId: String? = null, content: Post = post) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        compose.setContent {
            ReaderScreen(content, preferences, collapsed = false, bookmarked = false,
                onBack = {}, onComments = {}, onToggleBookmark = {}, onToggleCollapse = {},
                onTextScaleChange = {}, onImage = {}, onVideo = {}, initialBlockId = initialBlockId)
        }
    }

    private fun reveal(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    @Test fun partialTagsExpandAndCollapseWithRemoteWithoutLosingFocus() {
        show(ReaderPreferences(tagsMode = 0, textScale = 2f))
        reveal("Все теги (5)")
        compose.onNodeWithText("#Первый").assertExists()
        compose.onNodeWithText("#Третий").assertExists()
        compose.onNodeWithText("#Четвёртый").assertDoesNotExist()
        compose.onNodeWithText("Все теги (5)").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Все теги (5)").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("#Четвёртый").assertExists()
        compose.onNodeWithText("#Пятый").assertExists()
        compose.onNodeWithText("Свернуть теги").assertIsFocused()
        compose.onNodeWithText("Свернуть теги").performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("#Четвёртый").assertDoesNotExist()
        compose.onNodeWithText("Все теги (5)").assertIsFocused()
    }

    @Test fun allTagsCanAppearBeforeContent() {
        show(ReaderPreferences(tagsMode = 2, tagsAtTop = true, textScale = 1f))
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithText("#Пятый").assertIsDisplayed()
        compose.onNodeWithText("Все теги (5)").assertDoesNotExist()
        val tags = compose.onNodeWithText("#Первый").getBoundsInRoot()
        val body = compose.onNodeWithText("Основной текст").getBoundsInRoot()
        assertTrue("Top tags must precede content: $tags vs $body", tags.bottom <= body.top)
    }

    @Test fun allTagsCanAppearAfterContent() {
        show(ReaderPreferences(tagsMode = 2, tagsAtTop = false, textScale = 1f))
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.onNodeWithText("#Пятый").assertIsDisplayed()
        val tags = compose.onNodeWithText("#Первый").getBoundsInRoot()
        val body = compose.onNodeWithText("Основной текст").getBoundsInRoot()
        assertTrue("Bottom tags must follow content: $body vs $tags", body.bottom <= tags.top)
    }

    @Test fun screenTallLastBlockCountsAsTheEndOfThePost() {
        var reached = false
        val tall = post.copy(blocks = listOf(ContentBlock.Text("tall", "Строка\n".repeat(120))))
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        compose.setContent {
            ReaderScreen(tall, ReaderPreferences(tagsMode = 2), collapsed = false, bookmarked = false,
                onBack = {}, onComments = {}, onToggleBookmark = {}, onToggleCollapse = {},
                onTextScaleChange = {}, onImage = {}, onVideo = {}, onReachedEnd = { reached = true })
        }
        compose.waitUntil(5_000) { reached }
    }

    @Test fun voteButtonsDoNotMoveTheDiscussion() {
        val comments = (1..30).map { Comment("c$it", author = Author(name = "Автор $it"), blocks = listOf(ContentBlock.Text("t$it", "Комментарий номер $it")), rating = Rating(it.toLong())) }
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        compose.setContent {
            CommentsScreen(comments, 30, loading = false, error = null, hasMore = false, onBack = {}, onLoadMore = {}, onRetry = {}, onOpenBranch = {}, onImage = {}, onVideo = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Комментарий номер 4").fetchSemanticsNodes().isNotEmpty() }
        val text = compose.onNodeWithText("Комментарий номер 4")
        val before = text.getBoundsInRoot().top
        val up = compose.onAllNodes(hasText("▲") and hasClickAction())[3]
        val down = compose.onAllNodes(hasText("▼") and hasClickAction())[3]
        up.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        org.junit.Assert.assertEquals(before, text.getBoundsInRoot().top)
        down.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        org.junit.Assert.assertEquals(before, text.getBoundsInRoot().top)
    }

    @Test fun columnWidthAppliesToTheFeedAndActionsStayReachable() {
        val posts = listOf(Post("1", "https://pikabu.ru/story/_1", "Пост в узкой колонке", Author(name = "Автор"), listOf(ContentBlock.Text("t", "Текст")), emptyList(), Rating(5), 3))
        compose.setContent {
            FeedScreen(posts, "Горячее", loading = false, error = null, hasMore = false, collapsedIds = emptySet(), bookmarkedIds = emptySet(), onMenu = {}, onOpenPost = {}, onComments = {},
                onToggleBookmark = {}, onToggleCollapse = {}, onLoadMore = {}, onRetry = {}, preferences = ReaderPreferences(columnWidth = .4f))
        }
        val root = compose.onRoot().getBoundsInRoot()
        val title = compose.onNodeWithText("Пост в узкой колонке").getBoundsInRoot()
        org.junit.Assert.assertTrue("Column is narrowed and centred: ${title.left}", title.left > root.right * 0.25f)
        listOf("Комментарии 3", "В избранное", "Свернуть").forEach { label ->
            val action = compose.onNodeWithText(label, substring = true).getBoundsInRoot()
            org.junit.Assert.assertTrue("$label stays inside the column: ${action.right}", action.right < root.right * 0.75f && action.right > action.left)
        }
    }

    @Test fun postBelowTwoThirdsOfTheScreenLeavesRoomForTheNext() {
        fun post(id: String, lines: Int) = Post(id, "https://pikabu.ru/story/_$id", "Пост $id", Author(name = "Автор"),
            listOf(ContentBlock.Text("t$id", (1..lines).joinToString("\n") { "Строка $it" })), emptyList(), Rating(5), 3)
        var posts by mutableStateOf(listOf(post("half", 12), post("next", 1)))
        compose.setContent {
            FeedScreen(posts, "Горячее", loading = false, error = null, hasMore = false, collapsedIds = emptySet(), bookmarkedIds = emptySet(), onMenu = {}, onOpenPost = {}, onComments = {},
                onToggleBookmark = {}, onToggleCollapse = {}, onLoadMore = {}, onRetry = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Пост next").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Пост next").assertIsDisplayed()
        posts = listOf(post("tall", 24), post("next", 1))
        // A lazy list may keep an off-screen item in the semantics tree, so presence alone proves nothing.
        val next = compose.onAllNodesWithText("Пост next")
        compose.waitUntil(5_000) { next.fetchSemanticsNodes().indices.none { next[it].isDisplayed() } }
    }

    @Test fun screenStaysAwakeUntilTheRemoteGoesQuiet() {
        val presses = mutableIntStateOf(0)
        lateinit var view: android.view.View
        compose.setContent { view = LocalView.current; KeepScreenAwake(presses, millis = 1_000) }
        compose.waitUntil(5_000) { view.keepScreenOn }
        compose.waitUntil(5_000) { !view.keepScreenOn }
        presses.intValue++
        compose.waitUntil(5_000) { view.keepScreenOn }
    }

    @Test fun hiddenTagsDoNotCreateAnEmptyRow() {
        show(ReaderPreferences(tagsMode = 1, tagsAtTop = true))
        compose.onNodeWithText("Теги").assertDoesNotExist()
        compose.onNodeWithText("#Первый").assertDoesNotExist()
        compose.onNodeWithText("Все теги (5)").assertDoesNotExist()
        compose.onNodeWithText("Основной текст").assertIsDisplayed()
    }

    @Test fun topTagsDoNotOffsetRestoredBlockFocus() {
        val longPost = post.copy(blocks = (0..29).map { ContentBlock.Text("block-$it", "Абзац $it") })
        show(ReaderPreferences(tagsMode = 2, tagsAtTop = true), initialBlockId = "block-23", content = longPost)
        val target = hasAnyDescendant(hasText("Абзац 23")) and isFocused()
        compose.waitUntil(5_000) { compose.onAllNodes(target).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Абзац 23").assertIsDisplayed()
    }
}
