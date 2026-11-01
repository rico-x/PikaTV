package ru.pikatv

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.domain.*

/** Votes and reactions against a recording fake: what is sent, what is shown, and in which order answers arrive. */
@RunWith(AndroidJUnit4::class)
class VoteStateTest {
    private lateinit var model: PikaViewModel
    private lateinit var repo: RecordingRepository
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private val card get() = model.state.value.posts.single()

    @Before fun setUp() = main {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("pikatv_local", Context.MODE_PRIVATE).edit().clear().commit()
        repo = RecordingRepository()
        model = PikaViewModel(app, repo, loadInitialFeed = false)
        model.refreshFeed()
    }

    @After fun tearDown() = main { model.viewModelScope.cancel() }

    private fun await(condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 3_000
        while (!condition() && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(10)
        assertTrue("Completion condition", condition())
    }

    @Test fun repeatedPressInThePostWithdrawsWhatThePostShows() {
        main {
            model.openPost(repo.card)
            val shown = model.state.value.currentPost!!
            assertEquals(1, shown.vote)
            assertTrue(shown.reactions.single().active)
            model.vote(shown, 1)
            model.react(shown, repo.heart)
        }
        assertEquals(listOf(0), repo.votes)
        assertEquals(listOf(false), repo.reactions)
    }

    @Test fun openingThePostBringsItsStateToTheCard() {
        main { model.openPost(repo.card) }
        assertEquals(1, card.vote)
        assertTrue(card.reactions.single().active)
    }

    @Test fun pressedVoteIsVisibleWhileTheRequestRuns() {
        main {
            repo.gate = CompletableDeferred()
            model.vote(repo.card, 1)
        }
        assertFalse(repo.gate!!.isCompleted)
        assertEquals(1, card.vote)
    }

    @Test fun repeatWhileTheRequestRunsSendsNothing() {
        main {
            repo.gate = CompletableDeferred()
            model.vote(repo.card, 1)
            model.vote(repo.card, 1)
        }
        assertEquals(listOf(1), repo.votes)
        assertEquals(1, card.vote)
    }

    @Test fun refusedVoteAndReactionAreUndone() {
        main {
            repo.refuse = true
            model.vote(repo.card, 1)
            model.react(repo.card, repo.heart)
        }
        await { model.state.value.message != null }
        assertEquals(0, card.vote)
        assertFalse(card.reactions.single().active)
        assertEquals(10L, card.rating?.total)
    }

    @Test fun guestPressIsUndoneAndAsksToSignIn() {
        main {
            repo.guest = true
            model.vote(repo.card, 1)
        }
        await { model.state.value.loginRequest == 1 }
        assertEquals(0, card.vote)
    }

    @Test fun reactionSurvivesAVoteAnsweredAfterIt() {
        main {
            repo.gate = CompletableDeferred()
            model.vote(repo.card, 1)
            model.react(repo.card, repo.heart)
            repo.gate!!.complete(Unit)
        }
        await { repo.votes.size == 1 && !model.state.value.feedLoading }
        assertEquals(1, card.vote)
        assertTrue(card.reactions.single().active)
    }

    @Test fun voteSurvivesAReactionAnsweredAfterIt() {
        main {
            repo.reactionGate = CompletableDeferred()
            model.react(repo.card, repo.heart)
            model.vote(repo.card, 1)
            repo.reactionGate!!.complete(Unit)
        }
        assertEquals(1, card.vote)
        assertTrue(card.reactions.single().active)
    }

    @Test fun savedCopyOffersNoVoteUntilTheFreshPostArrives() {
        main {
            model.openPost(repo.card)
            model.back()
            model.selectSection(AppSection.HISTORY)
        }
        await { model.state.value.posts.isNotEmpty() }
        assertFalse(card.canVote)
        assertFalse(card.canReact)
        main { model.openPost(card) }
        assertTrue(model.state.value.currentPost!!.canVote)
        assertEquals(1, model.state.value.currentPost!!.vote)
    }

    @Test fun signingOutReloadsWhatTheOldSessionLoaded() {
        val loads = repo.feedLoads
        main { model.signOut() }
        await { repo.feedLoads > loads }
    }

    @Test fun completedVoteSurvivesAnOlderPageStillLoading() {
        main {
            repo.fullPost = repo.card
            repo.postGate = CompletableDeferred()
            model.openPost(repo.card)
            model.vote(model.state.value.currentPost!!, 1)
        }
        assertEquals(listOf(1), repo.votes)
        assertEquals(1, model.state.value.currentPost!!.vote)
        main { repo.postGate!!.complete(Unit) }
        await { !model.state.value.postLoading }
        assertEquals(1, model.state.value.currentPost!!.vote)
        assertEquals(1, card.vote)
    }

    @Test fun completedReactionSurvivesAnOlderPageStillLoading() {
        main {
            repo.fullPost = repo.card
            repo.postGate = CompletableDeferred()
            model.openPost(repo.card)
            model.react(model.state.value.currentPost!!, repo.heart)
        }
        assertEquals(listOf(true), repo.reactions)
        main { repo.postGate!!.complete(Unit) }
        await { !model.state.value.postLoading }
        assertTrue(model.state.value.currentPost!!.reactions.single().active)
        assertTrue(card.reactions.single().active)
    }

    @Test fun anOlderPageKeepsTheRollbackOfARefusedVote() {
        main {
            repo.fullPost = repo.card
            repo.postGate = CompletableDeferred()
            repo.refuse = true
            model.openPost(repo.card)
            model.vote(model.state.value.currentPost!!, 1)
        }
        await { model.state.value.message != null }
        main { repo.postGate!!.complete(Unit) }
        await { !model.state.value.postLoading }
        assertEquals(0, model.state.value.currentPost!!.vote)
        assertEquals(10L, card.rating?.total)
    }

    @Test fun aLaterExplicitReadCanRefreshTheServerState() {
        main {
            model.vote(repo.card, 1)
            repo.fullPost = repo.card
            model.openPost(card)
        }
        await { !model.state.value.postLoading }
        assertEquals(0, model.state.value.currentPost!!.vote)
    }

    @Test fun savedCopiesCannotSendVotesOrReactionsThroughTheModel() {
        main {
            model.openPost(repo.card)
            model.back()
            model.selectSection(AppSection.HISTORY)
            model.vote(card, 1)
            model.react(card, repo.heart)
        }
        assertTrue(repo.votes.isEmpty())
        assertTrue(repo.reactions.isEmpty())
    }

    @Test fun sessionChangeInvalidatesAndReloadsTheOpenDiscussion() {
        main {
            repo.comments = listOf(Comment("100", vote = 1, canReact = true, reactions = listOf(repo.heart.copy(active = true))))
            model.openPost(repo.card)
            model.openComments()
        }
        assertEquals(1, model.state.value.comments.single().vote)
        main {
            repo.comments = listOf(repo.comments.single().copy(vote = 0, reactions = listOf(repo.heart)))
            model.signOut()
        }
        await { repo.invalidations == 1 && repo.commentLoads >= 2 && !model.state.value.commentsLoading }
        assertEquals(0, model.state.value.comments.single().vote)
        assertFalse(model.state.value.comments.single().reactions.single().active)
    }

    @Test fun sessionChangeCancelsAnOldPendingVote() {
        main {
            repo.gate = CompletableDeferred()
            model.vote(repo.card, 1)
            model.signOut()
        }
        await { repo.invalidations == 1 }
        main { repo.gate!!.complete(Unit) }
        assertEquals(0, card.vote)
        assertEquals("Вы вышли из аккаунта. Локальные данные сохранены.", model.state.value.message)
    }

    private class RecordingRepository : PikabuRepository {
        val heart = Reaction(15, "❤️", 5)
        val card = Post("1", "https://example.invalid/story/1", "Пост", rating = Rating(10), reactions = listOf(heart), canReact = true)
        var fullPost = card.copy(vote = 1, rating = Rating(11), reactions = listOf(heart.copy(active = true)))
        var postGate: CompletableDeferred<Unit>? = null
        var comments = emptyList<Comment>()
        var commentLoads = 0
        var invalidations = 0
        var gate: CompletableDeferred<Unit>? = null
        var reactionGate: CompletableDeferred<Unit>? = null
        var refuse = false
        var guest = false
        var feedLoads = 0
        val votes = mutableListOf<Int>()
        val reactions = mutableListOf<Boolean>()
        override val reactionChoices = listOf(heart)
        private suspend fun answer(wait: CompletableDeferred<Unit>?) {
            wait?.await()
            if (guest) throw PikabuException(ContentError.AUTH_REQUIRED, "Войдите")
            if (refuse) throw PikabuException(ContentError.CONTENT_UNAVAILABLE, "Отказ")
        }
        override suspend fun getFeed(feed: FeedType, page: Int): FeedPage { feedLoads++; return FeedPage(listOf(card), page, false) }
        override fun invalidateSession() { invalidations++ }
        override suspend fun getPost(url: String): Post { val snapshot = fullPost; postGate?.await(); return snapshot }
        override suspend fun getComments(postUrl: String): CommentsPage { commentLoads++; return CommentsPage(comments, comments.size) }
        override suspend fun getCommentsByIds(postUrl: String, ids: List<String>) = emptyList<Comment>()
        override suspend fun getBranch(postUrl: String, commentId: String) = emptyList<Comment>()
        override suspend fun vote(post: Post, value: Int) { votes += value; answer(gate) }
        override suspend fun react(post: Post, reaction: Reaction, on: Boolean) { reactions += on; answer(reactionGate) }
        override suspend fun voteComment(postUrl: String, comment: Comment, value: Int) = Unit
        override suspend fun reactComment(postUrl: String, comment: Comment, reaction: Reaction, on: Boolean) = Unit
    }
}
