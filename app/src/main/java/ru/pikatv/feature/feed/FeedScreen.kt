package ru.pikatv.feature.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.pikatv.domain.ContentBlock
import ru.pikatv.domain.VOTE_KEYS_ON_POST
import ru.pikatv.domain.Post
import ru.pikatv.domain.Reaction
import ru.pikatv.domain.ReaderPreferences
import ru.pikatv.feature.reader.*

/** A card shows what fits on one screen; longer posts rarely need more blocks to fill it. */
private const val CARD_BLOCKS = 14
/** A post smaller than this share of the screen leaves the rest to the next post. */
private const val SHORT_POST_SHARE = 2f / 3
/** Paddings and the action row around a card's content. */
private val CardChrome = 46.dp
private val ReactionChrome = 28.dp

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun FeedScreen(posts: List<Post>, title: String, loading: Boolean, error: String?, hasMore: Boolean,
    collapsedIds: Set<String>, bookmarkedIds: Set<String>, onMenu: () -> Unit,
    onOpenPost: (Post) -> Unit, onComments: (Post) -> Unit, onToggleBookmark: (Post) -> Unit,
    onToggleCollapse: (Post) -> Unit, onLoadMore: () -> Unit, onRetry: () -> Unit,
    onFocused: (String) -> Unit = {}, initialFocusedPostId: String? = null, onLogin: (() -> Unit)? = null, preferences: ReaderPreferences = ReaderPreferences(), onVote: (Post, Int) -> Unit = { _, _ -> },
    reactionChoices: List<Reaction> = emptyList(), onReact: (Post, Reaction) -> Unit = { _, _ -> }) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val cardFocus = remember { mutableMapOf<String, FocusRequester>() }
    val menuFocus = remember { FocusRequester() }
    val footerFocus = remember { FocusRequester() }
    val loginFocus = remember { FocusRequester() }
    var lastFocused by remember(initialFocusedPostId) { mutableStateOf(initialFocusedPostId) }
    var hasFocus by remember { mutableStateOf(false) }
    var cardFocused by remember { mutableStateOf(false) }
    var listHeight by remember { mutableIntStateOf(0) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val footerAction = when {
        error != null -> "Повторить" to onRetry
        loading -> "Загрузка…" to {}
        hasMore -> "Загрузить ещё" to onLoadMore
        else -> null
    }
    // The list jumps instead of animating a whole screen on a slow TV; a post already on screen in full stays put.
    suspend fun reveal(index: Int) {
        val layout = listState.layoutInfo
        val shown = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (shown == null || shown.offset < 0 || shown.offset + shown.size > layout.viewportSize.height) listState.scrollToItem(index)
    }
    fun showCard(index: Int) {
        val id = posts.getOrNull(index)?.id ?: return
        scope.launch { reveal(index); withFrameNanos { }; cardFocus[id]?.requestFocus() }
    }
    fun showNext(index: Int) = when {
        index < posts.lastIndex -> showCard(index + 1)
        // New cards arrive by themselves; the footer matters only when nothing else will.
        footerAction != null && !loading -> { scope.launch { listState.scrollToItem(posts.size); withFrameNanos { }; footerFocus.requestFocus() }; Unit }
        else -> Unit
    }
    // Whenever the feed is visible without focus, focus goes to the remembered card, else the first card,
    // the login/retry action or the menu action. Appending a page never moves existing focus; the only
    // exception is the footer action that the system focused before the user reached any card.
    LaunchedEffect(windowFocused, posts, loading, error) {
        val waitingOnFooter = hasFocus && !cardFocused && posts.isNotEmpty()
        if (!windowFocused || (hasFocus && !waitingOnFooter)) return@LaunchedEffect
        val index = posts.indexOfFirst { it.id == lastFocused }.takeIf { it >= 0 } ?: if (posts.isEmpty()) -1 else 0
        when {
            index >= 0 -> {
                reveal(index)
                withFrameNanos { }
                if (!hasFocus || !cardFocused) cardFocus[posts[index].id]?.requestFocus()
            }
            loading -> Unit
            error != null -> { withFrameNanos { }; (if (onLogin != null) loginFocus else footerFocus).requestFocus() }
            else -> { withFrameNanos { }; menuFocus.requestFocus() }
        }
    }
    // On a TV the list keeps focus 30% down the screen, which would show the end of the previous post.
    Box(Modifier.fillMaxSize().background(TvBackground), contentAlignment = Alignment.TopCenter) { CompositionLocalProvider(LocalBringIntoViewSpec provides KeepPosition) {
    LazyColumn(state = listState, modifier = Modifier.readingColumn(preferences).onSizeChanged { listHeight = it.height }.onFocusChanged { hasFocus = it.hasFocus }) {
        itemsIndexed(posts, key = { _, post -> "post:${post.id}" }) { index, post ->
            PostCard(post, title, post.id in collapsedIds, post.id in bookmarkedIds, preferences, cardFocus.getOrPut(post.id) { FocusRequester() },
                Modifier.fillParentMaxHeight(), listHeight,
                onOpen = { onOpenPost(post) }, onComments = { onComments(post) }, onVote = { onVote(post, it) },
                reactionChoices = reactionChoices, onReact = { onReact(post, it) },
                onToggleBookmark = { onToggleBookmark(post) }, onToggleCollapse = { onToggleCollapse(post) },
                onPrevious = { showCard(index - 1) }, onNext = { showNext(index) }, onMenu = onMenu, onFocused = {
                    lastFocused = post.id
                    cardFocused = true
                    onFocused(post.id)
                    // The next page is requested while a few cards remain, without taking focus.
                    if (index >= posts.size - 3 && hasMore && !loading && error == null) onLoadMore()
                })
        }
        item(key = "footer") { Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp).onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || posts.isEmpty()) false else when (event.key) {
                Key.DirectionUp -> { showCard(posts.lastIndex); true }
                Key.DirectionLeft -> { onMenu(); true }
                else -> false
            }
        }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (error != null) Text(error, color = TvText, fontSize = 15.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (posts.isEmpty()) TvAction("☰  Меню", onMenu, Modifier.focusRequester(menuFocus))
                if (error != null && onLogin != null) TvAction("Войти в Пикабу", onLogin, Modifier.focusRequester(loginFocus))
                // One action slot keeps focus while a page loads, fails or is retried.
                footerAction?.let { (label, onClick) -> TvAction(label, onClick, Modifier.focusRequester(footerFocus)) }
            }
            if (posts.isEmpty() && !loading && error == null) Text("Пока нет публикаций", color = TvMuted, fontSize = 15.sp)
        } }
    }
    } }
}

/** Posts are shown by [FeedScreen] itself, so focus changes never scroll the list. */
@OptIn(ExperimentalFoundationApi::class)
private object KeepPosition : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/** The whole card takes focus and OK opens the post; → leads to its actions, ← to the hidden menu. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostCard(post: Post, section: String, collapsed: Boolean, bookmarked: Boolean, preferences: ReaderPreferences, focus: FocusRequester, fullScreen: Modifier, listHeight: Int,
    onOpen: () -> Unit, onComments: () -> Unit, onVote: (Int) -> Unit, reactionChoices: List<Reaction>, onReact: (Reaction) -> Unit, onToggleBookmark: () -> Unit, onToggleCollapse: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, onMenu: () -> Unit, onFocused: () -> Unit) {
    val actions = remember { List(5) { FocusRequester() } }
    val addReaction = remember { FocusRequester() }
    val reactions = preferences.showReactions && post.canReact
    val firstAction = if (post.canVote) 0 else 2
    // A narrow column wraps the row, and a line end has no neighbour to the right, so the actions form one chain for ←/→.
    fun action(index: Int) = Modifier.focusRequester(actions[index]).focusProperties {
        left = if (index == firstAction) focus else actions[index - 1]
        if (index < actions.lastIndex) right = actions[index + 1]
    }
    val scale = preferences.textScale
    val blockGap = BlockGaps.getOrElse(preferences.blockSpacing) { BlockGaps.first() }
    var focused by remember { mutableStateOf(false) }
    var viewHeight by remember { mutableIntStateOf(0) }
    var contentHeight by remember { mutableIntStateOf(0) }
    var actionFocused by remember { mutableStateOf(false) }
    // Back steps from the action rows to the post; only from the post itself does it leave the feed.
    BackHandler(enabled = actionFocused) { focus.requestFocus() }
    // An unloaded image is a placeholder, so a post is judged by its size only once its images have settled.
    val images = remember(post.id) { post.blocks.take(CARD_BLOCKS).count { it is ContentBlock.Image } }
    var settledImages by remember(post.id) { mutableIntStateOf(0) }
    val chrome = with(LocalDensity.current) { (CardChrome + if (reactions) ReactionChrome else 0.dp).roundToPx() }
    val small = settledImages >= images && contentHeight > 0 && contentHeight + chrome < listHeight * SHORT_POST_SHARE
    // Only a post filling most of the screen gets all of it; a smaller one leaves room for the next post.
    val short = collapsed || small
    Column((if (short) Modifier else fullScreen).fillMaxWidth().voteKeys(post.canVote && preferences.voteKeys == VOTE_KEYS_ON_POST, preferences, onVote)
        .padding(horizontal = 24.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Column(Modifier.fillMaxWidth().onFocusChanged { actionFocused = it.hasFocus }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionUp -> { onPrevious(); true }
                Key.DirectionDown -> { (if (reactions) addReaction else focus).requestFocus(); true }
                else -> false
            }
        }, itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$section  ·  ${post.author.name}", color = TvMuted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            RatingControl(post.rating?.total, post.vote, post.canVote, preferences, onVote, action(0), onFocused, downModifier = action(1))
            TvAction("Комментарии ${post.commentsCount}", onComments, action(2), onFocused)
            TvAction(if (bookmarked) "★ В избранном" else "☆ В избранное", onToggleBookmark, action(3), onFocused)
            TvAction(if (collapsed) "Развернуть" else "Свернуть", onToggleCollapse, action(4), onFocused)
        }
        if (reactions) ReactionRow(post.reactions, reactionChoices, onReact, Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionUp -> { actions[firstAction].requestFocus(); true }
                Key.DirectionDown -> { focus.requestFocus(); true }
                else -> false
            }
        }, Modifier.focusRequester(addReaction), onFocused)
        }
        Box(Modifier.fillMaxWidth().then(if (short) Modifier else Modifier.weight(1f)).onSizeChanged { viewHeight = it.height }
            .focusBorder(focused, 2.dp, RoundedCornerShape(4.dp))
            .focusRequester(focus).focusProperties { right = actions[firstAction] }
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionUp -> { onPrevious(); true }
                    Key.DirectionDown -> { onNext(); true }
                    Key.DirectionLeft -> { onMenu(); true }
                    else -> false
                }
            }.clickable(onClick = onOpen).clipToBounds().padding(horizontal = 6.dp, vertical = 3.dp)) {
            Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { contentHeight = it.height },
                verticalArrangement = Arrangement.spacedBy(blockGap + 2.dp)) {
                Text(post.title.ifBlank { "Открыть пост" }, color = TvText, fontSize = 18.sp * scale, lineHeight = 24.sp * scale, fontWeight = FontWeight.Bold)
                if (!collapsed) post.blocks.take(CARD_BLOCKS).forEach { BlockContent(it, scale, {}, {}, interactive = false, imageMaxHeight = 400.dp, onImageSettled = { settledImages++ }) }
            }
            if (!short && (post.isLong || contentHeight > viewHeight)) Text("OK — читать полностью", color = TvMuted, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomEnd).background(TvBackground, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}
