package ru.pikatv.feature.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.pikatv.domain.AppSection
import ru.pikatv.feature.reader.*

@Composable
fun NavigationMenu(selected: AppSection, onSelect: (AppSection) -> Unit, onDismiss: () -> Unit) {
    val requester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    BackHandler(onBack = onDismiss)
    LaunchedEffect(selected) { listState.scrollToItem(AppSection.entries.indexOf(selected) + 1); androidx.compose.runtime.withFrameNanos { }; requester.requestFocus() }
    Row(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xBB000000)).onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) { onDismiss(); true } else false
    }) {
        LazyColumn(state = listState, modifier = Modifier.width(220.dp).fillMaxHeight().background(TvSurface), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Text("PikaTV", color = TvAccent, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            items(AppSection.entries, key = { it.name }) { section ->
                TvAction((if (section == selected) "●  " else "    ") + section.title,
                    { onSelect(section) }, Modifier.fillMaxWidth().then(if (section == selected) Modifier.focusRequester(requester) else Modifier),
                    leftAligned = true)
            }
        }
    }
}
