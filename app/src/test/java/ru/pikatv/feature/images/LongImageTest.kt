package ru.pikatv.feature.images

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class LongImageTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }

    @Test fun tallScreenshotIsCoveredByBoundedStripsWithoutGaps() {
        val plan = stripPlan(width = 1080, height = 20_000, viewportWidth = 1920)
        assertEquals(1, plan.sampleSize)
        assertEquals(0, plan.strips.first().first)
        assertEquals(19_999, plan.strips.last().last)
        plan.strips.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertTrue(plan.strips.all { it.last - it.first + 1 <= 1024 })
    }

    @Test fun wideSourceIsSubsampledButNeverBelowViewportWidth() {
        val plan = stripPlan(width = 4000, height = 9000, viewportWidth = 1920)
        assertEquals(2, plan.sampleSize)
        assertTrue(4000 / plan.sampleSize >= 1920)
        // A decoded strip stays near 2000×1024 pixels whatever the source size.
        assertTrue(plan.strips.all { (it.last - it.first + 1) / plan.sampleSize <= 1024 })
    }

    @Test fun tallDetectionNeedsKnownSizes() {
        assertTrue(isTall(1080, 5000))
        assertFalse(isTall(1920, 1080))
        assertFalse(isTall(null, 5000))
        assertFalse(isTall(0, 5000))
    }

    @Test fun downloadRefusesOversizedBodyEvenWithoutContentLength() {
        val target = folder.newFile()
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(4096)), 512))
        try {
            downloadImage(OkHttpClient(), server.url("/long.png").toString(), target, maxBytes = 1024)
            fail("Expected size refusal")
        } catch (_: IOException) { }
        assertNull(server.takeRequest().getHeader("Cookie"))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(512))))
        downloadImage(OkHttpClient(), server.url("/small.png").toString(), target, maxBytes = 1024)
        assertEquals(512L, target.length())
    }
}
