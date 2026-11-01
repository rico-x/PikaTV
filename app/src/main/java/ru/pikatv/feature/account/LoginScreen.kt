package ru.pikatv.feature.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import ru.pikatv.feature.reader.LocalTvColors
import ru.pikatv.feature.reader.TvAction
import ru.pikatv.feature.reader.TvAccent
import ru.pikatv.feature.reader.TvBackground
import ru.pikatv.feature.reader.TvControl
import ru.pikatv.feature.reader.TvMuted
import ru.pikatv.feature.reader.TvSurface
import ru.pikatv.feature.reader.TvText

/** User-operated sign-in only. Passwords/cookies are never read by this screen. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onClose: () -> Unit, onCheckSession: () -> Unit) {
    var message by remember { mutableStateOf("Подготавливаем отдельную форму входа…") }
    var ready by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val yandexFocus = remember { FocusRequester() }
    val context = LocalContext.current
    // Arrows cannot leave a WebView, so Back first returns from the page to the native actions.
    BackHandler { if (webView?.hasFocus() == true) yandexFocus.requestFocus() else onClose() }
    LaunchedEffect(Unit) { yandexFocus.requestFocus() }

    // Page styles follow the chosen colour scheme.
    val palette = listOf("#101916" to TvBackground, "#1c2924" to TvSurface, "#30443a" to TvControl, "#e6f2ea" to TvText, "#acc1b4" to TvMuted, "#65d6a2" to TvAccent)
    val background = TvBackground.toArgb()
    val colorScheme = if (LocalTvColors.current.scheme.light) "light" else "dark"
    val isolate = remember { palette.fold(SignInScripts.ISOLATE.replace("color-scheme:dark", "color-scheme:$colorScheme")) { script, (original, color) -> script.replace(original, "#%06x".format(color.toArgb() and 0xFFFFFF)) } }
    Row(Modifier.fillMaxSize().background(TvBackground).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Вход в Пикабу", color = TvText, fontSize = 16.sp)
            TvAction("Войти с Яндекс ID", { webView?.let { (it.tag as? SignInClient)?.startYandex(it) } }, Modifier.fillMaxWidth().focusRequester(yandexFocus))
            TvAction("Проверить вход", {
                CookieManager.getInstance().flush()
                onCheckSession()
            }, Modifier.fillMaxWidth())
            TvAction("Закрыть", onClose, Modifier.fillMaxWidth())
            // A TV may use a keyboard without a screen, such as the phone remote's bridge. Its settings page
            // does not list that bridge, so only the system picker really switches the active keyboard.
            TvAction("Выбрать клавиатуру", { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }, Modifier.fillMaxWidth())
            Text("Отметьте «Я не робот» и отсканируйте QR-код камерой в приложении Яндекс. Либо введите логин и пароль в форме. Если клавиатура не появилась, выберите другую. После входа окно закроется само. Back возвращает к этим кнопкам.", color = TvMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        alpha = 0f
                        isFocusable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        setBackgroundColor(background)
                        settings.apply {
                            userAgentString = SignInPolicy.PIKABU_AGENT
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            mediaPlaybackRequiresUserGesture = true
                            safeBrowsingEnabled = true
                        }
                        CookieManager.getInstance().let { cookies ->
                            cookies.setAcceptCookie(true)
                            cookies.setAcceptThirdPartyCookies(this, false)
                        }
                        val client = SignInClient(isolate, onCheckSession) { visible, status ->
                            ready = visible
                            message = status
                        }
                        tag = client
                        webViewClient = client
                        setDownloadListener { _, _, _, _, _ ->
                            alpha = 0f
                            ready = false
                            message = "Загрузка файлов из окна входа не поддерживается."
                        }
                        // Not every TV opens the keyboard when a page field takes focus from the remote.
                        setOnKeyListener { view, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_UP && keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER))
                                view.context.getSystemService(InputMethodManager::class.java).showSoftInput(view, 0)
                            false
                        }
                        setOnFocusChangeListener { view, focused -> if (focused) (view as WebView).evaluateJavascript(SignInScripts.FOCUS_FIRST_FIELD, null) }
                        webView = this
                        loadUrl(SignInPolicy.SIGN_IN_URL)
                    }
                },
                onRelease = { view ->
                    webView = null
                    CookieManager.getInstance().flush()
                    (view.tag as? SignInClient)?.release(view)
                },
            )
            if (!ready) Text(message, color = TvText, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
        }
    }
}

private class SignInClient(private val isolateScript: String, private val onSignedIn: () -> Unit, private val status: (Boolean, String) -> Unit) : WebViewClient() {
    private var generation = 0
    private var disposed = false
    private var released = false
    private var failed = false
    private var monitorGeneration = -1
    private var yandexRequested = false
    private var signedInReported = false
    @Volatile private var pageHost: String? = null

    fun release(view: WebView, rendererGone: Boolean = false) {
        disposed = true
        generation++
        if (released) return
        released = true
        if (!rendererGone) { view.stopLoading(); view.onPause() }
        view.removeAllViews()
        view.destroy()
    }

    /** The button waits for the panel while the page loads and reopens it after a failure. */
    fun startYandex(view: WebView) {
        if (disposed) return
        yandexRequested = true
        if (failed) {
            view.settings.userAgentString = SignInPolicy.PIKABU_AGENT
            view.loadUrl(SignInPolicy.SIGN_IN_URL)
        } else if (view.alpha == 1f) pressYandex(view)
    }

    private fun pressYandex(view: WebView) {
        yandexRequested = false
        view.requestFocus()
        if (SignInPolicy.isPikabuPage(hostOf(view))) view.evaluateJavascript(SignInScripts.PRESS_YANDEX) { pressed ->
            if (!disposed && pressed != "true") fail(view, "Сайт не показал вход через Яндекс ID. Используйте логин и пароль.")
        }
    }

    private fun hostOf(view: WebView) = Uri.parse(view.url.orEmpty()).host?.lowercase()

    private fun hide(view: WebView, message: String) {
        view.alpha = 0f
        view.isFocusable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        status(false, message)
    }

    private fun fail(view: WebView, message: String) {
        failed = true
        hide(view, message)
    }

    private fun Uri.allowedAsPage() = SignInPolicy.pageAllowed(scheme, host?.lowercase(), port, userInfo)

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        generation++
        failed = false
        hide(view, "Подготавливаем отдельную форму входа…")
        if (!Uri.parse(url.orEmpty()).allowedAsPage()) {
            view.stopLoading()
            fail(view, "Этот адрес входа не поддерживается. Закройте окно и продолжите чтение.")
            return
        }
        val current = generation
        view.postDelayed({
            if (!disposed && current == generation && view.alpha == 0f && !signedInReported) {
                view.stopLoading()
                fail(view, "Форма входа недоступна. Возможны проверка браузера или изменение сайта. Полная страница скрыта.")
            }
        }, 30_000)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.url.allowedAsPage()) {
            // A link on a provider page simply does nothing; a redirect has no page to stay on.
            if (request.isForMainFrame && request.isRedirect) fail(view, "Этот способ входа или внешний адрес пока не поддерживается.")
            return true
        }
        val agent = SignInPolicy.agentFor(request.url.host?.lowercase())
        if (!request.isForMainFrame || view.settings.userAgentString == agent) return false
        // The cancelled navigation was not sent yet, so the address is requested once with the new agent.
        view.settings.userAgentString = agent
        view.loadUrl(request.url.toString())
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val host = uri.host?.lowercase()
        if (request.isForMainFrame) pageHost = host
        return if (SignInPolicy.requestAllowed(pageHost, uri.scheme, host, uri.port)) null
        else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    override fun onPageCommitVisible(view: WebView, url: String) { isolate(view, generation, 0) }
    override fun onPageFinished(view: WebView, url: String) {
        CookieManager.getInstance().flush()
        isolate(view, generation, 0)
    }

    private fun isolate(view: WebView, current: Int, attempt: Int) {
        if (disposed || current != generation) return
        // Yandex ID has its own page without ads; of a Pikabu page only the sign-in panel is ever shown.
        if (!SignInPolicy.isPikabuPage(hostOf(view))) {
            view.evaluateJavascript(SignInScripts.PREPARE_YANDEX, null)
            reveal(view, current, takeFocus = true)
            return
        }
        view.evaluateJavascript(isolateScript) { result ->
            if (disposed || current != generation) return@evaluateJavascript
            when {
                result == "\"signed-in\"" -> if (!signedInReported) {
                    signedInReported = true
                    hide(view, "Вход выполнен. Проверяем сессию…")
                    CookieManager.getInstance().flush()
                    onSignedIn()
                }
                result == "\"ready\"" -> {
                    reveal(view, current, takeFocus = false)
                    if (monitorGeneration != current) { monitorGeneration = current; monitor(view, current) }
                }
                // The panel opens only after the site's scripts have started.
                attempt < 40 -> view.postDelayed({ isolate(view, current, attempt + 1) }, 250)
                else -> fail(view, "Не удалось открыть форму входа. Полная страница скрыта. Если вы уже вошли, нажмите «Проверить вход».")
            }
        }
    }

    private fun reveal(view: WebView, current: Int, takeFocus: Boolean) {
        // Wait until the CSS-constrained DOM is in the next rendered frame.
        view.postVisualStateCallback(current.toLong(), object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (disposed || current != generation) return
                view.alpha = 1f
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                status(true, "")
                if (takeFocus) view.requestFocus()
                if (yandexRequested) pressYandex(view)
            }
        })
    }

    private fun monitor(view: WebView, current: Int) {
        view.postDelayed({
            if (!disposed && current == generation) {
                view.evaluateJavascript(SignInScripts.PANEL_OPEN) { value ->
                    if (disposed || current != generation) return@evaluateJavascript
                    if (value == "true") monitor(view, current)
                    else fail(view, "Форма закрыта. Нажмите «Войти с Яндекс ID» или «Проверить вход», если уже вошли.")
                }
            }
        }, 1_000)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) { generation++; fail(view, "Не удалось загрузить форму входа. Проверьте сеть и повторите позже.") }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) { generation++; fail(view, "Сайт не предоставил форму входа. Закройте окно и повторите позже.") }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        generation++
        fail(view, "Защищённое соединение с сайтом не подтверждено. Вход остановлен.")
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        hide(view, "Окно входа остановилось. Закройте его и откройте заново.")
        release(view, rendererGone = true)
        return true
    }
}
