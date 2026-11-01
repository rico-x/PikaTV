package ru.pikatv

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.domain.*
import ru.pikatv.feature.feed.FeedScreen
import ru.pikatv.feature.reader.ReaderScreen

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SavedCopyKeysTest {
    @get:Rule val compose = createComposeRule()
    private val saved = Post("42", "https://example.invalid/story/42", "Сохранённая копия",
        canVote = false, canReact = false, rating = Rating(10), blocks = listOf(ContentBlock.Text("t", "Текст")))

    @Test fun channelKeysCannotVoteOnASavedFeedCard() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val votes = mutableListOf<Int>()
        compose.setContent {
            FeedScreen(listOf(saved), "История", false, null, false, emptySet(), emptySet(), {}, {}, {}, {}, {}, {}, {},
                preferences = ReaderPreferences(voteKeys = VOTE_KEYS_ON_POST), onVote = { _, v -> votes += v })
        }
        compose.onAllNodesWithText("▲").assertCountEquals(0)
        val card = compose.onNode(hasText(saved.title) and hasClickAction())
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        card.performKeyInput { pressKey(Key.ChannelUp); pressKey(Key.ChannelDown) }
        assertTrue(votes.isEmpty())
    }

    @Test fun channelKeysCannotVoteBeforeTheSavedPostIsRefreshed() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val votes = mutableListOf<Int>()
        compose.setContent {
            ReaderScreen(saved, ReaderPreferences(voteKeys = VOTE_KEYS_ON_POST), false, false, {}, {}, {}, {}, {}, {}, {}, onVote = { votes += it })
        }
        val block = compose.onNode(hasAnyDescendant(hasText("Текст")) and isFocusable())
        block.performSemanticsAction(SemanticsActions.RequestFocus)
        block.performKeyInput { pressKey(Key.ChannelUp); pressKey(Key.ChannelDown) }
        assertTrue(votes.isEmpty())
    }
}
