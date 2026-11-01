package ru.pikatv.feature.account

// No bridge, HTML export, input values, form submission, or cookie inspection.
internal object SignInScripts {
    /** Presses the site's own button: the captcha and the redirect to Yandex stay the site's. */
    const val PRESS_YANDEX = """(function() {
        var button = document.querySelector('.auth__tab[data-type=signin] a[data-role=ya]');
        if (!button) return false;
        button.click();
        return true;
    })();"""

    /**
     * Returns `signed-in`, `ready` or `wait`. Keeps existing DOM/event handlers; only the sign-in panel
     * and the site's dialogs (captcha, SMS code, error toasts) are displayed, nothing else of the page.
     */
    val ISOLATE = """
        (function() {
          if (!document.body) return 'wait';
          var params = document.querySelector('script[data-entry=initParams]');
          try { if (params && JSON.parse(params.textContent).userID > 0) return 'signed-in'; } catch (e) {}
          document.body.style.setProperty('visibility', 'hidden', 'important');
          var panel = document.querySelector('.app__auth.auth_visible');
          if (!panel) { window.__pikatvSignInReady = false; return 'wait'; }
          panel.setAttribute('data-pikatv-panel', '');
          for (var p = panel.parentElement; p; p = p.parentElement) p.setAttribute('data-pikatv-path', '');
          var css = document.getElementById('pikatv-signin-style');
          if (!css) { css = document.createElement('style'); css.id = 'pikatv-signin-style'; document.head.appendChild(css); }
          css.textContent = `
            html, body { margin:0!important; padding:0!important; background:#101916!important; color-scheme:dark!important; height:auto!important; overflow:auto!important; }
            body * { visibility:hidden!important; }
            [data-pikatv-path] { display:block!important; position:static!important; transform:none!important; opacity:1!important; width:auto!important; height:auto!important; min-height:0!important; margin:0!important; padding:0!important; background:transparent!important; }
            html[data-pikatv-path], body[data-pikatv-path] { background:#101916!important; }
            [data-pikatv-path] > :not([data-pikatv-path]):not([data-pikatv-panel]):not([data-pikatv-keep]) { display:none!important; }
            [data-pikatv-panel], [data-pikatv-panel] *, [data-pikatv-keep], [data-pikatv-keep] * { visibility:visible!important; }
            [data-pikatv-panel] { display:block!important; position:static!important; transform:none!important; opacity:1!important; width:auto!important; height:auto!important; overflow:visible!important; max-width:460px!important; margin:0 auto!important; padding:10px 16px!important; background:#1c2924!important; }
            [data-pikatv-panel], [data-pikatv-panel] * { color:#e6f2ea!important; }
            [data-pikatv-panel] input:not([type=hidden]), [data-pikatv-panel] button { font-size:15px!important; height:34px!important; min-height:0!important; margin:0!important; background:#30443a!important; color:#e6f2ea!important; border:1px solid #acc1b4!important; }
            [data-pikatv-panel] input::placeholder { color:#acc1b4!important; opacity:1!important; }
            [data-pikatv-panel] a { color:#65d6a2!important; }
            [data-pikatv-panel] input:focus { background:#101916!important; border:3px solid #65d6a2!important; outline:none!important; }
            [data-pikatv-panel] button:focus, [data-pikatv-panel] button:focus * { background:#65d6a2!important; color:#101916!important; outline:none!important; }
            [data-pikatv-panel] video, [data-pikatv-panel] [class*=sponsor], [data-pikatv-panel] [data-type*=promo] { display:none!important; }
            [data-pikatv-panel] .auth__close, [data-pikatv-panel] .auth__msg_warning, .auth__tab[data-type=signin] .auth__link, .auth__tab[data-type=signin] .auth__divider, .auth__tab[data-type=signin] .auth__providers { display:none!important; }
            [data-captcha-popup] :focus, [data-captcha-puzzle-popover] :focus { outline:4px solid #65d6a2!important; outline-offset:2px!important; }
            [data-captcha-puzzle-popover] svg:focus { color:#65d6a2!important; outline:none!important; }
            [data-captcha-popup] button[class*=closeIcon] svg { display:none!important; }
            [data-captcha-popup] button[class*=closeIcon]::before { content:"✕"; color:#e6f2ea; font-size:18px; }
          `;
          function showDialogs() {
            document.querySelectorAll('[class*="ModalContainer__container"], [data-captcha-puzzle-popover], .popup, .toasts').forEach(function(dialog) {
              for (var n = dialog; n && n !== document.body; n = n.parentElement) n.setAttribute('data-pikatv-keep', '');
            });
            // Captcha controls are bare click targets: the remote reaches them only through focus.
            document.querySelectorAll('[data-captcha-popup] label[class*="Checkbox__host"], [data-captcha-puzzle-popover] [class*="btn"]').forEach(function(control) {
              if (control.hasAttribute('data-pikatv-remote')) return;
              control.setAttribute('data-pikatv-remote', '');
              control.setAttribute('tabindex', '0');
              if (control === document.querySelector('[data-captcha-puzzle-popover] svg[data-pikatv-remote]')) control.focus();
            });
            holdFocus();
          }
          // While the captcha is on screen focus stays in it, whatever the page or spatial navigation tries.
          function holdFocus() {
            var dialog = document.querySelector('[data-captcha-popup]');
            if (!dialog || !dialog.getClientRects().length || document.activeElement.closest('[data-captcha-popup], [data-captcha-puzzle-popover]')) return;
            // The close button comes first in the page; the remote should land on the checkbox.
            var first = dialog.querySelector('[data-pikatv-remote]') || dialog.querySelector('button');
            if (first) first.focus();
          }
          showDialogs();
          window.__pikatvSignInReady = true;
          if (!window.__pikatvSignInObserver) {
            window.__pikatvSignInObserver = new MutationObserver(showDialogs);
            window.__pikatvSignInObserver.observe(document.documentElement, {childList:true, subtree:true});
            document.addEventListener('focusin', holdFocus, true);
            document.addEventListener('keydown', function(event) {
              var target = document.activeElement;
              if (!target || !target.hasAttribute('data-pikatv-remote')) return;
              // Enter activates links and buttons only; these controls have nothing but a click handler.
              if (event.key === 'Enter' && !event.repeat) {
                event.preventDefault();
                target.dispatchEvent(new MouseEvent('click', {bubbles: true}));
                return;
              }
              // The answers form a ring, clockwise from the top: an arrow key walks it towards its own side.
              var ring = Array.from(document.querySelectorAll('[data-captcha-puzzle-popover] svg[data-pikatv-remote]'));
              var side = {ArrowUp: 0, ArrowRight: 2, ArrowDown: 4, ArrowLeft: 6}[event.key];
              if (!ring.length || side === undefined) return;
              event.preventDefault();
              event.stopPropagation();
              var index = ring.indexOf(target);
              var other = document.querySelector('[data-captcha-puzzle-popover] span[data-pikatv-remote]');
              var next = ring[side];
              if (index >= 0) {
                var turn = (side - index + 8) % 8;
                next = turn === 0 ? (side === 4 ? other : target) : ring[(index + (turn <= 4 ? 1 : 7)) % 8];
              }
              if (next) { next.focus(); next.scrollIntoView({block: 'nearest'}); }
            }, true);
          }
          return 'ready';
        })();
    """.trimIndent()

    /**
     * Opens the QR code and centres it: the Yandex card is taller than the window. The button does nothing
     * until the page's scripts have started, so it is pressed until the code appears. The page ignores
     * the WebView's spatial navigation, so ↑/↓ walk its controls in order.
     */
    const val PREPARE_YANDEX = """(function() {
        if (window.__pikatvQrTimer) return;
        var attempts = 0;
        window.__pikatvQrTimer = setInterval(function() {
          var code = document.querySelector('.QrComponent');
          if (code) code.scrollIntoView({block: 'center'});
          if (code || ++attempts > 40) { clearInterval(window.__pikatvQrTimer); return; }
          var button = document.querySelector('[data-testid="magic-btn"]');
          if (button) button.click();
        }, 500);
        var style = document.createElement('style');
        style.textContent = ':focus { outline:4px solid #65d6a2!important; outline-offset:2px!important; }';
        document.documentElement.appendChild(style);
        document.addEventListener('keydown', function(event) {
          var step = event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0;
          if (!step) return;
          var controls = Array.from(document.querySelectorAll('a[href], button:not([disabled]), input:not([type=hidden]):not([disabled])'))
            .filter(function(control) { return control.getClientRects().length > 0; });
          var next = controls[controls.indexOf(document.activeElement) + step];
          if (!next) return;
          event.preventDefault();
          next.focus();
          next.scrollIntoView({block: 'center'});
        }, true);
    })();"""

    /** A page has no focus of its own on arrival, so entering it starts at the first field. */
    const val FOCUS_FIRST_FIELD = """(function() {
        var current = document.activeElement;
        if (current && current.matches('input, button, a, [data-pikatv-remote]')) return;
        var field = document.querySelector('[data-pikatv-panel] input:not([type=hidden])');
        if (field) field.focus();
    })();"""

    const val PANEL_OPEN = "Boolean(window.__pikatvSignInReady && document.querySelector('.app__auth.auth_visible'))"
}
