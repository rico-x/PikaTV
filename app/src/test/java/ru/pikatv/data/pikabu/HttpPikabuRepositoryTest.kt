package ru.pikatv.data.pikabu

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.pikatv.domain.*
import java.util.concurrent.TimeUnit

class HttpPikabuRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: HttpPikabuRepository
    @Before fun setup() {
        server = MockWebServer()
        server.start()
        repository = HttpPikabuRepository(baseUrl = server.url("/").toString())
    }
    @After fun teardown() { server.shutdown() }
    private fun fixture(name: String) = javaClass.getResource("/pikabu/$name")!!.readText()
    private fun html(text: String) = MockResponse().setHeader("Content-Type", "text/html; charset=windows-1251")
        .setBody(Buffer().write(text.toByteArray(charset("windows-1251"))))
    private fun comment(id: String, text: String = "Ответ") = "<div class='comment' data-id='$id' data-author-id='1' data-meta='pid=0;r=2'><div class='comment__content'><p>$text</p></div></div>"
    private fun jsonString(value: String) = org.json.JSONObject.quote(value)
    private fun storyPage(userId: Int) = "<html><body><script data-entry='initParams' type='application/json'>{\"userID\":$userId,\"csrfToken\":\"token-1\"}</script>" +
        "<article class='story' data-story-id='7' data-comments='0' data-vote='0'><a class='story__title-link' href='/story/_7'>Пост</a><div class='story__content-inner'><p>Текст</p></div></article></body></html>"
    private val votedPost get() = Post("7", server.url("/story/_7").toString(), "Пост")

    @Test fun voteSendsStoryAndTokenOfSignedInPage() = runBlocking {
        server.enqueue(html(storyPage(userId = 5)))
        server.enqueue(MockResponse().setBody("{\"result\":true,\"message\":\"\",\"data\":{}}"))
        repository.vote(votedPost, -1)
        server.takeRequest(1, TimeUnit.SECONDS)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/ajax/vote_story.php", request.path)
        assertEquals("POST", request.method)
        assertEquals("story_id=7&vote=-1", request.body.readUtf8())
        assertEquals("token-1", request.getHeader("X-Csrf-Token"))
    }

    @Test fun reactionUsesItsOwnRequest() = runBlocking {
        server.enqueue(html(storyPage(userId = 5)))
        server.enqueue(MockResponse().setBody("{\"result\":true}"))
        repository.react(votedPost, Reaction(15, "❤️"), on = true)
        server.takeRequest(1, TimeUnit.SECONDS)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/ajax/save_emotion.php", request.path)
        assertEquals("story_id=7&type=15&state=1", request.body.readUtf8())
        assertEquals("token-1", request.getHeader("X-Csrf-Token"))
    }

    @Test fun commentReactionUsesItsOwnRequest() = runBlocking {
        server.enqueue(html(storyPage(userId = 5)))
        server.enqueue(MockResponse().setBody("{\"result\":true}"))
        repository.reactComment(votedPost.url, Comment("42"), Reaction(15, "❤️"), on = false)
        server.takeRequest(1, TimeUnit.SECONDS)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/ajax.php", request.path)
        assertEquals("comment_id=42&route=user-comment-emotions%2Fsave&type=15&state=0", request.body.readUtf8())
        assertEquals("token-1", request.getHeader("X-Csrf-Token"))
    }

    @Test fun paidReactionIsNeverSpent() = runBlocking {
        val paid = Reaction(26, "💩", canVote = false)
        listOf<suspend () -> Unit>({ repository.react(votedPost, paid, on = true) }, { repository.reactComment(votedPost.url, Comment("42"), paid, on = true) }).forEach { send ->
            val error = runCatching { send() }.exceptionOrNull() as PikabuException
            assertEquals(ContentError.CONTENT_UNAVAILABLE, error.kind)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun commentVoteUsesItsOwnRequest() = runBlocking {
        server.enqueue(html(storyPage(userId = 5)))
        server.enqueue(MockResponse().setBody("{\"result\":true}"))
        repository.voteComment(votedPost.url, Comment("42"), 1)
        server.takeRequest(1, TimeUnit.SECONDS)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/ajax/vote_comment.php", request.path)
        assertEquals("comment_id=42&vote=1", request.body.readUtf8())
        assertEquals("token-1", request.getHeader("X-Csrf-Token"))
    }

    @Test fun guestCannotVoteAndNothingIsSent() = runBlocking {
        server.enqueue(html(storyPage(userId = 0)))
        val error = runCatching { repository.vote(votedPost, 1) }.exceptionOrNull() as PikabuException
        assertEquals(ContentError.AUTH_REQUIRED, error.kind)
        assertEquals(1, server.requestCount)
    }

    @Test fun refusedVoteReportsSiteMessage() = runBlocking {
        server.enqueue(html(storyPage(userId = 5)))
        server.enqueue(MockResponse().setBody("{\"result\":false,\"message\":\"Голосование закрыто\"}"))
        val error = runCatching { repository.vote(votedPost, 1) }.exceptionOrNull() as PikabuException
        assertEquals("Голосование закрыто", error.message)
    }

    @Test fun guestWithCookiesIsAskedToSignInForPersonalFeed() = runBlocking {
        val guest = HttpPikabuRepository(cookieProvider = { "guest=1" }, baseUrl = server.url("/").toString())
        server.enqueue(html("<html><body><script data-entry='initParams' type='application/json'>{\"userID\":0,\"csrfToken\":\"t\"}</script><div class='stories-feed'></div></body></html>"))
        val error = runCatching { guest.getFeed(FeedType.SUBSCRIPTIONS, 1) }.exceptionOrNull() as PikabuException
        assertEquals(ContentError.AUTH_REQUIRED, error.kind)
    }

    @Test fun ratingAfterVoteCountsOneVotePerUser() {
        val post = Post("1", "u", "t", rating = Rating(10, 12, 2))
        assertEquals(Rating(11, 13, 2), post.withVote(1).rating)
        assertEquals(Rating(9, 12, 3), post.withVote(1).withVote(-1).rating)
        assertEquals(Rating(10, 12, 2), post.withVote(1).withVote(0).rating)
        assertEquals(Rating(4), Comment("1", rating = Rating(5), vote = 1).withVote(0).rating)
    }

    @Test fun parserReadsVoteOfPostAndComment() {
        val parser = PikabuParser()
        val doc = parser.document("<article class='story' data-story-id='7' data-comments='2' data-vote='-1' data-can-vote='false'><a class='story__title-link' href='/story/_7'>Т</a><div class='story__content-inner'><p>Т</p></div></article>" +
            "<div class='comment' data-id='1' data-meta='pid=0;r=2;v=1'><div class='comment__content'>а</div></div><div class='comment' data-id='2' data-meta='pid=0;r=2;ua=1'><div class='comment__content'>б</div></div>", "https://pikabu.ru/story/_7")
        val post = parser.requestedPost(doc, "https://pikabu.ru/story/_7")
        assertEquals(-1, post.vote)
        assertFalse(post.canVote)
        val comments = parser.comments(doc, emptyMap())
        assertEquals(1, comments[0].vote)
        assertTrue(comments[0].canVote)
        assertFalse(comments[1].canVote)
    }

    @Test fun windows1251DecodedFromHeaderAndMetaWithStablePaging() = runBlocking {
        server.enqueue(html(fixture("mixed.html")))
        val page = repository.getFeed(FeedType.HOT)
        assertEquals("Смешанный пост", page.items.single().title)
        assertTrue(server.takeRequest().path!!.contains("page=1"))
        server.enqueue(html(fixture("mixed.html")).removeHeader("Content-Type"))
        assertEquals("Смешанный пост", repository.getFeed(FeedType.NEW, 2).items.single().title)
        assertEquals("/new?page=2", server.takeRequest().path)
    }

    @Test fun initialSparseCommentsLoadOneBatchAndKeepAllPendingWithParents() = runBlocking {
        val tree = (1..51).joinToString(",") { if (it == 1) "[1,0,[[100,0]]]" else "[$it,0]" }
        val page = fixture("comments.html").replace("\"tree\":[[4,0,[[8,1,[[9,0]]]]],[2,0],[1,0]]", "\"tree\":[$tree]")
        server.enqueue(html(page))
        val entries = (listOf(100) + (1..40).toList()).filter { it != 2 }.take(40)
            .reversed().joinToString(",") { "{\"id\":${4000000000L + it},\"html\":${jsonString(comment((4000000000L + it).toString()))}}" }
        server.enqueue(MockResponse().setBody("{\"result\":true,\"data\":[$entries]}"))
        val result = repository.getComments(server.url("/story/test_10").toString())
        assertEquals(52, result.totalCount)
        assertEquals("4000000001", result.items.first().id)
        assertEquals("4000000001", result.items.first { it.id == "4000000100" }.parentId)
        assertEquals(11, result.pendingIds.size)
        assertEquals("4000000041", result.pendingIds.first())
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body.startsWith("action=get_comments_by_ids&ids="))
        assertEquals(40, body.substringAfter("&ids=").split("%2C").size)
        assertEquals("XMLHttpRequest", request.getHeader("X-Requested-With"))
    }

    @Test fun byIdsAndBranchPreserveTreeNotIndentAndDoNotRefetchPage() = runBlocking {
        server.enqueue(html(fixture("comments.html")))
        server.enqueue(MockResponse().setBody("{\"result\":true,\"data\":[]}"))
        val url = server.url("/story/test_10").toString()
        repository.getComments(url)
        server.enqueue(MockResponse().setBody("{\"result\":true,\"data\":[{\"id\":4000000008,\"html\":${jsonString(comment("4000000008"))}}]}"))
        assertEquals("4000000004", repository.getCommentsByIds(url, listOf("4000000008")).single().parentId)
        server.enqueue(MockResponse().setBody("{\"result\":true,\"data\":{\"html\":${jsonString(comment("4000000009"))}}}"))
        val child = repository.getBranch(url, "4000000008").single()
        assertEquals("4000000008", child.parentId)
        assertEquals(2, child.depth)
        assertEquals(4, server.requestCount)
    }

    @Test fun commentsRightAfterPostReuseItsPage() = runBlocking {
        val url = server.url("/story/test_10").toString()
        server.enqueue(html(fixture("comments.html")))
        server.enqueue(MockResponse().setBody("{\"result\":true,\"data\":[]}"))
        repository.getPost(url)
        repository.getComments(url)
        assertEquals(2, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test fun sessionChangeDiscardsTheCommentPageAndCsrfToken() = runBlocking {
        val url = server.url("/story/test_10").toString()
        server.enqueue(html(fixture("comments.html")))
        repository.getPost(url)
        repository.invalidateSession()
        server.enqueue(html(fixture("comments.html")))
        server.enqueue(MockResponse().setBody("{\"result\":true,\"data\":[]}"))
        repository.getComments(url)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)

        server.enqueue(html(storyPage(userId = 5)))
        server.enqueue(MockResponse().setBody("{\"result\":true}"))
        repository.vote(votedPost, 1)
        server.takeRequest()
        server.takeRequest()
        repository.invalidateSession()
        server.enqueue(html(storyPage(userId = 0)))
        assertKind(ContentError.AUTH_REQUIRED) { repository.vote(votedPost, 1) }
        assertEquals("GET", server.takeRequest().method)
        assertEquals(6, server.requestCount)
    }

    @Test fun aReadFromThePreviousSessionCannotRepopulateTheCommentCache() = runBlocking {
        val url = server.url("/story/test_10").toString()
        server.enqueue(html(fixture("comments.html")).setBodyDelay(200, TimeUnit.MILLISECONDS))
        val old = async { repository.getPost(url) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        repository.invalidateSession()
        old.await()
        server.enqueue(html(fixture("comments.html")))
        server.enqueue(MockResponse().setBody("{\"result\":true,\"data\":[]}"))
        repository.getComments(url)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
        assertEquals(3, server.requestCount)
    }

    @Test fun networkHttpAndChangedResponseAreTypedErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertKind(ContentError.RATE_LIMITED) { repository.getFeed(FeedType.HOT) }
        server.enqueue(MockResponse().setResponseCode(503))
        assertKind(ContentError.NETWORK) { repository.getFeed(FeedType.HOT) }
        server.enqueue(html("<h1>Bot check</h1>"))
        assertKind(ContentError.SCHEMA_CHANGED) { repository.getFeed(FeedType.HOT) }
        server.enqueue(html(fixture("comments.html")))
        server.enqueue(MockResponse().setBody("{\"result\":false,\"message\":\"Нужна авторизация\"}"))
        assertKind(ContentError.AUTH_REQUIRED) { repository.getComments(server.url("/story/test_10").toString()) }
    }

    @Test fun personalFeedsRequireActualSessionAndNeverTreatLoginAsEmptySuccess() = runBlocking {
        assertKind(ContentError.AUTH_REQUIRED) { repository.getFeed(FeedType.SUBSCRIPTIONS) }
        assertEquals(0, server.requestCount)
        repository = HttpPikabuRepository(cookieProvider = { "session=synthetic" }, baseUrl = server.url("/").toString())
        server.enqueue(html("<aside class=sidebar><div data-step=signin></div></aside><div class=stories-feed></div>"))
        assertKind(ContentError.AUTH_REQUIRED) { repository.getFeed(FeedType.SAVED) }
        assertEquals("session=synthetic", server.takeRequest().getHeader("Cookie"))
    }

    @Test fun publicForbiddenDoesNotPretendUserNeedsSignIn() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertKind(ContentError.NETWORK) { repository.getFeed(FeedType.HOT) }
        repository = HttpPikabuRepository(cookieProvider = { "session=synthetic" }, baseUrl = server.url("/").toString())
        server.enqueue(MockResponse().setResponseCode(403))
        assertKind(ContentError.AUTH_REQUIRED) { repository.getFeed(FeedType.SAVED) }
    }

    @Test fun externalRedirectNeverReceivesSession() = runBlocking {
        val outside = MockWebServer()
        outside.start()
        try {
            repository = HttpPikabuRepository(cookieProvider = { "session=synthetic" }, baseUrl = server.url("/").toString())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", outside.url("/")))
            assertKind(ContentError.CONTENT_UNAVAILABLE) { repository.getFeed(FeedType.HOT) }
            assertEquals(0, outside.requestCount)
        } finally { outside.shutdown() }
    }

    @Test fun sessionRenewedByRedirectIsKeptForTheNextRequest() = runBlocking {
        val jar = linkedMapOf("remember" to "old")
        val kept = mutableListOf<String>()
        repository = HttpPikabuRepository(cookieProvider = { jar.entries.joinToString("; ") { "${it.key}=${it.value}" } },
            cookieSink = { _, cookies -> cookies.forEach { kept += it; it.substringBefore(';').split('=', limit = 2).let { (name, value) -> jar[name] = value } } },
            baseUrl = server.url("/").toString())
        // The site renews an expired session by sending new cookies with a redirect to the same page.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/?page=1")
            .addHeader("Set-Cookie", "session=new; path=/; HttpOnly").addHeader("Set-Cookie", "remember=rotated; path=/"))
        server.enqueue(html(fixture("mixed.html")))
        assertTrue(repository.getFeed(FeedType.HOT).items.isNotEmpty())
        assertEquals("remember=old", server.takeRequest().getHeader("Cookie"))
        assertEquals("remember=rotated; session=new", server.takeRequest().getHeader("Cookie"))
        assertEquals(listOf("session=new; path=/; HttpOnly", "remember=rotated; path=/"), kept)
    }

    @Test fun guestKeepsNoCookies() = runBlocking {
        val kept = mutableListOf<String>()
        repository = HttpPikabuRepository(cookieSink = { _, cookies -> kept += cookies }, baseUrl = server.url("/").toString())
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/").addHeader("Set-Cookie", "visitor=1"))
        server.enqueue(html(fixture("mixed.html")).addHeader("Set-Cookie", "visitor=2"))
        assertTrue(repository.getFeed(FeedType.HOT).items.isNotEmpty())
        assertTrue(kept.isEmpty())
        assertNull(server.takeRequest().getHeader("Cookie"))
    }

    @Test fun responseOfAReplacedSessionKeepsNoCookies() = runBlocking {
        val kept = mutableListOf<String>()
        repository = HttpPikabuRepository(cookieProvider = { "remember=old" }, cookieSink = { _, cookies -> kept += cookies },
            baseUrl = server.url("/").toString())
        server.enqueue(html(fixture("mixed.html")).addHeader("Set-Cookie", "remember=rotated").setHeadersDelay(1, TimeUnit.SECONDS))
        val feed = async(Dispatchers.IO) { repository.getFeed(FeedType.HOT) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        repository.invalidateSession()
        feed.await()
        assertTrue(kept.isEmpty())
    }

    @Test fun coroutineCancellationCancelsTransport() = runBlocking {
        server.enqueue(html(fixture("mixed.html")).setHeadersDelay(5, TimeUnit.SECONDS))
        val job = async { repository.getFeed(FeedType.HOT) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun refusesUnboundedCommentsBeforeNetwork() = runBlocking {
        try {
            repository.getCommentsByIds(server.url("/story/test_10").toString(), (1..41).map(Int::toString))
            fail("Expected argument error")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }

    private suspend fun assertKind(kind: ContentError, block: suspend () -> Unit) {
        try { block(); fail("Expected $kind") } catch (e: PikabuException) { assertEquals(kind, e.kind) }
    }
}
