package ru.pikatv.local

import org.junit.Assert.*
import org.junit.Test
import ru.pikatv.domain.ReaderPreferences

class PreferencesImporterTest {
    @Test fun accountValuesAreNormalizedWithoutTouchingTvOverrides() {
        val current = ReaderPreferences(textScale = 1.75f, showAdult = false, collapseAfterDepth = 4)
        val result = PreferencesImporter.import("""{"main":{"isUsedScrollMode":true,"isAdultVisible":true,"maxCommentsBranchDepth":0,"defaultCommentsViewMode":3,"isAnimPreviewComment":true}}""", current, setOf("showAdult"))
        assertFalse(result.collapsePostsByDefault)
        assertFalse(result.showAdult)
        assertEquals(10, result.collapseAfterDepth)
        assertEquals(1.75f, result.textScale)
        assertTrue(result.pauseCommentGifs)
    }

    @Test fun incompleteOrUnknownSettingsPreserveExistingValues() {
        val current = ReaderPreferences(showHorror = false, commentsSort = 2, tagsMode = 1)
        val result = PreferencesImporter.import("""{"sitePreferences":{"defaultCommentsViewMode":999,"tagsFoldMode":999,"minimumStoryRatingView":100000}}""", current)
        assertEquals(current, result)
    }
}
