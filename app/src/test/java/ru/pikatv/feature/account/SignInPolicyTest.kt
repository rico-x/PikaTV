package ru.pikatv.feature.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInPolicyTest {
    private fun page(host: String, scheme: String = "https", port: Int = -1, userInfo: String? = null) =
        SignInPolicy.pageAllowed(scheme, host, port, userInfo)
    private fun request(pageHost: String, host: String) = SignInPolicy.requestAllowed(pageHost, "https", host, -1)

    @Test fun pagesAreLimitedToPikabuAndYandexId() {
        listOf("pikabu.ru", "www.pikabu.ru", "oauth.yandex.ru", "passport.yandex.ru", "sso.passport.yandex.ru", "sso.ya.ru").forEach { assertTrue(it, page(it)) }
        listOf("pikabu.ru.example.com", "notpikabu.ru", "evilyandex.ru", "yandex.ru.example.com", "yandex.ru", "accounts.google.com", "id.vk.com", "mc.yandex.ru", "an.yandex.ru")
            .forEach { assertFalse(it, page(it)) }
    }

    @Test fun pageAddressMustBePlainHttps() {
        assertFalse(page("pikabu.ru", scheme = "http"))
        assertFalse(page("pikabu.ru", port = 8443))
        assertFalse(page("pikabu.ru", userInfo = "user"))
        assertTrue(page("pikabu.ru", port = 443))
    }

    @Test fun pikabuPageLoadsOnlyFromPikabu() {
        assertTrue(request("pikabu.ru", "cs.pikabu.ru"))
        listOf("yandex.ru", "an.yandex.ru", "mc.yandex.ru", "yastatic.net", "ads.adfox.ru", "www.googletagmanager.com").forEach { assertFalse(it, request("pikabu.ru", it)) }
    }

    @Test fun yandexPageLoadsItsAssetsButNotTrackers() {
        listOf("passport.yandex.ru", "yastatic.net", "avatars.mds.yandex.net").forEach { assertTrue(it, request("passport.yandex.ru", it)) }
        listOf("mc.yandex.ru", "an.yandex.ru", "cs.pikabu.ru", "example.com").forEach { assertFalse(it, request("passport.yandex.ru", it)) }
    }

    @Test fun agentFollowsThePage() {
        assertEquals(SignInPolicy.PIKABU_AGENT, SignInPolicy.agentFor("pikabu.ru"))
        assertEquals(SignInPolicy.YANDEX_AGENT, SignInPolicy.agentFor("passport.yandex.ru"))
    }
}
