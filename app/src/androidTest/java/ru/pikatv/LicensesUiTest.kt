package ru.pikatv

import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.domain.ReaderPreferences
import ru.pikatv.feature.about.LicensesDialog
import ru.pikatv.feature.reader.*

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LicensesUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(light: Boolean, scale: Float) {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val colors = tvColors(ReaderPreferences(colorScheme = if (light) TvSchemes.indexOfFirst { it.light } else 0))
        compose.setContent {
            CompositionLocalProvider(LocalTvColors provides colors) {
                MaterialTheme(colorScheme = colors.material()) {
                    var open by remember { mutableStateOf(true) }
                    if (open) LicensesDialog(scale) { open = false } else Text("Настройки")
                }
            }
        }
    }

    private fun press(text: String) {
        val node = compose.onNodeWithText(text)
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        node.performKeyInput { pressKey(Key.Enter) }
    }

    @Test fun offlineCopyrightCanBeReadAndBackReturnsToSettings() {
        show(light = false, scale = 1f)
        compose.onNodeWithText("Использование LLM").assertIsDisplayed()
        press("Лицензия PikaTV — MIT")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Permission is hereby granted", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("legal-document").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithTag("legal-document").performKeyInput { pressKey(Key.DirectionDown) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("Лицензия PikaTV — MIT").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("Настройки").assertIsDisplayed()
    }

    @Test fun longLicensePagesRemainReachableInLightThemeAtDoubleScale() {
        show(light = true, scale = 2f)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Apache License 2.0"))
        press("Apache License 2.0")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Следующая страница").fetchSemanticsNodes().isNotEmpty() }
        press("Следующая страница")
        compose.onNode(hasText("Страница 2 из", substring = true)).assertIsDisplayed()
        press("Предыдущая страница")
        compose.onNodeWithText("Следующая страница").assertIsFocused()
        repeat(3) { press("Следующая страница") }
        compose.onNodeWithText("Предыдущая страница").assertIsFocused()
        compose.onNodeWithText("Следующая страница").assertDoesNotExist()
        compose.onNodeWithText("← К документам").assertIsDisplayed()
        press("← К документам")
        compose.onNodeWithText("Лицензия PikaTV — MIT").assertExists()
    }
}
