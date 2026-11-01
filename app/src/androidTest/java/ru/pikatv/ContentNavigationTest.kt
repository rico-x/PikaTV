package ru.pikatv

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.domain.*

/** Regression coverage for navigation through persisted summaries and failed append requests. */
@RunWith(AndroidJUnit4::class)
class ContentNavigationTest {
    private lateinit var model: PikaViewModel
    private lateinit var app: Application
    private val repository = Repository()
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    @Before fun setUp() = main {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("pikatv_local", Context.MODE_PRIVATE).edit().clear().commit()
        model = PikaViewModel(app, repository, loadInitialFeed = false)
    }

    @After fun tearDown() = main { model.viewModelScope.cancel() }

    @Test fun discussionOpenedFromHistoryReturnsToCompletePost() = main {
        model.openPost(repository.card)
        assertEquals(1, repository.postLoads)
        model.back()
        model.selectSection(AppSection.HISTORY)
        val summary = model.state.value.posts.single()
        assertFalse(summary.blocks.any { it.id == "tail" })
        model.openComments(summary)
        model.back()
        assertEquals(Screen.READER, model.state.value.screen)
        assertEquals(2, repository.postLoads)
        assertTrue(model.state.value.currentPost!!.blocks.any { it.id == "tail" })
        assertFalse(model.state.value.postLoading)
    }

    @Test fun retryThirdFeedPageKeepsFirstTwoPages() = main {
        model.refreshFeed()
        model.loadMoreFeed()
        repository.failThirdPage = true
        model.loadMoreFeed()
        assertNotNull(model.state.value.feedError)
        assertEquals(listOf("1", "2"), model.state.value.posts.map { it.id })
        repository.failThirdPage = false
        model.retryFeed()
        assertEquals(listOf(1, 2, 3, 3), repository.pages)
        assertEquals(listOf("1", "2", "3"), model.state.value.posts.map { it.id })
        assertNull(model.state.value.feedError)
    }

    @Test fun explicitExpandedPostSurvivesDefaultAndProcessChanges() = main {
        val collapsedDefault = model.state.value.preferences.copy(collapsePostsByDefault = true)
        model.changePreferences("collapsePostsByDefault", collapsedDefault)
        assertTrue(model.state.value.isCollapsed(repository.card))
        model.toggleCollapsed(repository.card)
        assertFalse(model.state.value.isCollapsed(repository.card))
        model.importPreparedSettings()
        assertTrue(model.state.value.preferences.collapsePostsByDefault)
        assertFalse(model.state.value.isCollapsed(repository.card))
        model.viewModelScope.cancel()
        model = PikaViewModel(app, repository, loadInitialFeed = false)
        assertFalse(model.state.value.isCollapsed(repository.card))
        model.toggleCollapsed(repository.card)
        assertTrue(model.state.value.isCollapsed(repository.card))
    }

    @Test fun guestVoteAsksToSignInAndKeepsRating() = main {
        repository.voteError = PikabuException(ContentError.AUTH_REQUIRED, "Чтобы голосовать, войдите в аккаунт")
        model.refreshFeed()
        val post = model.state.value.posts.first()
        model.vote(post, 1)
        assertEquals(1, model.state.value.loginRequest)
        assertNull(model.state.value.message)
        assertEquals(0, model.state.value.posts.first().vote)
    }

    @Test fun commentsUnderPostStartOnceAndNewPostForgetsThem() = main {
        model.openPost(repository.card)
        model.loadCommentsUnderPost()
        assertEquals(repository.card.id, model.state.value.commentsPostId)
        assertEquals(Screen.READER, model.state.value.screen)
        model.loadCommentsUnderPost()
        assertEquals(1, repository.commentLoads)
        model.back()
        model.openPost(repository.card.copy(id = "2", url = "https://example.invalid/other"))
        assertNull(model.state.value.commentsPostId)
    }

    private class Repository : PikabuRepository {
        override suspend fun react(post: Post, reaction: Reaction, on: Boolean) = Unit
        override suspend fun reactComment(postUrl: String, comment: Comment, reaction: Reaction, on: Boolean) = Unit
        val card = Post("1", "https://example.invalid/post", "Длинный пост",
            blocks = listOf(ContentBlock.Text("preview", "Начало")), commentsCount = 1)
        private val full = card.copy(blocks = card.blocks + ContentBlock.Text("tail", "Конец полного поста"))
        var postLoads = 0
        var failThirdPage = false
        val pages = mutableListOf<Int>()
        override suspend fun getFeed(feed: FeedType, page: Int): FeedPage {
            pages += page
            if (page == 3 && failThirdPage) throw PikabuException(ContentError.NETWORK, "Обрыв третьей страницы")
            return FeedPage(listOf(card.copy(id = page.toString())), page, page < 3)
        }
        override suspend fun getPost(url: String): Post { postLoads++; return full }
        var commentLoads = 0
        override suspend fun getComments(postUrl: String): CommentsPage { commentLoads++; return CommentsPage(emptyList(), 0) }
        override suspend fun getCommentsByIds(postUrl: String, ids: List<String>) = emptyList<Comment>()
        override suspend fun getBranch(postUrl: String, commentId: String) = emptyList<Comment>()
        var voteError: PikabuException? = null
        override suspend fun vote(post: Post, value: Int) { voteError?.let { throw it } }
        override suspend fun voteComment(postUrl: String, comment: Comment, value: Int) { voteError?.let { throw it } }
    }
}
