package ru.pikatv.local

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.pikatv.domain.*

class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("pikatv_local", Context.MODE_PRIVATE)
    // Lists are parsed once per process; stored posts keep only what a feed card shows,
    // because opening a post always reloads it in full.
    private var bookmarkCache: List<Post>? = null
    private var historyCache: List<Post>? = null
    private val readingBlocks by lazy { readReadingBlocks() }

    fun preferences(): ReaderPreferences {
        val d = ReaderPreferences()
        // Every save also stores untouched values, so only an explicit choice outlives a new compact default.
        val explicit = overrides()
        return d.copy(
            textScale = if ("textScale" in explicit) prefs.getFloat("textScale", d.textScale).coerceIn(.85f, 2f) else d.textScale,
            columnWidth = if ("columnWidth" in explicit) prefs.getFloat("columnWidth", d.columnWidth).coerceIn(.4f, 1f) else d.columnWidth,
            showAdult = prefs.getBoolean("showAdult", d.showAdult),
            showHorror = prefs.getBoolean("showHorror", d.showHorror),
            collapsePostsByDefault = prefs.getBoolean("collapsePostsByDefault", d.collapsePostsByDefault),
            expandLongPosts = prefs.getBoolean("expandLongPosts", d.expandLongPosts),
            tagsAtTop = prefs.getBoolean("tagsAtTop", d.tagsAtTop),
            tagsMode = prefs.getInt("tagsMode", d.tagsMode),
            commentsSort = prefs.getInt("commentsSort", d.commentsSort),
            collapseAfterDepth = prefs.getInt("collapseAfterDepth", d.collapseAfterDepth),
            pauseCommentGifs = prefs.getBoolean("pauseCommentGifs", d.pauseCommentGifs),
            autoplay = false,
            colorScheme = prefs.getInt("colorScheme", d.colorScheme),
            accent = prefs.getInt("accent", d.accent),
            menuAccent = prefs.getInt("menuAccent", d.menuAccent),
            blockSpacing = prefs.getInt("blockSpacing", d.blockSpacing),
            voteKeys = prefs.getInt("voteKeys", d.voteKeys),
            plusKey = prefs.getInt("plusKey", d.plusKey),
            minusKey = prefs.getInt("minusKey", d.minusKey),
            showReactions = prefs.getBoolean("showReactions", d.showReactions),
        )
    }

    fun savePreferences(value: ReaderPreferences, overrideKey: String? = null) {
        val editor = prefs.edit().putFloat("textScale", value.textScale).putFloat("columnWidth", value.columnWidth)
            .putBoolean("showAdult", value.showAdult).putBoolean("showHorror", value.showHorror)
            .putBoolean("collapsePostsByDefault", value.collapsePostsByDefault).putBoolean("expandLongPosts", value.expandLongPosts)
            .putBoolean("tagsAtTop", value.tagsAtTop).putInt("tagsMode", value.tagsMode)
            .putInt("commentsSort", value.commentsSort).putInt("collapseAfterDepth", value.collapseAfterDepth)
            .putBoolean("pauseCommentGifs", value.pauseCommentGifs)
            .putInt("colorScheme", value.colorScheme).putInt("accent", value.accent).putInt("menuAccent", value.menuAccent)
            .putInt("blockSpacing", value.blockSpacing).putInt("voteKeys", value.voteKeys).putInt("plusKey", value.plusKey).putInt("minusKey", value.minusKey)
            .putBoolean("showReactions", value.showReactions)
        if (overrideKey != null) editor.putStringSet("overrides", overrides() + overrideKey)
        editor.apply()
    }

    fun overrides(): Set<String> = prefs.getStringSet("overrides", emptySet())!!.toSet()
    fun importPreferences(json: String): ReaderPreferences = PreferencesImporter.import(json, preferences(), overrides()).also { savePreferences(it) }
    fun collapsed(): Set<String> = prefs.getStringSet("collapsed_posts", emptySet())!!.toSet()
    fun expanded(): Set<String> = prefs.getStringSet("expanded_posts", emptySet())!!.toSet()
    fun setCollapsed(id: String, value: Boolean): Set<String> {
        val collapsed = if (value) collapsed() + id else collapsed() - id
        val expanded = if (value) expanded() - id else expanded() + id
        prefs.edit().putStringSet("collapsed_posts", collapsed).putStringSet("expanded_posts", expanded).apply()
        return collapsed
    }
    fun bookmarks(): List<Post> = bookmarkCache ?: readPosts("bookmarks").also { bookmarkCache = it }
    fun history(): List<Post> = historyCache ?: readPosts("history").also { historyCache = it }
    fun toggleBookmark(post: Post) = bookmarks().let { old -> if (old.any { it.id == post.id }) old.filterNot { it.id == post.id } else listOf(summary(post)) + old }
        .also { bookmarkCache = it; savePosts("bookmarks", it) }
    fun recordRead(post: Post) = (listOf(summary(post)) + history().filterNot { it.id == post.id }).take(HISTORY_LIMIT)
        .also { historyCache = it; savePosts("history", it) }
    fun saveFocused(section: AppSection, id: String) { prefs.edit().putString("focus_${section.name}", id).apply() }
    fun focused(section: AppSection): String? = prefs.getString("focus_${section.name}", null)
    fun saveReadingBlock(postId: String, blockId: String) {
        readingBlocks.remove(postId)
        readingBlocks[postId] = blockId
        readingBlocks.keys.take((readingBlocks.size - READING_POSITIONS_LIMIT).coerceAtLeast(0)).forEach(readingBlocks::remove)
        prefs.edit().putString("reading_blocks", JSONArray(readingBlocks.map { (post, block) -> JSONArray(listOf(post, block)) }).toString()).apply()
    }
    fun readingBlock(postId: String): String? = readingBlocks[postId]

    private fun readReadingBlocks() = LinkedHashMap<String, String>().also { map ->
        runCatching {
            val array = JSONArray(prefs.getString("reading_blocks", "[]"))
            for (i in 0 until array.length()) array.getJSONArray(i).let { map[it.getString(0)] = it.getString(1) }
        }
    }

    /** A saved copy cannot know today's votes or the current session, so it offers none until the post is opened. */
    private fun summary(post: Post): Post {
        val preview = post.blocks.firstOrNull { it is ContentBlock.Text }
        return post.copy(vote = 0, canVote = false, reactions = emptyList(), canReact = false, blocks = post.blocks.mapNotNull { block -> when {
            block !is ContentBlock.Text -> block
            block !== preview -> null
            block.text.length > PREVIEW_CHARS -> block.copy(text = block.text.take(PREVIEW_CHARS).trimEnd() + "…")
            else -> block
        } })
    }

    private fun readPosts(key: String): List<Post> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { runCatching { decodePost(array.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun savePosts(key: String, posts: List<Post>) { prefs.edit().putString(key, JSONArray(posts.map(::encodePost)).toString()).apply() }

    private fun encodePost(post: Post) = JSONObject().apply {
        put("id", post.id); put("url", post.url); put("title", post.title); put("authorId", post.author.id); put("author", post.author.name)
        put("tags", JSONArray(post.tags)); put("comments", post.commentsCount); put("long", post.isLong); put("adult", post.isAdult); put("horror", post.isHorror)
        post.rating?.let { rating -> put("rating", JSONObject().apply { put("total", rating.total); put("pluses", rating.pluses); put("minuses", rating.minuses) }) }
        put("blocks", JSONArray(post.blocks.map { block -> JSONObject().apply {
            put("id", block.id)
            when (block) {
                is ContentBlock.Text -> { put("type", "text"); put("text", block.text); put("style", block.style.name) }
                is ContentBlock.Image -> { put("type", "image"); put("url", block.url); put("preview", block.previewUrl); put("width", block.width); put("height", block.height); put("description", block.description) }
                is ContentBlock.Video -> { put("type", "video"); put("url", block.url); put("poster", block.posterUrl); put("ratio", block.aspectRatio); put("title", block.title) }
                is ContentBlock.Link -> { put("type", "link"); put("url", block.url); put("label", block.label) }
                is ContentBlock.Unsupported -> { put("type", "unsupported"); put("description", block.description) }
            }
        } }))
    }

    private fun decodePost(o: JSONObject): Post {
        fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
        val a = o.optJSONArray("blocks") ?: JSONArray()
        val blocks = (0 until a.length()).mapNotNull { index ->
            val b = a.getJSONObject(index); val id = b.getString("id")
            when (b.optString("type")) {
                "text" -> ContentBlock.Text(id, b.optString("text"), runCatching { TextStyle.valueOf(b.optString("style")) }.getOrDefault(TextStyle.BODY))
                "image" -> ContentBlock.Image(id, b.getString("url"), b.stringOrNull("preview"), b.optInt("width").takeIf { it > 0 }, b.optInt("height").takeIf { it > 0 }, b.optString("description", "Изображение"))
                "video" -> ContentBlock.Video(id, b.stringOrNull("url"), b.stringOrNull("poster"), b.optDouble("ratio").toFloat().takeIf { it.isFinite() && it > 0 }, b.optString("title", "Видео"))
                "link" -> ContentBlock.Link(id, b.optString("url"), b.optString("label"))
                else -> ContentBlock.Unsupported(id, b.optString("description", "Неподдерживаемый блок"))
            }
        }
        val tags = o.optJSONArray("tags") ?: JSONArray()
        val r = o.optJSONObject("rating")
        return Post(o.getString("id"), o.getString("url"), o.getString("title"), Author(o.optString("authorId"), o.optString("author")), blocks,
            (0 until tags.length()).map { tags.getString(it) }, r?.let { Rating(it.optLong("total").takeUnless { _ -> it.isNull("total") }, it.optLong("pluses").takeUnless { _ -> it.isNull("pluses") }, it.optLong("minuses").takeUnless { _ -> it.isNull("minuses") }) },
            o.optInt("comments"), o.optBoolean("long"), o.optBoolean("adult"), o.optBoolean("horror"), canVote = false)
    }

    private companion object {
        const val HISTORY_LIMIT = 100
        const val READING_POSITIONS_LIMIT = 300
        const val PREVIEW_CHARS = 2_500
    }
}
