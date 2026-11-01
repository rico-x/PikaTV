package ru.pikatv.data.pikabu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import ru.pikatv.domain.*
import java.io.IOException
import java.nio.charset.Charset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Website adapter: reads content, and writes only votes. Cookies are supplied per request and never persisted here. */
class HttpPikabuRepository(
    client: OkHttpClient = OkHttpClient(),
    private val cookieProvider: (String) -> String? = { null },
    baseUrl: String = "https://pikabu.ru/",
) : PikabuRepository {
    // Redirects are followed explicitly so a Pikabu cookie can never reach an external host.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val base = baseUrl.toHttpUrl()
    private val parser = PikabuParser()
    private class CachedSnapshot(val snapshot: PikabuParser.CommentSnapshot, val loadedAt: Long)
    @Volatile private var session: PikabuParser.PageSession? = null
    @Volatile private var sessionGeneration = 0L
    private val snapshots = object : LinkedHashMap<String, CachedSnapshot>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSnapshot>?): Boolean = size > 8
    }

    override fun invalidateSession() = synchronized(snapshots) {
        sessionGeneration++
        session = null
        snapshots.clear()
    }

    private fun rememberSession(value: PikabuParser.PageSession?, generation: Long) = synchronized(snapshots) {
        if (generation == sessionGeneration && value != null) session = value
    }

    override suspend fun getFeed(feed: FeedType, page: Int): FeedPage = withContext(Dispatchers.IO) {
        val generation = sessionGeneration
        require(page > 0) { "page must be positive" }
        val target = base.resolve(feed.path)!!.newBuilder().addQueryParameter("page", page.toString()).build()
        if (feed.requiresAccount && cookieProvider(target.toString()).isNullOrBlank())
            throw PikabuException(ContentError.AUTH_REQUIRED, "Для этой ленты требуется вход")
        val response = request(target, requiresAccount = feed.requiresAccount)
        val doc = parser.document(response.text, response.url)
        rememberSession(parser.session(doc), generation)
        parser.feed(doc, page, feed.requiresAccount)
    }

    override suspend fun getPost(url: String): Post = withContext(Dispatchers.IO) {
        val generation = sessionGeneration
        val target = postUrl(url)
        val response = request(target)
        val doc = parser.document(response.text, response.url)
        rememberSession(parser.session(doc), generation)
        val post = parser.requestedPost(doc, target.toString())
        // The same page carries the comment tree: opening the discussion right after does not download it again.
        runCatching { parser.snapshot(doc, target.toString()) }.getOrNull()?.let { store(target, it, generation) }
        post
    }

    override suspend fun getComments(postUrl: String): CommentsPage = withContext(Dispatchers.IO) {
        val target = postUrl(postUrl)
        val snapshot = cachedSnapshot(target, SNAPSHOT_REUSE_MS) ?: loadSnapshot(target)
        val initialIds = snapshot.initial.mapTo(hashSetOf(), Comment::id)
        val pending = snapshot.tree.keys.filterNot(initialIds::contains)
        // Exactly one bounded read. The caller decides when to request subsequent portions.
        val batch = pending.take(MAX_COMMENT_BATCH)
        val loaded = if (batch.isEmpty()) emptyList() else commentsByIds(target, batch, snapshot)
        val consumed = batch.toSet()
        CommentsPage(parser.order(snapshot.initial + loaded, snapshot.tree), snapshot.total, pending.filterNot(consumed::contains))
    }

    override suspend fun getCommentsByIds(postUrl: String, ids: List<String>): List<Comment> = withContext(Dispatchers.IO) {
        require(ids.size <= MAX_COMMENT_BATCH) { "At most $MAX_COMMENT_BATCH comment IDs per request" }
        if (ids.isEmpty()) return@withContext emptyList()
        validateIds(ids)
        val target = postUrl(postUrl)
        val snapshot = cachedSnapshot(target) ?: loadSnapshot(target)
        commentsByIds(target, ids.distinct(), snapshot)
    }

    override suspend fun getBranch(postUrl: String, commentId: String): List<Comment> = withContext(Dispatchers.IO) {
        validateIds(listOf(commentId))
        val target = postUrl(postUrl)
        val snapshot = cachedSnapshot(target) ?: loadSnapshot(target)
        val data = ajax(target, "get_comments_subtree", "id", commentId)
        val html = (data as? JSONObject)?.opt("html") as? String
            ?: throw PikabuException(ContentError.SCHEMA_CHANGED, "Изменилась схема подветки")
        val comments = parser.comments(parser.document(html, target.toString()), snapshot.tree)
        // A known leaf may legitimately have an empty subtree.
        if (html.isNotBlank() && comments.isEmpty()) throw PikabuException(ContentError.SCHEMA_CHANGED, "Нет комментариев в ответе подветки")
        parser.order(comments, snapshot.tree)
    }

    override suspend fun vote(post: Post, value: Int) {
        require(value in -1..1) { "Vote must be -1, 0 or 1" }
        castVote(post.url, "/ajax/vote_story.php", "story_id", post.id, "vote" to value)
    }

    override suspend fun voteComment(postUrl: String, comment: Comment, value: Int) {
        require(value in -1..1) { "Vote must be -1, 0 or 1" }
        castVote(postUrl, "/ajax/vote_comment.php", "comment_id", comment.id, "vote" to value)
    }

    override val reactionChoices get() = Emotions.choices

    override suspend fun react(post: Post, reaction: Reaction, on: Boolean) {
        checkReaction(reaction, on)
        castVote(post.url, "/ajax/save_emotion.php", "story_id", post.id, "type" to reaction.id, "state" to if (on) 1 else 0)
    }

    override suspend fun reactComment(postUrl: String, comment: Comment, reaction: Reaction, on: Boolean) {
        checkReaction(reaction, on)
        castVote(postUrl, "/ajax.php", "comment_id", comment.id, "route" to "user-comment-emotions/save", "type" to reaction.id, "state" to if (on) 1 else 0)
    }

    /** Paid reactions spend the user's purchases, so they can be withdrawn here but never given. */
    private fun checkReaction(reaction: Reaction, on: Boolean) {
        if (on && !reaction.canVote) throw PikabuException(ContentError.CONTENT_UNAVAILABLE, "Эта реакция недоступна")
    }

    private suspend fun castVote(postUrl: String, path: String, key: String, id: String, vararg values: Pair<String, Any>) = withContext(Dispatchers.IO) {
        val generation = sessionGeneration
        validateIds(listOf(id))
        val target = postUrl(postUrl)
        // A token from an older page may belong to the guest session that preceded sign-in.
        val current = session?.takeIf { it.userId > 0 } ?: run {
            val response = request(target)
            parser.session(parser.document(response.text, response.url)).also { rememberSession(it, generation) }
        }
        if (generation != sessionGeneration) throw kotlinx.coroutines.CancellationException("Session changed")
        if (current == null || current.userId <= 0) throw PikabuException(ContentError.AUTH_REQUIRED, "Чтобы голосовать, войдите в аккаунт")
        val body = FormBody.Builder().add(key, id).apply { values.forEach { (name, value) -> add(name, value.toString()) } }.build()
        try {
            val result = runCatching { JSONObject(request(base.resolve(path)!!, body, target.toString(), csrfToken = current.csrfToken).text) }
                .getOrElse { if (it is PikabuException) throw it else throw PikabuException(ContentError.SCHEMA_CHANGED, "Изменилась схема ответа голосования", it) }
            if (!result.optBoolean("result")) throw PikabuException(ContentError.CONTENT_UNAVAILABLE, result.optString("message").ifBlank { "Сайт не принял голос" })
        } catch (e: PikabuException) {
            synchronized(snapshots) { if (generation == sessionGeneration) session = null }
            throw e
        }
    }

    private suspend fun commentsByIds(target: HttpUrl, ids: List<String>, snapshot: PikabuParser.CommentSnapshot): List<Comment> {
        val data = ajax(target, "get_comments_by_ids", "ids", ids.joinToString(",")) as? JSONArray
            ?: throw PikabuException(ContentError.SCHEMA_CHANGED, "Изменилась схема порции комментариев")
        val comments = mutableListOf<Comment>()
        val requested = ids.toSet()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: throw PikabuException(ContentError.SCHEMA_CHANGED, "Некорректный комментарий в ответе")
            val id = item.opt("id")?.toString()
            val html = item.opt("html") as? String
            if (id !in requested || html == null) throw PikabuException(ContentError.SCHEMA_CHANGED, "Несогласованный ID комментария")
            val parsed = parser.comments(parser.document(html, target.toString()), snapshot.tree)
            if (parsed.none { it.id == id }) throw PikabuException(ContentError.SCHEMA_CHANGED, "Комментарий $id не найден в HTML")
            comments += parsed.filter { it.id in requested }
        }
        // The website removes IDs omitted from a successful response (e.g. comments deleted since tree load).
        return parser.order(comments, snapshot.tree)
    }

    private suspend fun ajax(referer: HttpUrl, action: String, key: String, value: String): Any {
        val target = base.resolve("/ajax/comments_actions.php")!!
        val body = FormBody.Builder().add("action", action).add(key, value).build()
        val response = request(target, body, referer.toString())
        try {
            val json = JSONObject(response.text)
            if (!json.has("result")) throw PikabuException(ContentError.SCHEMA_CHANGED, "Нет результата запроса комментариев")
            if (!json.getBoolean("result")) {
                val message = json.optString("message", "Не удалось загрузить комментарии")
                val kind = if (Regex("авторизац|войти|войдите|auth|login", RegexOption.IGNORE_CASE).containsMatchIn(message))
                    ContentError.AUTH_REQUIRED else ContentError.CONTENT_UNAVAILABLE
                throw PikabuException(kind, message)
            }
            return json.get("data")
        } catch (e: PikabuException) { throw e }
        catch (e: Exception) { throw PikabuException(ContentError.SCHEMA_CHANGED, "Изменилась схема ответа комментариев", e) }
    }

    private suspend fun loadSnapshot(target: HttpUrl): PikabuParser.CommentSnapshot {
        val generation = sessionGeneration
        val response = request(target)
        val snapshot = parser.snapshot(parser.document(response.text, response.url), target.toString())
        store(target, snapshot, generation)
        return snapshot
    }

    private fun store(target: HttpUrl, snapshot: PikabuParser.CommentSnapshot, generation: Long) =
        synchronized(snapshots) {
            if (generation == sessionGeneration) snapshots[target.toString()] = CachedSnapshot(snapshot, System.currentTimeMillis())
        }

    /** By-ID and subtree reads may use any cached tree; a full comments load accepts only a recent one. */
    private fun cachedSnapshot(target: HttpUrl, maxAgeMs: Long = Long.MAX_VALUE): PikabuParser.CommentSnapshot? = synchronized(snapshots) {
        snapshots[target.toString()]?.takeIf { System.currentTimeMillis() - it.loadedAt <= maxAgeMs }?.snapshot
    }

    private fun postUrl(value: String): HttpUrl {
        val target = base.resolve(value) ?: throw PikabuException(ContentError.CONTENT_UNAVAILABLE, "Некорректная ссылка")
        if (!sameOrigin(target) || PikabuParser.storyId(target.toString()) == null)
            throw PikabuException(ContentError.CONTENT_UNAVAILABLE, "Поддерживаются ссылки на публикации Pikabu")
        return target.newBuilder().query(null).fragment(null).build()
    }

    private fun sameOrigin(url: HttpUrl) = url.scheme == base.scheme && url.host == base.host && url.port == base.port
    private fun validateIds(ids: List<String>) {
        require(ids.all { it.matches(Regex("[1-9]\\d*")) && it.toLongOrNull() != null }) { "Invalid comment ID" }
    }

    private data class PageResponse(val text: String, val url: String, val redirect: String? = null)

    private suspend fun request(target: HttpUrl, body: RequestBody? = null, referer: String? = null, requiresAccount: Boolean = false, csrfToken: String? = null): PageResponse {
        var url = target
        repeat(6) {
            val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) PikaTV/0.1")
                .header("Accept", if (body == null) "text/html" else "application/json")
            cookieProvider(url.toString())?.takeIf(String::isNotBlank)?.let { builder.header("Cookie", it) }
            referer?.let { builder.header("Referer", it) }
            csrfToken?.let { builder.header("X-Csrf-Token", it) }
            if (body != null) builder.post(body).header("X-Requested-With", "XMLHttpRequest")
            val response = execute(builder.build(), requiresAccount)
            val redirect = response.redirect ?: return response
            val next = url.resolve(redirect)
            if (next == null || !sameOrigin(next)) throw PikabuException(ContentError.CONTENT_UNAVAILABLE, "Внешнее перенаправление не поддерживается")
            if (next.encodedPath.contains("login") || next.encodedPath.contains("signin"))
                throw PikabuException(ContentError.AUTH_REQUIRED, "Сессия истекла, войдите снова")
            url = next
        }
        throw PikabuException(ContentError.NETWORK, "Слишком много перенаправлений")
    }

    private suspend fun execute(request: Request, requiresAccount: Boolean): PageResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(PikabuException(ContentError.NETWORK, "Ошибка соединения с Pikabu", e))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code in listOf(301, 302, 303, 307, 308)) {
                            val location = it.header("Location") ?: throw PikabuException(ContentError.SCHEMA_CHANGED, "Перенаправление без адреса")
                            return@use PageResponse("", it.request.url.toString(), location)
                        }
                        if (!it.isSuccessful) {
                            val kind = when (it.code) { 401 -> ContentError.AUTH_REQUIRED; 403 -> if (requiresAccount) ContentError.AUTH_REQUIRED else ContentError.NETWORK; 429 -> ContentError.RATE_LIMITED; 404, 410 -> ContentError.CONTENT_UNAVAILABLE; else -> ContentError.NETWORK }
                            val message = if (it.code == 403 && !requiresAccount) "Доступ к Pikabu временно ограничен (HTTP 403). Повторите позже." else "Pikabu ответил HTTP ${it.code}"
                            throw PikabuException(kind, message)
                        }
                        val body = it.body ?: throw PikabuException(ContentError.SCHEMA_CHANGED, "Пустой HTTP-ответ")
                        val source = body.source()
                        if (source.request(MAX_RESPONSE_BYTES + 1)) throw PikabuException(ContentError.SCHEMA_CHANGED, "Ответ Pikabu слишком большой")
                        PageResponse(decodePikabuText(source.readByteArray(), body.contentType()?.charset()), it.request.url.toString())
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    val failure = if (e is PikabuException) e else PikabuException(ContentError.NETWORK, "Ошибка чтения ответа Pikabu", e)
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    companion object {
        const val MAX_COMMENT_BATCH = 40
        private const val SNAPSHOT_REUSE_MS = 2 * 60 * 1000L
        private const val MAX_RESPONSE_BYTES = 12L * 1024 * 1024
    }
}

/** Pikabu pages are windows-1251: the HTTP charset wins, then the document meta; UTF-8 only when neither is declared. */
internal fun decodePikabuText(bytes: ByteArray, headerCharset: Charset?): String {
    val probe = String(bytes, Charsets.ISO_8859_1)
    val metaCharset = Regex("charset\\s*=\\s*[\\\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
        .find(probe.take(8192))?.groupValues?.get(1)?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
    return String(bytes, headerCharset ?: metaCharset ?: Charsets.UTF_8)
}
