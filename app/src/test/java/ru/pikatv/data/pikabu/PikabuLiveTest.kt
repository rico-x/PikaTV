package ru.pikatv.data.pikabu

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.pikatv.domain.*

/** Opt-in read-only compatibility probe. No session or account data is used. */
class PikabuLiveTest {
    /** Three public pages of each feed: confirmed ads are excluded, kept posts carry no legal ad marking (erid). */
    @Test fun liveFeedsExcludeAdsAndKeepOrdinaryPosts() {
        assumeTrue("Enable public-site checks explicitly", System.getenv("PIKATV_LIVE_READ") == "1")
        val parser = PikabuParser()
        val client = OkHttpClient()
        var excluded = 0
        var kept = 0
        for (type in listOf(FeedType.HOT, FeedType.BEST, FeedType.NEW)) for (page in 1..3) {
            val url = "https://pikabu.ru${type.path}?page=$page"
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) PikaTV/0.1").build()
            val html = client.newCall(request).execute().use { decodePikabuText(it.body!!.bytes(), it.body!!.contentType()?.charset()) }
            val doc = parser.document(html, url)
            val articles = doc.select("article.story[data-story-id]")
            val adIds = articles.filter(parser::isAd).map { it.attr("data-story-id") }.toSet()
            val posts = parser.feed(doc, page, false).items
            assertTrue("Public ${type.name} page $page", posts.isNotEmpty())
            assertTrue(posts.none { it.id in adIds })
            val keptIds = posts.map(Post::id).toSet()
            val keptArticles = articles.filter { it.attr("data-story-id") in keptIds }
            val marked = keptArticles.filter { it.select("a[href*=erid=], a[href*=erid%3D]").isNotEmpty() }
            assertTrue("erid-marked posts kept: ${marked.map { it.attr("data-story-id") }}", marked.isEmpty())
            val labelled = keptArticles.filter { article -> article.select("*").any { it.ownText().trim() == "Реклама" } }
            excluded += adIds.size
            kept += posts.size
            println("LIVE ADS ${type.name} p$page: articles=${articles.size} excluded=${adIds.size} kept=${posts.size} " +
                "pinned kept=${keptArticles.count { it.attr("data-is-fixed-story") == "true" }} " +
                "'Реклама' in kept=${labelled.map { it.attr("data-story-id") }} excluded ids=$adIds")
            Thread.sleep(1_000)
        }
        println("LIVE ADS total: excluded=$excluded kept=$kept")
    }

    @Test fun publicFeedsPostAndBoundedCommentRequests() = runBlocking {
        assumeTrue("Enable public-site checks explicitly", System.getenv("PIKATV_LIVE_READ") == "1")
        val repository = HttpPikabuRepository()
        for (type in listOf(FeedType.HOT, FeedType.BEST, FeedType.NEW)) {
            val feed = repository.getFeed(type)
            assertTrue("Public ${type.name} feed", feed.items.isNotEmpty())
            assertEquals(feed.items.size, feed.items.map(Post::id).distinct().size)
            println("LIVE ${type.name}: ${feed.items.size} posts, hasMore=${feed.hasMore}")
        }
        val url = "https://pikabu.ru/story/est_i_za_i_protiv_versiya_14474129"
        val post = repository.getPost(url)
        assertEquals("14474129", post.id)
        assertTrue(post.blocks.any { it is ContentBlock.Image })
        val comments = repository.getComments(url)
        assertTrue(comments.totalCount > 0)
        assertTrue(comments.items.isNotEmpty())
        // Mobile responses may already embed 41+ comments; our additional request is bounded separately.
        assertTrue(comments.items.size <= comments.totalCount)
        assertEquals(comments.items.size, comments.items.map(Comment::id).distinct().size)
        println("LIVE post=${post.id}: ${post.blocks.size} blocks, ${comments.items.size}/${comments.totalCount} comments, pending=${comments.pendingIds.size}")
        if (comments.pendingIds.isNotEmpty()) {
            val more = repository.getCommentsByIds(url, comments.pendingIds.take(3))
            assertTrue(more.isNotEmpty())
            assertTrue(more.all { it.id in comments.pendingIds.take(3) })
            println("LIVE next IDs: ${more.size} comments")
        }
        val parent = comments.items.firstOrNull { it.replyCount > 0 }
        if (parent != null) {
            val branch = repository.getBranch(url, parent.id)
            assertTrue(branch.isNotEmpty())
            assertTrue(branch.any { it.parentId == parent.id })
            println("LIVE subtree: ${branch.size} comments")
        }
    }
}
