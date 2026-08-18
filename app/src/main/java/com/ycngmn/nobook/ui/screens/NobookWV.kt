package com.ycngmn.nobook.ui.screens

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.multiplatform.webview.web.LoadingState
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberSaveableWebViewState
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.ycngmn.nobook.R
import com.ycngmn.nobook.ui.components.NetworkErrorDialog
import com.ycngmn.nobook.ui.components.settings.SettingsDialog
import com.ycngmn.nobook.ui.viewmodel.MainViewModel
import com.ycngmn.nobook.ui.viewmodel.SettingsViewModel
import com.ycngmn.nobook.utils.DESKTOP_USER_AGENT
import com.ycngmn.nobook.utils.ExternalRequestInterceptor
import com.ycngmn.nobook.utils.fileChooserWebViewParams
import com.ycngmn.nobook.utils.jsBridge.ClipboardBridge
import com.ycngmn.nobook.utils.jsBridge.DownloadBridge
import com.ycngmn.nobook.utils.jsBridge.DownloadFolderBridge
import com.ycngmn.nobook.utils.jsBridge.DownloadFolderPicker
import com.ycngmn.nobook.utils.jsBridge.NobookSettings
import com.ycngmn.nobook.utils.jsBridge.ThemeChange
import com.ycngmn.nobook.utils.rememberAutoDesktop
import com.ycngmn.nobook.utils.rememberImeHeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

private const val ANTI_RELOAD_SCRIPT = """
(function () {
  try {
    if (window.__nobookAntiReloadActive) return;
    window.__nobookAntiReloadActive = true;

    var defineAlways = function (obj, prop, value) {
      try {
        Object.defineProperty(obj, prop, { configurable: true, get: function () { return value; } });
      } catch (e) {}
    };

    defineAlways(document, "visibilityState", "visible");
    defineAlways(document, "hidden", false);
    defineAlways(document, "webkitVisibilityState", "visible");
    defineAlways(document, "webkitHidden", false);

    var blocked = ["visibilitychange", "webkitvisibilitychange", "blur", "pagehide", "freeze"];
    var origAdd = EventTarget.prototype.addEventListener;
    var origDispatch = EventTarget.prototype.dispatchEvent;

    EventTarget.prototype.addEventListener = function (type, listener, options) {
      if (blocked.indexOf(type) !== -1) return;
      return origAdd.call(this, type, listener, options);
    };

    EventTarget.prototype.dispatchEvent = function (evt) {
      if (evt && blocked.indexOf(evt.type) !== -1) return true;
      return origDispatch.call(this, evt);
    };

    window.onblur = null;
    window.onpagehide = null;
    document.onvisibilitychange = null;

    Object.defineProperty(document, "hasFocus", { configurable: true, value: function () { return true; } });

    console.info("[Nobook] Anti-Reload guard active");
  } catch (err) {
    console.error("[Nobook] Anti-Reload injection failed:", err);
  }
})();
"""

private val AFFILIATE_PARAM_PREFIXES = listOf(
    "aff_", "utm_", "af_", "deep_link_", "track_", "spm_", "scm_", "ad_", "algo_"
)

private val AFFILIATE_PARAM_EXACT = setOf(
    "sub_id", "smtt", "is_from_signup", "fbclid", "ttclid", "gclid", "msclkid",
    "yclid", "igshid", "_hsenc", "_openstat", "mc_cid", "mc_eid",
    "pid", "c", "businessId", "is_copy_url", "is_from_webapp", "sender_device",
    "sender_web_id", "enter_method", "share_app_id", "share_link_id", "checksum",
    "tk", "spm", "scm", "pvid", "bxsign", "algo_pvid", "algo_expid", "btsid",
    "ws_ab_test", "sk", "sourceType", "suid", "share_crt_v", "un", "shareurl",
    "tag", "linkCode", "ascsubtag", "creative", "camp", "creativeASIN", "ref_",
    "pf_rd_r", "pf_rd_p", "pf_rd_m", "pf_rd_s", "pf_rd_t", "pf_rd_i",
    "pd_rd_r", "pd_rd_w", "pd_rd_wg", "qid", "sr",
    "ved", "usg", "sa", "ei", "g_ep", "g_st", "source", "source_id", "entry", "coh",
    "context", "rdt", "s", "t", "ref_src", "ref_url",
    "extra_params", "traffic_source", "share_relation_params", "aff_trace_key", "exparams"
)

private fun sanitizeTrackingParams(url: String): String {
    return runCatching {
        val uri = Uri.parse(url)
        val builder = uri.buildUpon().clearQuery()
        for (paramName in uri.queryParameterNames) {
            val lower = paramName.lowercase()
            val isTrackingParam = AFFILIATE_PARAM_PREFIXES.any { lower.startsWith(it) } ||
                AFFILIATE_PARAM_EXACT.contains(lower)
            if (!isTrackingParam) {
                builder.appendQueryParameter(paramName, uri.getQueryParameter(paramName))
            }
        }
        val cleaned = builder.build().toString()
        cleaned.trimEnd('?', '&')
    }.getOrDefault(url)
}

private val REDIRECT_WRAPPER_PARAM_KEYS = listOf(
    "u", "url", "q", "target", "dest", "destination", "redirect", "redirect_url"
)

private fun unwrapRedirectWrapper(urlStr: String): String {
    return runCatching {
        val uri = Uri.parse(urlStr)
        for (key in REDIRECT_WRAPPER_PARAM_KEYS) {
            val raw = uri.getQueryParameter(key) ?: continue
            if (raw.isBlank()) continue
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            if (decoded.startsWith("http", ignoreCase = true)) return decoded
        }
        urlStr
    }.getOrDefault(urlStr)
}

private val MONETIZED_SHORTLINK_HOSTS = setOf(
    "s.shopee.vn", "shope.ee", "vn.shp.ee", "shp.ee",
    "s.lazada.vn", "s.lazada.com", "lzd.co",
    "vt.tiktok.com", "vm.tiktok.com",
    "m.tb.cn", "tb.cn", "s.click.taobao.com", "detail.m.tmall.com", "e.tb.cn",
    "1688.com", "aliexpress.com", "s.click.aliexpress.com", "m.aliexpress.com",
    "2.taobao.com",
    "amzn.to", "amzn.eu", "amzn.asia", "a.co",
    "t.co", "x.com", "redd.it", "bit.ly", "tinyurl.com", "goo.gl",
    "maps.app.goo.gl", "zalo.me", "chat.zalo.me", "fb.me", "fb.watch"
)

private fun isMonetizedShortLink(url: String): Boolean {
    return runCatching {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        MONETIZED_SHORTLINK_HOSTS.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)
}

private fun resolveFinalUrl(startUrl: String, maxHops: Int = 5): String {
    var current = startUrl
    repeat(maxHops) {
        val unwrapped = unwrapRedirectWrapper(current)
        if (unwrapped != current) {
            current = unwrapped
            return@repeat
        }
        val resolved = runCatching {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                )
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            }
            try {
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in intArrayOf(301, 302, 303, 307, 308) && !location.isNullOrBlank()) {
                    if (location.startsWith("http", ignoreCase = true)) location
                    else Uri.parse(current).buildUpon().encodedPath(location).build().toString()
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        if (resolved == null) return current
        current = resolved
    }
    return current
}

private val DEFAULT_SITE_BLOCKLIST = setOf(
    "coin-hive.com", "minergate.com", "coinhive.com"
)

private fun isBlockedSite(url: String): Boolean {
    if (DEFAULT_SITE_BLOCKLIST.isEmpty()) return false
    return DEFAULT_SITE_BLOCKLIST.any { blocked -> url.contains(blocked, ignoreCase = true) }
}

private fun isMessengerAppDeepLink(url: String): Boolean {
    val lower = url.lowercase()
    return lower.startsWith("fb-messenger://") ||
        (lower.startsWith("intent://") && lower.contains("messenger")) ||
        lower.contains("com.facebook.orca") ||
        (lower.startsWith("market://details") && lower.contains("orca"))
}

private fun isMessengerWebPath(url: String): Boolean {
    val lower = url.lowercase()
    return lower.contains("messenger.com") ||
        lower.contains("/messages/") ||
        lower.contains("/messages?") ||
        lower.contains("/direct/inbox")
}

private object VideoPlaybackBridge {
    @Volatile
    var onPlaybackChanged: ((Boolean) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @android.webkit.JavascriptInterface
    fun onVideoPlaying() {
        mainHandler.post { onPlaybackChanged?.invoke(true) }
    }

    @android.webkit.JavascriptInterface
    fun onVideoPaused() {
        mainHandler.post { onPlaybackChanged?.invoke(false) }
    }
}

/**
 * Zero-Trust Media Access bridge
 */
private class CallStateBridge(private val onCallStateChanged: (Boolean) -> Unit) {
    @JavascriptInterface
    fun notifyCallIntent(isCalling: Boolean) {
        Handler(Looper.getMainLooper()).post { onCallStateChanged(isCalling) }
    }
}

/**
 * Intent bridge for File Chooser
 */
private class UploadStateBridge(private val onUploadIntentChanged: (Boolean) -> Unit) {
    @JavascriptInterface
    fun notifyUploadIntent() {
        Handler(Looper.getMainLooper()).post { onUploadIntentChanged(true) }
    }
}

private val TRUSTED_WEBRTC_ORIGINS = setOf(
    "https://www.messenger.com",
    "https://messenger.com",
    "https://www.facebook.com",
    "https://m.facebook.com"
)

private fun createSecureWebChromeClient(getCallState: () -> Boolean, getUploadState: () -> Boolean, resetUploadState: () -> Unit): WebChromeClient {
    return object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            val originUrl = request.origin.toString().lowercase().trimEnd('/')
            val isTrusted = TRUSTED_WEBRTC_ORIGINS.any { trusted ->
                originUrl == trusted || originUrl.startsWith("$trusted/")
            }
            if (!isTrusted) {
                request.deny()
                return
            }

            val resources = request.resources
            val hasAudioOrVideo = resources.any {
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }
            if (!hasAudioOrVideo) {
                request.deny()
                return
            }

            if (!getCallState()) {
                request.deny()
                return
            }

            request.grant(resources)
        }

        override fun onShowFileChooser(
            webView: android.webkit.WebView?,
            filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            if (!getUploadState()) {
                filePathCallback?.onReceiveValue(null)
                return true
            }
            resetUploadState()
            return false 
        }
    }
}

private const val CALL_INTENT_DETECTOR_SCRIPT = """
(function() {
  if (window.__nobookCallDetectorActive) return;
  window.__nobookCallDetectorActive = true;

  document.addEventListener('click', function(e) {
    var target = e.target.closest ? e.target.closest(
      'div[aria-label*="call" i], div[aria-label*="goi" i], div[aria-label*="G\\u1ecdi" i], button[aria-label*="call" i], button[aria-label*="goi" i]'
    ) : null;
    if (target) {
      var label = (target.getAttribute('aria-label') || '').toLowerCase();
      var isEndCall = label.indexOf('end') !== -1 || label.indexOf('ket thuc') !== -1 || label.indexOf('k\\u1ebft th\\u00fac') !== -1;
      if (window.CallStateBridge && window.CallStateBridge.notifyCallIntent) {
        if (isEndCall) {
          window.CallStateBridge.notifyCallIntent(false);
        } else {
          window.CallStateBridge.notifyCallIntent(true);
          setTimeout(function() {
            window.CallStateBridge.notifyCallIntent(false);
          }, 30000);
        }
      }
    }
  }, true);

  console.info('[Nobook] Call-intent detector active (Zero-Trust media gate)');
})();
"""

private const val NETWORK_SANITIZER_AND_PRIVACY_SCRIPT = """
(function() {
  if (window.__nobookPrivacyEngineActive) return;
  window.__nobookPrivacyEngineActive = true;

  // 1. Anti-Clickjacking & Phishing
  if (window.top !== window.self) {
     try { window.top.location = window.self.location; } catch (e) {}
  }
  if (!window.location.hostname.includes("facebook.com") && !window.location.hostname.includes("messenger.com")) {
     if (document.querySelector('input[type="password"]')) {
         console.warn("[Nobook] Possible phishing detected on non-FB domain!");
     }
  }

  // 2. Apple SF Pro Font & CSS Reset
  var cssCore = '' +
    '@import url("https://fonts.cdnfonts.com/css/sf-pro-display");' +
    '* { font-family: "SF Pro Text", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif !important; }' +
    'h1, h2, h3, h4, h5, h6, [role="heading"] { font-family: "SF Pro Display", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif !important; }' +
    
    // ẨN TRIỆT ĐỂ POPUP BẮT TẢI APP MESSENGER TRÊN WEB BẰNG CSS (KHÔNG DÙNG JS POLLING GÂY LAG)
    'div[data-testid="mw_top_banner"], div[aria-label*="Get the Messenger app"], div[aria-label*="Sử dụng ứng dụng Messenger"], div[aria-label*="Cài đặt Messenger"], div[aria-label*="Tải ứng dụng Messenger"], div[aria-label*="Open in app"], a[href*="play.google.com/store/apps/details?id=com.facebook.orca"], a[href*="fb-messenger://"] {' +
    '  display: none !important; opacity: 0 !important; pointer-events: none !important;' +
    '}' +
    
    // Ẩn rác UI Quảng cáo
    '[aria-label="Sponsored"], [data-testid="story-sponsored-label"], [data-ad-comet-preview-id], [data-adunit], [data-sigil="m-feed-voice-subtitle"], div[id^="ad_"] {' +
    '  display: none !important;' +
    '}';

  var styleCore = document.createElement('style');
  styleCore.textContent = cssCore;
  document.head.appendChild(styleCore);

  // 3. J2TEAM Engine: Network Sanitizer, GPC, DNT & Total Reactions
  var BLOCKED_NETWORK_PATTERNS = [
    /an\.facebook\.com/,
    /pixel\.facebook\.com/,
    /graph\.facebook\.com\/v\d+\/\d+\/activities/,
    /graph\.facebook\.com\/.*\/logging/,
    /facebook\.com\/ajax\/bz/,
    /audience_network/,
    /storiesUpdateSeenStateMutation/i,
    /SeenMutation/i,
    /fbevents\.js/,
    /coin-hive\.com/,
    /minergate\.com/
  ];

  var origXhrOpen = XMLHttpRequest.prototype.open;
  var origXhrSend = XMLHttpRequest.prototype.send;

  XMLHttpRequest.prototype.open = function (method, url) {
    this.__nobookUrl = url;
    for (var i = 0; i < BLOCKED_NETWORK_PATTERNS.length; i++) {
      if (BLOCKED_NETWORK_PATTERNS[i].test(url)) {
        console.info('[Nobook] Blocked XHR:', url);
        arguments[1] = 'about:blank';
        break;
      }
    }
    return origXhrOpen.apply(this, arguments);
  };

  XMLHttpRequest.prototype.send = function (body) {
    try {
        if (typeof this.setRequestHeader === 'function') {
            this.setRequestHeader('sec-gpc', '1');
            this.setRequestHeader('dnt', '1');
        }
    } catch(e) {}
    
    this.addEventListener('load', function() {
        if (this.__nobookUrl && this.__nobookUrl.indexOf('graphql') !== -1) {
            try {
                if (this.responseText && this.responseText.indexOf('HIDE_COUNTS') !== -1) {
                    console.info('[Nobook] HIDE_COUNTS detected. Reacting to UI...');
                }
            } catch(e) {}
        }
    });
    return origXhrSend.apply(this, arguments);
  };

  var origFetch = window.fetch;
  window.fetch = async function (input, init) {
    var url = (typeof input === 'string') ? input : (input && input.url) || '';
    for (var i = 0; i < BLOCKED_NETWORK_PATTERNS.length; i++) {
      if (BLOCKED_NETWORK_PATTERNS[i].test(url)) {
        console.info('[Nobook] Blocked Fetch:', url);
        return Promise.resolve(new Response('{}', { status: 200 }));
      }
    }
    
    init = init || {};
    init.headers = init.headers || {};
    init.headers['sec-gpc'] = '1';
    init.headers['dnt'] = '1';

    try {
        var response = await origFetch.call(this, input, init);
        if (url.indexOf('graphql') !== -1) {
            var clone = response.clone();
            clone.text().then(function(text) {
                if(text.indexOf('CometUFIReactionsCountTooltipContentQuery') !== -1 && text.indexOf('HIDE_COUNTS') !== -1) {
                    console.info('[Nobook] Facebook is hiding counts. Executing A Calmer Feed logic.');
                }
            }).catch(function() {});
        }
        return response;
    } catch(err) {
        return Promise.reject(err);
    }
  };

  // 4. J2TEAM Engine: WebSocket Proxy (Hide Typing & Seen)
  try {
      var origWS = window.WebSocket;
      window.WebSocket = new Proxy(origWS, {
        construct: function(target, args) {
          var ws = new target.apply(this, args);
          var origSend = ws.send;
          ws.send = function(data) {
            try {
               if (typeof data === 'string' && data.indexOf('/ls_req') !== -1) {
                   if (data.indexOf('"type":4') !== -1) {
                       console.info('[Nobook] Chặn tín hiệu Đang Gõ (Typing)');
                       return;
                   }
                   if (data.indexOf('"type":3') !== -1 && data.indexOf('"label":"21"') !== -1) {
                       console.info('[Nobook] Chặn tín hiệu Đã Xem (Seen)');
                       return; 
                   }
               }
            } catch(e) {}
            return origSend.apply(this, arguments);
          };
          return ws;
        }
      });
  } catch(e) {}

  // 5. SMART FB TIMER (TỐI ƯU SIÊU NHỎ, 10px, CHỈ HIỆN TRƯỚC NGƯỠNG 1 PHÚT)
  (function initOptimizedTimer() {
    var STORAGE_KEY = 'nobook_fb_usage_seconds';
    var DATE_KEY = 'nobook_fb_usage_date';
    var WARN_INTERVAL_SEC = 1800; // 30 phút

    var todayStr = new Date().toDateString();
    if (localStorage.getItem(DATE_KEY) !== todayStr) {
      localStorage.setItem(DATE_KEY, todayStr);
      localStorage.setItem(STORAGE_KEY, '0');
    }

    var spentSeconds = parseInt(localStorage.getItem(STORAGE_KEY) || '0', 10);

    var badge = document.createElement('div');
    badge.id = 'nobook-smart-timer-badge';
    badge.style.cssText = 'position:fixed;top:8px;right:8px;background:rgba(0,0,0,0.4);' +
      'color:#fff;font-size:10px;padding:2px 5px;border-radius:4px;z-index:999999;font-family:monospace;' +
      'pointer-events:none;display:none;backdrop-filter:blur(2px);transition: opacity 0.3s;';
    document.body.appendChild(badge);

    function showPopupWarning(minutes) {
      var modal = document.createElement('div');
      modal.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.8);z-index:9999999;' +
        'display:flex;align-items:center;justify-content:center;font-family:sans-serif;';
      modal.innerHTML = 
        '<div style="background:#242526;color:#fff;padding:20px;border-radius:12px;max-width:280px;text-align:center;box-shadow:0 4px 20px rgba(0,0,0,0.5);">' +
          '<div style="font-size:32px;margin-bottom:8px;">⏳</div>' +
          '<div style="font-size:16px;font-weight:bold;margin-bottom:6px;">Nghỉ ngơi chút nhé!</div>' +
          '<div style="font-size:13px;color:#b0b3b8;margin-bottom:16px;">Bạn đã lướt Facebook liên tục <b>' + minutes + ' phút</b>.</div>' +
          '<button id="nobook-dismiss-smart-timer" style="width:100%;padding:10px;background:#1877f2;border:none;border-radius:8px;color:#fff;font-weight:bold;cursor:pointer;">Đã hiểu</button>' +
        '</div>';
      document.body.appendChild(modal);
      document.getElementById('nobook-dismiss-smart-timer').onclick = function() { 
          modal.remove(); 
          badge.style.display = 'none'; 
      };
    }

    setInterval(function () {
      if (document.visibilityState === 'visible') {
        spentSeconds += 1;
        if (spentSeconds % 5 === 0) localStorage.setItem(STORAGE_KEY, spentSeconds.toString());
        
        var mins = Math.floor(spentSeconds / 60);
        var secs = spentSeconds % 60;
        
        var nextThreshold = Math.ceil(mins / 30) * 30; 
        if (nextThreshold === 0) nextThreshold = 30;

        // Chỉ hiện badge đếm ngược ở 1 phút trước mốc (vd: 29:00 -> 29:59)
        if (nextThreshold - mins === 1) {
            badge.style.display = 'block';
            var formattedSecs = secs < 10 ? '0' + secs : secs;
            badge.textContent = mins + ':' + formattedSecs;
        } else {
            badge.style.display = 'none';
        }

        // Hiện Popup đúng mốc 30, 60, 90
        if (spentSeconds > 0 && spentSeconds % WARN_INTERVAL_SEC === 0) {
            showPopupWarning(Math.round(spentSeconds / 60));
        }
      }
    }, 1000);
  })();

  // 6. UPLOAD INTENT GATE
  document.addEventListener('click', function(e) {
      var target = e.target.closest ? e.target.closest('input[type="file"], [aria-label*="Photo"], [aria-label*="Video"], [aria-label*="Image"], [aria-label*="Attachment"], [aria-label*="Ảnh/video"], [aria-label*="Thêm ảnh"]') : null;
      if (target && window.UploadStateBridge) {
          window.UploadStateBridge.notifyUploadIntent();
      }
  }, true);

  // 7. DRAGGABLE AI ASSISTANT (APPLE ASSISTIVETOUCH STYLE & IMPORT COOKIE)
  window.toggleNobookAI = function() {
      var aiSidebar = document.getElementById('nobook-ai-sidebar');
      if (!aiSidebar) {
          aiSidebar = document.createElement('div');
          aiSidebar.id = 'nobook-ai-sidebar';
          aiSidebar.style.cssText = 'position:fixed;top:50px;right:20px;width:320px;height:480px;background:#fff;z-index:1000000;box-shadow:0 8px 30px rgba(0,0,0,0.4);border-radius:16px;display:flex;flex-direction:column;font-family:"SF Pro Text", sans-serif;overflow:hidden;';
          
          aiSidebar.innerHTML = 
            '<div id="nobook-ai-header" style="background:#000;color:#fff;padding:15px;font-weight:600;display:flex;justify-content:space-between;align-items:center;cursor:move;user-select:none;">' +
                '<span>✨ AI Assistant & Tools</span>' +
                '<span id="nobook-ai-close" style="cursor:pointer;font-size:18px;">✖</span>' +
            '</div>' +
            '<div style="padding:12px;border-bottom:1px solid #eaeaea;background:#f9f9f9;">' +
                '<div style="display:flex;gap:8px;margin-bottom:8px;">' +
                    '<select id="nobook-ai-model" style="flex:1;padding:8px;border-radius:8px;border:1px solid #ccc;font-size:13px;outline:none;">' +
                        '<option value="gpt">OpenAI GPT</option>' +
                        '<option value="gemini">Google Gemini</option>' +
                    '</select>' +
                '</div>' +
                '<input type="password" id="nobook-ai-key" placeholder="API Key (BYOK)" style="width:100%;padding:8px;box-sizing:border-box;border-radius:8px;border:1px solid #ccc;font-size:13px;margin-bottom:8px;outline:none;">' +
                '<div style="display:flex; gap:8px;">' +
                  '<button id="nobook-extract-cookie" style="flex:1;padding:8px;background:#34C759;color:#fff;border:none;border-radius:8px;cursor:pointer;font-size:12px;font-weight:600;">Xuất Cookie</button>' +
                  '<button id="nobook-import-cookie" style="flex:1;padding:8px;background:#FF9500;color:#fff;border:none;border-radius:8px;cursor:pointer;font-size:12px;font-weight:600;">Nhập Cookie</button>' +
                '</div>' +
            '</div>' +
            '<div id="nobook-ai-chat" style="flex:1;padding:12px;overflow-y:auto;background:#fff;font-size:14px;display:flex;flex-direction:column;line-height:1.4;"></div>' +
            '<div style="padding:12px;border-top:1px solid #eaeaea;display:flex;background:#f9f9f9;align-items:center;">' +
                '<input type="text" id="nobook-ai-input" placeholder="Hỏi AI..." style="flex:1;padding:10px 12px;border:1px solid #ccc;border-radius:20px;outline:none;font-size:14px;">' +
                '<button id="nobook-ai-send" style="background:none;border:none;color:#007AFF;font-weight:600;font-size:15px;cursor:pointer;padding:0 0 0 12px;">Gửi</button>' +
            '</div>';
          document.body.appendChild(aiSidebar);

          // Drag logic for Panel
          var header = document.getElementById('nobook-ai-header');
          var isDragging = false, startY = 0, startX = 0, startTop = 0, startLeft = 0;
          header.onmousedown = function(e) {
              isDragging = true;
              startX = e.clientX; startY = e.clientY;
              var rect = aiSidebar.getBoundingClientRect();
              startLeft = rect.left; startTop = rect.top;
              aiSidebar.style.right = 'auto'; 
              e.preventDefault();
          };
          header.ontouchstart = function(e) {
              isDragging = true;
              startX = e.touches[0].clientX; startY = e.touches[0].clientY;
              var rect = aiSidebar.getBoundingClientRect();
              startLeft = rect.left; startTop = rect.top;
              aiSidebar.style.right = 'auto';
          };
          window.addEventListener('mousemove', function(e) {
              if (!isDragging) return;
              aiSidebar.style.left = (startLeft + (e.clientX - startX)) + 'px';
              aiSidebar.style.top = (startTop + (e.clientY - startY)) + 'px';
          }, {passive: true});
          window.addEventListener('touchmove', function(e) {
              if (!isDragging) return;
              aiSidebar.style.left = (startLeft + (e.touches[0].clientX - startX)) + 'px';
              aiSidebar.style.top = (startTop + (e.touches[0].clientY - startY)) + 'px';
          }, {passive: true});
          window.addEventListener('mouseup', function() { isDragging = false; });
          window.addEventListener('touchend', function() { isDragging = false; });

          // Cookie Extraction Logic
          document.getElementById('nobook-extract-cookie').onclick = function() {
              var cookies = document.cookie;
              var formatted = "FB Cookies:\n" + cookies.split(';').map(c => c.trim()).join('\n');
              if (window.ClipboardBridge && window.ClipboardBridge.copyText) {
                  window.ClipboardBridge.copyText(formatted);
                  alert("Đã sao chép Cookie FB vào bộ nhớ đệm!");
              } else if (navigator.clipboard) {
                  navigator.clipboard.writeText(formatted).then(function() {
                      alert("Đã sao chép Cookie FB vào Clipboard hệ thống!");
                  }).catch(function(err) {
                      prompt("Copy thủ công:", formatted);
                  });
              }
          };

          // Cookie Import Logic
          document.getElementById('nobook-import-cookie').onclick = function() {
              var input = prompt("Dán FB Cookies vào đây (định dạng key=value;):");
              if (input) {
                  var cookiesArray = input.replace('FB Cookies:\n', '').split(';');
                  cookiesArray.forEach(function(c) {
                      var trimmed = c.trim();
                      if (trimmed) {
                          document.cookie = trimmed + "; path=/; domain=.facebook.com";
                      }
                  });
                  alert("Import Cookie thành công! Đang tải lại trang...");
                  window.location.reload();
              }
          };

          // Chat logic
          document.getElementById('nobook-ai-close').onclick = function() { aiSidebar.style.display = 'none'; };

          document.getElementById('nobook-ai-send').onclick = async function() {
              var input = document.getElementById('nobook-ai-input');
              var chat = document.getElementById('nobook-ai-chat');
              var key = document.getElementById('nobook-ai-key').value.trim();
              var model = document.getElementById('nobook-ai-model').value;
              var text = input.value.trim();
              if (!text || !key) { alert('Vui lòng nhập API Key và nội dung.'); return; }

              chat.innerHTML += '<div style="margin-bottom:12px;text-align:right;"><span style="background:#007AFF;color:#fff;padding:10px 14px;border-radius:18px 18px 4px 18px;display:inline-block;max-width:85%;text-align:left;">' + text + '</span></div>';
              input.value = '';
              chat.scrollTop = chat.scrollHeight;

              var resDiv = document.createElement('div');
              resDiv.style.cssText = 'margin-bottom:12px;text-align:left;';
              resDiv.innerHTML = '<span style="background:#F2F2F7;color:#000;padding:10px 14px;border-radius:18px 18px 18px 4px;display:inline-block;max-width:85%;">Đang tải...</span>';
              chat.appendChild(resDiv);
              chat.scrollTop = chat.scrollHeight;

              try {
                  var responseText = "";
                  if (model === 'gemini') {
                      const res = await fetch('https://generativelanguage.googleapis.com/v1beta/models/gemini-pro:generateContent?key=' + key, {
                          method: 'POST', headers: {'Content-Type': 'application/json'},
                          body: JSON.stringify({contents:[{parts:[{text:text}]}]})
                      });
                      const json = await res.json();
                      if (json.error) throw new Error(json.error.message);
                      responseText = json.candidates[0].content.parts[0].text;
                  } else {
                      const res = await fetch('https://api.openai.com/v1/chat/completions', {
                          method: 'POST', headers: {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key},
                          body: JSON.stringify({model: 'gpt-3.5-turbo', messages: [{role: 'user', content: text}]})
                      });
                      const json = await res.json();
                      if (json.error) throw new Error(json.error.message);
                      responseText = json.choices[0].message.content;
                  }
                  resDiv.innerHTML = '<span style="background:#F2F2F7;color:#000;padding:10px 14px;border-radius:18px 18px 18px 4px;display:inline-block;word-break:break-word;max-width:85%;">' + responseText.replace(/\n/g, '<br>') + '</span>';
              } catch(e) {
                  resDiv.innerHTML = '<span style="background:#FF3B30;color:#fff;padding:10px 14px;border-radius:18px 18px 18px 4px;display:inline-block;">Lỗi: ' + e.message + '</span>';
              }
              chat.scrollTop = chat.scrollHeight;
          };
      } else {
          aiSidebar.style.display = aiSidebar.style.display === 'none' ? 'flex' : 'none';
      }
  };

  // NÚT TRIGGER DẠNG ASSISTIVETOUCH (Mờ, Tròn, Draggable)
  var aiTrigger = document.createElement('div');
  aiTrigger.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" fill="#fff"><path d="M12 2a10 10 0 1 0 10 10A10.011 10.011 0 0 0 12 2zm0 18a8 8 0 1 1 8-8 8.009 8.009 0 0 1-8 8zm-1-13h2v6h-2zm0 8h2v2h-2z"/></svg>';
  aiTrigger.style.cssText = 'position:fixed;top:60%;right:10px;width:45px;height:45px;background:rgba(0,0,0,0.4);border-radius:50%;display:flex;align-items:center;justify-content:center;box-shadow:0 4px 10px rgba(0,0,0,0.3);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);cursor:move;z-index:999998;opacity:0.3;transition:opacity 0.3s; border:1px solid rgba(255,255,255,0.2);';
  
  aiTrigger.onmouseover = function() { aiTrigger.style.opacity = '1'; };
  aiTrigger.onmouseout = function() { aiTrigger.style.opacity = '0.3'; };
  
  var isTriggerDragging = false;
  var trigStartY, tStartTop, trigStartX, tStartLeft;
  
  aiTrigger.onmousedown = function(e) {
      isTriggerDragging = true;
      trigStartY = e.clientY; trigStartX = e.clientX;
      tStartTop = aiTrigger.offsetTop; tStartLeft = aiTrigger.offsetLeft;
      e.preventDefault();
  };
  aiTrigger.ontouchstart = function(e) {
      isTriggerDragging = true;
      trigStartY = e.touches[0].clientY; trigStartX = e.touches[0].clientX;
      tStartTop = aiTrigger.offsetTop; tStartLeft = aiTrigger.offsetLeft;
  };
  window.addEventListener('mousemove', function(e) {
      if (!isTriggerDragging) return;
      aiTrigger.style.top = (tStartTop + (e.clientY - trigStartY)) + 'px';
      aiTrigger.style.left = (tStartLeft + (e.clientX - trigStartX)) + 'px';
      aiTrigger.style.right = 'auto';
  }, {passive: true});
  window.addEventListener('touchmove', function(e) {
      if (!isTriggerDragging) return;
      aiTrigger.style.top = (tStartTop + (e.touches[0].clientY - trigStartY)) + 'px';
      aiTrigger.style.left = (tStartLeft + (e.touches[0].clientX - trigStartX)) + 'px';
      aiTrigger.style.right = 'auto';
  }, {passive: true});
  window.addEventListener('mouseup', function() { isTriggerDragging = false; });
  window.addEventListener('touchend', function() { isTriggerDragging = false; });

  aiTrigger.onclick = function(e) {
      if (Math.abs(e.clientY - trigStartY) < 5) {
          window.toggleNobookAI(); 
      }
  };
  document.body.appendChild(aiTrigger);

  console.info('[Nobook] Security, Privacy Engine & AI Assistant Active.');
})();
"""

private const val STORY_REEL_DOWNLOADER_SCRIPT = """
(function() {
  const CONFIG = {
    buttonZIndex: 999999,
    debug: false
  };

  let isProcessing = false;
  let currentContentContainer = null;
  let lastDownloadedUrl = null;
  const DOWNLOAD_BTN_ID = "nobook-global-downloader";
  const MIN_ORIGINAL_SIDE = 500;
  const MAX_ALBUM_ITEMS = 30;

  const SELECTORS = {
    mediaElements: [
      'div[role="dialog"] video:not([hidden])',
      'div[role="dialog"] img[src*="fbcdn"]:not([width="16"]):not([hidden])',
      'div.x1ey2m1c.x9f619.xds687c.x17qophe.x10l6tqk.x13vifvy[role="presentation"] video',
      'div.x1ey2m1c.x9f619.xds687c.x17qophe.x10l6tqk.x13vifvy[role="presentation"] img[src*="fbcdn"]',
      'div[data-pagelet="Story"] video',
      'div[aria-label*="reel"] video',
      'div[data-pagelet="ProfilePhoto"] img[src*="fbcdn"]',
      'div[role="article"] video:not([hidden])'
    ],
    containers: [
      'div[role="dialog"]',
      'div[data-pagelet="Story"]',
      'div[aria-label*="story"]',
      '.story-viewer',
      '.story_viewer',
      'div.x1ey2m1c.x9f619.xds687c.x17qophe.x10l6tqk.x13vifvy[role="presentation"]',
      'div[data-pagelet="ProfilePhoto"]',
      'div[aria-label*="photo"]',
      'div[data-pagelet*="ProfileAppSection"]',
      'div[data-pagelet^="FeedUnit"]',
      'div[role="article"]'
    ]
  };

  const isRealMediaUrl = (url) => {
    if (!url) return false;
    if (url.indexOf("data:image") === 0) return false;
    if (url.indexOf("blob:") === 0) return true;
    const lower = url.toLowerCase();
    const blockedKeywords = ["rsrc.php", "/assets/", "emoji.php", "static.", "placeholder", "favicon"];
    if (blockedKeywords.some(kw => lower.indexOf(kw) !== -1)) return false;
    return /scontent[^./]*\.fbcdn\.net\//i.test(url) ||
      /\.fbcdn\.net\/v\//i.test(url) ||
      /\.fbsbx\.com\//i.test(url) ||
      /\.fbcdn\.net\//i.test(url);
  };

  const isLargeEnough = (element) => {
    const rect = element.getBoundingClientRect();
    if (rect.width > 40 && rect.height > 40) return true;
    if (element.tagName === "VIDEO") {
      return (element.videoWidth || 0) > 50 && (element.videoHeight || 0) > 50;
    }
    if (element.tagName === "IMG") {
      return (element.naturalWidth || 0) > 50 && (element.naturalHeight || 0) > 50;
    }
    return false;
  };

  const isElementVisible = (element) => {
    const rect = element.getBoundingClientRect();
    return (
      rect.width > 0 && rect.height > 0 &&
      rect.bottom > 0 && rect.top < (window.innerHeight || document.documentElement.clientHeight) &&
      rect.right > 0 && rect.left < (window.innerWidth || document.documentElement.clientWidth)
    );
  };

  const findContentContainer = (element) => {
    if (!element) return null;
    for (const selector of SELECTORS.containers) {
      const container = element.closest(selector);
      if (container) return container;
    }
    return element.parentElement;
  };

  const getCurrentMediaElement = () => {
    for (const selector of SELECTORS.mediaElements) {
      const elements = document.querySelectorAll(selector);
      for (const element of elements) {
        if (isElementVisible(element) && (element.src || element.tagName === "VIDEO")) {
          return element;
        }
      }
    }
    return Array.from(
      document.querySelectorAll('video:not([hidden]), img[src*="fbcdn"]:not([width="16"]):not([hidden])')
    ).find(el => {
      return isElementVisible(el) && isLargeEnough(el);
    });
  };

  const stripFacebookCdnParams = (url) => {
    try {
      const u = new URL(url, window.location.href);
      if (/\.fbcdn\.net$/i.test(u.hostname) || /\.fbsbx\.com$/i.test(u.hostname) ||
          u.hostname === "fbcdn.net" || u.hostname === "fbsbx.com") {
        u.searchParams.delete("stp");
        return u.toString();
      }
    } catch (e) { /* ignore */ }
    return url;
  };

  const extractPlayableUrlFromPage = () => {
    try {
      const html = document.documentElement.innerHTML;
      const patterns = [
        /"browser_native_hd_url":"([^"]+)"/,
        /"browser_native_sd_url":"([^"]+)"/,
        /"playable_url_quality_hd":"([^"]+)"/,
        /"playable_url":"([^"]+)"/
      ];
      for (const p of patterns) {
        const m = html.match(p);
        if (m && m[1]) {
          return m[1].replace(/\\/g, '/').replace(/\\u0025/g, '%');
        }
      }
    } catch (e) { /* ignore */ }
    return null;
  };

  const extractOriginalImageUrlFromPage = () => {
    try {
      const html = document.documentElement.innerHTML;
      let best = null;
      let bestArea = -1;

      const patternsOrdered = [
        /"image":\{"height":(\d+),"uri":"([^"]+)","width":(\d+)\}/g,
        /"image":\{"uri":"([^"]+)","width":(\d+),"height":(\d+)\}/g
      ];

      patternsOrdered.forEach((re, idx) => {
        let m;
        while ((m = re.exec(html)) !== null) {
          let uri, w, h;
          if (idx === 0) { h = parseInt(m[1], 10); uri = m[2]; w = parseInt(m[3], 10); }
          else { uri = m[1]; w = parseInt(m[2], 10); h = parseInt(m[3], 10); }
          if (w < MIN_ORIGINAL_SIDE || h < MIN_ORIGINAL_SIDE) continue;
          const area = (w || 0) * (h || 0);
          if (area > bestArea) { bestArea = area; best = uri; }
        }
      });

      if (best) return best.replace(/\\/g, '/').replace(/\\u0025/g, '%');
    } catch (e) { /* ignore */ }
    return null;
  };

  const getBestVideoSource = (videoElement) => {
    const fromRelay = extractPlayableUrlFromPage();
    if (fromRelay) return fromRelay;
    try {
      const sources = Array.from(videoElement.querySelectorAll("source"))
        .map(s => ({
          url: s.src,
          width: parseInt(s.getAttribute("data-width") || s.getAttribute("width") || "0", 10),
          bitrateMatch: (s.src.match(/[?&](?:br|bitrate|vencode_tag)=(\d+)/) || [])[1]
        }))
        .filter(s => s.url);

      if (sources.length > 0) {
        sources.sort((a, b) => {
          const scoreA = a.width || parseInt(a.bitrateMatch || "0", 10);
          const scoreB = b.width || parseInt(b.bitrateMatch || "0", 10);
          return scoreB - scoreA;
        });
        if (sources[0].width || sources[0].bitrateMatch) return sources[0].url;
      }
    } catch (e) { /* fall through to default */ }

    const candidate = videoElement.currentSrc || videoElement.src;
    if (!candidate || candidate.indexOf("blob:") === 0) {
      return null;
    }
    return candidate;
  };

  const getBestImageSource = (imgEl) => {
    let best = null;
    try {
      if (imgEl.srcset) {
        const candidates = imgEl.srcset.split(',')
          .map(s => s.trim().split(/\s+/))
          .filter(p => p[0] && isRealMediaUrl(p[0]));
        let bestW = -1;
        candidates.forEach(([srcUrl, size]) => {
          const w = parseInt((size || '').replace('w', ''), 10) || 0;
          if (w > bestW) { bestW = w; best = srcUrl; }
        });
      }
    } catch (e) { /* ignore */ }

    const current = imgEl.currentSrc || imgEl.src;
    const chosen = (best && isRealMediaUrl(best)) ? best : (isRealMediaUrl(current) ? current : (best || current));
    return stripFacebookCdnParams(chosen);
  };

  const downloadMedia = (url) => {
    if (!url || url.indexOf("blob:") === 0) {
      return Promise.resolve();
    }
    return fetch(url)
      .then(response => response.blob())
      .then(blob => new Promise((resolve) => {
        if (window.DownloadBridge && window.DownloadBridge.downloadBase64File) {
          const reader = new FileReader();
          reader.onloadend = function() {
            if (reader.result) {
              window.DownloadBridge.downloadBase64File(
                reader.result,
                blob.type || "image/jpeg"
              );
            }
            resolve();
          };
          reader.readAsDataURL(blob);
        } else {
          resolve();
        }
      }))
      .catch(err => {
        console.error("Error downloading media:", err);
      });
  };

  const downloadAllSequentially = (urls, onProgress) => {
    let i = 0;
    const total = urls.length;
    const next = () => {
      if (i >= total) return;
      const url = stripFacebookCdnParams(urls[i]);
      i += 1;
      downloadMedia(url).then(() => {
        if (typeof onProgress === "function") onProgress(i, total);
        setTimeout(next, 400);
      });
    };
    next();
  };

  const collectPostMediaUrls = (container) => {
    const urls = [];
    if (!container) return urls;

    const videos = Array.from(container.querySelectorAll("video"));
    videos.forEach((v) => {
      const best = getBestVideoSource(v);
      if (best && isRealMediaUrl(best)) urls.push(best);
    });

    const images = Array.from(container.querySelectorAll('img[src*="fbcdn"]'))
      .filter(img => img.complete && (img.naturalWidth || 0) > 0)
      .filter(img => isRealMediaUrl(img.currentSrc || img.src))
      .filter(img => isLargeEnough(img));
    images.forEach(img => {
      const src = getBestImageSource(img);
      if (src && isRealMediaUrl(src)) urls.push(src);
    });

    const unique = Array.from(new Set(urls.filter(Boolean)));
    return unique.slice(0, MAX_ALBUM_ITEMS);
  };

  const collectPostMediaUrlsAsync = (container, callback) => {
    if (!container) { callback([]); return; }

    const IDLE_MS = 800;
    const MAX_WAIT_MS = 3000;
    let idleTimer = null;
    let finished = false;

    const finish = () => {
      if (finished) return;
      finished = true;
      clearTimeout(idleTimer);
      clearTimeout(maxTimer);
      observer.disconnect();
      callback(collectPostMediaUrls(container));
    };

    const maxTimer = setTimeout(finish, MAX_WAIT_MS);

    const observer = new MutationObserver((mutations) => {
      const hasNewMedia = mutations.some((mutation) => {
        if (mutation.type === "childList") {
          return Array.from(mutation.addedNodes).some((node) =>
            node.nodeType === 1 && (node.tagName === "IMG" || node.tagName === "VIDEO" ||
              (node.querySelector && node.querySelector("img, video")))
          );
        }
        if (mutation.type === "attributes") {
          return mutation.target && (mutation.target.tagName === "IMG" || mutation.target.tagName === "VIDEO") &&
            (mutation.attributeName === "src" || mutation.attributeName === "srcset");
        }
        return false;
      });
      if (hasNewMedia) {
        clearTimeout(idleTimer);
        idleTimer = setTimeout(finish, IDLE_MS);
      }
    });

    observer.observe(container, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["src", "srcset"]
    });

    try {
      if (container.scrollHeight > container.clientHeight) {
        container.scrollTop = container.scrollHeight;
      }
    } catch (e) { /* ignore */ }

    idleTimer = setTimeout(finish, IDLE_MS);
  };

  const MODAL_ID = "nobook-album-choice-modal";

  const closeAlbumModal = () => {
    const el = document.getElementById(MODAL_ID);
    if (el) el.remove();
  };

  const showAlbumChoiceModal = (mediaCount, downloadCurrent, downloadAll) => {
    closeAlbumModal();

    const overlay = document.createElement("div");
    overlay.id = MODAL_ID;
    overlay.style.cssText =
      "position:fixed;inset:0;background:rgba(0,0,0,0.6);z-index:999999;" +
      "display:flex;align-items:center;justify-content:center;";
    overlay.addEventListener("click", (e) => { if (e.target === overlay) closeAlbumModal(); });

    const box = document.createElement("div");
    box.style.cssText =
      "background:#1c1c1e;color:#fff;border-radius:14px;padding:20px;" +
      "max-width:320px;width:88%;box-shadow:0 8px 24px rgba(0,0,0,0.4);" +
      "font-family:sans-serif;position:relative;";

    const closeBtn = document.createElement("button");
    closeBtn.textContent = "\u00D7";
    closeBtn.setAttribute("aria-label", "Dong");
    closeBtn.style.cssText =
      "position:absolute;top:8px;right:10px;background:none;border:none;" +
      "color:#aaa;font-size:20px;cursor:pointer;line-height:1;";
    closeBtn.addEventListener("click", closeAlbumModal);

    const title = document.createElement("div");
    title.textContent = "Phát hiện bài viết có " + mediaCount + " ảnh/video";
    title.style.cssText = "font-size:15px;font-weight:600;margin:4px 24px 16px 0;";

    const btnCurrent = document.createElement("button");
    btnCurrent.textContent = "Tải ảnh/video đang xem (Bản gốc)";
    btnCurrent.style.cssText =
      "display:block;width:100%;padding:11px;margin-bottom:8px;border:none;" +
      "border-radius:8px;background:#3a3a3c;color:#fff;font-size:13px;cursor:pointer;";
    btnCurrent.addEventListener("click", () => { closeAlbumModal(); downloadCurrent(); });

    const btnAll = document.createElement("button");
    btnAll.textContent = "Tải toàn bộ Album (" + mediaCount + " tệp gốc)";
    btnAll.style.cssText =
      "display:block;width:100%;padding:11px;border:none;border-radius:8px;" +
      "background:rgba(24,119,242,0.95);color:#fff;font-size:13px;cursor:pointer;";
    btnAll.addEventListener("click", () => {
      btnAll.disabled = true;
      btnCurrent.disabled = true;
      btnAll.style.opacity = "0.6";
      btnCurrent.style.opacity = "0.6";
      downloadAll((done, total) => {
        title.textContent = "Đang tải " + done + "/" + total + " tệp...";
        if (done >= total) {
          setTimeout(closeAlbumModal, 500);
        }
      });
    });

    box.appendChild(closeBtn);
    box.appendChild(title);
    box.appendChild(btnCurrent);
    box.appendChild(btnAll);
    overlay.appendChild(box);
    document.body.appendChild(overlay);
  };

  const extractAndDownloadMedia = () => {
    const mediaElement = getCurrentMediaElement();
    const postContainer = mediaElement ? findContentContainer(mediaElement) : currentContentContainer;

    const downloadCurrentSingle = () => {
      if (mediaElement && mediaElement.tagName === "VIDEO") {
        const bestUrl = getBestVideoSource(mediaElement);
        if (bestUrl) { downloadMedia(bestUrl); lastDownloadedUrl = bestUrl; }
        return;
      }
      if (mediaElement && mediaElement.src) {
        const bestImgUrl = getBestImageSource(mediaElement);
        downloadMedia(bestImgUrl);
        lastDownloadedUrl = bestImgUrl;
        return;
      }

      const container = currentContentContainer || document.body;
      const videoElement = container.querySelector("video:not([hidden])");
      if (videoElement) {
        const bestUrl = getBestVideoSource(videoElement);
        if (bestUrl) { downloadMedia(bestUrl); lastDownloadedUrl = bestUrl; return; }
      }

      const images = Array.from(container.querySelectorAll("img"))
        .filter(img =>
          img.src &&
          !img.src.includes("data:image") &&
          img.src !== lastDownloadedUrl &&
          isRealMediaUrl(img.currentSrc || img.src)
        )
        .filter(img => isElementVisible(img) && isLargeEnough(img))
        .sort((a, b) => {
          const areaA = a.getBoundingClientRect().width * a.getBoundingClientRect().height;
          const areaB = b.getBoundingClientRect().width * b.getBoundingClientRect().height;
          return areaB - areaA;
        });

      if (images.length > 0) {
        const bestImgUrl = getBestImageSource(images[0]);
        downloadMedia(bestImgUrl);
        lastDownloadedUrl = bestImgUrl;
        return;
      }

      const backgroundElements = Array.from(container.querySelectorAll("*"));
      for (const el of backgroundElements) {
        const style = window.getComputedStyle(el);
        const bgImage = style.backgroundImage;
        if (
          bgImage && bgImage !== "none" &&
          (bgImage.includes("fbcdn.net") || bgImage.includes("fbsbx.com"))
        ) {
          const imageUrl = stripFacebookCdnParams(
            bgImage.replace(/^url\(['"](.+)['"]\)$/, "$1")
          );
          if (!isRealMediaUrl(imageUrl)) continue;
          downloadMedia(imageUrl);
          lastDownloadedUrl = imageUrl;
          return;
        }
      }

      const fallback = extractPlayableUrlFromPage() || extractOriginalImageUrlFromPage();
      if (fallback && isRealMediaUrl(fallback)) {
        downloadMedia(stripFacebookCdnParams(fallback));
        lastDownloadedUrl = fallback;
        return;
      }
    };

    collectPostMediaUrlsAsync(postContainer, (albumUrls) => {
      if (albumUrls.length >= 2) {
        showAlbumChoiceModal(
          albumUrls.length,
          downloadCurrentSingle,
          (onProgress) => downloadAllSequentially(albumUrls, onProgress)
        );
        return;
      }
      downloadCurrentSingle();
    });
  };

  const createDownloadButton = () => {
    const css = '' +
      '#nobook-global-downloader {' +
        'position: fixed;' +
        'top: 70px;' +
        'right: 15px;' +
        'width: 40px;' +
        'height: 40px;' +
        'background-color: rgba(0, 0, 0, 0.7);' +
        'color: white;' +
        'border-radius: 50%;' +
        'z-index: 999999;' +
        'border: none;' +
        'display: none;' +
        'align-items: center;' +
        'justify-content: center;' +
        'font-size: 20px;' +
        'box-shadow: 0 2px 5px rgba(0,0,0,0.3);' +
        'cursor: pointer;' +
        'background-image: url(\'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 -960 960 960" fill="white"><path d="M480-320 280-520l56-58 104 104v-326h80v326l104-104 56 58-200 200ZM240-160q-33 0-56.5-23.5T160-240v-120h80v120h480v-120h80v120q0 33-23.5 56.5T720-160H240Z"/></svg>\');' +
        'background-repeat: no-repeat;' +
        'background-position: center;' +
        'background-size: 24px;' +
      '}' +
      '#nobook-global-downloader.visible {' +
        'display: flex !important;' +
      '}';

    const style = document.createElement("style");
    style.textContent = css;
    document.head.appendChild(style);

    const btn = document.createElement("button");
    btn.id = DOWNLOAD_BTN_ID;
    btn.setAttribute("aria-label", "Download content");

    btn.addEventListener("click", () => {
      currentContentContainer = null;
      const mediaElement = getCurrentMediaElement();
      if (mediaElement) {
        currentContentContainer = findContentContainer(mediaElement);
      }
      extractAndDownloadMedia();
    });

    let pressTimer = null;
    let longPressTriggered = false;
    const startPress = () => {
      longPressTriggered = false;
      pressTimer = setTimeout(() => {
        longPressTriggered = true;
        if (window.DownloadFolderBridge && window.DownloadFolderBridge.pickFolder) {
          window.DownloadFolderBridge.pickFolder();
        }
      }, 700);
    };
    const endPress = () => { if (pressTimer) clearTimeout(pressTimer); };
    btn.addEventListener("touchstart", startPress, { passive: true });
    btn.addEventListener("touchend", endPress);
    btn.addEventListener("mousedown", startPress);
    btn.addEventListener("mouseup", endPress);

    document.body.appendChild(btn);

    return btn;
  };

  const updateButtonVisibility = () => {
    let btn = document.getElementById(DOWNLOAD_BTN_ID);
    if (!btn) btn = createDownloadButton();

    const mediaElement = getCurrentMediaElement();
    if (mediaElement) {
      currentContentContainer = findContentContainer(mediaElement);
      btn.classList.add("visible");
      return;
    }

    const highlightedStoryContainer = document.querySelector(
      'div.x1ey2m1c.x9f619.xds687c.x17qophe.x10l6tqk.x13vifvy[role="presentation"]'
    );

    if (highlightedStoryContainer) {
      const mediaInHighlight = highlightedStoryContainer.querySelector(
        'video, img[src*="fbcdn"]'
      );

      if (mediaInHighlight && isElementVisible(mediaInHighlight)) {
        currentContentContainer = highlightedStoryContainer;
        btn.classList.add("visible");
        return;
      }
    }

    btn.classList.remove("visible");
    currentContentContainer = null;
  };

  const processPage = () => {
    if (isProcessing) return;
    isProcessing = true;
    try {
      updateButtonVisibility();
    } finally {
      isProcessing = false;
    }
  };

  const init = () => {
    currentContentContainer = null;
    lastDownloadedUrl = null;
    processPage();

    const observer = new MutationObserver(mutations => {
      const hasRelevantChanges = mutations.some(
        mutation =>
          (mutation.type === "childList" && mutation.addedNodes.length > 0) ||
          (mutation.type === "attributes" &&
            (mutation.target.tagName === "VIDEO" ||
              mutation.target.tagName === "IMG"))
      );
      if (hasRelevantChanges) processPage();
    });

    observer.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["src", "style", "class"]
    });

    window.addEventListener("scroll", () => {
      requestAnimationFrame(processPage);
    }, { passive: true });

    window.__nobookProcessDownloader = processPage;
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
"""

private const val MESSENGER_GUARD_SCRIPT = """
(function () {
  try {
    if (window.__nobookMessengerGuardActive) return;
    window.__nobookMessengerGuardActive = true;

    function isMessengerDeepLink(url) {
      if (!url) return false;
      var l = String(url).toLowerCase();
      return l.indexOf("fb-messenger://") === 0 ||
        (l.indexOf("intent://") === 0 && l.indexOf("messenger") !== -1) ||
        l.indexOf("com.facebook.orca") !== -1 ||
        (l.indexOf("market://details") === 0 && l.indexOf("orca") !== -1) ||
        (l.indexOf("play.google.com/store/apps/details") !== -1 && l.indexOf("com.facebook.orca") !== -1);
    }

    document.addEventListener("click", function (e) {
      var el = e.target;
      var link = el && el.closest ? el.closest("a[href]") : null;
      
      // BẮT SỰ KIỆN CLICK VÀO ICON MESSENGER ĐỂ BYPASS TẢI APP TRÊN WEB MOBILE
      var isMsgIcon = el && el.closest ? el.closest('a[href*="/messages/"], a[aria-label="Messenger"], div[aria-label="Messenger"], svg[aria-label="Messenger"]') : null;
      if (isMsgIcon) {
        e.preventDefault();
        e.stopPropagation();
        if (typeof e.stopImmediatePropagation === "function") e.stopImmediatePropagation();
        console.info("[Nobook] Chuyển hướng Messenger Web (Bypass ép tải App)");
        window.location.href = "https://www.facebook.com/messages/";
        return;
      }

      if (link && isMessengerDeepLink(link.href)) {
        e.preventDefault();
        e.stopPropagation();
        if (typeof e.stopImmediatePropagation === "function") e.stopImmediatePropagation();
      }
    }, true);

    var origOpen = window.open;
    window.open = function (url) {
      if (isMessengerDeepLink(url)) {
        console.info("[Nobook] Blocked window.open to Messenger deep link:", url);
        return null;
      }
      return origOpen.apply(window, arguments);
    };

    console.info("[Nobook] Messenger deep-link guard & Web Bypass active");
  } catch (err) {
    console.error("[Nobook] Messenger guard injection failed:", err);
  }
})();
"""

private const val LINK_CLEANER_SCRIPT = """
(function () {
  try {
    if (window.__nobookLinkCleanerActive) return;
    window.__nobookLinkCleanerActive = true;

    var FB_TRACKING_PARAMS = [
      'fbclid', '__tn__', '__cft__', '__xts__', 'refid', 'ref', 'notif_t',
      'notif_id', 'tn', 'hc_ref', 'eid', 'fref', 'source', 'source_id',
      'gclid', 'ttclid', 'msclkid', 'yclid', 'igshid', '_hsenc', '_openstat',
      'mc_cid', 'mc_eid', 'ved', 'usg', 'sa', 'ei', 'g_ep', 'g_st', 'entry', 'coh',
      'context', 'rdt', 's', 't', 'ref_src', 'ref_url',
      'tk', 'spm', 'scm', 'pvid', 'bxsign', 'algo_pvid', 'algo_expid', 'btsid',
      'ws_ab_test', 'sk', 'sourceType', 'suid', 'share_crt_v', 'un', 'shareurl',
      'tag', 'linkCode', 'ascsubtag', 'creative', 'camp', 'creativeASIN', 'ref_',
      'pf_rd_r', 'pf_rd_p', 'pf_rd_m', 'pf_rd_s', 'pf_rd_t', 'pf_rd_i',
      'pd_rd_r', 'pd_rd_w', 'pd_rd_wg', 'qid', 'sr',
      'extra_params', 'traffic_source', 'share_relation_params', 'aff_trace_key', 'exparams'
    ];
    var AFF_PREFIXES = ['utm_', 'aff_', 'af_', 'deep_link_', 'track_', 'spm_', 'scm_', 'ad_', 'algo_'];
    var WRAPPER_PARAM_KEYS = ['u', 'url', 'q', 'target', 'dest', 'destination', 'redirect', 'redirect_url'];

    function unwrapRedirect(urlStr) {
      try {
        var u = new URL(urlStr, window.location.href);
        for (var i = 0; i < WRAPPER_PARAM_KEYS.length; i++) {
          var raw = u.searchParams.get(WRAPPER_PARAM_KEYS[i]);
          if (!raw) continue;
          var decoded = decodeURIComponent(raw);
          if (/^https?:\/\//i.test(decoded)) return decoded;
        }
      } catch (e) { /* ignore */ }
      return urlStr;
    }

    function stripParams(urlStr) {
      try {
        var u = new URL(urlStr, window.location.href);
        FB_TRACKING_PARAMS.forEach(function (p) { u.searchParams.delete(p); });
        Array.from(u.searchParams.keys()).forEach(function (k) {
          var lower = k.toLowerCase();
          if (AFF_PREFIXES.some(function (pref) { return lower.indexOf(pref) === 0; })) {
            u.searchParams.delete(k);
          }
        });
        var result = u.toString();
        return result.replace(/[?&]$/, '');
      } catch (e) {
        return urlStr;
      }
    }

    function cleanLink(a) {
      if (!a.href) return;
      var cleaned = unwrapRedirect(a.href);
      cleaned = stripParams(cleaned);
      if (cleaned !== a.href) {
        try { a.href = cleaned; } catch (e) { /* ignore */ }
      }
    }

    function scan() {
      document.querySelectorAll('a[href]').forEach(function (a) {
        if (a.dataset.nobookLinkCleaned) return;
        cleanLink(a);
        a.dataset.nobookLinkCleaned = '1';
      });
    }

    scan();
    var observer = new MutationObserver(function () { scan(); });
    observer.observe(document.body, { childList: true, subtree: true });

    console.info('[Nobook] Link cleaner active');
  } catch (err) {
    console.error('[Nobook] Link cleaner injection failed:', err);
  }
})();
"""

private const val TEXT_SELECTION_SCRIPT = """
(function () {
  try {
    if (window.__nobookTextSelectionActive) return;
    window.__nobookTextSelectionActive = true;

    var css = `
      * { -webkit-user-select: text !important; user-select: text !important; }
      *::selection { background: #3578E5 !important; color: #fff !important; }
    `;
    var style = document.createElement('style');
    style.textContent = css;
    document.head.appendChild(style);

    document.addEventListener('contextmenu', function (e) { e.stopPropagation(); }, true);
    document.addEventListener('selectstart', function (e) { e.stopPropagation(); }, true);

    function copyToClipboard(text) {
      try {
        var ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.top = '0';
        ta.style.left = '0';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
        return true;
      } catch (e) {
        console.error('[Nobook] Copy failed:', e);
        return false;
      }
    }

    function extractFormattedText(root) {
      var BLOCK_TAGS = { DIV: 1, P: 1, LI: 1, UL: 1, OL: 1, SECTION: 1, ARTICLE: 1 };
      var lines = [];
      var current = '';
      function walk(node) {
        if (node.nodeType === Node.TEXT_NODE) {
          current += node.textContent;
          return;
        }
        if (node.nodeType !== Node.ELEMENT_NODE) return;
        var tag = node.tagName;
        if (tag === 'BR') { lines.push(current); current = ''; return; }
        var isBlock = !!BLOCK_TAGS[tag];
        if (isBlock && current.trim().length > 0) { lines.push(current); current = ''; }
        for (var i = 0; i < node.childNodes.length; i++) walk(node.childNodes[i]);
        if (isBlock) { if (current.trim().length > 0) lines.push(current); current = ''; }
      }
      walk(root);
      if (current.trim().length > 0) lines.push(current);
      return lines
        .map(function (l) { return l.replace(/[ \t]+/g, ' ').trim(); })
        .filter(function (l) { return l.length > 0; })
        .join('\n\n');
    }

    function findMainTextContainer(start) {
      var el = start;
      while (el && el !== document.body) {
        if (el.getAttribute && (el.getAttribute('data-ad-preview') === 'message' ||
            el.getAttribute('data-ad-comet-preview') === 'message')) {
          return el;
        }
        el = el.parentElement;
      }
      return null;
    }

    var COPY_ALL_ID = 'nobook-copy-all-btn';
    var copyAllTimer = null;

    function removeCopyAllButton() {
      var el = document.getElementById(COPY_ALL_ID);
      if (el) el.parentNode.removeChild(el);
      if (copyAllTimer) { clearTimeout(copyAllTimer); copyAllTimer = null; }
    }

    function showCopyAllButton(targetEl) {
      removeCopyAllButton();
      var rect = targetEl.getBoundingClientRect();
      var btn = document.createElement('button');
      btn.id = COPY_ALL_ID;
      btn.textContent = 'Copy toan bai (giu format)';
      btn.style.position = 'fixed';
      btn.style.top = Math.max(rect.top - 40, 8) + 'px';
      btn.style.left = Math.max(rect.left, 8) + 'px';
      btn.style.zIndex = '999999';
      btn.style.padding = '8px 14px';
      btn.style.borderRadius = '18px';
      btn.style.border = 'none';
      btn.style.backgroundColor = 'rgba(24,119,242,0.95)';
      btn.style.color = '#fff';
      btn.style.fontSize = '13px';
      btn.style.boxShadow = '0 2px 6px rgba(0,0,0,0.4)';
      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        var text = extractFormattedText(targetEl);
        copyToClipboard(text);
        removeCopyAllButton();
      });
      document.body.appendChild(btn);
      copyAllTimer = setTimeout(removeCopyAllButton, 5000);
    }

    document.addEventListener('dblclick', function (e) {
      var container = findMainTextContainer(e.target) ||
        (e.target.closest ? e.target.closest('div[role="article"]') : null);
      if (container) showCopyAllButton(container);
    });

    var SEL_BTN_ID = 'nobook-copy-selection-btn';
    var selTimer = null;

    function removeSelectionButton() {
      var el = document.getElementById(SEL_BTN_ID);
      if (el) el.parentNode.removeChild(el);
      if (selTimer) { clearTimeout(selTimer); selTimer = null; }
    }

    function showSelectionButton(rect, text) {
      removeSelectionButton();
      var btn = document.createElement('button');
      btn.id = SEL_BTN_ID;
      btn.textContent = 'Copy';
      btn.style.position = 'fixed';
      btn.style.top = Math.max(rect.top - 38, 8) + 'px';
      btn.style.left = Math.max(rect.left, 8) + 'px';
      btn.style.zIndex = '999999';
      btn.style.padding = '6px 12px';
      btn.style.borderRadius = '14px';
      btn.style.border = 'none';
      btn.style.backgroundColor = 'rgba(0,0,0,0.85)';
      btn.style.color = '#fff';
      btn.style.fontSize = '13px';
      btn.addEventListener('mousedown', function (e) { e.preventDefault(); });
      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        copyToClipboard(text);
        removeSelectionButton();
      });
      document.body.appendChild(btn);
      selTimer = setTimeout(removeSelectionButton, 6000);
    }

    document.addEventListener('selectionchange', function () {
      var sel = window.getSelection();
      var text = sel ? sel.toString() : '';
      if (text && text.trim().length > 0 && sel.rangeCount > 0) {
        var rect = sel.getRangeAt(0).getBoundingClientRect();
        if (rect.width > 0 || rect.height > 0) showSelectionButton(rect, text);
      } else {
        removeSelectionButton();
      }
    });

    console.info('[Nobook] Text selection active');
  } catch (err) {
    console.error('[Nobook] Text selection injection failed:', err);
  }
})();
"""

private const val CONTRAST_GUARD_SCRIPT = """
(function () {
  try {
    if (window.__nobookContrastGuardActive) return;
    window.__nobookContrastGuardActive = true;

    function luminance(rgb) {
      var m = rgb.match(/\d+/g);
      if (!m || m.length < 3) return null;
      var r = m[0] / 255, g = m[1] / 255, b = m[2] / 255;
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    function getEffectiveBg(el) {
      var node = el;
      var depth = 0;
      while (node && depth < 8) {
        var bg = window.getComputedStyle(node).backgroundColor;
        if (bg && bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent') return bg;
        node = node.parentElement;
        depth++;
      }
      return null;
    }

    function fixLowContrast(root) {
      var nodes = root.querySelectorAll('textarea, input, [contenteditable], div, span');
      nodes.forEach(function (el) {
        try {
          var cs = window.getComputedStyle(el);
          var color = cs.color;
          var lc = luminance(color);
          if (lc === null) return;

          var isEditable = el.tagName === 'TEXTAREA' || el.tagName === 'INPUT' || el.hasAttribute('contenteditable');
          var bg = isEditable ? getEffectiveBg(el) : cs.backgroundColor;
          if (!bg || bg === 'rgba(0, 0, 0, 0)') return;

          var lb = luminance(bg);
          if (lb === null) return;

          if (Math.abs(lb - lc) < 0.15 && lb < 0.3) {
            el.style.setProperty('color', '#ffffff', 'important');
            el.style.setProperty('caret-color', '#ffffff', 'important');
          }
        } catch (e) { /* ignore per-node errors */ }
      });
    }

    var run = function () { fixLowContrast(document.body); };
    run();
    var observer = new MutationObserver(function () { run(); });
    observer.observe(document.body, { childList: true, subtree: true });

    window.__nobookFixContrast = run;

    console.info('[Nobook] Contrast guard active');
  } catch (err) {
    console.error('[Nobook] Contrast guard injection failed:', err);
  }
})();
"""

private const val UX_EXTRAS_SCRIPT = """
(function () {
  try {
    if (window.__nobookUxExtrasActive) return;
    window.__nobookUxExtrasActive = true;

    var addVideoControls = function () {
      document.querySelectorAll('video').forEach(function (v) {
        if (!v.hasAttribute('controls')) {
          v.setAttribute('controls', 'controls');
          v.setAttribute('playsinline', '');
          v.controls = true;
        }
      });
    };

    var MAGNIFIER_ID = 'nobook-image-magnifier-overlay';
    var closeMagnifier = function () {
      var el = document.getElementById(MAGNIFIER_ID);
      if (el) el.remove();
    };
    var openMagnifier = function (src) {
      closeMagnifier();
      var overlay = document.createElement('div');
      overlay.id = MAGNIFIER_ID;
      overlay.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.9);z-index:999998;' +
        'display:flex;align-items:center;justify-content:center;';
      overlay.addEventListener('click', closeMagnifier);
      var img = document.createElement('img');
      img.src = src;
      img.style.cssText = 'max-width:95%;max-height:95%;object-fit:contain;';
      img.addEventListener('click', function (e) { e.stopPropagation(); });
      overlay.appendChild(img);
      document.body.appendChild(overlay);
    };
    var bindImageMagnifier = function () {
      document.querySelectorAll('img[src*="fbcdn"]').forEach(function (img) {
        if (img.dataset.nobookMagnifierBound) return;
        img.dataset.nobookMagnifierBound = '1';
        img.addEventListener('dblclick', function (e) {
          e.preventDefault();
          openMagnifier(img.currentSrc || img.src);
        });
      });
    };

    var runAll = function () {
      addVideoControls();
      bindImageMagnifier();
    };

    runAll();
    var observer = new MutationObserver(function () { runAll(); });
    observer.observe(document.body, { childList: true, subtree: true });

    console.info('[Nobook] UX extras active');
  } catch (err) {
    console.error('[Nobook] UX extras injection failed:', err);
  }
})();
"""

private const val SPONSORED_VI_SCRIPT = """
(function () {
  try {
    if (window.__nobookSponsoredViActive) return;
    window.__nobookSponsoredViActive = true;

    var VI_SPONSORED_KEYWORDS = [
      '\u0111\u01b0\u1ee3c t\u00e0i tr\u1ee3',
      'duoc tai tro',
      'noi dung duoc tai tro',
      'n\u1ed9i dung \u0111\u01b0\u1ee3c t\u00e0i tr\u1ee3'
    ];

    var normalize = function (text) {
      return (text || '').toLowerCase();
    };

    var isSponsoredLabel = function (text) {
      var norm = normalize(text);
      return VI_SPONSORED_KEYWORDS.some(function (kw) { return norm.indexOf(kw) !== -1; });
    };

    var hideSponsoredPosts = function () {
      var candidates = document.querySelectorAll(
        'span, a[role="link"], div[aria-label]'
      );
      candidates.forEach(function (el) {
        var label = el.getAttribute ? (el.getAttribute('aria-label') || '') : '';
        var text = el.textContent || '';
        if (isSponsoredLabel(label) || isSponsoredLabel(text)) {
          var post = el.closest('div[role="article"]') ||
                     el.closest('div[data-pagelet^="FeedUnit"]');
          if (post) {
            post.style.display = 'none';
          }
        }
      });
    };

    hideSponsoredPosts();
    var observer = new MutationObserver(function () { hideSponsoredPosts(); });
    observer.observe(document.body, { childList: true, subtree: true });

    console.info('[Nobook] Vietnamese sponsored-post filter active');
  } catch (err) {
    console.error('[Nobook] Vietnamese sponsored filter injection failed:', err);
  }
})();
"""

private const val TOPIC_KEYWORD_FILTER_SCRIPT = """
(function () {
  try {
    if (window.__nobookTopicFilterActive) return;
    window.__nobookTopicFilterActive = true;

    var KEYWORDS = [];

    if (KEYWORDS.length === 0) {
      console.info('[Nobook] Topic keyword filter loaded (no keywords configured)');
      return;
    }

    var normalize = function (text) {
      return (text || '').toLowerCase();
    };

    var matchesKeyword = function (text) {
      var norm = normalize(text);
      return KEYWORDS.some(function (kw) { return norm.indexOf(kw.toLowerCase()) !== -1; });
    };

    var filterFeed = function () {
      document.querySelectorAll('div[role="article"]').forEach(function (post) {
        if (post.dataset.nobookTopicChecked) return;
        var text = post.innerText || '';
        if (matchesKeyword(text)) {
          post.style.display = 'none';
        }
        post.dataset.nobookTopicChecked = '1';
      });
    };

    filterFeed();
    var observer = new MutationObserver(function () { filterFeed(); });
    observer.observe(document.body, { childList: true, subtree: true });

    console.info('[Nobook] Topic keyword filter active (' + KEYWORDS.length + ' keywords)');
  } catch (err) {
    console.error('[Nobook] Topic keyword filter injection failed:', err);
  }
})();
"""

private const val PERFORMANCE_OPTIMIZATION_SCRIPT = """
(function () {
  try {
    if (window.__nobookPerformanceOptActive) return;
    window.__nobookPerformanceOptActive = true;

    var CULL_CSS = `
      div[role="article"], div[data-pagelet^="FeedUnit"], [data-pagelet*="FeedUnit"] {
        content-visibility: auto;
        contain-intrinsic-size: auto 1000px;
      }
      * { scroll-behavior: auto !important; }
    `;
    var style = document.createElement('style');
    style.setAttribute('data-nobook-perf', '1');
    style.textContent = CULL_CSS;
    document.head.appendChild(style);

    var observedVideos = new WeakSet();

    var handleIntersections = function (entries) {
      entries.forEach(function (entry) {
        var video = entry.target;
        if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
          if (video.hasAttribute('data-nobook-paused')) {
            video.removeAttribute('data-nobook-paused');
            video.preload = 'auto';
            if (video.dataset.nobookWasPlaying === '1') {
              var p = video.play();
              if (p && typeof p.catch === 'function') p.catch(function () {});
              if (window.NobookVideoBridge && window.NobookVideoBridge.onVideoPlaying) { try { window.NobookVideoBridge.onVideoPlaying(); } catch (e) {} }
            }
          }
        } else {
          if (!video.paused) {
            video.dataset.nobookWasPlaying = '1';
            video.pause();
          } else {
            video.dataset.nobookWasPlaying = '0';
          }
          video.removeAttribute('autoplay');
          video.setAttribute('data-nobook-paused', '1');
          video.preload = 'none';
          if (window.NobookVideoBridge && window.NobookVideoBridge.onVideoPaused) { try { window.NobookVideoBridge.onVideoPaused(); } catch (e) {} }
        }
      });
    };

    var io = new IntersectionObserver(handleIntersections, {
      root: null,
      rootMargin: '200px 0px',
      threshold: [0, 0.25, 0.5]
    });

    var observeVideos = function () {
      document.querySelectorAll('video').forEach(function (v) {
        if (observedVideos.has(v)) return;
        observedVideos.add(v);
        io.observe(v);
        v.addEventListener('play', function () {
          if (window.NobookVideoBridge && window.NobookVideoBridge.onVideoPlaying) { try { window.NobookVideoBridge.onVideoPlaying(); } catch (e) {} }
        });
        v.addEventListener('pause', function () {
          if (window.NobookVideoBridge && window.NobookVideoBridge.onVideoPaused) { try { window.NobookVideoBridge.onVideoPaused(); } catch (e) {} }
        });
      });
    };

    observeVideos();
    var mo = new MutationObserver(function () { observeVideos(); });
    mo.observe(document.body, { childList: true, subtree: true });

    window.__nobookLazyLoadVideos = observeVideos;

    console.info('[Nobook] Performance optimization active');
  } catch (err) {
    console.error('[Nobook] Performance optimization injection failed:', err);
  }
})();
"""

private const val MASTER_LOOP_SCRIPT = """
(function () {
  try {
    if (window.__nobookMasterLoopActive) return;
    window.__nobookMasterLoopActive = true;

    var ric = window.requestIdleCallback || function (cb) {
      return setTimeout(function () { cb({ timeRemaining: function () { return 1; }, didTimeout: false }); }, 1);
    };

    function masterNobookLoop() {
      ric(function (deadline) {
        try {
          if (window.__nobookProcessDownloader) window.__nobookProcessDownloader();
          if (window.__nobookFixContrast) window.__nobookFixContrast();
          if (window.__nobookLazyLoadVideos) window.__nobookLazyLoadVideos();
        } catch (e) {
          console.error('[Nobook] Master Loop Error', e);
        }
      }, { timeout: 2000 });
      setTimeout(masterNobookLoop, 1500);
    }

    masterNobookLoop();
    console.info('[Nobook] Master idle loop active');
  } catch (err) {
    console.error('[Nobook] Master loop injection failed:', err);
  }
})();
"""

@Composable
fun NobookWebView(
    url: String,
    settingsVM: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            context.getSharedPreferences("nobook_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("download_folder_uri", uri.toString())
                .apply()
            Toast.makeText(context, "Đã chọn thư mục lưu tải xuống mới", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        DownloadFolderPicker.onPickRequested = { folderPickerLauncher.launch(null) }
        onDispose { DownloadFolderPicker.onPickRequested = null }
    }

    val state = rememberSaveableWebViewState(url)
    val navigator = rememberWebViewNavigator(
        requestInterceptor = ExternalRequestInterceptor { externalUrl ->
            if (isMessengerAppDeepLink(externalUrl)) {
                // Prevent jumping to native app
            } else if (isBlockedSite(externalUrl)) {
                Toast.makeText(
                    context,
                    "Nobook: Đã chặn link này theo danh sách blocklist",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                val cleanUrl = sanitizeTrackingParams(externalUrl)
                if (isMonetizedShortLink(cleanUrl)) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val resolved = runCatching { resolveFinalUrl(cleanUrl) }.getOrDefault(cleanUrl)
                        val finalUrl = sanitizeTrackingParams(resolved)
                        withContext(Dispatchers.Main) {
                            val intent = Intent(Intent.ACTION_VIEW, finalUrl.toUri())
                            runCatching {
                                context.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    resources.getString(R.string.not_supported),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                } else {
                    val intent = Intent(Intent.ACTION_VIEW, cleanUrl.toUri())
                    runCatching {
                        context.startActivity(intent)
                    }.onFailure {
                        Toast.makeText(
                            context,
                            resources.getString(R.string.not_supported),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    )

    LaunchedEffect(navigator) {
        val bundle = state.viewState
        if (bundle == null) {
            navigator.loadUrl(url)
        }
    }

    var exitScroll by remember { mutableStateOf(false) }
    BackHandler {
        if (exitScroll) {
            activity?.finish()
        } else {
            navigator.evaluateJavaScript("backHandlerNB();") {
                val backHandled = it.removeSurrounding("\"")
                when (backHandled) {
                    "false" -> {
                        if (navigator.canGoBack) {
                            navigator.navigateBack()
                        } else {
                            activity?.finish()
                        }
                    }
                    "exit" -> activity?.finish()
                    "scrolling" -> exitScroll = true
                }
            }
        }
    }

    LaunchedEffect(exitScroll) {
        if (exitScroll) {
            delay(800)
            exitScroll = false
        }
    }

    val isDesktop by settingsVM.desktopLayout.collectAsState()
    val isAutoRevert by settingsVM.isRevertDesktop.collectAsState()
    val isAutoDesktop = rememberAutoDesktop()

    LaunchedEffect(Unit) {
        if (isAutoDesktop && !isDesktop) {
            settingsVM.setRevertDesktop(true)
            settingsVM.setDesktopLayout(true)
        }
        else if (!isAutoDesktop && isAutoRevert) {
            settingsVM.setRevertDesktop(false)
            settingsVM.setDesktopLayout(false)
        }
    }

    var isLoading by rememberSaveable { mutableStateOf(true) }
    val isError = state.errorsForCurrentRequest.lastOrNull()?.isFromMainFrame == true

    val viewModel: MainViewModel = viewModel {
        MainViewModel(
            resources = resources,
            settings = settingsVM
        )
    }

    val themeColor by viewModel.themeColor
    var isImmersiveMode by rememberSaveable { mutableStateOf(settingsVM.immersiveMode.value) }

    fun setWindow(immersive: Boolean) {
        val window = activity?.window ?: return
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)

        if (immersive) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            val isLight = ColorUtils.calculateLuminance(themeColor.toArgb()) > 0.5
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.isAppearanceLightStatusBars = isLight
            windowInsetsController.isAppearanceLightNavigationBars = isLight
        }
        isImmersiveMode = immersive
    }

    LaunchedEffect(isImmersiveMode, themeColor.value) {
        setWindow(isImmersiveMode)
    }

    val userScripts by viewModel.scripts
    val loadingState = state.loadingState

    LaunchedEffect(loadingState, userScripts) {
        if (loadingState is LoadingState.Finished) {
            userScripts?.let { scripts ->
                navigator.evaluateJavaScript(scripts) {
                    isLoading = false
                }
            }
            navigator.evaluateJavaScript(ANTI_RELOAD_SCRIPT) {}
            navigator.evaluateJavaScript(CALL_INTENT_DETECTOR_SCRIPT) {}
            navigator.evaluateJavaScript(STORY_REEL_DOWNLOADER_SCRIPT) {}
            navigator.evaluateJavaScript(MESSENGER_GUARD_SCRIPT) {}
            navigator.evaluateJavaScript(LINK_CLEANER_SCRIPT) {}
            navigator.evaluateJavaScript(TEXT_SELECTION_SCRIPT) {}
            navigator.evaluateJavaScript(CONTRAST_GUARD_SCRIPT) {}
            navigator.evaluateJavaScript(UX_EXTRAS_SCRIPT) {}
            navigator.evaluateJavaScript(SPONSORED_VI_SCRIPT) {}
            navigator.evaluateJavaScript(TOPIC_KEYWORD_FILTER_SCRIPT) {}
            navigator.evaluateJavaScript(PERFORMANCE_OPTIMIZATION_SCRIPT) {}
            navigator.evaluateJavaScript(MASTER_LOOP_SCRIPT) {}
            navigator.evaluateJavaScript(NETWORK_SANITIZER_AND_PRIVACY_SCRIPT) {}
        }
    }

    if (isError && isLoading) {
        NetworkErrorDialog { activity?.finish() }
        return
    }

    var settingsToggle by rememberSaveable { mutableStateOf(false) }
    if (settingsToggle) {
        setWindow(false)
        SettingsDialog(
            themeColor = themeColor,
            onDismiss = {
                setWindow(settingsVM.immersiveMode.value)
                settingsToggle = false
            },
            onReload = {
                isLoading = true
                viewModel.setThemeColor(Color.Transparent)
                setWindow(settingsVM.immersiveMode.value)
                viewModel.refresh(
                    resources = resources,
                    settings = settingsVM
                )
                navigator.reload()
            }
        )
    }

    if (isLoading) {
        SplashLoading(
            if (loadingState is LoadingState.Loading) {
                loadingState.progress
            } else {
                0.8F
            }
        )
    }

    var messengerDesktopUaApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.lastLoadedUrl, isDesktop) {
        val currentUrl = state.lastLoadedUrl ?: return@LaunchedEffect
        if (isDesktop) {
            if (messengerDesktopUaApplied) messengerDesktopUaApplied = false
            return@LaunchedEffect
        }
        val onMessengerPath = isMessengerWebPath(currentUrl)
        if (onMessengerPath && !messengerDesktopUaApplied) {
            messengerDesktopUaApplied = true
            
            // Custom UA Logic Integration: Get from SharedPreferences, fallback to DESKTOP_USER_AGENT
            val prefs = context.getSharedPreferences("nobook_prefs", android.content.Context.MODE_PRIVATE)
            val customUa = prefs.getString("custom_user_agent", "")
            val activeUa = if (!customUa.isNullOrEmpty()) customUa else DESKTOP_USER_AGENT
            
            state.nativeWebView.settings.userAgentString = activeUa
            navigator.reload()
        } else if (!onMessengerPath && messengerDesktopUaApplied) {
            messengerDesktopUaApplied = false
            state.nativeWebView.settings.userAgentString = ""
        }
    }

    LaunchedEffect(isDesktop) {
        val userAgent = if (isDesktop) {
            val prefs = context.getSharedPreferences("nobook_prefs", android.content.Context.MODE_PRIVATE)
            val customUa = prefs.getString("custom_user_agent", "")
            if (!customUa.isNullOrEmpty()) customUa else DESKTOP_USER_AGENT
        } else ""
        state.nativeWebView.settings.userAgentString = userAgent
    }

    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    runCatching {
                        state.nativeWebView.onPause()
                        state.nativeWebView.pauseTimers()
                        @Suppress("DEPRECATION")
                        state.nativeWebView.settings.setRenderPriority(WebSettings.RenderPriority.LOW)
                        state.nativeWebView.setLayerType(View.LAYER_TYPE_NONE, null)
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    runCatching {
                        state.nativeWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        state.nativeWebView.onResume()
                        state.nativeWebView.resumeTimers()
                        @Suppress("DEPRECATION")
                        state.nativeWebView.settings.setRenderPriority(WebSettings.RenderPriority.HIGH)
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    if (activity?.isFinishing == true) {
                        runCatching {
                            state.nativeWebView.stopLoading()
                            state.nativeWebView.loadUrl("about:blank")
                            state.nativeWebView.clearHistory()
                            state.nativeWebView.removeAllViews()
                            state.nativeWebView.destroy()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(state) {
        VideoPlaybackBridge.onPlaybackChanged = { isPlaying ->
            runCatching {
                state.nativeWebView.setLayerType(
                    if (isPlaying) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE,
                    null
                )
            }
        }
        onDispose { VideoPlaybackBridge.onPlaybackChanged = null }
    }

    var isUserCalling by remember { mutableStateOf(false) }
    var isUploadIntent by remember { mutableStateOf(false) }

    val barsInsets = WindowInsets.systemBars.asPaddingValues()
    val imeHeight = rememberImeHeight()

    WebView(
        modifier = Modifier
            .fillMaxSize()
            .background(themeColor)
            .then(
                if (isImmersiveMode) {
                    Modifier.padding(bottom = imeHeight)
                } else {
                    Modifier.padding(
                        top = barsInsets.calculateTopPadding(),
                        bottom = maxOf(barsInsets.calculateBottomPadding(), imeHeight)
                    )
                }
            ),
        state = state,
        navigator = navigator,
        platformWebViewParams = fileChooserWebViewParams(),
        captureBackPresses = false,
        onCreated = { webView ->

            android.webkit.WebView.setWebContentsDebuggingEnabled(true)

            webView.webChromeClient = createSecureWebChromeClient(
                getCallState = { isUserCalling },
                getUploadState = { isUploadIntent },
                resetUploadState = { isUploadIntent = false }
            )

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            cookieManager.flush()

            runCatching {
                state.webSettings.apply {
                    isJavaScriptEnabled = true
                    androidWebSettings.apply {
                        domStorageEnabled = true
                        hideDefaultVideoPoster = true
                        mediaPlaybackRequiresUserGesture = false
                    }
                }
            }

            webView.apply {
                addJavascriptInterface(
                    NobookSettings { settingsToggle = true },
                    "SettingsBridge"
                )
                addJavascriptInterface(
                    ThemeChange { viewModel.setThemeColor(Color(it)) },
                    "ThemeBridge"
                )
                addJavascriptInterface(
                    DownloadBridge(context),
                    "DownloadBridge"
                )
                addJavascriptInterface(
                    DownloadFolderBridge(context),
                    "DownloadFolderBridge"
                )
                addJavascriptInterface(
                    ClipboardBridge(context),
                    "ClipboardBridge"
                )
                addJavascriptInterface(
                    VideoPlaybackBridge,
                    "NobookVideoBridge"
                )
                addJavascriptInterface(
                    CallStateBridge { isCalling -> isUserCalling = isCalling },
                    "CallStateBridge"
                )
                addJavascriptInterface(
                    UploadStateBridge { intent -> isUploadIntent = intent },
                    "UploadStateBridge"
                )

                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false

                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                settings.cacheMode = WebSettings.LOAD_DEFAULT
                @Suppress("DEPRECATION")
                runCatching {
                    settings.setRenderPriority(WebSettings.RenderPriority.HIGH)
                }

                val isWeakOrMetered = runCatching {
                    val connectivityManager = context.getSystemService(
                        android.content.Context.CONNECTIVITY_SERVICE
                    ) as? android.net.ConnectivityManager
                    val activeNetwork = connectivityManager?.activeNetwork
                    val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
                    capabilities == null ||
                        (!capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                            !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                }.getOrDefault(true)
                
                runCatching {
                    settings.loadsImagesAutomatically = !isWeakOrMetered
                }
            }
        }
    )
}
