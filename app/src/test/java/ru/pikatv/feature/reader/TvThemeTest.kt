package ru.pikatv.feature.reader

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.pikatv.domain.ReaderPreferences
import ru.pikatv.domain.withMinusKey
import ru.pikatv.domain.withPlusKey
import kotlin.math.pow

class TvThemeTest {
    private fun linear(channel: Float) = if (channel <= .03928f) channel / 12.92f else ((channel + .055f) / 1.055f).pow(2.4f)
    private fun luminance(c: Color) = .2126f * linear(c.red) + .7152f * linear(c.green) + .0722f * linear(c.blue)
    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + .05f) / (dark + .05f)
    }

    private fun assertContrast(name: String, a: Color, b: Color, minimum: Float) =
        assertTrue("$name: ${contrast(a, b)}", contrast(a, b) >= minimum)

    @Test fun nightReadingIsTheDefault() = assertTrue(!TvSchemes.first().light && TvSchemes.count { it.light } >= 3)

    @Test fun everySchemeKeepsItsToneAndIsReadable() = TvSchemes.forEach { s ->
        if (s.light) assertTrue("${s.title} must stay light", luminance(s.background) > .7f && luminance(s.surface) > .7f && luminance(s.control) > .6f)
        else assertTrue("${s.title} must stay dark", luminance(s.background) < .03f && luminance(s.surface) < .05f && luminance(s.control) < .12f)
        assertContrast("${s.title} text/background", s.text, s.background, 4.5f)
        assertContrast("${s.title} text/surface", s.text, s.surface, 4.5f)
        assertContrast("${s.title} text/control", s.text, s.control, 4.5f)
        assertContrast("${s.title} muted/surface", s.muted, s.surface, 4.5f)
        assertContrast("${s.title} muted/background", s.muted, s.background, 4.5f)
    }

    @Test fun pressedVoteColoursStayReadable() = TvSchemes.forEach { s ->
        val colors = TvColors(s, TvAccents.first().color(s))
        listOf(colors.votePlus, colors.voteMinus).forEach { color ->
            assertContrast("${s.title} label on pressed vote", s.background, color, 4.5f)
            assertContrast("${s.title} rating on surface", color, s.surface, 4.5f)
            assertContrast("${s.title} pressed vote on surface", color, s.surface, 3f)
        }
    }

    @Test fun everyAccentWorksWithEveryScheme() = TvSchemes.forEach { s ->
        TvAccents.forEach { a ->
            val accent = a.color(s)
            assertContrast("${s.title}/${a.title} focus on surface", accent, s.surface, 3f)
            assertContrast("${s.title}/${a.title} focus on background", accent, s.background, 3f)
            assertContrast("${s.title}/${a.title} focus beside control", accent, s.control, 3f)
            assertContrast("${s.title}/${a.title} focused label", s.background, accent, 4.5f)
        }
    }

    @Test fun unknownStoredIndexFallsBackToDefaults() {
        val colors = tvColors(ReaderPreferences(colorScheme = 99, accent = -1))
        assertEquals(TvSchemes.first(), colors.scheme)
        assertEquals(TvAccents.first().color, colors.accent)
    }

    @Test fun menuAccentIsIndependent() {
        val preferences = ReaderPreferences(accent = 1, menuAccent = 2)
        assertEquals(TvAccents[1].color, tvColors(preferences).accent)
        assertEquals(TvAccents[2].color, tvColors(preferences, preferences.menuAccent).accent)
    }

    @Test fun oneKeyNeverServesBothVotes() {
        val preferences = ReaderPreferences(plusKey = 10, minusKey = 20)
        assertEquals(30, preferences.withPlusKey(30).plusKey)
        assertEquals(20, preferences.withPlusKey(30).minusKey)
        assertEquals(listOf(20, 10), preferences.withPlusKey(20).run { listOf(plusKey, minusKey) })
        assertEquals(listOf(20, 10), preferences.withMinusKey(10).run { listOf(plusKey, minusKey) })
    }

    @Test fun columnWidthStepsStayOnWholeTens() {
        assertEquals(.8f, stepColumnWidth(.79999f, 0))
        assertEquals(.7f, stepColumnWidth(.79999f, -1))
        assertEquals(1f, stepColumnWidth(1f, 1))
        assertEquals(.4f, stepColumnWidth(.4f, -1))
    }

    @Test fun textScaleStepsStayWithinLimits() {
        assertEquals(MIN_TEXT_SCALE, stepTextScale(MIN_TEXT_SCALE, up = false))
        assertEquals(MAX_TEXT_SCALE, stepTextScale(MAX_TEXT_SCALE, up = true))
        assertEquals(1.15f, stepTextScale(1f, up = true))
    }
}
