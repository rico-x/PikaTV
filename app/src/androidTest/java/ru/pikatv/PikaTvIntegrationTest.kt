package ru.pikatv

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.domain.*
import ru.pikatv.feature.reader.TvAccents
import ru.pikatv.feature.reader.TvSchemes
import ru.pikatv.feature.reader.VoteMinus
import ru.pikatv.feature.reader.VotePlus

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PikaTvIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var model: PikaViewModel
    private val repository = FakeRepository()
    private lateinit var root: android.view.View

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("pikatv_local", Context.MODE_PRIVATE).edit().clear().commit()
        model = PikaViewModel(app, repository, loadInitialFeed = true)
        compose.setContent { root = LocalView.current; PikaTvApp(model) }
        compose.waitUntil(5_000) { model.state.value.posts.isNotEmpty() }
    }

    /** Scrolls lists item by item until an off-screen element is displayed, as ↑/↓ on the remote would.
     *  A lazy list may keep an off-screen item in the semantics tree, so presence alone is not enough. */
    private fun reveal(matcher: SemanticsMatcher) {
        val found = compose.onAllNodes(matcher)
        fun displayed() = found.fetchSemanticsNodes().indices.any { found[it].isDisplayed() }
        if (displayed()) return
        val lists = compose.onAllNodes(hasScrollToIndexAction())
        for (list in lists.fetchSemanticsNodes().indices) for (index in 0 until 40) {
            if (runCatching { lists[list].performScrollToIndex(index) }.isFailure) break
            if (displayed()) return
        }
    }

    private fun focusAndPress(text: String, key: Key = Key.Enter) {
        reveal(hasText(text, substring = true) and hasClickAction())
        compose.onNode(hasText(text, substring = true) and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(hasText(text, substring = true) and hasClickAction()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun drawerDpadSelectAndDismiss() {
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        compose.onNodeWithText("PikaTV").assertIsDisplayed()
        focusAndPress("Лучшее")
        compose.onNodeWithText("Пост лучшего").assertIsDisplayed()
        compose.onNodeWithText("PikaTV").assertDoesNotExist()
        focusAndPress("Пост лучшего", Key.DirectionLeft)
        focusAndPress("Горячее")
        compose.onNodeWithText("Смешанный пост").assertIsDisplayed()
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        compose.onNodeWithText("PikaTV").assertIsDisplayed()
        focusAndPress("Горячее", Key.DirectionRight)
        compose.onNodeWithText("PikaTV").assertDoesNotExist()
        compose.onNodeWithText("Смешанный пост").assertIsDisplayed()
    }

    @Test fun readingScaleCollapseBookmarkAndHistory() {
        focusAndPress("Смешанный пост")
        reveal(hasText("Текст 100%"))
        compose.onNodeWithText("Текст 100%").assertExists()
        focusAndPress("А+")
        compose.onNodeWithText("Текст 115%").assertExists()
        focusAndPress("☆ В избранное")
        compose.onNodeWithText("★ В избранном").assertExists()
        val collapse = hasText("Свернуть") and hasClickAction()
        reveal(collapse)
        compose.onNode(collapse).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(collapse).performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText("Развернуть").assertExists()
        compose.onNodeWithText("Синтетический текст", substring = true).assertDoesNotExist()
        val expand = hasText("Развернуть") and hasClickAction()
        compose.onNode(expand).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(expand).performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        reveal(hasText("Синтетический текст", substring = true))
        compose.onNodeWithText("Синтетический текст", substring = true).assertExists()
        focusAndPress("← Назад")
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("Избранное")
        compose.onNodeWithText("Смешанный пост").assertExists()
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("История")
        compose.onNodeWithText("Смешанный пост").assertExists()
    }

    @Test fun commentsCollapseAndLoadMore() {
        focusAndPress("Комментарии 3")
        compose.onNodeWithText("Корневой комментарий").assertExists()
        compose.onNodeWithText("Вложенный комментарий").assertExists()
        focusAndPress("Свернуть ветку")
        compose.onNodeWithText("Вложенный комментарий").assertDoesNotExist()
        focusAndPress("Развернуть ветку")
        compose.onNodeWithText("Вложенный комментарий").assertExists()
        focusAndPress("Свернуть все")
        compose.onNodeWithText("Вложенный комментарий").assertDoesNotExist()
        focusAndPress("Развернуть все")
        compose.onNodeWithText("Вложенный комментарий").assertExists()
        // Reaching the last comments loads the next portion without a button.
        compose.waitUntil(5_000) { model.state.value.pendingCommentIds.isEmpty() && model.state.value.comments.size == 3 }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Догруженный комментарий"))
        compose.onNodeWithText("Догруженный комментарий").assertExists()
    }

    @Test fun collapsedBranchAndFocusSurviveImageViewer() {
        focusAndPress("Комментарии 3")
        focusAndPress("Свернуть ветку")
        compose.onNodeWithText("Вложенный комментарий").assertDoesNotExist()
        val commentImage = hasContentDescription("Картинка комментария")
        compose.onNode(commentImage and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(commentImage and hasClickAction()).performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5_000) { compose.onAllNodes(commentImage).fetchSemanticsNodes().size == 2 }
        // A real remote Back reaches the viewer dialog window, not the comments screen.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodes(commentImage).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Развернуть ветку").assertExists()
        compose.onNodeWithText("Вложенный комментарий").assertDoesNotExist()
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun videoErrorReturnsToCollapsedDiscussionWithoutLosingPlace() {
        focusAndPress("Комментарии 3")
        focusAndPress("Свернуть ветку")
        focusAndPress("Видео комментария")
        compose.onNodeWithText("У этого видео нет доступной ссылки для воспроизведения.").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Закрыть видео").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Развернуть ветку").assertExists()
        compose.onNodeWithText("Вложенный комментарий").assertDoesNotExist()
        compose.waitUntil(5_000) { compose.onAllNodes(isFocused()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(Screen.COMMENTS, model.state.value.screen)
    }

    @Test fun failedCommentPortionKeepsTreeAndRetriesOnlyThatPortion() {
        repository.failMoreComments = true
        focusAndPress("Комментарии 3")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Синтетический обрыв порции").fetchSemanticsNodes().isNotEmpty() }
        reveal(hasText("Корневой комментарий"))
        compose.onNodeWithText("Корневой комментарий").assertExists()
        repository.failMoreComments = false
        focusAndPress("Повторить")
        reveal(hasText("Догруженный комментарий"))
        compose.onNodeWithText("Догруженный комментарий").assertExists()
        assertEquals(1, repository.fullCommentLoads)
    }

    @Test fun deepBranchOpensSeparatelyWithParentContext() {
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("Лучшее")
        focusAndPress("Комментарии 7")
        // Beyond the indentation limit a reply names its parent.
        reveal(hasText("ответ на: Автор 5", substring = true))
        compose.onNodeWithText("ответ на: Автор 5", substring = true).assertExists()
        val separate = hasText("Ветка отдельно") and hasClickAction()
        reveal(separate)
        compose.onAllNodes(separate)[0].performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onAllNodes(separate)[0].performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        reveal(hasText("Отдельная ветка"))
        reveal(hasText("Выше по ветке · Автор 1", substring = true))
        compose.onNodeWithText("Выше по ветке · Автор 1", substring = true).assertExists()
        reveal(hasText("Глубина 0"))
        compose.onNodeWithText("Глубина 0").assertDoesNotExist()
        // Back leaves the branch first, not the discussion.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Отдельная ветка").fetchSemanticsNodes().isEmpty() }
        reveal(hasText("Глубина 0"))
        compose.onNodeWithText("Глубина 0").assertExists()
    }

    @Test fun commentsFollowReaderTextScale() {
        compose.runOnIdle { model.changePreferences("textScale", model.state.value.preferences.copy(textScale = 1f)) }
        focusAndPress("Комментарии 3")
        val normal = compose.onNodeWithText("Корневой комментарий").getBoundsInRoot().height
        compose.runOnIdle { model.changePreferences("textScale", model.state.value.preferences.copy(textScale = 2f)) }
        val large = compose.onNodeWithText("Корневой комментарий").getBoundsInRoot().height
        assertTrue("$normal -> $large", large > normal * 1.5f)
    }

    private fun waitForFocus(matcher: SemanticsMatcher) =
        compose.waitUntil(5_000) { compose.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().isNotEmpty() }

    @Test fun screensTakeInitialFocusAndFeedLoadsNextPageItself() {
        waitForFocus(hasText("Смешанный пост") and hasClickAction())
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("Лучшее")
        // Focus on the last card requests the next page; the new card does not take focus.
        compose.waitUntil(5_000) { model.state.value.posts.size == 2 }
        waitForFocus(hasText("Пост лучшего") and hasClickAction())
        focusAndPress("Пост лучшего", Key.DirectionLeft)
        focusAndPress("Горячее")
        focusAndPress("Смешанный пост")
        waitForFocus(hasAnyDescendant(hasText("Синтетический текст", substring = true)))
        focusAndPress("Комментарии 3")
        waitForFocus(hasAnyDescendant(hasText("Корневой комментарий")))
    }

    @Test fun personalFeedWithoutSessionOffersSignIn() {
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("Подписки")
        waitForFocus(hasText("Войти в Пикабу") and hasClickAction())
        compose.onNodeWithText("Для этой ленты требуется вход").assertExists()
        compose.onNodeWithText("Повторить").assertExists()
    }

    @Test fun menuIsHiddenAndActionsStayCompactAndUsableAtTwoHundredPercent() {
        compose.onNodeWithText("Меню", substring = true).assertDoesNotExist()
        focusAndPress("Смешанный пост")
        compose.runOnIdle {
            val preferences = model.state.value.preferences.copy(textScale = 2f)
            model.changePreferences("textScale", preferences)
        }
        reveal(hasText("← Назад"))
        compose.onNodeWithText("Текст 200%").assertIsDisplayed()
        listOf("А+", "← Назад").forEach { label ->
            val height = compose.onNodeWithText(label).assertIsDisplayed().assertHeightIsAtLeast(26.dp).getBoundsInRoot().height
            assertTrue("$label: $height", height <= 30.dp)
        }
        focusAndPress("← Назад")
        compose.onNodeWithText("Смешанный пост").assertIsDisplayed()
    }

    @Test fun feedFailureIsVisibleAndCanBeRetried() {
        repository.failNewFeed = true
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("Свежее")
        compose.waitUntil(5_000) { model.state.value.feedError != null }
        compose.onNodeWithText("Синтетическая ошибка сети").assertExists()
        compose.onNodeWithText("Пока нет публикаций").assertDoesNotExist()
        repository.failNewFeed = false
        focusAndPress("Повторить")
        compose.waitUntil(5_000) { model.state.value.posts.isNotEmpty() }
        compose.onNodeWithText("Смешанный пост").assertExists()
    }

    @Test fun importPreservesExplicitTextScale() {
        focusAndPress("Смешанный пост")
        focusAndPress("А+")
        compose.onNodeWithText("Текст 115%").assertExists()
        focusAndPress("← Назад")
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("Настройки")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Импорт подготовленных настроек"))
        focusAndPress("Импорт подготовленных настроек")
        compose.onNodeWithText("Явные настройки TV сохранены", substring = true).assertExists()
        focusAndPress("Импорт подготовленных настроек")
        compose.onNodeWithText("Явные настройки TV сохранены", substring = true).assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Размер текста 115%"))
        compose.onNodeWithText("Размер текста 115%").assertExists()
    }

    @Test fun commentsFollowThePostAutomatically() {
        focusAndPress("Смешанный пост")
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(4)
        compose.waitUntil(5_000) { model.state.value.comments.isNotEmpty() }
        assertEquals(Screen.READER, model.state.value.screen)
        reveal(hasText("Корневой комментарий"))
        compose.onNodeWithText("Корневой комментарий").assertExists()
        val video = compose.onNodeWithContentDescription("Превью видео", useUnmergedTree = true).getBoundsInRoot()
        val comment = compose.onNodeWithText("Корневой комментарий").getBoundsInRoot()
        assertTrue("Comments follow the post: $video vs $comment", video.bottom <= comment.top)
    }

    private fun press(label: String, key: Key) {
        val target = hasText(label, substring = true) and hasClickAction()
        compose.onNode(target).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(target).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun rating(expected: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }

    @Test fun ratingVotesWithOkAndChannelKeys() {
        compose.onNodeWithText("11").assertExists()
        press("▲", Key.Enter)
        rating("12")
        assertEquals(listOf("mixed" to 1), repository.votes)
        // The opposite key replaces the vote, a repeated one withdraws it, as on the site.
        press("▲", Key.ChannelDown)
        rating("10")
        press("▲", Key.ChannelDown)
        rating("11")
        assertEquals(listOf("mixed" to 1, "mixed" to -1, "mixed" to 0), repository.votes)
        press("▼", Key.ChannelUp)
        rating("12")
        // Away from the rating the keys do nothing in the default mode.
        press("Смешанный пост", Key.ChannelDown)
        compose.waitForIdle()
        assertEquals(4, repository.votes.size)
    }

    @Test fun channelKeysFollowTheirSettings() {
        compose.runOnIdle { model.changePreferences("voteKeys", model.state.value.preferences.copy(voteKeys = VOTE_KEYS_ON_POST)) }
        press("Смешанный пост", Key.ChannelUp)
        rating("12")
        compose.runOnIdle { model.changePreferences("plusKey", model.state.value.preferences.withPlusKey(DEFAULT_MINUS_KEY)) }
        press("Смешанный пост", Key.ChannelUp)
        rating("10")
        compose.runOnIdle { model.changePreferences("voteKeys", model.state.value.preferences.copy(voteKeys = VOTE_KEYS_OFF)) }
        press("Смешанный пост", Key.ChannelUp)
        press("▲", Key.ChannelDown)
        compose.waitForIdle()
        assertEquals(2, repository.votes.size)
        compose.onNodeWithText("10").assertExists()
    }

    @Test fun voteKeysAreChosenByPressingThem() {
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("Настройки")
        val capture = hasTestTag("remote-key-capture")
        reveal(hasText("Ставить плюс", substring = true))
        compose.onNodeWithText("Ставить плюс: Следующий канал").assertExists()
        compose.onNodeWithText("Ставить минус: Предыдущий канал").assertExists()
        focusAndPress("Ставить плюс")
        compose.waitUntil(5_000) { compose.onAllNodes(capture).fetchSemanticsNodes().isNotEmpty() }
        // Keys that run the interface are refused and the choice stays open.
        compose.onNode(capture).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithText("Эта кнопка нужна для управления", substring = true).assertExists()
        compose.onNode(capture).performKeyInput { pressKey(Key(KeyEvent.KEYCODE_PROG_RED)) }
        compose.waitUntil(5_000) { compose.onAllNodes(capture).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Ставить плюс: Красная").assertExists()
        focusAndPress("Ставить минус")
        compose.waitUntil(5_000) { compose.onAllNodes(capture).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(capture).performKeyInput { pressKey(Key(KeyEvent.KEYCODE_PROG_RED)) }
        compose.waitUntil(5_000) { compose.onAllNodes(capture).fetchSemanticsNodes().isEmpty() }
        // The same key cannot do both: the plus takes the minus key's old value.
        compose.onNodeWithText("Ставить минус: Красная").assertExists()
        compose.onNodeWithText("Ставить плюс: Предыдущий канал").assertExists()
        focusAndPress("← Назад")
        press("▲", Key(KeyEvent.KEYCODE_PROG_RED))
        rating("10")
        assertEquals(listOf("mixed" to -1), repository.votes)
    }

    private fun selectComment(text: String) = waitForFocus(hasAnyDescendant(hasText(text)))
    private fun keyOnComment(text: String, key: Key) = compose.onNode(hasAnyDescendant(hasText(text)) and isFocused()).performKeyInput { pressKey(key) }

    @Test fun commentRatingVotesWithTheSameKeysAsPost() {
        focusAndPress("Комментарии 3")
        compose.onNodeWithText("21").assertExists()
        selectComment("Корневой комментарий")
        keyOnComment("Корневой комментарий", Key.ChannelUp)
        rating("22")
        keyOnComment("Корневой комментарий", Key.ChannelDown)
        rating("20")
        keyOnComment("Корневой комментарий", Key.ChannelDown)
        rating("21")
        assertEquals(listOf("root" to 1, "root" to -1, "root" to 0), repository.votes)
        // ▲ works with OK as well, for a remote without channel keys.
        compose.onAllNodes(hasText("▲") and hasClickAction())[0].performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onAllNodes(hasText("▲") and hasClickAction())[0].performKeyInput { pressKey(Key.Enter) }
        rating("22")
    }

    @Test fun switchedOffChannelKeysLeaveCommentsAlone() {
        compose.runOnIdle { model.changePreferences("voteKeys", model.state.value.preferences.copy(voteKeys = VOTE_KEYS_OFF)) }
        focusAndPress("Комментарии 3")
        selectComment("Корневой комментарий")
        keyOnComment("Корневой комментарий", Key.ChannelUp)
        compose.waitForIdle()
        assertTrue(repository.votes.isEmpty())
    }

    private fun focusedBackground(label: String) = compose.onNode(hasText(label, substring = true) and isFocused())
        .captureToImage().toPixelMap().let { it[it.width / 2, 3] }

    @Test fun leftArrowOpensHiddenMenuFromReading() {
        focusAndPress("Смешанный пост")
        waitForFocus(hasAnyDescendant(hasText("Синтетический текст", substring = true)))
        compose.onNode(hasAnyDescendant(hasText("Синтетический текст", substring = true)) and isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("PikaTV").assertIsDisplayed()
    }

    @Test fun colorSchemeAndAccentsApplyAtOnceAndSurviveRestart() {
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        assertEquals(TvAccents[0].color, focusedBackground("Горячее"))
        focusAndPress("Настройки")
        focusAndPress("Графит")
        compose.onNodeWithText("✓ Графит").assertExists()
        compose.runOnIdle { assertEquals(1, model.state.value.preferences.colorScheme) }
        compose.runOnIdle { model.changePreferences("menuAccent", model.state.value.preferences.copy(menuAccent = 2)) }
        compose.runOnIdle { model.changePreferences("accent", model.state.value.preferences.copy(accent = 1)) }
        compose.waitForIdle()
        assertEquals(TvAccents[1].color, focusedBackground("Графит"))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val saved = ru.pikatv.local.LocalStore(app).preferences()
        assertEquals(listOf(1, 1, 2), listOf(saved.colorScheme, saved.accent, saved.menuAccent))
    }

    @Test fun cardShowsWholePostAndArrowsMoveBetweenPosts() {
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("Лучшее")
        compose.waitUntil(5_000) { model.state.value.posts.size == 2 }
        focusAndPress("Пост лучшего", Key.DirectionDown)
        waitForFocus(hasText("Второй пост лучшего") and hasClickAction())
        focusAndPress("Второй пост лучшего", Key.DirectionUp)
        waitForFocus(hasText("Пост лучшего") and hasClickAction())
        focusAndPress("Пост лучшего", Key.DirectionRight)
        waitForFocus(hasText("▲") and hasClickAction())
        focusAndPress("Пост лучшего", Key.DirectionLeft)
        focusAndPress("Горячее")
        // The first card fills the screen and shows the post, not a short excerpt.
        val card = compose.onNode(hasText("Смешанный пост") and hasClickAction()).getBoundsInRoot()
        assertTrue("Card height ${card.height}", card.height > 400.dp)
        compose.onNodeWithContentDescription("Тестовое изображение", useUnmergedTree = true).assertExists()
    }

    @Test fun actionsOfLaterPostDoNotShiftTheList() {
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("Лучшее")
        compose.waitUntil(5_000) { model.state.value.posts.size == 2 }
        focusAndPress("Пост лучшего", Key.DirectionDown)
        waitForFocus(hasText("Второй пост лучшего") and hasClickAction())
        val before = compose.onNodeWithText("Второй пост лучшего", substring = true).getBoundsInRoot().top
        focusAndPress("Второй пост лучшего", Key.DirectionRight)
        waitForFocus(hasText("▲") and hasClickAction())
        assertEquals(before, compose.onNodeWithText("Второй пост лучшего", substring = true).getBoundsInRoot().top)
    }

    @Test fun shortPostsShareTheScreenAndLongPostsTakeItAll() {
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("Лучшее")
        compose.waitUntil(5_000) { model.state.value.posts.size == 2 }
        val second = hasText("Второй пост лучшего") and hasClickAction()
        compose.waitUntil(5_000) { compose.onAllNodes(second).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Пост лучшего").assertIsDisplayed()
        compose.onNode(second).assertIsDisplayed()
        val first = compose.onNode(hasText("Пост лучшего") and hasClickAction()).getBoundsInRoot()
        assertTrue("Short card keeps its size: ${first.height}", first.height < 180.dp)
        // Moving between posts that are both on screen does not scroll.
        val top = compose.onNode(second).getBoundsInRoot().top
        focusAndPress("Пост лучшего", Key.DirectionDown)
        waitForFocus(second)
        assertEquals(top, compose.onNode(second).getBoundsInRoot().top)
    }

    private fun buttonColor(label: String, index: Int = 0) = compose.onAllNodes(hasText(label) and hasClickAction())[index]
        .captureToImage().toPixelMap().let { it[it.width / 6, it.height / 2] }

    @Test fun reactionsArePressedWithdrawnAddedAndHidden() {
        val card = hasText("Смешанный пост", substring = true) and hasClickAction()
        waitForFocus(card)
        press("❤️ 5", Key.Enter)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("❤️ 6").fetchSemanticsNodes().isNotEmpty() }
        press("❤️ 6", Key.Enter)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("❤️ 5").fetchSemanticsNodes().isNotEmpty() }
        press("+ Эмодзи", Key.Enter)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("🔥") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        press("🔥", Key.Enter)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("🔥 1").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(Triple("mixed", 15, true), Triple("mixed", 15, false), Triple("mixed", 13, true)), repository.reactions.toList())
        // A reaction the site sells is a counter, not a button.
        compose.onNodeWithText("💩 2").assertExists()
        assertEquals(0, compose.onAllNodes(hasText("💩 2") and hasClickAction()).fetchSemanticsNodes().size)
        compose.runOnUiThread { model.changePreferences("showReactions", model.state.value.preferences.copy(showReactions = false)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("+ Эмодзи").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun commentReactionsFollowTheSameSetting() {
        focusAndPress("Комментарии 3")
        selectComment("Корневой комментарий")
        press("😂 3", Key.Enter)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("😂 4").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(Triple("root", 16, true)), repository.reactions.toList())
        compose.runOnUiThread { model.changePreferences("showReactions", model.state.value.preferences.copy(showReactions = false)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("😂 4").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun reactionRowLiesBetweenTheActionsAndThePost() {
        val card = hasText("Смешанный пост", substring = true) and hasClickAction()
        waitForFocus(card)
        focusAndPress("Смешанный пост", Key.DirectionRight)
        waitForFocus(hasText("▲") and hasClickAction())
        compose.onNode(hasText("▲") and hasClickAction() and isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        val add = hasText("+ Эмодзи") and hasClickAction()
        waitForFocus(add)
        compose.onNode(add and isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
        waitForFocus(hasText("▲") and hasClickAction())
        compose.onNode(hasText("▲") and hasClickAction() and isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        waitForFocus(add)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitForFocus(card)
    }

    @Test fun lightSchemeRepaintsTheScreen() {
        val light = TvSchemes.indexOfFirst { it.title == "Бумага" }
        compose.runOnUiThread { model.changePreferences("colorScheme", model.state.value.preferences.copy(colorScheme = light)) }
        compose.waitUntil(5_000) { compose.onRoot().captureToImage().toPixelMap()[2, 2] == TvSchemes[light].background }
    }

    @Test fun readingKeepsTheScreenOn() = compose.waitUntil(5_000) { root.keepScreenOn }

    @Test fun choosingTheCurrentFeedReloadsItFromTheTop() {
        focusAndPress("Смешанный пост", Key.DirectionLeft)
        focusAndPress("Лучшее")
        compose.waitUntil(5_000) { model.state.value.posts.size == 2 }
        val first = hasText("Пост лучшего") and hasClickAction()
        val second = hasText("Второй пост лучшего") and hasClickAction()
        compose.waitUntil(5_000) { compose.onAllNodes(second).fetchSemanticsNodes().isNotEmpty() }
        focusAndPress("Пост лучшего", Key.DirectionDown)
        waitForFocus(second)
        val loads = repository.feedLoads
        focusAndPress("Второй пост лучшего", Key.DirectionLeft)
        focusAndPress("Лучшее")
        compose.waitUntil(5_000) { repository.feedLoads > loads && model.state.value.posts.size == 2 }
        waitForFocus(first)
    }

    @Test fun upFromTheFirstBlockGoesToTheFirstAction() {
        focusAndPress("Смешанный пост")
        val block = hasAnyDescendant(hasText("Синтетический текст", substring = true))
        waitForFocus(block)
        compose.onNode(block and isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
        waitForFocus(hasText("← Назад") and hasClickAction())
    }

    @Test fun messageBarLeavesByItselfWithoutTakingFocus() {
        val card = hasText("Смешанный пост", substring = true) and hasClickAction()
        waitForFocus(card)
        compose.runOnUiThread { model.importPreparedSettings() }
        val message = hasText("импорт", substring = true)
        compose.waitUntil(5_000) { compose.onAllNodes(message).fetchSemanticsNodes().isNotEmpty() }
        waitForFocus(card)
        compose.waitUntil(9_000) { compose.onAllNodes(message).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun backFromFeedActionsReturnsToThePost() {
        val card = hasText("Смешанный пост", substring = true) and hasClickAction()
        waitForFocus(card)
        focusAndPress("Смешанный пост", Key.DirectionRight)
        waitForFocus(hasText("▲") and hasClickAction())
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitForFocus(card)
    }

    @Test fun backLeavesCommentButtonsOneLevelAtATime() {
        focusAndPress("Комментарии 3")
        selectComment("Корневой комментарий")
        val up = compose.onAllNodes(hasText("▲") and hasClickAction())[0]
        up.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        selectComment("Корневой комментарий")
        compose.onNodeWithText("Корневой комментарий").assertExists()
    }

    @Test fun nothingToTheLeftOfARootCommentKeepsFocusOnIt() {
        focusAndPress("Комментарии 3")
        selectComment("Корневой комментарий")
        keyOnComment("Корневой комментарий", Key.DirectionLeft) // collapses the branch
        selectComment("Корневой комментарий")
        keyOnComment("Корневой комментарий", Key.DirectionLeft)
        compose.waitForIdle()
        selectComment("Корневой комментарий")
    }

    @Test fun pressedVoteStaysColouredUntilWithdrawn() {
        compose.onNodeWithText("Смешанный пост", substring = true).assertExists()
        focusAndPress("Комментарии 3")
        selectComment("Корневой комментарий")
        // A vote made earlier is already pressed; neither button of an unvoted comment is.
        assertEquals(VotePlus, buttonColor("▲", 1))
        val neutral = buttonColor("▼", 1)
        assertEquals(neutral, buttonColor("▲"))
        assertEquals(neutral, buttonColor("▼"))
        keyOnComment("Корневой комментарий", Key.ChannelUp)
        rating("22")
        assertEquals(VotePlus, buttonColor("▲"))
        assertEquals(neutral, buttonColor("▼"))
        keyOnComment("Корневой комментарий", Key.ChannelDown)
        rating("20")
        assertEquals(neutral, buttonColor("▲"))
        assertEquals(VoteMinus, buttonColor("▼"))
        keyOnComment("Корневой комментарий", Key.ChannelDown)
        rating("21")
        assertEquals(neutral, buttonColor("▲"))
        assertEquals(neutral, buttonColor("▼"))
    }

    @Test fun oneOkOpensVideoBlockWithPreviewBesideIt() {
        focusAndPress("Смешанный пост")
        val video = hasText("Тестовое видео", substring = true) and hasClickAction()
        reveal(video)
        compose.onNodeWithContentDescription("Превью видео", useUnmergedTree = true).assertExists()
        val preview = compose.onNodeWithContentDescription("Превью видео", useUnmergedTree = true).getBoundsInRoot()
        val label = compose.onNodeWithText("▶  Тестовое видео", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("Preview $preview beside $label", preview.right <= label.left)
        compose.onNode(video).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(video).performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Закрыть видео").fetchSemanticsNodes().isNotEmpty() }
    }

    private class FakeRepository : PikabuRepository {
        val votes = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        override suspend fun vote(post: Post, value: Int) { votes += post.id to value }
        override suspend fun voteComment(postUrl: String, comment: Comment, value: Int) { votes += comment.id to value }
        @Volatile var failNewFeed = false
        @Volatile var feedLoads = 0
        val reactions = java.util.Collections.synchronizedList(mutableListOf<Triple<String, Int, Boolean>>())
        override val reactionChoices = listOf(Reaction(15, "❤️"), Reaction(13, "🔥"))
        override suspend fun react(post: Post, reaction: Reaction, on: Boolean) { reactions += Triple(post.id, reaction.id, on) }
        override suspend fun reactComment(postUrl: String, comment: Comment, reaction: Reaction, on: Boolean) { reactions += Triple(comment.id, reaction.id, on) }
        @Volatile var failMoreComments = false
        @Volatile var fullCommentLoads = 0
        private val mixed = Post("mixed", "https://example.invalid/mixed", "Смешанный пост", Author(name = "Автор"), canReact = true,
            reactions = listOf(Reaction(15, "❤️", 5), Reaction(26, "💩", 2, canVote = false)),
            blocks = listOf(ContentBlock.Text("text", "Синтетический текст для проверки чтения. ".repeat(20)),
                ContentBlock.Image("image", "https://example.invalid/image.png", description = "Тестовое изображение"),
                ContentBlock.Video("video", "https://example.invalid/video.mp4", posterUrl = "https://example.invalid/poster.webp", aspectRatio = .5625f, title = "Тестовое видео")), commentsCount = 3, isLong = true, rating = Rating(11, 15, 4))
        private val best = Post("best", "https://example.invalid/best", "Пост лучшего", commentsCount = 7)
        private val deep = (0..6).map { d -> Comment("d$d", parentId = if (d == 0) null else "d${d - 1}", author = Author(name = "Автор $d"),
            blocks = listOf(ContentBlock.Text("dt$d", "Глубина $d")), depth = d, replyCount = if (d < 6) 1 else 0) }
        private val bestNext = Post("best-2", "https://example.invalid/best-2", "Второй пост лучшего")
        private val root = Comment("root", author = Author(name = "Первый"), rating = Rating(21), canReact = true, reactions = listOf(Reaction(16, "😂", 3)), blocks = listOf(ContentBlock.Text("r", "Корневой комментарий"),
            ContentBlock.Image("ri", "https://example.invalid/comment.png", description = "Картинка комментария"),
            ContentBlock.Video("rv", null, title = "Видео комментария")), replyCount = 1)
        private val child = Comment("child", parentId = "root", depth = 1, blocks = listOf(ContentBlock.Text("c", "Вложенный комментарий")), vote = 1)
        override suspend fun getFeed(feed: FeedType, page: Int): FeedPage {
            feedLoads++
            if (feed == FeedType.NEW && failNewFeed) throw PikabuException(ContentError.NETWORK, "Синтетическая ошибка сети")
            if (feed.requiresAccount) throw PikabuException(ContentError.AUTH_REQUIRED, "Для этой ленты требуется вход")
            if (feed == FeedType.BEST) return if (page == 1) FeedPage(listOf(best), 1, true) else FeedPage(listOf(bestNext), page, false)
            return FeedPage(listOf(mixed), page, false)
        }
        override suspend fun getPost(url: String) = if (url == best.url) best else mixed
        override suspend fun getComments(postUrl: String): CommentsPage {
            fullCommentLoads++
            return if (postUrl == best.url) CommentsPage(deep, deep.size) else CommentsPage(listOf(root, child), 3, listOf("more"))
        }
        override suspend fun getCommentsByIds(postUrl: String, ids: List<String>): List<Comment> {
            if (failMoreComments) throw PikabuException(ContentError.NETWORK, "Синтетический обрыв порции")
            return listOf(Comment("more", blocks = listOf(ContentBlock.Text("m", "Догруженный комментарий"))))
        }
        override suspend fun getBranch(postUrl: String, commentId: String) = listOf(child)
    }
}
