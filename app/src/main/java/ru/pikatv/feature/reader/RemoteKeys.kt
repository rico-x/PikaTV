package ru.pikatv.feature.reader

import android.view.KeyEvent
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Remote presses anywhere in the app: the screen stays awake for a while after the last one. */
val LocalRemotePresses = staticCompositionLocalOf<MutableIntState?> { null }

/** A dialog is a window of its own, so its presses, a web page's included, never reach the main screen's key handlers. */
@Composable
fun TrackedDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) =
    Dialog(onDismissRequest, properties) {
        val presses = LocalRemotePresses.current
        val view = LocalView.current
        DisposableEffect(view, presses) {
            val window = (view as? DialogWindowProvider ?: view.parent as? DialogWindowProvider)?.window
            val original = window?.callback
            if (presses != null && original != null) window.callback = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean { presses.intValue++; return original.dispatchKeyEvent(event) }
            }
            onDispose { window?.callback = original }
        }
        content()
    }

/** Navigation keys stay with the interface; the system keeps Home, and keys without a code cannot be stored. */
private val ReservedKeys = setOf(KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_HOME)

fun remoteKeyName(code: Int): String = when (code) {
    KeyEvent.KEYCODE_CHANNEL_UP -> "Следующий канал"
    KeyEvent.KEYCODE_CHANNEL_DOWN -> "Предыдущий канал"
    KeyEvent.KEYCODE_PROG_RED -> "Красная"
    KeyEvent.KEYCODE_PROG_GREEN -> "Зелёная"
    KeyEvent.KEYCODE_PROG_YELLOW -> "Жёлтая"
    KeyEvent.KEYCODE_PROG_BLUE -> "Синяя"
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Пуск/пауза"
    KeyEvent.KEYCODE_MEDIA_REWIND -> "Перемотка назад"
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "Перемотка вперёд"
    KeyEvent.KEYCODE_PAGE_UP -> "Страница вверх"
    KeyEvent.KEYCODE_PAGE_DOWN -> "Страница вниз"
    KeyEvent.KEYCODE_MENU -> "Меню"
    KeyEvent.KEYCODE_INFO -> "Инфо"
    KeyEvent.KEYCODE_GUIDE -> "Гид"
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> "Цифра ${code - KeyEvent.KEYCODE_0}"
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}

/** The next remote key becomes [title]'s key; Back leaves the setting as it was. */
@Composable
fun RemoteKeyCapture(title: String, onKey: (Int) -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    var busy by remember { mutableStateOf(false) }
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(TvBackground).testTag("remote-key-capture").focusRequester(focus)
        .onPreviewKeyEvent { event ->
            val code = event.key.nativeKeyCode
            when {
                event.type != KeyEventType.KeyDown || code == KeyEvent.KEYCODE_BACK -> false
                code in ReservedKeys -> { busy = true; true }
                else -> { onKey(code); true }
            }
        }.focusable(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$title: нажмите кнопку пульта", color = TvText, fontSize = 18.sp)
            Text(if (busy) "Эта кнопка нужна для управления, выберите другую" else "Back — отмена", color = TvMuted, fontSize = 13.sp)
        }
    }
}
