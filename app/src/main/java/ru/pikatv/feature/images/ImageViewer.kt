package ru.pikatv.feature.images

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import ru.pikatv.domain.ContentBlock
import ru.pikatv.feature.reader.TvAction
import ru.pikatv.feature.reader.TvSurface
import ru.pikatv.feature.reader.TvText

private enum class ViewMode { FIT, WIDTH }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageViewer(image: ContentBlock.Image, onClose: () -> Unit) {
    var zoom by remember(image.id) { mutableFloatStateOf(1f) }
    var x by remember(image.id) { mutableFloatStateOf(0f) }
    var y by remember(image.id) { mutableFloatStateOf(0f) }
    var toolbar by remember(image.id) { mutableStateOf(false) }
    // Tall images open at screen width; the choice made in the toolbar is never overridden.
    var mode by remember(image.id) { mutableStateOf(if (isTall(image.width, image.height)) ViewMode.WIDTH else ViewMode.FIT) }
    var modeChosen by remember(image.id) { mutableStateOf(false) }
    var notice by remember(image.id) { mutableStateOf<String?>(null) }
    val strips = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val imageFocus = remember { FocusRequester() }
    val toolbarFocus = remember { FocusRequester() }
    BackHandler { if (toolbar) toolbar = false else onClose() }
    LaunchedEffect(toolbar, image.id) { if (toolbar) toolbarFocus.requestFocus() else imageFocus.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val maxX = constraints.maxWidth * (zoom - 1f) / 2f
        val maxY = constraints.maxHeight * (zoom - 1f) / 2f
        val page = constraints.maxHeight * 0.7f
        when (mode) {
            ViewMode.FIT -> AsyncImage(model = image.url, contentDescription = image.description, contentScale = ContentScale.Fit,
                onSuccess = { state -> state.painter.intrinsicSize.let { if (!modeChosen && isTall(it.width.toInt(), it.height.toInt())) mode = ViewMode.WIDTH } },
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = x; translationY = y })
            ViewMode.WIDTH -> WidthImage(image.url, constraints.maxWidth, strips) {
                mode = ViewMode.FIT; modeChosen = true; notice = "Не удалось открыть по ширине, изображение вписано в экран"
            }
        }
        Box(Modifier.fillMaxSize().focusRequester(imageFocus).onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { toolbar = true; true }
                Key.DirectionUp -> { if (mode == ViewMode.WIDTH) scope.launch { strips.animateScrollBy(-page) } else y = (y + 90f).coerceIn(-maxY, maxY); true }
                Key.DirectionDown -> { if (mode == ViewMode.WIDTH) scope.launch { strips.animateScrollBy(page) } else y = (y - 90f).coerceIn(-maxY, maxY); true }
                Key.DirectionLeft -> { if (mode == ViewMode.FIT) x = (x + 90f).coerceIn(-maxX, maxX); true }
                Key.DirectionRight -> { if (mode == ViewMode.FIT) x = (x - 90f).coerceIn(-maxX, maxX); true }
                else -> false
            }
        }.focusable())
        notice?.let { Text(it, color = TvText, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopCenter).background(TvSurface.copy(alpha = .87f)).padding(6.dp)) }
        if (toolbar) FlowRow(Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 10.dp).background(TvSurface.copy(alpha = .87f)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            TvAction(if (mode == ViewMode.FIT) "По ширине" else "Вписать", {
                mode = if (mode == ViewMode.FIT) ViewMode.WIDTH else ViewMode.FIT
                modeChosen = true; notice = null; zoom = 1f; x = 0f; y = 0f; toolbar = false
            }, Modifier.focusRequester(toolbarFocus))
            if (mode == ViewMode.FIT) {
                TvAction("−", { zoom = (zoom - .5f).coerceAtLeast(1f); x = 0f; y = 0f })
                Text("${(zoom * 100).toInt()}%", color = TvText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                TvAction("+", { zoom = (zoom + .5f).coerceAtMost(4f) })
                TvAction("По размеру", { zoom = 1f; x = 0f; y = 0f })
            }
            TvAction("Закрыть", onClose)
            TvAction("К изображению", { toolbar = false })
        }
    }
}
