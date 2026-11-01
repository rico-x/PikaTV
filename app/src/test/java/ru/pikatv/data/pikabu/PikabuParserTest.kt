package ru.pikatv.data.pikabu

import org.junit.Assert.*
import org.junit.Test
import ru.pikatv.domain.*

class PikabuParserTest {
    private val parser = PikabuParser()
    private fun fixture(name: String) = javaClass.getResource("/pikabu/$name")!!.readText()

    @Test fun mixedContentPreservesOrderAndOnlyConfirmedAdsAreFiltered() {
        val doc = parser.document(fixture("mixed.html"), "https://pikabu.ru/")
        val feed = parser.feed(doc, 1, false)
        assertEquals(listOf("14474129"), feed.items.map(Post::id))
        assertTrue(feed.hasMore)
        val blocks = feed.items.single().blocks
        assertEquals(listOf(ContentBlock.Text::class.java, ContentBlock.Image::class.java, ContentBlock.Text::class.java,
            ContentBlock.Video::class.java, ContentBlock.Text::class.java), blocks.map { it.javaClass })
        assertEquals("До картинки", (blocks[0] as ContentBlock.Text).text)
        assertEquals("https://example.org/full.jpg", (blocks[1] as ContentBlock.Image).url)
        assertEquals("https://example.org/real.mp4", (blocks[3] as ContentBlock.Video).url)
        assertEquals(TextStyle.QUOTE, (blocks[4] as ContentBlock.Text).style)
        assertEquals(listOf("Тест"), feed.items.single().tags)
        assertFalse(parser.feed(doc, 2, false).hasMore)
    }

    @Test fun legallyMarkedAndAffiliatePostsAreAdsButMentioningAdvertisingIsNot() {
        fun article(id: Int, body: String) = "<article class=\"story\" data-story-id=\"$id\" data-comments=\"0\"><a class=\"story__title-link\" href=\"/story/x_$id\">Пост $id</a>" +
            "<div class=\"story__main\"><div class=\"story__content\"><div class=\"story__content-inner\">$body</div></div></div></article>"
        val html = "<meta charset=\"windows-1251\"><div class=\"stories-feed\" data-page-last=\"1\">" +
            article(11, "<p>Скидки</p><p>Реклама. ООО «Пример» ИНН 0000000000, erid: synthetic</p>") +
            article(12, "<p><a href=\"/story/x_12?u=https%3A%2F%2Fshop.example%2F%3Ferid%3Dsynthetic&t=x&h=y\">товар</a></p>") +
            article(13, "<p><a href=\"https://example.org\" title=\"https://go.example/?erid=synthetic\">товар</a></p>") +
            article(14, "<div class=\"story__promo\">Промо</div><p>Текст</p>") +
            article(15, "<p>Реклама на телевидении надоела, поэтому читаю Пикабу.</p>") +
            "<article class=\"story\" data-story-id=\"16\"><div class=\"story__main\"><div id=\"slot-1\"></div></div></article></div>"
        assertEquals(listOf("15"), parser.feed(parser.document(html, "https://pikabu.ru/"), 1, false).items.map(Post::id))
    }

    @Test fun outgoingLinkKeepsRealTargetInsteadOfSiteRedirect() {
        val wrapped = "<a href=\"/story/x_1?u=https%3A%2F%2Fexample.org%2Fpage%3Fa%3D1&t=label&h=abc\">источник</a>"
        val internal = "<a href=\"/story/other_2?u=1\">другой пост</a>"
        val html = fixture("mixed.html").replace("<p>Между <b>блоками</b></p>", "<p>$wrapped $internal</p>")
        val links = parser.feed(parser.document(html, "https://pikabu.ru/"), 1, false).items.single().blocks.filterIsInstance<ContentBlock.Link>()
        assertEquals(listOf("https://example.org/page?a=1", "https://pikabu.ru/story/other_2?u=1"), links.map { it.url })
        assertEquals("источник", links.first().label)
    }

    @Test fun ratingUsesLongAndHidesInconsistentDetail() {
        // Formula from observed public HTML, independently calculated using 64-bit integers.
        val id = 14474129L
        val author = 2547559L
        fun encode(n: Long) = -((n + 253537024L) * (id % 99 + 1) + author % 98)
        val html = fixture("mixed.html").replace("-7606152653", encode(1396).toString()).replace("-7606112333", encode(52).toString())
        val rating = parser.feed(parser.document(html, "https://pikabu.ru"), 1, false).items.single().rating!!
        assertEquals(Rating(1344, 1396, 52), rating)
        assertTrue(encode(1396) < Int.MIN_VALUE)
        val mismatch = html.replace("data-rating=\"1344\"", "data-rating=\"999\"")
        assertEquals(Rating(999), parser.feed(parser.document(mismatch, "https://pikabu.ru"), 1, false).items.single().rating)
    }

    @Test fun sparseTreeKeepsLongIdsParentAndServerOrder() {
        val snapshot = parser.snapshot(parser.document(fixture("comments.html"), "https://pikabu.ru/story/test_10"), "https://pikabu.ru/story/test_10")
        assertEquals(5, snapshot.total)
        assertEquals(listOf("4000000004", "4000000008", "4000000009", "4000000002", "4000000001"), snapshot.tree.keys.toList())
        assertEquals("4000000004", snapshot.tree["4000000008"]!!.parentId)
        assertEquals(2, snapshot.tree["4000000009"]!!.depth)
        assertEquals(listOf("4000000002"), snapshot.initial.map(Comment::id))
    }

    @Test fun requestedStoryDoesNotBecomeRecommendedStory() {
        val doc = parser.document(fixture("mixed.html"), "https://pikabu.ru/story/missing_999")
        assertKind(ContentError.CONTENT_UNAVAILABLE) { parser.requestedPost(doc, "https://pikabu.ru/story/missing_999") }
        assertKind(ContentError.AD_BLOCKED) { parser.requestedPost(doc, "https://pikabu.ru/story/ad_2") }
    }

    @Test fun schemaAndLoginDoNotBecomeEmptyFeedOrZeroComments() {
        assertKind(ContentError.SCHEMA_CHANGED) { parser.feed(parser.document("<h1>Captcha</h1>", "https://pikabu.ru"), 1, false) }
        assertKind(ContentError.AUTH_REQUIRED) { parser.feed(parser.document("<main><form id=signin-form></form></main>", "https://pikabu.ru/subs"), 1, true) }
        val broken = fixture("comments.html").replace("\"min\":4000000000", "\"min\":\"bad\"")
        assertKind(ContentError.SCHEMA_CHANGED) { parser.snapshot(parser.document(broken, "https://pikabu.ru"), "https://pikabu.ru/story/test_10") }
    }

    @Test fun reactionsAreReadAndThoseTheUserCannotGiveAreOnlyShown() {
        val parser = PikabuParser()
        val head = "<article class='story' data-story-id='7' data-comments='2'><a class='story__title-link' href='/story/_7'>Т</a><div class='story__content-inner'><p>Т</p></div>"
        val block = "<div class='story__emotions'><div class='emotions'><button data-id='15' data-count='136' class='emotion-btn emotion-btn_active'></button>" +
            "<button data-id='26' data-count='1' class='emotion-btn'></button><button data-id='999' data-count='2' class='emotion-btn'></button>" +
            "<button class='emotion-btn emotion-btn_expand' data-count='0' hidden></button><button data-id='15' class='emotion-btn emotion-btn_like' hidden></button><button class='emotion-btn emotion-btn_add'></button></div></div>"
        val post = parser.feed(parser.document("$head$block</article>", "https://pikabu.ru/"), 1, false).items.single()
        assertTrue(post.canReact)
        assertEquals(listOf(15 to 136, 26 to 1, 999 to 2), post.reactions.map { it.id to it.count })
        assertEquals(listOf(true, false, false), post.reactions.map { it.canVote })
        assertTrue(post.reactions[0].active)
        val old = parser.feed(parser.document("$head</article>", "https://pikabu.ru/"), 1, false).items.single()
        assertFalse(old.canReact)
        assertTrue(old.reactions.isEmpty())
    }

    @Test fun commentKeepsItsOwnReactionsNotThoseOfItsReplies() {
        val parser = PikabuParser()
        val emotions = { id: Int, count: Int -> "<div class='comment__emotions'><div class='emotions'><button data-id='$id' data-count='$count' class='emotion-btn'></button><button class='emotion-btn emotion-btn_add'></button></div></div>" }
        val doc = parser.document("<div class='comment' data-id='1' data-meta='pid=0;r=2'><div class='comment__body'><div class='comment__content'>а</div>${emotions(15, 3)}</div>" +
            "<div class='comment__children'><div class='comment' data-id='2' data-meta='pid=1;r=1'><div class='comment__body'><div class='comment__content'>б</div>${emotions(1, 7)}</div></div></div></div>" +
            "<div class='comment' data-id='3' data-meta='pid=0;r=1'><div class='comment__body'><div class='comment__content'>в</div></div></div>", "https://pikabu.ru/story/_7")
        val comments = parser.comments(doc, emptyMap()).associateBy { it.id }
        assertEquals(listOf(15 to 3), comments.getValue("1").reactions.map { it.id to it.count })
        assertEquals(listOf(1 to 7), comments.getValue("2").reactions.map { it.id to it.count })
        assertTrue(comments.getValue("1").canReact)
        assertFalse(comments.getValue("3").canReact)
    }

    @Test fun deletedPostInPersonalListDoesNotBreakTheFeed() {
        val parser = PikabuParser()
        fun story(id: Int, content: String) = "<article class='story' data-story-id='$id'" + (if (content.isEmpty()) "" else " data-comments='2'") +
            "><a class='story__title-link' href='/story/_$id'>Пост $id</a>$content</article>"
        val page = story(1, "<div class='story__content-inner'><p>Текст</p></div>") + story(2, "")
        val posts = parser.feed(parser.document(page, "https://pikabu.ru/saved"), 1, false).items
        assertEquals(listOf("1", "2"), posts.map { it.id })
        assertEquals("Публикация удалена или недоступна", (posts[1].blocks.single() as ContentBlock.Unsupported).description)
        assertEquals(0, posts[1].commentsCount)
        assertKind(ContentError.SCHEMA_CHANGED) { parser.feed(parser.document(story(3, "") + story(4, ""), "https://pikabu.ru/saved"), 1, false) }
    }

    @Test fun storyInsideCommentKeepsItsTextWithoutTheSiteExpandLabel() {
        val parser = PikabuParser()
        val doc = parser.document("""<div class="comment" data-id="1" data-meta="pid=0;r=2"><div class="comment__content">
            <article class="story story_comment" data-story-id="9"><div class="story__content"><div class="story__content-inner">
            <div class="story-block story-block_type_text"><p>Весь текст</p></div></div>
            <div class="story__read-more"><span class="story__read-more-label">Показать полностью</span> <span>1</span></div></div></article>
            </div></div>""", "https://pikabu.ru/story/_7")
        assertEquals(listOf("Весь текст"), parser.comments(doc, emptyMap()).single().blocks.map { (it as ContentBlock.Text).text })
    }

    @Test fun deletedCommentRemainsWithTreeParent() {
        val snapshot = parser.snapshot(parser.document(fixture("comments.html"), "https://pikabu.ru"), "https://pikabu.ru/story/test_10")
        val html = "<div class='comment comment_deleted' data-id='4000000009' data-meta='pid=99'><div class='comment__deleted'>Удалён</div></div>"
        val comment = parser.comments(parser.document(html, "https://pikabu.ru"), snapshot.tree).single()
        assertTrue(comment.isDeleted)
        assertEquals("4000000008", comment.parentId)
        assertEquals(2, comment.depth)
    }

    @Test fun nestedDeletedChildDoesNotSupplyParentMetadataOrDeleteParent() {
        val html = """<div class="comment" data-id="1"><div class="comment__content"><p>Родитель</p></div>
            <div class="comment__children"><div class="comment comment_deleted" data-id="2">
            <div class="comment__user" data-name="Ребёнок"></div><div class="comment__deleted">Удалён</div>
            <div class="comment__rating-count">99</div></div></div></div>"""
        val comments = parser.comments(parser.document(html, "https://pikabu.ru"), emptyMap())
        val parent = comments.first()
        assertFalse(parent.isDeleted)
        assertEquals("Пользователь", parent.author.name)
        assertNull(parent.rating)
        assertEquals("1", comments.last().parentId)
        assertTrue(comments.last().isDeleted)
    }

    private fun assertKind(expected: ContentError, block: () -> Unit) {
        try { block(); fail("Expected $expected") } catch (e: PikabuException) { assertEquals(expected, e.kind) }
    }
}
