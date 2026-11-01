package ru.pikatv.local

import org.json.JSONObject
import ru.pikatv.domain.ReaderPreferences

object PreferencesImporter {
    fun import(json: String, current: ReaderPreferences, overrides: Set<String> = emptySet()): ReaderPreferences {
        val root = JSONObject(json)
        val source = root.optJSONObject("sitePreferences") ?: root.optJSONObject("main") ?: root
        fun bool(key: String, fallback: Boolean) = if (source.has(key) && !source.isNull(key)) when (val value = source.opt(key)) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> fallback }
            else -> fallback
        } else fallback
        fun int(key: String, fallback: Int): Int = source.opt(key)?.let { when (it) { is Number -> it.toInt(); is String -> it.toIntOrNull(); else -> null } } ?: fallback
        fun retain(key: String) = key in overrides
        val depth = int("maxCommentsBranchDepth", current.collapseAfterDepth).let { if (it == 0) 10 else it.coerceIn(1, 50) }
        return current.copy(
            showAdult = if (retain("showAdult")) current.showAdult else bool("isAdultVisible", current.showAdult),
            showHorror = if (retain("showHorror")) current.showHorror else bool("isHorrorVisible", current.showHorror),
            collapsePostsByDefault = if (retain("collapsePostsByDefault")) current.collapsePostsByDefault else !bool("isUsedScrollMode", !current.collapsePostsByDefault),
            expandLongPosts = if (retain("expandLongPosts")) current.expandLongPosts else bool("isFullStoryView", current.expandLongPosts),
            tagsAtTop = if (retain("tagsAtTop")) current.tagsAtTop else bool("isTagsAtTop", current.tagsAtTop),
            tagsMode = if (retain("tagsMode")) current.tagsMode else int("tagsFoldMode", current.tagsMode).takeIf { it in 0..2 } ?: current.tagsMode,
            commentsSort = if (retain("commentsSort")) current.commentsSort else int("defaultCommentsViewMode", current.commentsSort).takeIf { it in 1..3 } ?: current.commentsSort,
            collapseAfterDepth = if (retain("collapseAfterDepth")) current.collapseAfterDepth else depth,
            pauseCommentGifs = if (retain("pauseCommentGifs")) current.pauseCommentGifs else bool("isAnimPreviewComment", current.pauseCommentGifs),
            // The first TV version always starts video by explicit remote action.
            autoplay = false,
        )
    }
}
