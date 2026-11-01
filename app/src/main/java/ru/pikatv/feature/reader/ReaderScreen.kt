package ru.pikatv.feature.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.pikatv.domain.VOTE_KEYS_ON_POST
import ru.pikatv.domain.Comment
import ru.pikatv.domain.ContentBlock
import ru.pikatv.domain.Post
import ru.pikatv.domain.Reaction
import ru.pikatv.domain.ReaderPreferences
import ru.pikatv.feature.comments.CommentsHeader
import ru.pikatv.feature.comments.commentItems
import ru.pikatv.feature.comments.rememberCommentThread

/** Gaps between reading blocks: tight by default, wider ones are chosen in settings. */
val BlockGaps = listOf(2.dp, 6.dp, 12.dp)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(post: Post, preferences: ReaderPreferences, collapsed: Boolean, bookmarked: Boolean,
    onBack: () -> Unit, onComments: () -> Unit, onToggleBookmark: () -> Unit, onToggleCollapse: () -> Unit,
    onTextScaleChange: (Float) -> Unit, onImage: (ContentBlock.Image) -> Unit, onVideo: (ContentBlock.Video) -> Unit,
    onMenu: () -> Unit = {}, initialBlockId: String? = null, onFocusedBlock: (String) -> Unit = {}, contentLoading: Boolean = false,
    onVote: (Int) -> Unit = {}, comments: List<Comment> = emptyList(), commentsTotal: Int = post.commentsCount, commentsLoading: Boolean = false,
    commentsError: String? = null, hasMoreComments: Boolean = false, onReachedEnd: () -> Unit = {}, onLoadMoreComments: () -> Unit = {},
    onRetryComments: () -> Unit = {}, onOpenBranch: (Comment) -> Unit = {}, onVoteComment: (Comment, Int) -> Unit = { _, _ -> },
    reactionChoices: List<Reaction> = emptyList(), onReact: (Reaction) -> Unit = {}, onReactComment: (Comment, Reaction) -> Unit = { _, _ -> }) {
    val scale = preferences.textScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
    val blockGap = BlockGaps.getOrElse(preferences.blockSpacing) { BlockGaps.first() }
    val listState = rememberLazyListState()
    val blockFocus = remember(post.id) { mutableMapOf<String, FocusRequester>() }
    val backFocus = remember { FocusRequester() }
    var focusedBlock by remember(post.id) { mutableStateOf(initialBlockId) }
    var hasFocus by remember { mutableStateOf(false) }
    val showTags = post.tags.isNotEmpty() && preferences.tagsMode != 1
    val tagsBeforeContent = showTags && preferences.tagsAtTop
    // Actions and title precede the blocks; a visible top tag row is one extra lazy item.
    val firstBlockIndex = 2 + if (tagsBeforeContent) 1 else 0
    val lastBlockItem = firstBlockIndex + post.blocks.size - 1
    val showReactions = preferences.showReactions && post.canReact && !collapsed
    val lastPostItem = lastBlockItem + (if (showTags && !tagsBeforeContent) 1 else 0) + (if (showReactions) 1 else 0)
    val commentsShown = comments.isNotEmpty() || commentsLoading || commentsError != null
    val scope = rememberCoroutineScope()
    // The discussion continues the same list, so the post leads straight into it, as on the site.
    val thread = rememberCommentThread(comments, preferences.collapseAfterDepth, listState, firstItem = lastPostItem + 2)
    var commentFocused by remember(post.id) { mutableStateOf(false) }
    BackHandler { if (!thread.leaveBranch()) onBack() }
    // Tags below the last block hold no focus, so a screen-tall last block must count as the end of the post.
    LaunchedEffect(post.id, contentLoading, collapsed, lastBlockItem) {
        if (contentLoading || collapsed) return@LaunchedEffect
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= lastBlockItem }.first { it }
        onReachedEnd()
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // Focus returns to the last read block only while the screen has none: first open, arrival of
    // the full post, or a closed media/menu dialog. Existing focus and screen state are never reset.
    // Without a remembered block reading starts at the first one under the title (the action rows are
    // one ↑ away); a collapsed or empty post focuses «Назад».
    LaunchedEffect(windowFocused, post.blocks, collapsed, contentLoading, tagsBeforeContent) {
        if (!windowFocused || hasFocus) return@LaunchedEffect
        if (commentFocused) { thread.focusTarget = thread.lastFocused; return@LaunchedEffect }
        val id = focusedBlock
        val index = post.blocks.indexOfFirst { it.id == id }
        when {
            collapsed -> backFocus.requestFocus()
            index >= 0 -> {
                if (listState.layoutInfo.visibleItemsInfo.none { it.key == "block:$id" }) listState.scrollToItem(index + firstBlockIndex)
                withFrameNanos { }
                if (!hasFocus) blockFocus[id]?.requestFocus()
            }
            // A remembered block may still be missing from a shortened history entry.
            id != null && contentLoading -> Unit
            else -> {
                val firstId = post.blocks.firstOrNull()?.id
                if (firstId == null) { backFocus.requestFocus(); return@LaunchedEffect }
                if (listState.layoutInfo.visibleItemsInfo.none { it.key == "block:$firstId" }) listState.scrollToItem(firstBlockIndex)
                withFrameNanos { }
                if (!hasFocus) blockFocus[firstId]?.requestFocus()
            }
        }
    }
    Box(Modifier.fillMaxSize().background(TvBackground).voteKeys(post.canVote && preferences.voteKeys == VOTE_KEYS_ON_POST && !commentFocused, preferences, onVote)
        .padding(horizontal = 24.dp), contentAlignment = Alignment.TopCenter) { CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        LazyColumn(state = listState, modifier = Modifier.readingColumn(preferences)
            .onFocusChanged { hasFocus = it.hasFocus }, contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(blockGap)) {
            item(key = "actions") { FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TvAction("← Назад", onBack, Modifier.focusRequester(backFocus))
                Box(Modifier.align(Alignment.CenterVertically)) { RatingControl(post.rating?.total, post.vote, post.canVote, preferences, onVote) }
                TvAction("Комментарии ${post.commentsCount}", onComments)
                TvAction(if (bookmarked) "★ В избранном" else "☆ В избранное", onToggleBookmark)
                TvAction(if (collapsed) "Развернуть" else "Свернуть", onToggleCollapse)
                TvAction("А−", { onTextScaleChange(stepTextScale(scale, up = false)) })
                Text("Текст ${(scale * 100).toInt()}%", color = TvMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 2.dp))
                TvAction("А+", { onTextScaleChange(stepTextScale(scale, up = true)) })
            } }
            item(key = "title") { Column {
                Text(post.title, color = TvText, fontSize = 20.sp * scale, lineHeight = 26.sp * scale, fontWeight = FontWeight.Bold)
                Text(post.author.name, color = TvMuted, fontSize = 12.sp)
            } }
            if (tagsBeforeContent) item(key = "tags") { PostTags(post, preferences.tagsMode, scale) }
            if (!collapsed) items(post.blocks, key = { "block:${it.id}" }) { block ->
                val open: (() -> Unit)? = when (block) {
                    is ContentBlock.Image -> ({ onImage(block) })
                    is ContentBlock.Video -> ({ onVideo(block) })
                    else -> null
                }
                ReadingArea(Modifier.fillMaxWidth().focusRequester(blockFocus.getOrPut(block.id) { FocusRequester() })
                    .onFocusChanged { if (it.hasFocus) { focusedBlock = block.id; commentFocused = false; onFocusedBlock(block.id) } }
                    .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) { onMenu(); true } else false }, open, onDownAtEnd = {
                        // Without a discussion below nothing takes focus; the list still shows the tags.
                        val scrollList = block === post.blocks.last() && !commentsShown && !showReactions && listState.canScrollForward
                        if (scrollList) scope.launch { listState.animateScrollBy(listState.layoutInfo.viewportSize.height / 2f) }
                        scrollList
                    }, onUpAtStart = {
                        // A free search would land mid-row on whatever sits above the block's centre.
                        val toActions = block === post.blocks.first() && !tagsBeforeContent
                        if (toActions) scope.launch { listState.scrollToItem(0); withFrameNanos { }; backFocus.requestFocus() }
                        toActions
                    }) {
                    BlockContent(block, scale, onImage, onVideo, interactive = open == null)
                }
            }
            if (showTags && !tagsBeforeContent) item(key = "tags") { PostTags(post, preferences.tagsMode, scale) }
            if (showReactions) item(key = "reactions") { ReactionRow(post.reactions, reactionChoices, onReact) }
            if (!collapsed && commentsShown) {
                item(key = "comments-header") { Box(Modifier.padding(top = 8.dp)) { CommentsHeader(thread, commentsTotal, onBack = null) } }
                commentItems(thread, preferences.copy(textScale = scale), commentsLoading, commentsError, hasMoreComments, onLoadMoreComments, onRetryComments, onOpenBranch,
                    onImage, onVideo, onVoteComment, reactionChoices, onReactComment, onFocusedComment = { commentFocused = true })
            }
        }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostTags(post: Post, mode: Int, scale: Float) {
    var expanded by remember(post.id) { mutableStateOf(false) }
    val partial = mode == 0 && post.tags.size > 3
    val visibleTags = if (partial && !expanded) post.tags.take(3) else post.tags
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Теги", color = TvMuted, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            visibleTags.forEach { tag -> Text("#$tag", color = TvMuted, fontSize = 13.sp * scale, lineHeight = 18.sp * scale) }
        }
        if (partial) TvAction(if (expanded) "Свернуть теги" else "Все теги (${post.tags.size})", { expanded = !expanded })
    }
}
