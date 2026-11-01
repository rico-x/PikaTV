package ru.pikatv.feature.comments

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pikatv.domain.VOTE_KEYS_OFF
import ru.pikatv.domain.Comment
import ru.pikatv.domain.ContentBlock
import ru.pikatv.domain.Reaction
import ru.pikatv.domain.ReaderPreferences
import ru.pikatv.feature.reader.*

/** Indentation stops at this depth; deeper replies name their parent and can be read as a separate branch. */
private const val MAX_INDENT_DEPTH = 5
private const val BRANCH_CONTEXT = 3
/** The next portion is requested while this many comments remain, so reading does not stop at a button. */
private const val LOAD_AHEAD = 5

/** One discussion: the comments screen and the list under a post share its state and items. */
@Stable
class CommentThread {
    var comments by mutableStateOf(emptyList<Comment>())
        private set
    var collapsed by mutableStateOf(emptySet<String>())
    // A separate branch shows a few ancestors as context, then the chosen comment and its replies.
    var branchRoot by mutableStateOf<String?>(null)
    var focusTarget by mutableStateOf<String?>(null)
    var lastFocused by mutableStateOf<String?>(null)
    val focusRequesters = mutableMapOf<String, FocusRequester>()
    private var initialized = emptySet<String>()
    private val byId by derivedStateOf { comments.associateBy { it.id } }
    private val parents by derivedStateOf { comments.mapNotNullTo(hashSetOf()) { it.parentId } }
    private fun ancestors(id: String?): List<String> = generateSequence(byId[id]?.parentId) { byId[it]?.parentId }.take(512).toList()
    // Collapsed ancestors of a separate branch do not hide it; collapsing inside the branch still works.
    private val rootChain by derivedStateOf { ancestors(branchRoot).toSet() }
    val context by derivedStateOf { ancestors(branchRoot).take(BRANCH_CONTEXT).toSet() }
    val visible by derivedStateOf { comments.filter { comment ->
        val chain = ancestors(comment.id)
        val inView = branchRoot == null || comment.id == branchRoot || comment.id in context || branchRoot in chain
        inView && chain.none { it in collapsed && it !in rootChain }
    } }
    val baseDepth get() = visible.firstOrNull()?.depth?.takeIf { branchRoot != null } ?: 0
    fun hasReplies(comment: Comment) = comment.replyCount > 0 || comment.id in parents
    fun parentName(comment: Comment) = byId[comment.parentId]?.author?.name
    fun collapseAll() { collapsed = comments.filter(::hasReplies).mapTo(hashSetOf()) { it.id } }
    fun expandAll() { collapsed = emptySet() }
    fun leaveBranch(): Boolean {
        val root = branchRoot ?: return false
        focusTarget = root
        branchRoot = null
        return true
    }
    fun update(items: List<Comment>, collapseAfterDepth: Int) {
        comments = items
        val newComments = items.filter { it.id !in initialized }
        collapsed = collapsed + newComments.filter { it.depth >= (collapseAfterDepth.coerceAtLeast(1) - 1) && it.replyCount > 0 }.map { it.id }
        initialized = initialized + newComments.map { it.id }
    }
}

/** [firstItem] is the list index of the first comment, which differs between the two lists. */
@Composable
fun rememberCommentThread(comments: List<Comment>, collapseAfterDepth: Int, listState: LazyListState, firstItem: Int, initialFocusedCommentId: String? = null): CommentThread {
    val thread = remember { CommentThread().also { it.lastFocused = initialFocusedCommentId; it.focusTarget = initialFocusedCommentId } }
    LaunchedEffect(comments, collapseAfterDepth) { thread.update(comments, collapseAfterDepth) }
    LaunchedEffect(thread.focusTarget, thread.visible) {
        val target = thread.focusTarget ?: return@LaunchedEffect
        val index = thread.visible.indexOfFirst { it.id == target }
        if (index >= 0) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == "comment:$target" }) listState.scrollToItem(index + firstItem)
            withFrameNanos { }
            thread.focusRequesters.getOrPut(target) { FocusRequester() }.requestFocus()
            thread.focusTarget = null
        }
    }
    return thread
}

/** Under a post there is no «Назад»: the post itself is the way back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommentsHeader(thread: CommentThread, totalCount: Int, onBack: (() -> Unit)?, backFocus: Modifier = Modifier) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (thread.branchRoot == null) {
            onBack?.let { TvAction("← Назад", it, backFocus) }
            Text("Комментарии · $totalCount", color = TvText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        } else {
            TvAction("← Все комментарии", { thread.leaveBranch() }, backFocus)
            Text("Отдельная ветка", color = TvText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        TvAction("Свернуть все", thread::collapseAll)
        TvAction("Развернуть все", thread::expandAll)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentItem(thread: CommentThread, comment: Comment, preferences: ReaderPreferences, onOpenBranch: (Comment) -> Unit,
    onImage: (ContentBlock.Image) -> Unit, onVideo: (ContentBlock.Video) -> Unit, onVote: (Comment, Int) -> Unit,
    reactionChoices: List<Reaction>, onReact: (Comment, Reaction) -> Unit, onFocused: (Comment) -> Unit) {
    val hasReplies = thread.hasReplies(comment)
    val isContext = comment.id in thread.context
    val baseDepth = thread.baseDepth
    var areaFocused by remember(comment.id) { mutableStateOf(false) }
    var innerFocused by remember(comment.id) { mutableStateOf(false) }
    val focus = thread.focusRequesters.getOrPut(comment.id) { FocusRequester() }
    // Back steps out of the comment's buttons one level at a time instead of leaving the whole discussion.
    BackHandler(enabled = innerFocused) { focus.requestFocus() }
    ReadingArea(Modifier.padding(start = ((comment.depth - baseDepth).coerceIn(0, MAX_INDENT_DEPTH) * 12).dp).fillMaxWidth()
        .background(TvSurface, RoundedCornerShape(6.dp))
        .focusRequester(focus)
        .onFocusChanged { areaFocused = it.isFocused; innerFocused = it.hasFocus && !it.isFocused; if (it.hasFocus) { thread.lastFocused = comment.id; onFocused(comment) } }
        // A selected comment is already "the rating": reaching ▲ inside it would take several presses.
        .voteKeys(preferences.voteKeys != VOTE_KEYS_OFF && !comment.isDeleted && comment.canVote, preferences) { onVote(comment, it) }
        .onPreviewKeyEvent { event ->
            if (!areaFocused || event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft -> when {
                    hasReplies && comment.id !in thread.collapsed && !isContext -> { thread.collapsed = thread.collapsed + comment.id; thread.focusTarget = comment.id; true }
                    comment.parentId != null && thread.visible.any { it.id == comment.parentId } -> { thread.focusTarget = comment.parentId; true }
                    // Nothing lies to the left of a root comment; a free search would jump to a button far above.
                    else -> true
                }
                Key.DirectionRight -> when {
                    comment.id in thread.collapsed && !isContext -> { thread.collapsed = thread.collapsed - comment.id; true }
                    hasReplies && !isContext -> { onOpenBranch(comment); true }
                    else -> false
                }
                else -> false
            }
        }.padding(horizontal = 8.dp, vertical = 4.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val parentName = thread.parentName(comment)?.takeIf { comment.depth - baseDepth > MAX_INDENT_DEPTH }
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!comment.isDeleted) RatingControl(comment.rating?.total, comment.vote, comment.canVote, preferences, { onVote(comment, it) }, small = true)
            Text((if (isContext) "Выше по ветке · " else "") + (if (comment.isDeleted) "Комментарий удалён" else comment.author.name) +
                (parentName?.let { "  ·  ответ на: $it" } ?: ""), color = TvMuted, fontSize = 12.sp)
            if (preferences.showReactions && comment.canReact) ReactionChips(comment.reactions, reactionChoices, { onReact(comment, it) }, small = true)
        }
        if (!comment.isDeleted) comment.blocks.forEach { BlockContent(it, preferences.textScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE), onImage, onVideo, imageMaxHeight = 260.dp) }
        if (hasReplies && !isContext) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TvAction(if (comment.id in thread.collapsed) "Развернуть ветку" else "Свернуть ветку", {
                thread.collapsed = if (comment.id in thread.collapsed) thread.collapsed - comment.id else thread.collapsed + comment.id
            })
            TvAction("Открыть ответы (${comment.replyCount})", { onOpenBranch(comment) })
            if (comment.id != thread.branchRoot && comment.depth - baseDepth >= MAX_INDENT_DEPTH - 1)
                TvAction("Ветка отдельно", { thread.branchRoot = comment.id; thread.collapsed = thread.collapsed - comment.id })
        }
      }
    }
}

fun LazyListScope.commentItems(thread: CommentThread, preferences: ReaderPreferences, loading: Boolean, error: String?, hasMore: Boolean,
    onLoadMore: () -> Unit, onRetry: () -> Unit, onOpenBranch: (Comment) -> Unit,
    onImage: (ContentBlock.Image) -> Unit, onVideo: (ContentBlock.Video) -> Unit, onVote: (Comment, Int) -> Unit,
    reactionChoices: List<Reaction> = emptyList(), onReact: (Comment, Reaction) -> Unit = { _, _ -> }, onFocusedComment: (String) -> Unit = {}) {
    items(thread.visible, key = { "comment:${it.id}" }) { comment ->
        CommentItem(thread, comment, preferences, onOpenBranch, onImage, onVideo, onVote, reactionChoices, onReact) { focused ->
            onFocusedComment(focused.id)
            val remaining = thread.visible.size - thread.visible.indexOfFirst { it.id == focused.id }
            if (remaining <= LOAD_AHEAD && hasMore && !loading && error == null && thread.branchRoot == null) onLoadMore()
        }
    }
    item(key = "comments-footer") { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (error != null) Text(error, color = TvText, fontSize = 14.sp)
        // One action slot keeps focus while a portion loads, fails or is retried.
        val action = when {
            error != null -> "Повторить" to onRetry
            loading -> "Загрузка…" to {}
            hasMore && thread.branchRoot == null -> "Загрузить ещё" to onLoadMore
            else -> null
        }
        action?.let { (label, onClick) -> TvAction(label, onClick) }
        if (thread.comments.isEmpty() && !loading && error == null) Text("Комментариев пока нет", color = TvMuted, fontSize = 14.sp)
    } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CommentsScreen(comments: List<Comment>, totalCount: Int, loading: Boolean, error: String?, hasMore: Boolean,
    onBack: () -> Unit, onLoadMore: () -> Unit, onRetry: () -> Unit, onOpenBranch: (Comment) -> Unit,
    onImage: (ContentBlock.Image) -> Unit, onVideo: (ContentBlock.Video) -> Unit,
    preferences: ReaderPreferences = ReaderPreferences(), onVote: (Comment, Int) -> Unit = { _, _ -> },
    reactionChoices: List<Reaction> = emptyList(), onReact: (Comment, Reaction) -> Unit = { _, _ -> },
    initialFocusedCommentId: String? = null, onFocusedComment: (String) -> Unit = {}) {
    val listState = rememberLazyListState()
    val thread = rememberCommentThread(comments, preferences.collapseAfterDepth, listState, firstItem = 1, initialFocusedCommentId)
    var hasFocus by remember { mutableStateOf(false) }
    val backFocus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    BackHandler { if (!thread.leaveBranch()) onBack() }
    val hasVisible = thread.visible.isNotEmpty()
    // Without focus (first load, closed media dialog) focus goes to the last comment, else the first one;
    // collapsed branches stay as they were. An empty or failed discussion focuses «Назад».
    LaunchedEffect(windowFocused, hasVisible, loading) {
        if (!windowFocused || hasFocus) return@LaunchedEffect
        val target = thread.lastFocused?.takeIf { id -> thread.visible.any { it.id == id } } ?: thread.visible.firstOrNull()?.id
        if (target != null) thread.focusTarget = target else if (!loading) backFocus.requestFocus()
    }
    Box(Modifier.fillMaxSize().background(TvBackground), contentAlignment = Alignment.TopCenter) { CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        LazyColumn(state = listState, modifier = Modifier.readingColumn(preferences).onFocusChanged { hasFocus = it.hasFocus }, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "header") { CommentsHeader(thread, totalCount, onBack, Modifier.focusRequester(backFocus)) }
            commentItems(thread, preferences, loading, error, hasMore, onLoadMore, onRetry, onOpenBranch, onImage, onVideo, onVote, reactionChoices, onReact, onFocusedComment)
        }
    } }
}
