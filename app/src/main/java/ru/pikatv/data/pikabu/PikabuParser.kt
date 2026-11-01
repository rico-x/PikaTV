package ru.pikatv.data.pikabu

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import ru.pikatv.domain.*
import java.net.URI
import java.net.URLDecoder

/** The only layer which understands Pikabu's HTML and compact comment tree. */
internal class PikabuParser {
    data class TreeNode(val id: String, val parentId: String?, val depth: Int, val replyCount: Int)
    data class CommentSnapshot(val tree: LinkedHashMap<String, TreeNode>, val initial: List<Comment>, val total: Int)

    fun document(html: String, url: String): Document = Jsoup.parse(html, url)

    fun feed(doc: Document, page: Int, authenticated: Boolean): FeedPage {
        // A guest with cookies gets an ordinary page without stories, not a login page.
        if (authenticated && (loginReturned(doc) || session(doc)?.userId == 0L)) fail(ContentError.AUTH_REQUIRED, "Для этой ленты требуется вход")
        val articles = doc.select("article.story[data-story-id]")
        val container = doc.selectFirst(".stories-feed")
        if (articles.isEmpty() && container == null) fail(ContentError.SCHEMA_CHANGED, "Не найдена структура ленты")
        val stories = articles.filterNot(::isAd)
        // A deleted post stays in personal lists as a stub; changed markup would leave no post with content.
        if (stories.isNotEmpty() && stories.none { contentOf(it) != null }) fail(ContentError.SCHEMA_CHANGED, "Нет содержимого публикаций")
        val posts = stories.map { post(it, stubAllowed = true) }.distinctBy(Post::id)
        val last = container?.attr("data-page-last")?.toIntOrNull()
        val more = if (last != null) page < last else doc.select("a[href]").any {
            (it.hasClass("pagination__page") || it.attr("data-role") == "next") &&
                Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()?.let { p -> p > page } == true
        }
        return FeedPage(posts, page, more)
    }

    fun requestedPost(doc: Document, url: String): Post {
        val requestedId = storyId(url) ?: fail(ContentError.CONTENT_UNAVAILABLE, "Нужна ссылка на публикацию Pikabu")
        val articles = doc.select("article.story[data-story-id]")
        val article = articles.firstOrNull { it.attr("data-story-id") == requestedId }
            ?: fail(if (articles.isEmpty() && doc.selectFirst(".story__deleted, .story_deleted, .story__message") == null)
                ContentError.SCHEMA_CHANGED else ContentError.CONTENT_UNAVAILABLE, "Публикация недоступна")
        if (isAd(article)) fail(ContentError.AD_BLOCKED, "Партнёрский материал скрыт")
        return post(article)
    }

    /** Site flags plus the feed-post markers of RU AdList (uBlock) for pikabu.ru: sponsor/promo blocks,
     *  affiliate erid links and the legal «Реклама … ИНН» marking. Structural heuristics of the list are not
     *  used: they would hide ordinary posts after a markup change. Pinned and Pikabu+ are not ad markers. */
    fun isAd(article: Element): Boolean = article.attr("data-ca") == "1" ||
        article.selectFirst("[class*=sponsor], .story__advert-info, .story__promo, .story__placeholder") != null ||
        article.select("a[href], [title]").any { el -> listOf(el.attr("href"), el.attr("title")).any(::isAdLink) } ||
        article.select("p").any { legalAdMarking.containsMatchIn(it.text()) } ||
        (article.selectFirst(".story__title-link") == null &&
            article.selectFirst("[id^=adfox_], .ya-advert-2, .story__main > [id]") != null)

    private fun isAdLink(value: String) = value.contains("erid=", true) || value.contains("erid%3D", true) || value.contains("dhwnh.com", true)

    private fun contentOf(article: Element) = article.selectFirst(".story__content-inner") ?: article.selectFirst(".story__content")

    private fun post(article: Element, stubAllowed: Boolean = false): Post {
        val id = article.attr("data-story-id").takeIf { it.matches(Regex("\\d+")) }
            ?: fail(ContentError.SCHEMA_CHANGED, "Некорректный ID публикации")
        val title = article.selectFirst(".story__title-link") ?: fail(ContentError.SCHEMA_CHANGED, "Нет заголовка публикации $id")
        val content = contentOf(article)
        if (content == null && !stubAllowed) fail(ContentError.SCHEMA_CHANGED, "Нет содержимого публикации $id")
        val count = article.attr("data-comments").toIntOrNull()?.takeIf { it >= 0 }
            ?: if (content == null) 0 else fail(ContentError.SCHEMA_CHANGED, "Неизвестно количество комментариев $id")
        val tags = article.select(".story__tags .tags__tag, .story__tags .tag").map { it.text() }.filter(String::isNotBlank).distinct()
        val blocks = content?.let { blocks(it, "post-$id") } ?: listOf(ContentBlock.Unsupported("post-$id-0", "Публикация удалена или недоступна"))
        return Post(id, url(title, "href") ?: "https://pikabu.ru/story/_$id", title.text(),
            Author(article.attr("data-author-id"), article.attr("data-author-name").ifBlank {
                article.selectFirst(".user__nick")?.text() ?: "Пикабу"
            }), blocks, tags, rating(article, id, false), count,
            article.attr("data-story-long") == "true" || article.attr("data-long") == "1" || article.hasClass("story_long") || blocks.size > 12,
            article.attr("data-adult") == "1" || article.hasClass("story_adult") || tags.any { it == "18+" || it.equals("NSFW", true) },
            article.attr("data-horror") == "1" || tags.any { it.equals("Жесть", true) },
            article.attr("data-vote").toIntOrNull()?.coerceIn(-1, 1) ?: 0, article.attr("data-can-vote") != "false",
            reactions(article.selectFirst(".story__emotions")), article.selectFirst(".story__emotions") != null)
    }

    private fun reactions(block: Element?) = block?.select(".emotion-btn[data-id][data-count]").orEmpty().mapNotNull { button ->
        val id = button.attr("data-id").toIntOrNull() ?: return@mapNotNull null
        val count = button.attr("data-count").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        Emotions.reaction(id, count, button.hasClass("emotion-btn_active"))
    }

    /** The page's own session data; a guest page has user ID 0. */
    data class PageSession(val userId: Long, val csrfToken: String)

    fun session(doc: Document): PageSession? = doc.selectFirst("script[data-entry=initParams]")?.let { script ->
        runCatching { JSONObject(script.data()).let { PageSession(it.optLong("userID"), it.optString("csrfToken")) } }.getOrNull()
    }?.takeIf { it.csrfToken.isNotBlank() }

    fun snapshot(doc: Document, postUrl: String): CommentSnapshot {
        val post = requestedPost(doc, postUrl)
        val tree = linkedMapOf<String, TreeNode>()
        doc.selectFirst("script[data-entry=comments-tree]")?.let { script ->
            try {
                val json = JSONObject(script.data().ifBlank { script.html() })
                val min = json.getLong("min")
                if (min < 0) fail(ContentError.SCHEMA_CHANGED, "Некорректный comments-tree.min")
                fun read(nodes: JSONArray, parent: String?, depth: Int) {
                    if (depth > 512 || tree.size > 100_000) fail(ContentError.SCHEMA_CHANGED, "Слишком большое дерево комментариев")
                    for (i in 0 until nodes.length()) {
                        val node = nodes.getJSONArray(i)
                        val offset = node.getLong(0)
                        val id = Math.addExact(min, offset).takeIf { it > 0 }?.toString()
                            ?: fail(ContentError.SCHEMA_CHANGED, "Некорректный ID в дереве")
                        val children = node.optJSONArray(2)
                        if (tree.containsKey(id)) fail(ContentError.SCHEMA_CHANGED, "Повторный ID в дереве")
                        tree[id] = TreeNode(id, parent, depth, children?.length() ?: 0)
                        if (children != null) read(children, id, depth + 1)
                    }
                }
                read(json.getJSONArray("tree"), null, 0)
            } catch (e: PikabuException) { throw e }
            catch (e: Exception) { throw PikabuException(ContentError.SCHEMA_CHANGED, "Изменилась схема дерева комментариев", e) }
        }
        val initial = comments(doc, tree)
        if (tree.isEmpty() && initial.size < post.commentsCount) fail(ContentError.SCHEMA_CHANGED, "Комментарии неполные, дерево отсутствует")
        return CommentSnapshot(tree, order(initial, tree), maxOf(post.commentsCount, tree.size, initial.size))
    }

    fun comments(doc: Document, tree: Map<String, TreeNode>): List<Comment> = doc.select(".comment[data-id]").map { el ->
        val id = el.attr("data-id").takeIf { it.matches(Regex("\\d+")) }
            ?: fail(ContentError.SCHEMA_CHANGED, "Некорректный ID комментария")
        val meta = el.attr("data-meta").split(';').mapNotNull {
            val pair = it.split('=', limit = 2); if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
        val info = tree[id]
        val parent = if (info != null) info.parentId else meta["pid"]?.takeUnless { it == "0" }
            ?: el.parents().firstOrNull { it.hasClass("comment") }?.attr("data-id")
        val content = el.ownComment(".comment__content")
        val deleted = el.hasClass("comment_deleted") || el.hasClass("comment_removed") ||
            el.ownComment(".comment__deleted, .comment__removed") != null
        if (content == null && !deleted) fail(ContentError.SCHEMA_CHANGED, "Нет содержимого комментария $id")
        val author = el.ownComment(".comment__user")
        val emotions = el.ownComment(".comment__emotions")
        Comment(id, parent, Author(el.attr("data-author-id"), author?.attr("data-name")?.ifBlank { author.text() } ?: "Пользователь"),
            content?.let { blocks(it, "comment-$id") } ?: emptyList(), rating(el, id, true, meta["r"]?.toLongOrNull()),
            info?.depth ?: el.attr("data-indent").toIntOrNull()?.coerceAtLeast(0) ?: 0,
            deleted, info?.replyCount ?: 0, meta["v"]?.toIntOrNull()?.coerceIn(-1, 1) ?: 0, meta["ua"] !in setOf("1", "true"),
            reactions(emotions), emotions != null && !deleted)
    }.distinctBy(Comment::id)

    fun order(comments: List<Comment>, tree: Map<String, TreeNode>): List<Comment> {
        val indexes = tree.keys.withIndex().associate { it.value to it.index }
        return comments.distinctBy(Comment::id).sortedBy { indexes[it.id] ?: Int.MAX_VALUE }
    }

    fun loginReturned(doc: Document): Boolean =
        doc.selectFirst(".sidebar [data-step=signin], main #signin-form, main .auth__notice, body > .auth #signin-form") != null ||
            doc.location().let { it.contains("/login") || it.contains("/signin") }

    private fun rating(el: Element, id: String, comment: Boolean, metaTotal: Long? = null): Rating? {
        val block = if (comment) el.ownComment(".comment__rating") else el.selectFirst(".story__rating-block")
        val total = metaTotal ?: el.attr("data-rating").toLongOrNull() ?: if (comment)
            el.ownComment(".comment__rating-count")?.text()?.replace(" ", "")?.toLongOrNull() else null
        fun decode(raw: String): Long? {
            val value = raw.toLongOrNull() ?: return null
            if (value >= 0) return value
            val author = el.attr("data-author-id").toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val numericId = id.toLongOrNull() ?: return null
            if (value == Long.MIN_VALUE) return null
            return ((-value - author % 98) / (numericId % 99 + 1) - 253537024L).takeIf { it >= 0 }
        }
        val plus = block?.let { decode(it.attr("data-pluses")) }
        val minus = block?.let { decode(it.attr("data-minuses")) }
        if (plus != null && minus != null && (total == null || plus - minus == total)) return Rating(total ?: plus - minus, plus, minus)
        return total?.let { Rating(it) }
    }

    /** Walks source order and emits native blocks; scripts, wrappers and ad slots never become UI. */
    private fun blocks(root: Element, prefix: String): List<ContentBlock> {
        val result = mutableListOf<ContentBlock>()
        val text = StringBuilder()
        var style = TextStyle.BODY
        fun nextId() = "$prefix-${result.size}"
        fun flush() {
            val value = text.toString().replace(Regex("[\\t\\x0B\\f\\r ]+"), " ").replace(Regex(" *\\n *"), "\n").trim()
            if (value.isNotEmpty()) result += ContentBlock.Text(nextId(), value, style)
            text.clear()
        }
        fun visit(node: Node) {
            if (node is TextNode) { text.append(node.text()); return }
            val el = node as? Element ?: return
            if (el.normalName() in setOf("script", "style", "noscript", "svg", "button") ||
                el.hasClass("promo-wrapper") || el.attr("data-type").startsWith("promo_") || el.hasClass("story__read-more") ||
                el.classNames().any { it.startsWith("story-block_type_ad") }) return
            if (el.hasClass("story-block_type_fundraising")) {
                flush()
                result += ContentBlock.Unsupported(nextId(), "Интерактивный сбор средств")
                return
            }
            if (el.normalName() == "img") {
                flush()
                val preview = url(el, "data-src", "src")
                val full = url(el, "data-large-image", "data-original") ?: preview
                if (full != null) result += ContentBlock.Image(nextId(), full, preview,
                    el.attr("width").toIntOrNull(), el.attr("height").toIntOrNull(), el.attr("alt").ifBlank { "Изображение" })
                return
            }
            if (el.hasClass("story-block_type_video") || el.normalName() in setOf("video", "iframe") || el.hasClass("comment-video")) {
                flush()
                val video = if (el.normalName() in setOf("video", "iframe")) el else el.selectFirst("video, iframe, .player[data-source]") ?: el
                val direct = video.selectFirst("source[src]")?.let { url(it, "src") } ?: url(video, "src", "data-hls")
                val source = url(video, "data-source")?.let {
                    if (video.attr("data-type") == "video-file" && !it.contains(Regex("\\.(mp4|webm|m3u8)(\\?|$)"))) "$it.mp4" else it
                }
                val available = url(video, "data-video-url") ?: el.selectFirst("a[href]")?.let { url(it, "href") }
                result += ContentBlock.Video(nextId(), direct ?: source ?: available,
                    url(video, "data-poster", "poster", "data-thumbnail-url"), video.attr("data-ratio").toFloatOrNull())
                return
            }
            if (el.normalName() == "a" && el.selectFirst("img, video, iframe") == null) {
                val target = url(el, "href")?.let(::unwrapRedirect)
                if (target != null) {
                    flush()
                    result += ContentBlock.Link(nextId(), target, el.text().ifBlank { target })
                    return
                }
            }
            val block = el.normalName() in setOf("p", "div", "li", "blockquote", "pre", "h1", "h2", "h3", "h4", "figure")
            if (block) flush()
            val previousStyle = style
            style = when (el.normalName()) { "blockquote" -> TextStyle.QUOTE; "pre" -> TextStyle.CODE; "h1", "h2", "h3", "h4" -> TextStyle.HEADING; else -> style }
            if (el.normalName() == "br") text.append('\n')
            el.childNodes().forEach(::visit)
            if (block) flush()
            style = previousStyle
        }
        root.childNodes().forEach(::visit)
        flush()
        return result
    }

    private fun url(el: Element, vararg attributes: String): String? = attributes.firstNotNullOfOrNull { name ->
        el.attr(name).takeIf(String::isNotBlank)?.let { raw ->
            runCatching { URI(el.baseUri()).resolve(raw).toString() }.getOrNull()?.takeIf {
                it.startsWith("https://") || it.startsWith("http://")
            }
        }
    }

    /** Outgoing links are wrapped as pikabu.ru/…?u=<target>&t=…&h=…; the TV keeps the real target. */
    private fun unwrapRedirect(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.host?.removePrefix("www.") != "pikabu.ru") return url
        val params = uri.rawQuery?.split('&').orEmpty()
        if (params.none { it.startsWith("h=") }) return url
        val target = params.firstOrNull { it.startsWith("u=") }?.removePrefix("u=")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: return url
        return target.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: url
    }

    private fun Element.ownComment(selector: String): Element? = select(selector).firstOrNull { it.closest(".comment") === this }

    companion object {
        private val legalAdMarking = Regex("Реклама.*ИНН", RegexOption.IGNORE_CASE)
        fun storyId(url: String): String? = Regex("/story/[^/?#]*_(\\d+)(?:[/?#]|$)").find(url)?.groupValues?.get(1)
        private fun fail(kind: ContentError, message: String): Nothing = throw PikabuException(kind, message)
    }
}
