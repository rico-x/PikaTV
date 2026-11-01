package ru.pikatv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.pikatv.domain.*
import ru.pikatv.feature.account.LoginScreen
import ru.pikatv.feature.comments.CommentsScreen
import ru.pikatv.feature.feed.FeedScreen
import ru.pikatv.feature.images.ImageViewer
import ru.pikatv.feature.navigation.NavigationMenu
import ru.pikatv.feature.player.NativeVideoPlayer
import ru.pikatv.feature.reader.*

@Composable
fun PikaTvApp(viewModel: PikaViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ContentBlock.Image?>(null) }
    var video by remember { mutableStateOf<ContentBlock.Video?>(null) }
    var login by remember { mutableStateOf(false) }
    LaunchedEffect(state.loginRequest) { if (state.loginRequest > 0) login = true }
    val dialogProperties = remember { DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false) }
    val presses = remember { mutableIntStateOf(0) }
    KeepScreenAwake(presses)
    val colors = tvColors(state.preferences)
    CompositionLocalProvider(LocalTvColors provides colors, LocalRemotePresses provides presses) { MaterialTheme(colorScheme = colors.material(), typography = TvTypography) {
        Box(Modifier.fillMaxSize().background(TvBackground).onPreviewKeyEvent { presses.intValue++; false }) {
            key(state.navigationKey) {
                when (state.screen) {
                    Screen.FEED -> FeedScreen(
                        posts = state.posts.filter { (state.preferences.showAdult || !it.isAdult) && (state.preferences.showHorror || !it.isHorror) },
                        title = state.section.title, loading = state.feedLoading, error = state.feedError, hasMore = state.hasMore,
                        collapsedIds = state.posts.filter(state::isCollapsed).mapTo(mutableSetOf()) { it.id }, bookmarkedIds = state.bookmarkedIds,
                        onMenu = { menu = true }, onOpenPost = viewModel::openPost, onComments = { viewModel.openComments(it) },
                        onToggleBookmark = viewModel::toggleBookmark, onToggleCollapse = viewModel::toggleCollapsed,
                        onLoadMore = viewModel::loadMoreFeed, onRetry = viewModel::retryFeed,
                        onFocused = viewModel::focusPost, initialFocusedPostId = state.initialFeedFocus,
                        onLogin = if (state.feedNeedsLogin) { { login = true } } else null, preferences = state.preferences, onVote = viewModel::vote,
                        reactionChoices = viewModel.reactionChoices, onReact = viewModel::react,
                    )
                    Screen.READER -> state.currentPost?.let { post ->
                        ReaderScreen(post, state.preferences, state.isCollapsed(post), post.id in state.bookmarkedIds,
                            onBack = viewModel::back, onComments = { viewModel.openComments() },
                            onToggleBookmark = { viewModel.toggleBookmark(post) }, onToggleCollapse = { viewModel.toggleCollapsed(post) },
                            onTextScaleChange = { viewModel.changePreferences("textScale", state.preferences.copy(textScale = it)) },
                            onImage = { image = it }, onVideo = { video = it }, onMenu = { menu = true },
                            initialBlockId = state.initialBlockId, onFocusedBlock = viewModel::focusBlock, contentLoading = state.postLoading,
                            onVote = { viewModel.vote(post, it) }, comments = if (state.commentsPostId == post.id) state.comments else emptyList(), commentsTotal = state.commentsTotal,
                            commentsLoading = state.commentsLoading, commentsError = state.commentsError, hasMoreComments = state.pendingCommentIds.isNotEmpty(),
                            onReachedEnd = viewModel::loadCommentsUnderPost, onLoadMoreComments = viewModel::loadMoreComments,
                            onRetryComments = viewModel::retryComments, onOpenBranch = viewModel::openBranch, onVoteComment = viewModel::voteComment,
                            reactionChoices = viewModel.reactionChoices, onReact = { viewModel.react(post, it) }, onReactComment = viewModel::reactComment)
                    }
                    Screen.COMMENTS -> CommentsScreen(state.comments, state.commentsTotal, state.commentsLoading, state.commentsError,
                        state.pendingCommentIds.isNotEmpty(), onBack = viewModel::back, onLoadMore = viewModel::loadMoreComments,
                        onRetry = viewModel::retryComments, onOpenBranch = viewModel::openBranch,
                        onImage = { image = it }, onVideo = { video = it }, preferences = state.preferences, onVote = viewModel::voteComment,
                        reactionChoices = viewModel.reactionChoices, onReact = viewModel::reactComment)
                    Screen.SETTINGS -> SettingsScreen(state, viewModel)
                    Screen.ACCOUNT -> AccountScreen(state, viewModel, onLogin = { login = true })
                }
            }
            if (state.screen == Screen.READER && (state.postLoading || state.postError != null)) {
                Row(Modifier.align(Alignment.BottomCenter).background(TvSurface).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.postError ?: "Загрузка полного поста…", color = TvText, fontSize = 13.sp)
                    if (state.postError != null) TvAction("Загрузить пост снова", { state.currentPost?.let(viewModel::openPost) })
                }
            }
            // ↓ belongs to the list, so the bar cannot hold a button: it leaves by itself and never takes focus.
            state.message?.let { message ->
                LaunchedEffect(message) { delay(MESSAGE_MILLIS); viewModel.clearMessage() }
                Text(message, color = TvText, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(TvSurface).padding(horizontal = 24.dp, vertical = 6.dp))
            }
        }
        if (menu) TrackedDialog(onDismissRequest = { menu = false }, properties = dialogProperties) {
            CompositionLocalProvider(LocalTvColors provides tvColors(state.preferences, state.preferences.menuAccent)) {
                NavigationMenu(state.section, onSelect = { menu = false; viewModel.selectSection(it) }, onDismiss = { menu = false })
            }
        }
        // Media dialogs overlay the current screen without recreating it, so reading state survives;
        // the screens themselves return focus once the dialog window is gone.
        image?.let { media -> TrackedDialog(onDismissRequest = { image = null }, properties = dialogProperties) {
            ImageViewer(media) { image = null }
        } }
        video?.let { media -> TrackedDialog(onDismissRequest = { video = null }, properties = dialogProperties) {
            NativeVideoPlayer(media) { video = null }
        } }
        if (login) TrackedDialog(onDismissRequest = { login = false }, properties = dialogProperties) {
            LoginScreen(onClose = { login = false }, onCheckSession = { login = false; viewModel.refreshAccount(importSettings = false) })
        }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(title: String, options: List<Pair<String, Color?>>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = TvMuted, fontSize = 12.sp)
        FlowRow(Modifier.settingsRow(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEachIndexed { index, (label, dot) ->
                TvAction((if (index == selected) "✓ " else "") + label, { onSelect(index) }, dot = dot)
            }
        }
    }
}

/** ←/→ stay in a row: past its end a free search lands several rows away. */
private fun Modifier.settingsRow() = focusProperties {
    onExit = { if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right) cancelFocusChange() }
}.focusGroup()

/** A long post goes minutes without a press, so the TV's screensaver waits; a screen left alone still gets it. */
@Composable
internal fun KeepScreenAwake(presses: IntState, millis: Long = KEEP_AWAKE_MILLIS) {
    val view = LocalView.current
    LaunchedEffect(Unit) {
        snapshotFlow { presses.intValue }.collectLatest {
            view.keepScreenOn = true
            delay(millis)
            view.keepScreenOn = false
        }
    }
}

private const val MESSAGE_MILLIS = 5_000L
private const val KEEP_AWAKE_MILLIS = 30 * 60_000L

private enum class KeyChoice(val title: String) { PLUS("Ставить плюс"), MINUS("Ставить минус") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(state: AppState, vm: PikaViewModel) {
    BackHandler(onBack = vm::back)
    val p = state.preferences
    val scheme = LocalTvColors.current.scheme
    val firstFocus = remember { FocusRequester() }
    var keyChoice by remember { mutableStateOf<KeyChoice?>(null) }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }
    keyChoice?.let { choice -> TrackedDialog(onDismissRequest = { keyChoice = null }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        RemoteKeyCapture(choice.title, { code ->
            keyChoice = null
            if (choice == KeyChoice.PLUS) vm.changePreferences("plusKey", p.withPlusKey(code)) else vm.changePreferences("minusKey", p.withMinusKey(code))
        }, onCancel = { keyChoice = null })
    } }
    LazyColumn(Modifier.fillMaxSize().background(TvBackground), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { TvAction("← Назад", vm::back, Modifier.focusRequester(firstFocus)); Text("Настройки", color = TvText, fontSize = 16.sp) } }
        item { ChoiceRow("Цветовая схема", TvSchemes.map { it.title to it.control }, p.colorScheme) { vm.changePreferences("colorScheme", p.copy(colorScheme = it)) } }
        item { ChoiceRow("Акцент меню", TvAccents.map { it.title(scheme) to it.color(scheme) }, p.menuAccent) { vm.changePreferences("menuAccent", p.copy(menuAccent = it)) } }
        item { ChoiceRow("Акцент кнопок и фокуса", TvAccents.map { it.title(scheme) to it.color(scheme) }, p.accent) { vm.changePreferences("accent", p.copy(accent = it)) } }
        item { FlowRow(Modifier.settingsRow(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("Размер текста ${(p.textScale * 100).roundToInt()}%", color = TvText, fontSize = 13.sp)
            TvAction("А−", { vm.changePreferences("textScale", p.copy(textScale = stepTextScale(p.textScale, up = false))) })
            TvAction("А+", { vm.changePreferences("textScale", p.copy(textScale = stepTextScale(p.textScale, up = true))) })
            Spacer(Modifier.width(12.dp))
            Text("Ширина текста ${(p.columnWidth * 100).roundToInt()}%", color = TvText, fontSize = 13.sp)
            TvAction("Уже", { vm.changePreferences("columnWidth", p.copy(columnWidth = stepColumnWidth(p.columnWidth, -1))) })
            TvAction("Шире", { vm.changePreferences("columnWidth", p.copy(columnWidth = stepColumnWidth(p.columnWidth, 1))) })
        } }
        item { ChoiceRow("Голос кнопками пульта", listOf("Когда выбран рейтинг" to null, "На всём посте" to null, "Выключен" to null), p.voteKeys) { vm.changePreferences("voteKeys", p.copy(voteKeys = it)) } }
        item { FlowRow(Modifier.settingsRow(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TvAction("Ставить плюс: ${remoteKeyName(p.plusKey)}", { keyChoice = KeyChoice.PLUS })
            TvAction("Ставить минус: ${remoteKeyName(p.minusKey)}", { keyChoice = KeyChoice.MINUS })
        } }
        item { ChoiceRow("Расстояние между абзацами", listOf("Плотно" to null, "Обычно" to null, "Свободно" to null), p.blockSpacing) { vm.changePreferences("blockSpacing", p.copy(blockSpacing = it)) } }
        item { FlowRow(Modifier.settingsRow(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TvAction("Эмодзи постов: ${if (p.showReactions) "показывать" else "скрывать"}", { vm.changePreferences("showReactions", p.copy(showReactions = !p.showReactions)) })
            TvAction("18+ контент: ${if (p.showAdult) "показывать" else "скрывать"}", { vm.changePreferences("showAdult", p.copy(showAdult = !p.showAdult)) })
            TvAction("Посты с тегом «жесть»: ${if (p.showHorror) "показывать" else "скрывать"}", { vm.changePreferences("showHorror", p.copy(showHorror = !p.showHorror)) })
            TvAction("Посты по умолчанию: ${if (p.collapsePostsByDefault) "свёрнуты" else "развёрнуты"}", { vm.changePreferences("collapsePostsByDefault", p.copy(collapsePostsByDefault = !p.collapsePostsByDefault)) })
            TvAction("Теги: ${when (p.tagsMode) { 1 -> "скрыты"; 2 -> "все"; else -> "частично" }}", { vm.changePreferences("tagsMode", p.copy(tagsMode = (p.tagsMode + 1) % 3)) })
            TvAction("Теги: ${if (p.tagsAtTop) "над текстом" else "под текстом"}", { vm.changePreferences("tagsAtTop", p.copy(tagsAtTop = !p.tagsAtTop)) })
            TvAction("Ветки: сворачивать после ${p.collapseAfterDepth} уровней", { vm.changePreferences("collapseAfterDepth", p.copy(collapseAfterDepth = when (p.collapseAfterDepth) { 3 -> 5; 5 -> 10; else -> 3 })) })
            TvAction("Импорт подготовленных настроек", vm::importPreparedSettings)
            TvAction("Импортировать настройки из аккаунта", { vm.refreshAccount() })
        } }
        item { Text("Импорт не меняет сайт и сохраняет настройки этого телевизора. Сортировка комментариев из импорта пока не применяется.", color = TvMuted, fontSize = 12.sp, lineHeight = 16.sp) }
    }
}

@Composable
private fun AccountScreen(state: AppState, vm: PikaViewModel, onLogin: () -> Unit) {
    BackHandler(onBack = vm::back)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().background(TvBackground).padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { TvAction("← Назад", vm::back, Modifier.focusRequester(focus)); Text("Аккаунт Пикабу", color = TvText, fontSize = 16.sp) }
        Text(state.accountName?.let { "Вы вошли как $it" } ?: "Войдите, чтобы читать подписки и сохранённое на сайте.", color = TvText, fontSize = 14.sp)
        if (state.accountBusy) Text("Проверка сессии…", color = TvMuted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TvAction("Войти в Пикабу", onLogin)
            TvAction("Проверить вход и импортировать настройки", { vm.refreshAccount() })
            if (state.accountName != null) TvAction("Выйти из аккаунта", vm::signOut)
        }
        Text("Вход выполняется отдельно в приложении. Настройки сайта, подписки и оценки не изменяются. Локальные история и избранное доступны без аккаунта.", color = TvMuted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}
