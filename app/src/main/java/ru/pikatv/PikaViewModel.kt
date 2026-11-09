package ru.pikatv

import android.app.Application
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.pikatv.data.account.AccountService
import ru.pikatv.data.pikabu.HttpPikabuRepository
import ru.pikatv.domain.*
import ru.pikatv.local.LocalStore

enum class Screen { FEED, READER, COMMENTS, SETTINGS, ACCOUNT }

data class AppState(
    val section: AppSection = AppSection.HOT,
    val screen: Screen = Screen.FEED,
    val posts: List<Post> = emptyList(),
    val feedLoading: Boolean = false,
    val feedError: String? = null,
    val feedNeedsLogin: Boolean = false,
    val hasMore: Boolean = false,
    val page: Int = 0,
    val currentPost: Post? = null,
    val postLoading: Boolean = false,
    val postError: String? = null,
    val comments: List<Comment> = emptyList(),
    val commentsLoading: Boolean = false,
    val commentsError: String? = null,
    val commentsTotal: Int = 0,
    val commentsPostId: String? = null,
    val pendingCommentIds: List<String> = emptyList(),
    val preferences: ReaderPreferences = ReaderPreferences(),
    val collapsedIds: Set<String> = emptySet(),
    val expandedIds: Set<String> = emptySet(),
    val bookmarkedIds: Set<String> = emptySet(),
    val accountName: String? = null,
    val accountBusy: Boolean = false,
    val message: String? = null,
    val loginRequest: Int = 0,
    val navigationKey: Int = 0,
    val initialFeedFocus: String? = null,
    val initialBlockId: String? = null,
)

fun AppState.isCollapsed(post: Post): Boolean = post.id !in expandedIds &&
    (post.id in collapsedIds || preferences.collapsePostsByDefault)

private fun pikabuCookies(url: String): String? = CookieManager.getInstance().getCookie(url)

/** Keeps cookies Pikabu sets in its responses, as the WebView itself would, so a renewed session replaces the expired one. */
private fun keepPikabuCookies(url: String, cookies: List<String>) {
    val manager = CookieManager.getInstance()
    cookies.forEach { manager.setCookie(url, it) }
    manager.flush()
}

class PikaViewModel(
    application: Application,
    private val repository: PikabuRepository = HttpPikabuRepository(cookieProvider = ::pikabuCookies, cookieSink = ::keepPikabuCookies),
    private val loadInitialFeed: Boolean = true,
) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val mutableState = MutableStateFlow(AppState(preferences = store.preferences(), collapsedIds = store.collapsed(), expandedIds = store.expanded(), bookmarkedIds = store.bookmarks().map { it.id }.toSet(), initialFeedFocus = store.focused(AppSection.HOT)))
    val state = mutableState.asStateFlow()
    private val cache = mutableMapOf<AppSection, AppState>()
    private val focused = mutableMapOf<AppSection, String>()
    private var lastContentSection = AppSection.HOT
    private var currentReadingBlock: String? = null
    private var feedJob: Job? = null
    private var postJob: Job? = null
    private var commentsJob: Job? = null
    private data class VoteRequest(val session: Long, var job: Job? = null)
    private val votingIds = mutableMapOf<String, VoteRequest>()
    @Volatile private var sessionGeneration = 0L
    // Only one full-post read runs at a time. Its flags outlive a POST completed before this GET.
    private class PostRead(val id: String, var voteChanged: Boolean, var reactionsChanged: Boolean)
    private var postRead: PostRead? = null
    private var retryFeedAppend = false
    // «Повторить» repeats the request that failed; a failed portion never reloads the loaded tree.
    private var lastCommentsRequest: () -> Unit = ::reloadComments

    init { if (loadInitialFeed) refreshFeed() }

    fun selectSection(section: AppSection) {
        val old = mutableState.value
        if (section == old.section && old.screen == Screen.FEED) { reloadFeed(); return }
        if (old.screen == Screen.FEED) cache[old.section] = old
        feedJob?.cancel(); postJob?.cancel(); commentsJob?.cancel()
        if (section.feed != null || section == AppSection.BOOKMARKS || section == AppSection.HISTORY) lastContentSection = section
        // A failed load leaves no posts: showing it again would pass for an empty feed.
        val cached = cache[section]?.takeIf { it.posts.isNotEmpty() }
        val screen = when (section) { AppSection.SETTINGS -> Screen.SETTINGS; AppSection.ACCOUNT -> Screen.ACCOUNT; else -> Screen.FEED }
        mutableState.update { it.copy(section = section, screen = screen, posts = cached?.posts.orEmpty(), page = cached?.page ?: 0, hasMore = cached?.hasMore ?: false,
            feedLoading = false, feedError = null, feedNeedsLogin = false, postLoading = false, commentsLoading = false, navigationKey = it.navigationKey + 1,
            initialFeedFocus = focused[section] ?: store.focused(section)) }
        if (screen == Screen.FEED && (cached == null || section == AppSection.BOOKMARKS || section == AppSection.HISTORY)) refreshFeed()
        // The session outlives the process, so the screen asks the site instead of offering to sign in again.
        if (section == AppSection.ACCOUNT && mutableState.value.accountName == null) refreshAccount(importSettings = false, quiet = true)
    }

    /** Choosing the current feed is how a remote asks for new posts, and reading starts again from the top. */
    private fun reloadFeed() {
        mutableState.update { it.copy(posts = emptyList(), page = 0, hasMore = false, initialFeedFocus = null, navigationKey = it.navigationKey + 1) }
        refreshFeed()
    }

    fun refreshFeed() = loadFeed(append = false)
    fun retryFeed() = loadFeed(append = retryFeedAppend)
    fun loadMoreFeed() { if (!mutableState.value.feedLoading && mutableState.value.hasMore) loadFeed(append = true) }

    private fun loadFeed(append: Boolean) {
        val section = mutableState.value.section
        if (section == AppSection.BOOKMARKS || section == AppSection.HISTORY) {
            val posts = if (section == AppSection.BOOKMARKS) store.bookmarks() else store.history()
            mutableState.update { it.copy(posts = posts, feedLoading = false, feedError = null, hasMore = false, page = 1) }
            return
        }
        val feed = section.feed ?: return
        val page = if (append) mutableState.value.page + 1 else 1
        retryFeedAppend = append
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            mutableState.update { it.copy(feedLoading = true, feedError = null, feedNeedsLogin = false) }
            try {
                val loaded = repository.getFeed(feed, page)
                mutableState.update { current ->
                    if (current.section != section) current else current.copy(posts = ((if (append) current.posts else emptyList()) + loaded.items).distinctBy { it.id },
                        feedLoading = false, page = loaded.page, hasMore = loaded.hasMore)
                }
                cache[section] = mutableState.value
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(feedLoading = false, feedError = errorMessage(e),
                feedNeedsLogin = (e as? PikabuException)?.kind == ContentError.AUTH_REQUIRED) } }
        }
    }

    fun openPost(post: Post) {
        cache[mutableState.value.section] = mutableState.value
        postJob?.cancel(); commentsJob?.cancel()
        currentReadingBlock = store.readingBlock(post.id)
        mutableState.update { it.copy(screen = Screen.READER, currentPost = post, postLoading = true, postError = null, initialBlockId = currentReadingBlock,
            comments = emptyList(), commentsPostId = null, commentsError = null, commentsLoading = false, commentsTotal = post.commentsCount, pendingCommentIds = emptyList(),
            navigationKey = it.navigationKey + 1) }
        store.recordRead(post)
        loadFullPost(post)
    }

    private fun loadFullPost(post: Post) {
        postJob?.cancel()
        val read = PostRead(post.id, "post:${post.id}" in votingIds, "reaction:${post.id}" in votingIds)
        postRead = read
        postJob = viewModelScope.launch {
            try {
                val fetched = repository.getPost(post.url)
                // A press made while the post was loading is newer than the page that just arrived.
                val shown = mutableState.value.currentPost?.takeIf { it.id == post.id }
                val full = fetched.let { if (shown != null && read.voteChanged) it.copy(vote = shown.vote, rating = shown.rating) else it }
                    .let { if (shown != null && read.reactionsChanged) it.copy(reactions = shown.reactions) else it }
                if (shown != null) mutableState.update { it.copy(currentPost = full, postLoading = false) }
                updatePost(full.id) { it.syncedWith(full) }
                store.recordRead(full)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(postLoading = false, postError = errorMessage(e)) } }
            finally { if (postRead === read) postRead = null }
        }
    }

    fun openComments() { mutableState.value.currentPost?.let { openComments(it) } }

    fun openComments(post: Post) {
        val old = mutableState.value
        val needsFullPost = old.screen == Screen.FEED || old.currentPost?.id != post.id
        if (old.screen == Screen.FEED) cache[old.section] = old
        if (needsFullPost) {
            store.recordRead(post)
            currentReadingBlock = store.readingBlock(post.id)
        }
        mutableState.update { it.copy(screen = Screen.COMMENTS, currentPost = post, comments = emptyList(), commentsTotal = post.commentsCount,
            pendingCommentIds = emptyList(), initialBlockId = currentReadingBlock,
            postLoading = needsFullPost || it.postLoading, postError = if (needsFullPost) null else it.postError,
            navigationKey = it.navigationKey + 1) }
        // Feed/history cards contain only a summary. Fetch the complete post even when the
        // discussion is opened first, so Back always returns to the complete reader.
        if (needsFullPost) loadFullPost(post)
        reloadComments()
    }

    /** The list under a post starts once the reader reaches its end. */
    fun loadCommentsUnderPost() {
        val current = mutableState.value
        if (current.currentPost != null && current.commentsPostId != current.currentPost.id && !current.commentsLoading) reloadComments()
    }

    fun retryComments() = lastCommentsRequest()

    fun reloadComments() {
        val post = mutableState.value.currentPost ?: return
        lastCommentsRequest = ::reloadComments
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            mutableState.update { it.copy(commentsPostId = post.id, commentsLoading = true, commentsError = null) }
            try {
                val loaded = repository.getComments(post.url)
                mutableState.update { it.copy(comments = orderedComments(loaded.items), commentsTotal = loaded.totalCount,
                    pendingCommentIds = loaded.pendingIds, commentsLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(commentsLoading = false, commentsError = errorMessage(e)) } }
        }
    }

    fun loadMoreComments() {
        val current = mutableState.value
        if (current.commentsLoading || current.pendingCommentIds.isEmpty()) return
        val post = current.currentPost ?: return
        val ids = current.pendingCommentIds.take(40)
        lastCommentsRequest = ::loadMoreComments
        commentsJob = viewModelScope.launch {
            mutableState.update { it.copy(commentsLoading = true, commentsError = null) }
            try {
                val loaded = repository.getCommentsByIds(post.url, ids)
                mutableState.update { it.copy(comments = orderedComments(it.comments + loaded), pendingCommentIds = it.pendingCommentIds.filterNot { id -> id in ids }, commentsLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(commentsLoading = false, commentsError = errorMessage(e)) } }
        }
    }

    fun openBranch(comment: Comment) {
        val post = mutableState.value.currentPost ?: return
        if (mutableState.value.commentsLoading) return
        lastCommentsRequest = { openBranch(comment) }
        commentsJob = viewModelScope.launch {
            mutableState.update { it.copy(commentsLoading = true, commentsError = null) }
            try {
                val loaded = repository.getBranch(post.url, comment.id)
                val ids = loaded.map { it.id }.toSet()
                mutableState.update { it.copy(comments = orderedComments(it.comments + loaded), pendingCommentIds = it.pendingCommentIds.filterNot { id -> id in ids }, commentsLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(commentsLoading = false, commentsError = errorMessage(e)) } }
        }
    }

    private fun orderedComments(items: List<Comment>): List<Comment> {
        val unique = items.distinctBy { it.id }
        val ids = unique.map { it.id }.toSet()
        val children = unique.groupBy { it.parentId }
        val result = mutableListOf<Comment>()
        val seen = mutableSetOf<String>()
        fun visit(comment: Comment) {
            if (!seen.add(comment.id)) return
            result += comment
            children[comment.id].orEmpty().forEach(::visit)
        }
        unique.filter { it.parentId == null || it.parentId !in ids }.forEach(::visit)
        unique.forEach(::visit)
        return result
    }

    fun back() {
        when (mutableState.value.screen) {
            Screen.COMMENTS -> { commentsJob?.cancel(); mutableState.update { it.copy(screen = Screen.READER, initialBlockId = currentReadingBlock, navigationKey = it.navigationKey + 1) } }
            Screen.READER -> { postJob?.cancel(); commentsJob?.cancel(); mutableState.update { it.copy(screen = Screen.FEED, initialFeedFocus = focused[it.section] ?: store.focused(it.section), navigationKey = it.navigationKey + 1) } }
            Screen.SETTINGS, Screen.ACCOUNT -> selectSection(lastContentSection)
            Screen.FEED -> Unit
        }
    }

    fun focusPost(id: String) { focused[mutableState.value.section] = id; store.saveFocused(mutableState.value.section, id) }
    fun focusBlock(id: String) { currentReadingBlock = id; mutableState.value.currentPost?.let { store.saveReadingBlock(it.id, id) } }
    fun toggleBookmark(post: Post) { val saved = store.toggleBookmark(post); mutableState.update { it.copy(bookmarkedIds = saved.map { p -> p.id }.toSet()) }; if (mutableState.value.section == AppSection.BOOKMARKS && mutableState.value.screen == Screen.FEED) refreshFeed() }
    fun toggleCollapsed(post: Post) {
        val collapsed = store.setCollapsed(post.id, !mutableState.value.isCollapsed(post))
        mutableState.update { it.copy(collapsedIds = collapsed, expandedIds = store.expanded()) }
    }
    /** Pressing the same direction again withdraws the vote, as on the site. */
    fun vote(post: Post, direction: Int) {
        val current = shownPost(post)
        if (!current.canVote) return
        val value = if (current.vote == direction) 0 else direction
        castVote("post:${post.id}", { repository.vote(current, value) },
            show = { postRead?.takeIf { it.id == post.id }?.voteChanged = true; updatePost(post.id) { it.withVote(value) } },
            undo = { updatePost(post.id) { it.withVote(current.vote) } })
    }

    /** The post the user sees: the full one on reading screens, the card in a feed. */
    private fun shownPost(post: Post): Post {
        val state = mutableState.value
        val open = state.currentPost?.takeIf { it.id == post.id && state.screen != Screen.FEED }
        return open ?: state.posts.firstOrNull { it.id == post.id } ?: post
    }

    /** Changes every copy of a post, and each copy keeps whatever else changed in it meanwhile. */
    private fun updatePost(id: String, change: (Post) -> Post) {
        fun List<Post>.changed() = map { if (it.id == id) change(it) else it }
        mutableState.update { it.copy(posts = it.posts.changed(), currentPost = it.currentPost?.let { open -> if (open.id == id) change(open) else open }) }
        cache.keys.toList().forEach { section -> cache[section] = cache.getValue(section).run { copy(posts = posts.changed()) } }
    }

    private fun updateComment(id: String, change: (Comment) -> Comment) =
        mutableState.update { it.copy(comments = it.comments.map { item -> if (item.id == id) change(item) else item }) }

    val reactionChoices get() = repository.reactionChoices

    fun react(post: Post, reaction: Reaction) {
        val current = shownPost(post)
        if (!current.canReact) return
        val on = current.reactions.none { it.id == reaction.id && it.active }
        castVote("reaction:${post.id}", { repository.react(current, reaction, on) },
            show = { postRead?.takeIf { it.id == post.id }?.reactionsChanged = true; updatePost(post.id) { it.withReaction(reaction, on) } },
            undo = { updatePost(post.id) { it.copy(reactions = current.reactions) } })
    }

    fun reactComment(comment: Comment, reaction: Reaction) {
        val post = mutableState.value.currentPost ?: return
        val current = mutableState.value.comments.firstOrNull { it.id == comment.id } ?: return
        if (!current.canReact) return
        val on = current.reactions.none { it.id == reaction.id && it.active }
        castVote("reaction-comment:${comment.id}", { repository.reactComment(post.url, current, reaction, on) },
            show = { updateComment(comment.id) { it.withReaction(reaction, on) } }, undo = { updateComment(comment.id) { it.copy(reactions = current.reactions) } })
    }

    fun voteComment(comment: Comment, direction: Int) {
        val post = mutableState.value.currentPost ?: return
        val current = mutableState.value.comments.firstOrNull { it.id == comment.id } ?: return
        if (!current.canVote) return
        val value = if (current.vote == direction) 0 else direction
        castVote("comment:${comment.id}", { repository.voteComment(post.url, current, value) },
            show = { updateComment(comment.id) { it.withVote(value) } }, undo = { updateComment(comment.id) { it.withVote(current.vote) } })
    }

    /** The press shows at once and a refused one is undone. A repeat while the request runs changes nothing: its result is already on screen. */
    private fun castVote(key: String, send: suspend () -> Unit, show: () -> Unit, undo: () -> Unit) {
        if (key in votingIds) return
        val request = VoteRequest(sessionGeneration)
        votingIds[key] = request
        show()
        request.job = viewModelScope.launch {
            try {
                send()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (request.session != sessionGeneration) return@launch
                undo()
                val signIn = (e as? PikabuException)?.kind == ContentError.AUTH_REQUIRED
                // The sign-in window explains itself; a bar under it would stay after it closes.
                mutableState.update { if (signIn) it.copy(loginRequest = it.loginRequest + 1) else it.copy(message = errorMessage(e)) }
            } finally { if (votingIds[key] === request) votingIds.remove(key) }
        }
    }

    fun changePreferences(key: String, value: ReaderPreferences) { store.savePreferences(value, key); mutableState.update { it.copy(preferences = value) } }
    fun importPreparedSettings() {
        try {
            val json = getApplication<Application>().assets.open("account-preferences.snapshot.json").bufferedReader().use { it.readText() }
            val preferences = store.importPreferences(json)
            mutableState.update { it.copy(preferences = preferences, message = "Настройки импортированы. Явные настройки TV сохранены.") }
        } catch (e: Exception) { mutableState.update { it.copy(message = "Не удалось импортировать настройки: ${e.message}") } }
    }

    fun refreshAccount(importSettings: Boolean = true, quiet: Boolean = false) = viewModelScope.launch {
        mutableState.update { it.copy(accountBusy = true, message = null) }
        try {
            val generation = sessionGeneration
            val account = AccountService(cookieProvider = ::pikabuCookies,
                cookieSink = { url, cookies -> if (generation == sessionGeneration) keepPikabuCookies(url, cookies) }).readSettings()
            val preferences = if (importSettings) store.importPreferences(account.settingsJson) else mutableState.value.preferences
            val signedIn = mutableState.value.accountName != account.displayName
            mutableState.update { it.copy(accountName = account.displayName, preferences = preferences, accountBusy = false, message = if (quiet) null else "Аккаунт подключён${if (importSettings) ", настройки импортированы" else ""}") }
            // The quiet check only learns the name of a session that was already in use.
            if (signedIn && !quiet) sessionChanged()
            else if (mutableState.value.screen == Screen.FEED && mutableState.value.feedNeedsLogin) refreshFeed()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableState.update { it.copy(accountBusy = false, accountName = null, message = if (quiet) null else errorMessage(e)) } }
    }

    fun signOut() {
        // Responses still in flight belong to the old session: their cookies must not return after the removal.
        sessionGeneration++
        repository.invalidateSession()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            mutableState.update { it.copy(accountName = null, message = "Вы вышли из аккаунта. Локальные данные сохранены.") }
            sessionChanged()
        }
    }

    /** Votes and personal feeds belong to a session, so nothing loaded under the old one stays on screen. */
    private fun sessionChanged() {
        sessionGeneration++
        val votes = votingIds.values.toList()
        votingIds.clear()
        votes.forEach { it.job?.cancel() }
        feedJob?.cancel(); postJob?.cancel(); commentsJob?.cancel()
        postRead = null
        repository.invalidateSession()
        cache.clear()
        val state = mutableState.value
        val reading = state.screen == Screen.READER || state.screen == Screen.COMMENTS
        val hadComments = reading && state.currentPost != null &&
            (state.screen == Screen.COMMENTS || state.commentsPostId == state.currentPost.id)
        lastCommentsRequest = ::reloadComments
        mutableState.update { it.copy(posts = emptyList(), feedLoading = false, feedError = null,
            currentPost = it.currentPost?.copy(vote = 0, canVote = false, reactions = emptyList(), canReact = false),
            postLoading = reading && it.currentPost != null, comments = emptyList(), commentsPostId = null, commentsLoading = false,
            commentsError = null, pendingCommentIds = emptyList()) }
        if (state.section.feed != null || state.screen == Screen.FEED) refreshFeed()
        if (reading) state.currentPost?.let(::loadFullPost)
        if (hadComments) reloadComments()
    }

    fun clearMessage() { mutableState.update { it.copy(message = null) } }
    private fun errorMessage(e: Exception) = when (e) {
        is PikabuException -> e.message ?: "Не удалось загрузить контент"
        else -> "Ошибка загрузки. Проверьте подключение и повторите."
    }
}
