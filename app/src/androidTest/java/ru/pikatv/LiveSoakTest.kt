package ru.pikatv

import android.app.Application
import android.content.Context
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.domain.AppSection
import ru.pikatv.domain.Post
import java.io.File

/**
 * Opt-in acceptance run on public Pikabu (guest, read-only):
 * `-e pikatvSoakMinutes 6 -e pikatvSoakPost <story URL with 1000+ comments>`.
 * Rotates feeds with D-pad reading, loads a large discussion in bounded portions, cuts the app's network
 * once mid-run and samples memory every 30 s into files/soak.csv. Without the arguments it is skipped.
 */
@RunWith(AndroidJUnit4::class)
class LiveSoakTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val samples = mutableListOf<Long>()
    private val csv = StringBuilder("elapsed_s,phase,pss_kb,java_kb,native_kb,posts,comments\n")
    private var start = 0L
    private var nextSample = 0L

    // Remote pace: a press lands after the previous scroll settles, as with a person holding the remote.
    private fun key(code: Int, times: Int = 1) = repeat(times) { instrumentation.sendKeyDownUpSync(code); Thread.sleep(700) }

    private fun shell(command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }

    /** Waits without failing the run on one slow live response; the caller decides what a timeout means. */
    private fun await(timeoutMs: Long = 30_000, condition: () -> Boolean): Boolean =
        runCatching { compose.waitUntil(timeoutMs) { condition() } }.isSuccess

    private fun sample(model: PikaViewModel, phase: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < nextSample) return
        nextSample = now + 30_000
        val runtime = Runtime.getRuntime()
        val pss = Debug.getPss()
        val state = model.state.value
        val line = "${(now - start) / 1000},$phase,$pss,${(runtime.totalMemory() - runtime.freeMemory()) / 1024}," +
            "${Debug.getNativeHeapAllocatedSize() / 1024},${state.posts.size},${state.comments.size}"
        samples += pss
        csv.append(line).append('\n')
        Log.i("PikaSoak", line)
    }

    @Test fun readingRunWithLargeDiscussionAndNetworkCut() {
        val args = InstrumentationRegistry.getArguments()
        val minutes = args.getString("pikatvSoakMinutes")?.toLongOrNull()
        val discussionUrl = args.getString("pikatvSoakPost")
        assumeTrue("Set -e pikatvSoakMinutes and -e pikatvSoakPost to run", minutes != null && discussionUrl != null)
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("pikatv_local", Context.MODE_PRIVATE).edit().clear().commit()
        instrumentation.setInTouchMode(false)
        val model = PikaViewModel(app)
        compose.setContent { PikaTvApp(model) }
        start = SystemClock.elapsedRealtime()
        nextSample = start
        val deadline = start + minutes!! * 60_000
        val discussion = Post(Regex("_(\\d+)$").find(discussionUrl!!)!!.groupValues[1], discussionUrl, "Большое обсуждение")
        var largestDiscussion = 0
        var networkCutShown = false
        var networkRestored = false
        var unrecovered = 0
        var round = 0
        assertTrue("First feed", await(60_000) { model.state.value.posts.isNotEmpty() })
        while (SystemClock.elapsedRealtime() < deadline) {
            val section = listOf(AppSection.HOT, AppSection.BEST, AppSection.NEW)[round % 3]
            compose.runOnIdle { model.selectSection(section) }
            if (!await { model.state.value.posts.isNotEmpty() && !model.state.value.feedLoading }) {
                compose.runOnIdle { model.refreshFeed() }
                if (!await { model.state.value.posts.isNotEmpty() }) unrecovered++
            }
            // Two presses per card; the last cards request the next page without moving focus.
            val before = model.state.value.posts.size
            repeat(10) { key(KeyEvent.KEYCODE_DPAD_DOWN, 2); sample(model, "feed") }
            val focusedId = app.getSharedPreferences("pikatv_local", Context.MODE_PRIVATE).getString("focus_${section.name}", null)
            Log.i("PikaSoak", "feed ${section.name}: posts $before→${model.state.value.posts.size}, focused index ${model.state.value.posts.indexOfFirst { it.id == focusedId }}")
            val posts = model.state.value.posts
            if (posts.isNotEmpty()) {
                compose.runOnIdle { model.openPost(posts[(round * 5) % posts.size]) }
                await { !model.state.value.postLoading }
                repeat(4) { key(KeyEvent.KEYCODE_DPAD_DOWN, 4); sample(model, "post") }
                key(KeyEvent.KEYCODE_BACK)
            }
            if (round % 4 == 0) {
                compose.runOnIdle { model.openPost(discussion) }
                await { !model.state.value.postLoading }
                compose.runOnIdle { model.openComments() }
                await(60_000) { model.state.value.comments.isNotEmpty() && !model.state.value.commentsLoading }
                // The first visit reads the whole discussion portion by portion; later visits only the start.
                val target = if (round == 0) 1_100 else 100
                while (model.state.value.pendingCommentIds.isNotEmpty() && model.state.value.comments.size < target &&
                    SystemClock.elapsedRealtime() < deadline) {
                    compose.runOnIdle { model.loadMoreComments() }
                    if (!await { !model.state.value.commentsLoading } || model.state.value.commentsError != null) { unrecovered++; break }
                    key(KeyEvent.KEYCODE_DPAD_DOWN, 3)
                    sample(model, "comments")
                    Thread.sleep(1_000)
                }
                largestDiscussion = maxOf(largestDiscussion, model.state.value.comments.size)
                repeat(10) { key(KeyEvent.KEYCODE_DPAD_DOWN, 6); key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_DPAD_RIGHT); sample(model, "comments") }
                key(KeyEvent.KEYCODE_BACK)
                key(KeyEvent.KEYCODE_BACK)
            }
            if (!networkRestored && SystemClock.elapsedRealtime() - start > (deadline - start) / 2) {
                // The app alone loses network; an error with «Повторить» must replace silence or an empty feed.
                shell("cmd connectivity set-chain3-enabled true")
                shell("cmd connectivity set-package-networking-enabled false ${app.packageName}")
                compose.runOnIdle { model.refreshFeed() }
                networkCutShown = await(60_000) { model.state.value.feedError != null && model.state.value.posts.isNotEmpty() }
                sample(model, "offline")
                shell("cmd connectivity set-package-networking-enabled true ${app.packageName}")
                shell("cmd connectivity set-chain3-enabled false")
                Thread.sleep(3_000)
                compose.runOnIdle { model.refreshFeed() }
                networkRestored = await(60_000) { model.state.value.feedError == null && !model.state.value.feedLoading && model.state.value.posts.isNotEmpty() }
            }
            round++
            // A reading pause also keeps the request rate to the public site low.
            Thread.sleep(5_000)
        }
        Runtime.getRuntime().gc()
        nextSample = 0
        sample(model, "end")
        File(app.filesDir, "soak.csv").writeText(csv.toString())
        Log.i("PikaSoak", "rounds=$round largestDiscussion=$largestDiscussion cutShown=$networkCutShown restored=$networkRestored unrecovered=$unrecovered")
        assertTrue("Large discussion loaded: $largestDiscussion", largestDiscussion >= 1_000)
        assertTrue("Network cut shown as an error", networkCutShown)
        assertTrue("Network restored", networkRestored)
        assertTrue("Unrecovered live failures: $unrecovered", unrecovered == 0)
        // No unbounded growth: the last third of the run stays near the middle third.
        val third = samples.size / 3
        if (third >= 2) {
            val middle = samples.subList(third, 2 * third).sorted()[third / 2]
            val last = samples.subList(2 * third, samples.size).sorted()[(samples.size - 2 * third) / 2]
            assertTrue("PSS median middle=$middle KB last=$last KB", last <= middle * 5 / 4)
        }
    }
}
