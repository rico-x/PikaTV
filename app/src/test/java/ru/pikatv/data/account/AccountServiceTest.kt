package ru.pikatv.data.account

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.pikatv.domain.ContentError
import ru.pikatv.domain.PikabuException

class AccountServiceTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }
    private fun service() = AccountService(cookieProvider = { "session=synthetic" }, baseUrl = server.url("/").toString())
    private fun settingsPage(name: String) = "<html><head><meta charset=\"windows-1251\"></head><body>" +
        "<script data-entry=\"settings\">{\"main\":{\"userName\":\"$name\",\"isAdultVisible\":true,\"email\":\"synthetic@example.invalid\"}}</script></body></html>"

    @Test fun windows1251FromMetaAndOnlyAllowedSettingsKept() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
            .setBody(Buffer().write(settingsPage("Читатель").toByteArray(charset("windows-1251")))))
        val result = service().readSettings()
        assertEquals("Читатель", result.displayName)
        assertEquals("{\"isAdultVisible\":true}", result.settingsJson)
        assertEquals("session=synthetic", server.takeRequest().getHeader("Cookie"))
    }

    @Test fun sameOriginRedirectFollowedButExternalNeverReceivesSession() = runBlocking {
        val outside = MockWebServer()
        outside.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/settings/"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", outside.url("/settings")))
            assertKind(ContentError.CONTENT_UNAVAILABLE) { service().readSettings() }
            assertEquals(2, server.requestCount)
            assertEquals(0, outside.requestCount)
        } finally { outside.shutdown() }
    }

    @Test fun loginRedirectAndForbiddenRequireSignIn() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/login?next=/settings"))
        assertKind(ContentError.AUTH_REQUIRED) { service().readSettings() }
        server.enqueue(MockResponse().setResponseCode(403))
        assertKind(ContentError.AUTH_REQUIRED) { service().readSettings() }
    }

    private suspend fun assertKind(kind: ContentError, block: suspend () -> Unit) {
        try { block(); fail("Expected $kind") } catch (e: PikabuException) { assertEquals(kind, e.kind) }
    }
}
