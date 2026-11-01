package ru.pikatv.feature.account

/** Network limits of the sign-in window: pikabu.ru and the Yandex ID pages it hands over to. */
internal object SignInPolicy {
    // The site binds its script and captcha to the form only after this address opens the sign-in panel;
    // without it a submit is a plain POST that reloads the page with empty fields.
    const val SIGN_IN_URL = "https://pikabu.ru/signin.php?authRedirect=1&authType=signIn"

    // Only the mobile layout has the sign-in panel; Yandex offers the QR code only to a desktop browser.
    const val PIKABU_AGENT = "Mozilla/5.0 (Linux; Android) PikaTV/0.1"
    const val YANDEX_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"

    private val trackers = setOf("an.yandex.ru", "bs.yandex.ru", "mc.yandex.ru", "yabs.yandex.ru")

    fun isPikabuPage(host: String?) = host == "pikabu.ru" || host == "www.pikabu.ru"

    fun isYandexPage(host: String?) = host != null && host !in trackers && (host.endsWith(".yandex.ru") || host.endsWith(".ya.ru"))

    fun agentFor(host: String?) = if (isYandexPage(host)) YANDEX_AGENT else PIKABU_AGENT

    fun pageAllowed(scheme: String?, host: String?, port: Int, userInfo: String?): Boolean =
        scheme == "https" && port in listOf(-1, 443) && userInfo == null && (isPikabuPage(host) || isYandexPage(host))

    /** A page may load only from its own side, so the site's ad requests stay blocked next to the form. */
    fun requestAllowed(pageHost: String?, scheme: String?, host: String?, port: Int): Boolean {
        if (scheme != "https" || port !in listOf(-1, 443) || host == null) return false
        return if (isYandexPage(pageHost)) isYandexPage(host) || isYandexAsset(host) else host == "pikabu.ru" || host.endsWith(".pikabu.ru")
    }

    private fun isYandexAsset(host: String) = host == "yastatic.net" || host.endsWith(".yastatic.net") || host.endsWith(".yandex.net")
}
