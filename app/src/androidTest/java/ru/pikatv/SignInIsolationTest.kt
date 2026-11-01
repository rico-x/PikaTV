package ru.pikatv

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.pikatv.feature.account.SignInScripts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic pages with the site's structure: no network, credentials or real captcha are involved. */
@RunWith(AndroidJUnit4::class)
class SignInIsolationTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var web: WebView

    private val panel = """<div class="app__auth auth auth_visible"><div class="auth__tab" data-type="signin">
        <div class="auth__msg" id="message">Неверный пароль</div>
        <form class="auth__form" id="form"><input name="username"><input type="password" name="password"><button type="submit" id="submit">Войти</button></form>
        <button class="auth__link" id="signup">Создать аккаунт</button>
        <div class="auth__providers" id="providers"><a data-role="ya" id="ya" onclick="window.pressed = (window.pressed || 0) + 1; return false">Войти с Яндекс ID</a></div>
    </div></div>"""

    @SuppressLint("SetJavaScriptEnabled")
    private fun load(body: String, userId: Int = 0) {
        val loaded = CountDownLatch(1)
        compose.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                    web = this
                    loadDataWithBaseURL("https://pikabu.ru/", """<!doctype html><html><head><script data-entry="initParams" type="application/json">{"userID":$userId}</script></head>
                        <body><div class="promo" id="promo">Реклама</div><div class="app"><div id="feed">Лента</div>$body</div></body></html>""", "text/html", "UTF-8", null)
                }
            })
        }
        compose.waitForIdle()
        assertEquals(true, loaded.await(20, TimeUnit.SECONDS))
    }

    private fun eval(script: String): String {
        var result = ""
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; done.countDown() } }
        assertEquals(true, done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun shown(id: String) = eval("(function(){var e=document.getElementById('$id'),s=getComputedStyle(e);return s.display!=='none'&&s.visibility==='visible'})()")

    private fun addCaptcha() = eval("""(function(){
        var c=document.createElement('div');c.className='ModalContainer__container--X';
        c.innerHTML='<div class="ModalContainer__content--X"><div data-captcha-popup id="popup"><button id="close">x</button><label class="Checkbox__host--X" id="tick"><input type="checkbox" id="box" style="display:none"></label></div></div>';
        document.body.append(c);
        return true})()""")

    /** Eight answers clockwise from the top, as on the site, with a clip path that hides any outline. */
    private fun addPuzzle() = eval("""(function(){
        window.clicks=0;var p=document.createElement('div');p.setAttribute('data-captcha-puzzle-popover','');p.id='puzzle';
        var html='';for(var i=0;i<8;i++)html+='<svg class="Puzzle__btn--X" id="a'+i+'" width="50" height="50" style="clip-path:circle(20px);color:rgb(1,2,3)"></svg>';
        p.innerHTML=html+'<span class="Label__altLabel__btn--X" id="alternative">другой вариант</span>';
        document.body.append(p);
        for(var j=0;j<8;j++)document.getElementById('a'+j).addEventListener('click',function(){window.clicks++});
        document.getElementById('alternative').addEventListener('click',function(){window.clicks+=10});
        return true})()""")

    @Test fun onlyPanelIsShownAndSignInMessagesStayVisible() {
        load(panel)
        assertEquals("\"ready\"", eval(SignInScripts.ISOLATE))
        assertEquals("false", shown("promo"))
        assertEquals("false", shown("feed"))
        assertEquals("true", shown("form"))
        assertEquals("true", shown("message"))
        assertEquals("false", shown("signup"))
    }

    @Test fun panelIsNotAFocusStopForTheRemote() {
        load(panel)
        eval(SignInScripts.ISOLATE)
        // A scrollable container takes keyboard focus in current WebView, adding a press before the first field.
        assertEquals("\"visible\"", eval("getComputedStyle(document.querySelector('[data-pikatv-panel]')).overflowY"))
    }

    @Test fun remoteTicksCaptchaAddedToBody() {
        load(panel)
        eval(SignInScripts.ISOLATE)
        instrumentation.runOnMainSync { web.requestFocus() }
        instrumentation.setInTouchMode(false)
        addCaptcha()
        compose.waitUntil(5_000) { shown("popup") == "true" && shown("tick") == "true" }
        compose.waitUntil(5_000) { eval("document.activeElement.id") == "\"tick\"" }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { eval("document.getElementById('box').checked") == "true" }
        Thread.sleep(500)
        assertEquals("One press ticks once", "true", eval("document.getElementById('box').checked"))
    }

    @Test fun siteButtonIsPressedThoughHidden() {
        load(panel)
        eval(SignInScripts.ISOLATE)
        assertEquals("false", shown("providers"))
        assertEquals("true", eval(SignInScripts.PRESS_YANDEX))
        assertEquals("1", eval("window.pressed"))
    }

    @Test fun missingSiteButtonIsReported() {
        load("""<div class="app__auth auth auth_visible"><div class="auth__tab" data-type="signin"></div></div>""")
        assertEquals("false", eval(SignInScripts.PRESS_YANDEX))
    }

    @Test fun signedInPageIsReportedInsteadOfPanel() {
        load(panel, userId = 42)
        assertEquals("\"signed-in\"", eval(SignInScripts.ISOLATE))
    }

    @Test fun guestPageWithoutOpenPanelWaits() {
        load("""<div class="app__auth auth"><form class="auth__form"></form></div>""")
        assertEquals("\"wait\"", eval(SignInScripts.ISOLATE))
    }

    private fun preparePuzzle() {
        load(panel)
        eval(SignInScripts.ISOLATE)
        instrumentation.runOnMainSync { web.requestFocus() }
        instrumentation.setInTouchMode(false)
        addPuzzle()
        compose.waitUntil(5_000) { eval("document.activeElement.id") == "\"a0\"" }
    }

    private fun press(key: Int, expected: String) {
        instrumentation.sendKeyDownUpSync(key)
        compose.waitUntil(5_000) { eval("document.activeElement.id") == "\"$expected\"" }
    }

    @Test fun remoteAnswersPuzzleOnceForEachPress() {
        preparePuzzle()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { eval("window.clicks") != "0" }
        assertEquals("1", eval("window.clicks"))
        eval("document.getElementById('alternative').focus()")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) { eval("window.clicks") != "1" }
        assertEquals("11", eval("window.clicks"))
    }

    @Test fun focusedAnswerIsHighlightedThoughItsOutlineIsClipped() {
        preparePuzzle()
        assertEquals("\"rgb(101, 214, 162)\"", eval("getComputedStyle(document.activeElement).color"))
        assertEquals("\"rgb(1, 2, 3)\"", eval("getComputedStyle(document.getElementById('a1')).color"))
    }

    @Test fun arrowKeysWalkTheRingTowardsTheirSide() {
        preparePuzzle()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, "a1")
        press(KeyEvent.KEYCODE_DPAD_RIGHT, "a2")
        press(KeyEvent.KEYCODE_DPAD_RIGHT, "a2")
        press(KeyEvent.KEYCODE_DPAD_DOWN, "a3")
        press(KeyEvent.KEYCODE_DPAD_DOWN, "a4")
        press(KeyEvent.KEYCODE_DPAD_DOWN, "alternative")
        press(KeyEvent.KEYCODE_DPAD_UP, "a0")
        press(KeyEvent.KEYCODE_DPAD_LEFT, "a7")
        press(KeyEvent.KEYCODE_DPAD_LEFT, "a6")
    }

    @Test fun formBehindTheCaptchaTakesNoFocus() {
        load(panel)
        eval(SignInScripts.ISOLATE)
        val password = "document.querySelector('[name=password]')"
        instrumentation.runOnMainSync { web.requestFocus() }
        instrumentation.setInTouchMode(false)
        compose.waitUntil(5_000) { eval("document.hasFocus()") == "true" }
        eval("$password.focus()")
        addCaptcha()
        compose.waitUntil(5_000) { eval("document.activeElement.id") == "\"tick\"" }
        eval("$password.focus()")
        assertEquals("\"tick\"", eval("document.activeElement.id"))
        // A dialog the site hides but keeps in the page must not lock the form.
        eval("document.querySelector('[data-captcha-popup]').style.display = 'none'")
        eval("$password.focus()")
        assertEquals("\"password\"", eval("document.activeElement.name"))
    }

    @Test fun rejectedCaptchaWithoutCheckboxStillGetsFocus() {
        load(panel)
        eval(SignInScripts.ISOLATE)
        eval("""(function(){var c=document.createElement('div');c.className='ModalContainer__container--X';
            c.innerHTML='<div data-captcha-popup><button id="close">x</button></div>';document.body.append(c);return true})()""")
        compose.waitUntil(5_000) { eval("document.activeElement.id") == "\"close\"" }
    }

    @Test fun enteringThePageStartsAtTheFirstField() {
        load(panel)
        eval(SignInScripts.ISOLATE)
        eval(SignInScripts.FOCUS_FIRST_FIELD)
        assertEquals("\"username\"", eval("document.activeElement.name"))
    }
}
