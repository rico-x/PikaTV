package ru.pikatv.feature.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.pikatv.feature.reader.*

internal data class LegalDocument(val title: String, val path: String)
internal val legalDocuments = listOf(
    LegalDocument("Лицензия PikaTV — MIT", "LICENSE"),
    LegalDocument("Уведомление об авторских правах", "NOTICE.md"),
    LegalDocument("Сторонние компоненты", "THIRD_PARTY_NOTICES.md"),
    LegalDocument("Лицензии и уведомления библиотек", "licenses/runtime-notices.txt"),
    LegalDocument("Apache License 2.0", "licenses/Apache-2.0.txt"),
    LegalDocument("jsoup — MIT", "licenses/jsoup-MIT.txt"),
    LegalDocument("Pikabu Return Minus — MIT", "licenses/Pikabu-Return-Minus-MIT.txt"),
    LegalDocument("Mozilla Public License 2.0", "licenses/MPL-2.0.txt"),
    LegalDocument("Public Suffix List — уведомление", "licenses/OkHttp-PublicSuffix-NOTICE.txt"),
    LegalDocument("Public Suffix List — исходные правила", "licenses/publicsuffix-list-okhttp-4.12.0.txt"),
    LegalDocument("Состав библиотек", "licenses/dependency-inventory.json"),
    LegalDocument("Конфиденциальность", "PRIVACY.md"),
    LegalDocument("Отказ от гарантий", "DISCLAIMER.md"),
    LegalDocument("Использование LLM", "AI_DISCLOSURE.md"),
)

@Composable
fun LicensesDialog(textScale: Float = 1f, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<LegalDocument?>(null) }
    TrackedDialog(onDismissRequest = { if (selected != null) selected = null else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val focus = remember(selected) { FocusRequester() }
        LaunchedEffect(selected) { focus.requestFocus() }
        Column(Modifier.fillMaxSize().background(TvBackground).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvAction(if (selected == null) "← К настройкам" else "← К документам", {
                if (selected == null) onClose() else selected = null
            }, Modifier.focusRequester(focus))
            val document = selected
            if (document == null) {
                val context = LocalContext.current
                val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
                Text("PikaTV $version · О приложении и лицензии", color = TvText, fontSize = (18 * textScale).sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(legalDocuments.size) { index ->
                        val entry = legalDocuments[index]
                        TvAction(entry.title, { selected = entry }, Modifier.fillMaxWidth())
                    }
                }
            } else key(document.path) {
                LegalText(document, textScale)
            }
        }
    }
}

@Composable
private fun ColumnScope.LegalText(document: LegalDocument, scale: Float) {
    val assets = LocalContext.current.assets
    val loaded by produceState<Result<List<String>>?>(null, document.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val text = assets.open("legal/${document.path}").bufferedReader(Charsets.UTF_8).use { it.readText() }
                require(text.isNotBlank())
                // Bound text layout even for the full Public Suffix List, preserving all characters.
                buildList {
                    var start = 0
                    while (start < text.length) {
                        var end = (start + 3200).coerceAtMost(text.length)
                        if (end < text.length && text[end - 1].isHighSurrogate()) end--
                        add(text.substring(start, end))
                        start = end
                    }
                }
            }
        }
    }
    var page by remember { mutableIntStateOf(0) }
    Text(document.title, color = TvText, fontSize = (16 * scale).sp)
    val result = loaded
    if (result == null) Text("Загрузка документа…", color = TvMuted, fontSize = 16.sp)
    else if (result.isFailure) Text("Не удалось открыть документ. Вернитесь к списку и повторите.", color = TvText, fontSize = 16.sp)
    else {
        val pages = result.getOrThrow()
        val previous = remember { FocusRequester() }
        val next = remember { FocusRequester() }
        var restoreControl by remember { mutableStateOf(false) }
        LaunchedEffect(page) {
            if (restoreControl) {
                if (page == 0) next.requestFocus() else previous.requestFocus()
                restoreControl = false
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            key("previous") { if (page > 0) TvAction("Предыдущая страница", { restoreControl = page == 1; page-- }, Modifier.focusRequester(previous)) }
            Text("Страница ${page + 1} из ${pages.size}", color = TvMuted, fontSize = 13.sp)
            key("next") { if (page < pages.lastIndex) TvAction("Следующая страница", { restoreControl = page == pages.lastIndex - 1; page++ }, Modifier.focusRequester(next)) }
        }
        key(page) {
            ReadingArea(Modifier.weight(1f).fillMaxWidth().testTag("legal-document")) {
                Text(pages[page], color = TvText, fontSize = (16 * scale).sp, lineHeight = (22 * scale).sp)
            }
        }
    }
}
