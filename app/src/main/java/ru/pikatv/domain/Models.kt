package ru.pikatv.domain

enum class FeedType(val title: String, val path: String, val requiresAccount: Boolean = false) {
    HOT("Горячее", "/"),
    BEST("Лучшее", "/best"),
    NEW("Свежее", "/new"),
    SUBSCRIPTIONS("Подписки", "/subs", true),
    SAVED("Сохранённые на сайте", "/saved-stories", true),
    VISITED("Просмотренное на сайте", "/visited-stories", true),
}

enum class AppSection(val title: String, val feed: FeedType? = null) {
    HOT("Горячее", FeedType.HOT),
    BEST("Лучшее", FeedType.BEST),
    NEW("Свежее", FeedType.NEW),
    SUBSCRIPTIONS("Подписки", FeedType.SUBSCRIPTIONS),
    BOOKMARKS("Избранное"),
    HISTORY("История"),
    SAVED("Сохранённые на сайте", FeedType.SAVED),
    VISITED("Просмотренное на сайте", FeedType.VISITED),
    SETTINGS("Настройки"),
    ACCOUNT("Аккаунт"),
}

data class Author(val id: String = "", val name: String = "Пикабу")

data class Rating(val total: Long? = null, val pluses: Long? = null, val minuses: Long? = null)

enum class TextStyle { BODY, HEADING, QUOTE, CODE }

sealed interface ContentBlock {
    val id: String

    data class Text(override val id: String, val text: String, val style: TextStyle = TextStyle.BODY) : ContentBlock
    data class Image(
        override val id: String,
        val url: String,
        val previewUrl: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val description: String = "Изображение",
    ) : ContentBlock
    data class Video(
        override val id: String,
        val url: String?,
        val posterUrl: String? = null,
        val aspectRatio: Float? = null,
        val title: String = "Видео",
    ) : ContentBlock
    data class Link(override val id: String, val url: String, val label: String) : ContentBlock
    data class Unsupported(override val id: String, val description: String) : ContentBlock
}

data class Post(
    val id: String,
    val url: String,
    val title: String,
    val author: Author = Author(),
    val blocks: List<ContentBlock> = emptyList(),
    val tags: List<String> = emptyList(),
    val rating: Rating? = null,
    val commentsCount: Int = 0,
    val isLong: Boolean = false,
    val isAdult: Boolean = false,
    val isHorror: Boolean = false,
    /** The signed-in user's vote: 1, 0 or -1. */
    val vote: Int = 0,
    val canVote: Boolean = true,
    val reactions: List<Reaction> = emptyList(),
    val canReact: Boolean = false,
)

/** One emoji reaction under a post or comment. Reactions the user cannot give are only shown. */
data class Reaction(val id: Int, val emoji: String, val count: Int = 0, val active: Boolean = false, val canVote: Boolean = true)

/** The site keeps one reaction per user and item: a new one replaces the old, and one nobody holds disappears. */
private fun List<Reaction>.after(choice: Reaction, on: Boolean): List<Reaction> {
    val released = map { if (it.active) it.copy(count = it.count - 1, active = false) else it }
    val updated = when {
        !on -> released
        released.any { it.id == choice.id } -> released.map { if (it.id == choice.id) it.copy(count = it.count + 1, active = true) else it }
        else -> released + choice.copy(count = 1, active = true)
    }
    return updated.filter { it.count > 0 }
}

fun Post.withReaction(choice: Reaction, on: Boolean) = copy(reactions = reactions.after(choice, on))

/** A card keeps its preview; what the site says about the post and the user's actions comes from the fresh post. */
fun Post.syncedWith(fresh: Post) = copy(rating = fresh.rating ?: rating, commentsCount = fresh.commentsCount,
    vote = fresh.vote, canVote = fresh.canVote, reactions = fresh.reactions, canReact = fresh.canReact)
fun Comment.withReaction(choice: Reaction, on: Boolean) = copy(reactions = reactions.after(choice, on))

/** The rating after a user changes their vote: the site keeps one vote per user and item. */
private fun Rating.afterVote(old: Int, new: Int): Rating {
    fun count(direction: Int, value: Long?) = value?.plus((if (new == direction) 1 else 0) - (if (old == direction) 1 else 0))
    return copy(total = total?.plus(new - old), pluses = count(1, pluses), minuses = count(-1, minuses))
}

fun Post.withVote(value: Int) = copy(vote = value, rating = rating?.afterVote(vote, value))
fun Comment.withVote(value: Int) = copy(vote = value, rating = rating?.afterVote(vote, value))

data class Comment(
    val id: String,
    val parentId: String? = null,
    val author: Author = Author(),
    val blocks: List<ContentBlock> = emptyList(),
    val rating: Rating? = null,
    val depth: Int = 0,
    val isDeleted: Boolean = false,
    val replyCount: Int = 0,
    val vote: Int = 0,
    val canVote: Boolean = true,
    val reactions: List<Reaction> = emptyList(),
    val canReact: Boolean = false,
)

data class FeedPage(val items: List<Post>, val page: Int, val hasMore: Boolean)

data class CommentsPage(
    val items: List<Comment>,
    val totalCount: Int,
    val pendingIds: List<String> = emptyList(),
)

data class ReaderPreferences(
    val textScale: Float = 1f,
    val columnWidth: Float = 1f,
    val showAdult: Boolean = false,
    val showHorror: Boolean = true,
    val collapsePostsByDefault: Boolean = false,
    val expandLongPosts: Boolean = false,
    val tagsAtTop: Boolean = false,
    val tagsMode: Int = 0,
    val commentsSort: Int = 3,
    val collapseAfterDepth: Int = 10,
    val pauseCommentGifs: Boolean = true,
    val autoplay: Boolean = false,
    val colorScheme: Int = 0,
    val accent: Int = 0,
    val menuAccent: Int = 0,
    val blockSpacing: Int = 0,
    val voteKeys: Int = VOTE_KEYS_ON_RATING,
    val plusKey: Int = DEFAULT_PLUS_KEY,
    val minusKey: Int = DEFAULT_MINUS_KEY,
    val showReactions: Boolean = true,
)

/** A key serves one vote: taking the other vote's key hands that vote this one's old key. */
fun ReaderPreferences.withPlusKey(code: Int) = copy(plusKey = code, minusKey = if (code == minusKey) plusKey else minusKey)
fun ReaderPreferences.withMinusKey(code: Int) = copy(minusKey = code, plusKey = if (code == plusKey) minusKey else plusKey)

const val VOTE_KEYS_ON_RATING = 0
const val VOTE_KEYS_ON_POST = 1
const val VOTE_KEYS_OFF = 2
/** KEYCODE_CHANNEL_UP and KEYCODE_CHANNEL_DOWN. */
const val DEFAULT_PLUS_KEY = 166
const val DEFAULT_MINUS_KEY = 167

enum class ContentError { NETWORK, AUTH_REQUIRED, RATE_LIMITED, SCHEMA_CHANGED, CONTENT_UNAVAILABLE, AD_BLOCKED }

class PikabuException(val kind: ContentError, message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
