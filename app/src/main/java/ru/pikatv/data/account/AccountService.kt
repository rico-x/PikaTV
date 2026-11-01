package ru.pikatv.data.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import ru.pikatv.data.pikabu.decodePikabuText
import ru.pikatv.domain.ContentError
import ru.pikatv.domain.PikabuException
import java.util.concurrent.TimeUnit

data class AccountSettings(val displayName: String, val settingsJson: String)

class AccountService(
    private val cookieProvider: (String) -> String?,
    client: OkHttpClient = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build(),
    baseUrl: String = "https://pikabu.ru/",
) {
    // Redirects are followed explicitly: OkHttp keeps a manual Cookie header on cross-host redirects.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val base = baseUrl.toHttpUrl()

    suspend fun readSettings(): AccountSettings = withContext(Dispatchers.IO) {
        var url = base.resolve("/settings")!!
        repeat(MAX_REDIRECTS + 1) {
            val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 16; PikaTV) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36")
            cookieProvider(url.toString())?.takeIf { it.isNotBlank() }?.let { request.header("Cookie", it) }
            client.newCall(request.build()).execute().use { response ->
                if (response.isRedirect) {
                    url = redirectTarget(url, response.header("Location"))
                    return@repeat
                }
                if (response.code in listOf(401, 403)) throw PikabuException(ContentError.AUTH_REQUIRED, "Войдите в аккаунт Пикабу в приложении")
                if (!response.isSuccessful) throw PikabuException(ContentError.NETWORK, "Настройки недоступны: HTTP ${response.code}")
                val body = response.body ?: throw PikabuException(ContentError.SCHEMA_CHANGED, "Пустой ответ настроек")
                return@withContext parse(decodePikabuText(body.bytes(), body.contentType()?.charset()), url.toString())
            }
        }
        throw PikabuException(ContentError.NETWORK, "Слишком много перенаправлений")
    }

    private fun redirectTarget(from: HttpUrl, location: String?): HttpUrl {
        val next = location?.let(from::resolve) ?: throw PikabuException(ContentError.SCHEMA_CHANGED, "Перенаправление без адреса")
        if (next.scheme != base.scheme || next.host != base.host || next.port != base.port)
            throw PikabuException(ContentError.CONTENT_UNAVAILABLE, "Внешнее перенаправление не поддерживается")
        if (next.encodedPath.contains("login") || next.encodedPath.contains("signin"))
            throw PikabuException(ContentError.AUTH_REQUIRED, "Войдите в аккаунт Пикабу в приложении")
        return next
    }

    private fun parse(html: String, url: String): AccountSettings {
        val doc = Jsoup.parse(html, url)
        val script = doc.selectFirst("script[data-entry=settings]") ?: throw PikabuException(ContentError.AUTH_REQUIRED, "Войдите в аккаунт Пикабу в приложении")
        val main = JSONObject(script.data()).optJSONObject("main") ?: throw PikabuException(ContentError.SCHEMA_CHANGED, "Изменился формат настроек Пикабу")
        val allowed = listOf("defaultFeed", "isUsedAjaxLoad", "isUsedScrollMode", "isFullStoryView", "isAdultVisible", "isHorrorVisible", "isTagsAtTop", "tagsFoldMode", "isVideoAutoplayMode", "defaultCommentsViewMode", "maxCommentsBranchDepth", "isAnimPreviewComment")
        val safe = JSONObject()
        allowed.filter { main.has(it) }.forEach { safe.put(it, main.get(it)) }
        return AccountSettings(main.optString("userName", "Пользователь"), safe.toString())
    }

    private companion object { const val MAX_REDIRECTS = 5 }
}
