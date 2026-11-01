package ru.pikatv.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ru.pikatv.domain.ReaderPreferences

@Immutable
data class TvScheme(val title: String, val background: Color, val surface: Color, val control: Color, val text: Color, val muted: Color, val light: Boolean = false)

/** A bright accent has no contrast on a light scheme, so each one has a deep twin for those schemes. */
@Immutable
data class TvAccentColor(val title: String, val color: Color, val deep: Color, val deepTitle: String = title) {
    fun color(scheme: TvScheme) = if (scheme.light) deep else color
    fun title(scheme: TvScheme) = if (scheme.light) deepTitle else title
}

/** Dark schemes come first: night reading is the default. TvThemeTest checks the contrast of each combination. */
val TvSchemes = listOf(
    TvScheme("Хвойная", Color(0xFF101916), Color(0xFF1C2924), Color(0xFF30443A), Color(0xFFE6F2EA), Color(0xFFACC1B4)),
    TvScheme("Графит", Color(0xFF131416), Color(0xFF1F2124), Color(0xFF34373C), Color(0xFFE8E9EB), Color(0xFFB1B4BA)),
    TvScheme("Полночь", Color(0xFF0E1420), Color(0xFF182233), Color(0xFF2B3A52), Color(0xFFE4EAF4), Color(0xFFAAB7CB)),
    TvScheme("Чёрная", Color(0xFF000000), Color(0xFF121212), Color(0xFF2C2C2C), Color(0xFFDEDEDE), Color(0xFFA6A6A6)),
    TvScheme("Сепия", Color(0xFF1A1510), Color(0xFF28211A), Color(0xFF42372B), Color(0xFFEBE1D2), Color(0xFFC0B19D)),
    TvScheme("Светлая", Color(0xFFF2F3F5), Color(0xFFFFFFFF), Color(0xFFE1E4E9), Color(0xFF1B1E22), Color(0xFF555B65), light = true),
    TvScheme("Бумага", Color(0xFFF3ECDD), Color(0xFFFBF6EA), Color(0xFFE5DBC6), Color(0xFF2A2418), Color(0xFF5F5543), light = true),
    TvScheme("Небо", Color(0xFFE9F0F7), Color(0xFFF7FAFD), Color(0xFFD5E1ED), Color(0xFF14202E), Color(0xFF4B5A6B), light = true),
)

val TvAccents = listOf(
    TvAccentColor("Мятный", Color(0xFF65D6A2), Color(0xFF0B6E47)),
    TvAccentColor("Небесный", Color(0xFF6CC4F5), Color(0xFF0B639E)),
    TvAccentColor("Янтарный", Color(0xFFF4B860), Color(0xFF8A5200)),
    TvAccentColor("Коралловый", Color(0xFFFF8A7A), Color(0xFFB23A29)),
    TvAccentColor("Лавандовый", Color(0xFFB9A1F7), Color(0xFF5F43BC)),
    TvAccentColor("Жёлтый", Color(0xFFF2E05C), Color(0xFF6E5F00)),
    TvAccentColor("Розовый", Color(0xFFF48FB1), Color(0xFFB02C64)),
    TvAccentColor("Белый", Color(0xFFE8E8E8), Color(0xFF3A3F47), deepTitle = "Графитовый"),
)

/** Material's body style adds a tall fixed line and letter spacing, which take room from content. */
val TvTypography = Typography().let { it.copy(bodyLarge = it.bodyLarge.copy(lineHeight = 1.25.em, letterSpacing = 0.sp)) }

/** Fixed meanings, independent of the chosen accent: a pressed plus is green, a pressed minus is red. */
val VotePlus = Color(0xFF34C759)
val VoteMinus = Color(0xFFFF6B6B)
private val VotePlusDeep = Color(0xFF0B6E2E)
private val VoteMinusDeep = Color(0xFFBF2626)

@Immutable
data class TvColors(val scheme: TvScheme, val accent: Color) {
    val votePlus get() = if (scheme.light) VotePlusDeep else VotePlus
    val voteMinus get() = if (scheme.light) VoteMinusDeep else VoteMinus

    fun material(): ColorScheme = scheme.run {
        if (light) lightColorScheme(primary = accent, onPrimary = background, background = background, onBackground = text, surface = surface, onSurface = text, surfaceVariant = control, onSurfaceVariant = muted)
        else darkColorScheme(primary = accent, onPrimary = background, background = background, onBackground = text, surface = surface, onSurface = text, surfaceVariant = control, onSurfaceVariant = muted)
    }
}

fun tvColors(preferences: ReaderPreferences, accent: Int = preferences.accent): TvColors {
    val scheme = TvSchemes.getOrElse(preferences.colorScheme) { TvSchemes.first() }
    return TvColors(scheme, TvAccents.getOrElse(accent) { TvAccents.first() }.color(scheme))
}

val LocalTvColors = staticCompositionLocalOf { TvColors(TvSchemes.first(), TvAccents.first().color) }

val TvBackground: Color @Composable @ReadOnlyComposable get() = LocalTvColors.current.scheme.background
val TvSurface: Color @Composable @ReadOnlyComposable get() = LocalTvColors.current.scheme.surface
val TvControl: Color @Composable @ReadOnlyComposable get() = LocalTvColors.current.scheme.control
val TvText: Color @Composable @ReadOnlyComposable get() = LocalTvColors.current.scheme.text
val TvMuted: Color @Composable @ReadOnlyComposable get() = LocalTvColors.current.scheme.muted
val TvAccent: Color @Composable @ReadOnlyComposable get() = LocalTvColors.current.accent
val TvVotePlus: Color @Composable @ReadOnlyComposable get() = LocalTvColors.current.votePlus
val TvVoteMinus: Color @Composable @ReadOnlyComposable get() = LocalTvColors.current.voteMinus
