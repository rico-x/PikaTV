package ru.pikatv.data.pikabu

import ru.pikatv.domain.Reaction

/** The site's reactions with the closest Unicode emoji: its own icons are a multicolour SVG sprite. */
internal object Emotions {
    private val free = linkedMapOf(
        15 to "❤️", 1 to "😄", 16 to "😂", 2 to "😇", 3 to "😮", 4 to "😥", 9 to "😭", 5 to "😡", 10 to "🥰", 25 to "😒", 36 to "🤔", 12 to "🤦",
        7 to "👏", 42 to "👌", 13 to "🔥", 6 to "💯", 41 to "100500", 14 to "💔", 8 to "🤡", 37 to "🤮", 38 to "🌈", 23 to "🗿", 22 to "🍿", 11 to "🕊️",
        24 to "🍓", 40 to "🐔", 39 to "🪗", 35 to "🎄",
    )
    /** The site sells these or keeps them for moderators, so here they are only counted, never spent. */
    private val shownOnly = mapOf(
        26 to "💩", 27 to "💀", 28 to "🥹", 29 to "🙏", 30 to "🫠", 33 to "🤬", 34 to "🎉",
        17 to "🔨", 18 to "✅", 19 to "🧁", 20 to "⏳", 21 to "👀",
    )

    val choices = free.map { (id, emoji) -> Reaction(id, emoji) }

    fun reaction(id: Int, count: Int, active: Boolean) = Reaction(id, free[id] ?: shownOnly[id] ?: "•", count, active, canVote = id in free)
}
