package ru.pikatv.feature.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.roundToInt
import ru.pikatv.domain.VOTE_KEYS_ON_RATING
import ru.pikatv.domain.ContentBlock
import ru.pikatv.domain.Post
import ru.pikatv.domain.Reaction
import ru.pikatv.domain.ReaderPreferences
import ru.pikatv.domain.TextStyle

/** Reading text scale presets from PLAN.md; an older in-between value moves to its nearest neighbour. */
val TextScaleSteps = listOf(.85f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f)
private val BlankLines = Regex("\\n\\s*\\n")
const val MIN_TEXT_SCALE = .85f
const val MAX_TEXT_SCALE = 2f

fun stepTextScale(current: Float, up: Boolean): Float =
    if (up) TextScaleSteps.firstOrNull { it > current + 0.01f } ?: TextScaleSteps.last()
    else TextScaleSteps.lastOrNull { it < current - 0.01f } ?: TextScaleSteps.first()

/** Whole tens of percent: a stored .8 stepped by float arithmetic would read as 79%. */
fun stepColumnWidth(current: Float, step: Int): Float = (((current * 10).roundToInt() + step) / 10f).coerceIn(.4f, 1f)

// Compose draws a 0.dp border as a hairline, so unfocused elements get no border at all.
@Composable
internal fun Modifier.focusBorder(focused: Boolean, width: Dp, shape: Shape, color: Color = TvAccent): Modifier =
    if (focused) border(width, color, shape) else this

/** The reading column: one width setting for the feed, posts and comments, centred on screen. */
fun Modifier.readingColumn(preferences: ReaderPreferences): Modifier = fillMaxHeight().fillMaxWidth(preferences.columnWidth.coerceIn(.4f, 1f))

/** On a TV lists keep focus 30% down the screen; reading lists move only as far as a focused element needs. */
@OptIn(ExperimentalFoundationApi::class)
val MinimalScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset >= 0 && end <= containerSize -> 0f
            offset < 0 && end > containerSize -> 0f
            abs(offset) < abs(end - containerSize) -> offset
            else -> end - containerSize
        }
    }
}

@Composable
fun TvAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, onFocused: () -> Unit = {}, dot: Color? = null, leftAligned: Boolean = false, pressedColor: Color? = null, small: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .clip(RoundedCornerShape(6.dp))
        .background(pressedColor ?: if (focused) TvAccent else TvControl)
        .focusBorder(focused, 2.dp, RoundedCornerShape(6.dp), if (pressedColor != null) TvText else TvAccent)
        .clickable(onClick = onClick).defaultMinSize(minWidth = if (small) 24.dp else 30.dp, minHeight = if (small) 18.dp else 26.dp)
        .padding(horizontal = if (small) 5.dp else 8.dp, vertical = if (small) 0.dp else 3.dp),
        contentAlignment = if (leftAligned) Alignment.CenterStart else Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (dot != null) Box(Modifier.size(11.dp).background(dot, CircleShape).border(1.dp, TvText, CircleShape))
            Text(label, color = if (focused || pressedColor != null) TvBackground else TvText, fontSize = (if (small) 11 else 13).sp, lineHeight = (if (small) 14 else 16).sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionRow(reactions: List<Reaction>, choices: List<Reaction>, onReact: (Reaction) -> Unit, modifier: Modifier = Modifier, addModifier: Modifier = Modifier, onFocused: () -> Unit = {}) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        ReactionChips(reactions, choices, onReact, addModifier, onFocused)
    }
}

/** Reactions as on the site; the ones the user cannot give stay plain counters. A comment shows them small, beside its author. */
@Composable
fun ReactionChips(reactions: List<Reaction>, choices: List<Reaction>, onReact: (Reaction) -> Unit, addModifier: Modifier = Modifier, onFocused: () -> Unit = {}, small: Boolean = false) {
    var picking by remember { mutableStateOf(false) }
    if (picking) ReactionPicker(choices, reactions.firstOrNull { it.active }?.id, { picking = false; onReact(it) }) { picking = false }
    // A row may wrap, and a line end has no neighbour to the right, so the buttons form one chain for ←/→.
    val buttons = reactions.filter { it.canVote || it.active }
    val stops = remember(buttons.size) { List(buttons.size + 1) { FocusRequester() } }
    fun stop(index: Int) = Modifier.focusRequester(stops[index]).focusProperties {
        if (index > 0) left = stops[index - 1]
        if (index < buttons.size) right = stops[index + 1]
    }
    TvAction(if (small) "+ 🙂" else "+ Эмодзи", { picking = true }, addModifier.then(stop(0)), onFocused, small = small)
    reactions.forEach { reaction ->
        val label = "${reaction.emoji} ${reaction.count}"
        val button = buttons.indexOf(reaction)
        if (button >= 0) TvAction(label, { onReact(reaction) }, stop(button + 1), onFocused, pressedColor = TvAccent.takeIf { reaction.active }, small = small)
        else Text(label, color = TvMuted, fontSize = (if (small) 11 else 13).sp, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionPicker(choices: List<Reaction>, active: Int?, onPick: (Reaction) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    TrackedDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { first.requestFocus() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FlowRow(Modifier.widthIn(max = 480.dp).background(TvSurface, RoundedCornerShape(8.dp)).padding(10.dp).focusGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEachIndexed { index, choice ->
                    TvAction(choice.emoji, { onPick(choice) }, if (index == 0) Modifier.focusRequester(first) else Modifier, pressedColor = TvAccent.takeIf { choice.id == active })
                }
            }
        }
    }
}

/** Remote keys chosen in settings vote where the settings allow it; ▲ and ▼ with OK always work. */
fun Modifier.voteKeys(enabled: Boolean, preferences: ReaderPreferences, onVote: (Int) -> Unit): Modifier =
    if (!enabled) this else onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false else when (event.key.nativeKeyCode) {
            preferences.plusKey -> { onVote(1); true }
            preferences.minusKey -> { onVote(-1); true }
            else -> false
        }
    }

/** What the user cannot vote on shows only its rating. */
@Composable
fun RatingControl(total: Long?, vote: Int, canVote: Boolean, preferences: ReaderPreferences, onVote: (Int) -> Unit,
    upModifier: Modifier = Modifier, onFocused: () -> Unit = {}, small: Boolean = false, downModifier: Modifier = Modifier) {
    Row(Modifier.voteKeys(canVote && preferences.voteKeys == VOTE_KEYS_ON_RATING, preferences, onVote),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (canVote) TvAction("▲", { onVote(1) }, upModifier, onFocused, pressedColor = TvVotePlus.takeIf { vote > 0 }, small = small)
        Text(total?.toString() ?: "—", color = if (vote > 0) TvVotePlus else if (vote < 0) TvVoteMinus else TvText, fontSize = (if (small) 12 else 13).sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
        if (canVote) TvAction("▼", { onVote(-1) }, downModifier, onFocused, pressedColor = TvVoteMinus.takeIf { vote < 0 }, small = small)
    }
}

/** With [interactive] false the block only shows content and its container takes focus and OK. */
@Composable
fun BlockContent(block: ContentBlock, scale: Float, onImage: (ContentBlock.Image) -> Unit, onVideo: (ContentBlock.Video) -> Unit,
    interactive: Boolean = true, imageMaxHeight: Dp = 490.dp, onImageSettled: () -> Unit = {}) {
    when (block) {
        // Blank lines from the site's markup would double the gap between paragraphs.
        is ContentBlock.Text -> Text(remember(block.text) { block.text.replace(BlankLines, "\n").trim() }, color = TvText,
            fontSize = (if (block.style == TextStyle.HEADING) 18 else 16).sp * scale,
            lineHeight = (if (block.style == TextStyle.HEADING) 24 else 22).sp * scale,
            fontWeight = if (block.style == TextStyle.HEADING) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (block.style == TextStyle.CODE) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.fillMaxWidth().then(if (block.style == TextStyle.QUOTE) Modifier.border(1.dp, TvMuted, RoundedCornerShape(4.dp)).padding(6.dp) else Modifier))
        is ContentBlock.Image -> {
            var focused by remember { mutableStateOf(false) }
            AsyncImage(model = block.previewUrl ?: block.url, contentDescription = block.description,
                contentScale = ContentScale.Fit, onSuccess = { onImageSettled() }, onError = { onImageSettled() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = imageMaxHeight).then(if (interactive) Modifier
                    .onFocusChanged { focused = it.isFocused }.focusBorder(focused, 2.dp, RoundedCornerShape(4.dp))
                    .clickable { onImage(block) } else Modifier))
        }
        is ContentBlock.Video -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            block.posterUrl?.let { poster ->
                AsyncImage(model = poster, contentDescription = "Превью видео", contentScale = ContentScale.Crop,
                    // The button is measured first: in a narrow column the poster shrinks rather than hides it.
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 150.dp).aspectRatio((block.aspectRatio ?: (16f / 9f)).coerceIn(.4f, 2.4f))
                        .clip(RoundedCornerShape(4.dp)).background(TvControl))
            }
            if (interactive) TvAction("▶  ${block.title}", { onVideo(block) })
            else Text("▶  ${block.title}", color = TvText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.background(TvControl, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        // Links cannot be opened on the TV, so only their text and site are shown, not a full tracking URL.
        is ContentBlock.Link -> {
            val site = runCatching { java.net.URI(block.url).host?.removePrefix("www.") }.getOrNull() ?: block.url
            val label = block.label.takeUnless { it.isBlank() || it.startsWith("http://") || it.startsWith("https://") }
            Text(label?.let { "$it  ·  $site" } ?: site, color = TvAccent, fontSize = 14.sp * scale)
        }
        is ContentBlock.Unsupported -> Text(block.description, color = TvMuted, fontSize = 14.sp * scale)
    }
}

/** A bounded reading area: up/down scroll its text first, then leave at either boundary.
 *  [onActivate] makes the area itself act on OK, so a single press opens its media.
 *  [onDownAtEnd] handles ↓ after the text has ended and returns true when it did. */
@Composable
fun ReadingArea(modifier: Modifier = Modifier, onActivate: (() -> Unit)? = null, onDownAtEnd: () -> Boolean = { false }, onUpAtStart: () -> Boolean = { false }, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    Box(modifier.onFocusChanged { focused = it.hasFocus }
        .focusBorder(focused, 2.dp, RoundedCornerShape(4.dp))
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionDown -> if (scroll.value < scroll.maxValue) { scope.launch { scroll.animateScrollTo((scroll.value + 360).coerceAtMost(scroll.maxValue)) }; true } else onDownAtEnd()
                Key.DirectionUp -> if (scroll.value > 0) { scope.launch { scroll.animateScrollTo((scroll.value - 360).coerceAtLeast(0)) }; true } else onUpAtStart()
                else -> false
            }
        }.then(if (onActivate != null) Modifier.clickable(onClick = onActivate) else Modifier.focusable())
        .heightIn(max = 510.dp).verticalScroll(scroll).padding(horizontal = 4.dp, vertical = 2.dp)) { content() }
}
