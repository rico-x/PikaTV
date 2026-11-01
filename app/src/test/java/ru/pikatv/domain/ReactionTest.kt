package ru.pikatv.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionTest {
    private val heart = Reaction(15, "❤️", 5)
    private val fire = Reaction(13, "🔥", 1, active = true)
    private val post = Post("1", "https://pikabu.ru/story/_1", "Пост", reactions = listOf(heart, fire), canReact = true)

    private fun Post.counts() = reactions.map { Triple(it.id, it.count, it.active) }

    @Test fun newReactionReplacesTheOldOneAndAnEmptyOneDisappears() =
        assertEquals(listOf(Triple(15, 6, true)), post.withReaction(heart, on = true).counts())

    @Test fun withdrawnReactionLowersItsCount() =
        assertEquals(listOf(Triple(15, 5, false)), post.withReaction(fire, on = false).counts())

    @Test fun commentFollowsTheSameRules() =
        assertEquals(listOf(Reaction(15, "❤️", 6, active = true)), Comment("1", reactions = listOf(heart, fire)).withReaction(heart, on = true).reactions)

    @Test fun reactionNobodyGaveIsAdded() =
        assertEquals(listOf(Triple(15, 5, false), Triple(1, 1, true)), post.withReaction(Reaction(1, "😄"), on = true).counts())
}
