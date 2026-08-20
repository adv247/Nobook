package com.ycngmn.nobook.ui.screens

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
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
import com.ycngmn.nobook.ui.components.SplashLoading
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

// =========================================================================================
// 1. URL SANITIZATION & REDIRECT RESOLVER (AFFILIATE, TRACKING, YOUTUBE & SHORTLINKS)
// =========================================================================================

private val AFFILIATE_PARAM_PREFIXES = listOf(
    "aff_", "utm_", "af_", "deep_link_", "track_", "spm_", "scm_", "ad_", "algo_", "si"
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
    "extra_params", "traffic_source", "share_relation_params", "aff_trace_key", "exparams",
    "feature", "si", "app", "emb", "kw", "target_url"
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
    "maps.app.goo.gl", "zalo.me", "chat.zalo.me", "fb.me", "fb.watch", "youtu.be"
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
                    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
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

// =========================================================================================
// 2. THREAD-SAFE NATIVE BRIDGES (AI NATIVE PROXY, BOOKMARKS, FILTERS, TOP SITES, VIDEO)
// =========================================================================================

/**
 * Thread-safe Video Playback bridge to prevent CalledFromWrongThreadException
 */
private object VideoPlaybackBridge {
    @Volatile
    var onPlaybackChanged: ((Boolean) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onVideoPlaying() {
        mainHandler.post {
            runCatching { onPlaybackChanged?.invoke(true) }
        }
    }

    @JavascriptInterface
    fun onVideoPaused() {
        mainHandler.post {
            runCatching { onPlaybackChanged?.invoke(false) }
        }
    }
}

/**
 * Zero-Trust Media Access bridge
 */
private class CallStateBridge(private val onCallStateChanged: (Boolean) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun notifyCallIntent(isCalling: Boolean) {
        mainHandler.post {
            runCatching { onCallStateChanged(isCalling) }
        }
    }
}

/**
 * Intent bridge for File Chooser
 */
private class UploadStateBridge(private val onUploadIntentChanged: (Boolean) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun notifyUploadIntent() {
        mainHandler.post {
            runCatching { onUploadIntentChanged(true) }
        }
    }
}

/**
 * NATIVE AI PROXY BRIDGE
 * Bypasses browser CORS & CSP constraints on Facebook by executing HTTP requests via native Android IO thread.
 * Fixes "Failed to fetch" on Gemini, OpenAI, DeepSeek permanently!
 */
private class NativeAiProxyBridge(
    private val context: Context,
    private val evaluateJsOnWebView: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun executeAiRequest(requestId: String, model: String, apiKey: String, promptJson: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val responseText = when (model) {
                    "gemini" -> callGeminiNative(apiKey, promptJson)
                    "deepseek" -> callDeepSeekNative(apiKey, promptJson)
                    else -> callOpenAiNative(apiKey, promptJson)
                }
                
                withContext(Dispatchers.Main) {
                    val escaped = JSONObject.quote(responseText)
                    val jsCallback = "window.__nobookAiNativeCallback && window.__nobookAiNativeCallback('$requestId', true, $escaped);"
                    evaluateJsOnWebView(jsCallback)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errMsg = JSONObject.quote(e.message ?: "Lỗi kết nối AI (Native)")
                    val jsCallback = "window.__nobookAiNativeCallback && window.__nobookAiNativeCallback('$requestId', false, $errMsg);"
                    evaluateJsOnWebView(jsCallback)
                }
            }
        }
    }

    private fun callGeminiNative(key: String, prompt: String): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$key"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        }

        val requestPayload = JSONObject().apply {
            val contentsArr = JSONArray().apply {
                val partsObj = JSONObject().apply {
                    val partsArr = JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                    }
                    put("parts", partsArr)
                }
                put(partsObj)
            }
            put("contents", contentsArr)
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestPayload.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseStr = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        conn.disconnect()

        val json = JSONObject(responseStr)
        if (json.has("error")) {
            throw Exception(json.getJSONObject("error").optString("message", "Gemini Error"))
        }

        val candidates = json.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                return parts.getJSONObject(0).optString("text", "")
            }
        }
        return "Không nhận được phản hồi hợp lệ từ Gemini."
    }

    private fun callOpenAiNative(key: String, prompt: String): String {
        val endpoint = "https://api.openai.com/v1/chat/completions"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Authorization", "Bearer $key")
        }

        val requestPayload = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            val messagesArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            put("messages", messagesArr)
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestPayload.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseStr = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        conn.disconnect()

        val json = JSONObject(responseStr)
        if (json.has("error")) {
            throw Exception(json.getJSONObject("error").optString("message", "OpenAI Error"))
        }

        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            return choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: ""
        }
        return "Không có dữ liệu trả về từ OpenAI."
    }

    private fun callDeepSeekNative(key: String, prompt: String): String {
        val endpoint = "https://api.deepseek.com/chat/completions"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Authorization", "Bearer $key")
        }

        val requestPayload = JSONObject().apply {
            put("model", "deepseek-chat")
            val messagesArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            put("messages", messagesArr)
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestPayload.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseStr = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        conn.disconnect()

        val json = JSONObject(responseStr)
        if (json.has("error")) {
            throw Exception(json.getJSONObject("error").optString("message", "DeepSeek Error"))
        }

        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            return choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: ""
        }
        return "Không có phản hồi từ DeepSeek."
    }
}

/**
 * Native Bookmark & Tab Group Storage Bridge
 */
private class NobookFeaturesBridge(private val context: Context) {
    private val prefs = context.getSharedPreferences("nobook_features_prefs", Context.MODE_PRIVATE)

    @JavascriptInterface
    fun getSavedKeywords(): String {
        return prefs.getString("filter_keywords", "[]") ?: "[]"
    }

    @JavascriptInterface
    fun saveKeywords(jsonArrayStr: String) {
        prefs.edit().putString("filter_keywords", jsonArrayStr).apply()
    }

    @JavascriptInterface
    fun getBookmarks(): String {
        return prefs.getString("bookmarks_list", "[]") ?: "[]"
    }

    @JavascriptInterface
    fun saveBookmarks(jsonArrayStr: String) {
        prefs.edit().putString("bookmarks_list", jsonArrayStr).apply()
    }

    @JavascriptInterface
    fun recordTopSite(title: String, url: String) {
        runCatching {
            val raw = prefs.getString("top_sites_freq", "{}") ?: "{}"
            val obj = JSONObject(raw)
            val count = obj.optInt(url, 0) + 1
            obj.put(url, count)
            prefs.edit().putString("top_sites_freq", obj.toString()).apply()

            // Save title mapping
            val titles = JSONObject(prefs.getString("top_sites_titles", "{}") ?: "{}")
            titles.put(url, title)
            prefs.edit().putString("top_sites_titles", titles.toString()).apply()
        }
    }

    @JavascriptInterface
    fun getTopSites(): String {
        return runCatching {
            val raw = prefs.getString("top_sites_freq", "{}") ?: "{}"
            val titlesRaw = prefs.getString("top_sites_titles", "{}") ?: "{}"
            val obj = JSONObject(raw)
            val titles = JSONObject(titlesRaw)

            val list = mutableListOf<Triple<String, String, Int>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val url = keys.next()
                val freq = obj.getInt(url)
                val t = titles.optString(url, url)
                list.add(Triple(t, url, freq))
            }
            list.sortByDescending { it.third }

            val res = JSONArray()
            list.take(8).forEach {
                val item = JSONObject()
                item.put("title", it.first)
                item.put("url", it.second)
                item.put("visits", it.third)
                res.put(item)
            }
            res.toString()
        }.getOrDefault("[]")
    }

    @JavascriptInterface
    fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

private val TRUSTED_WEBRTC_ORIGINS = setOf(
    "https://www.messenger.com",
    "https://messenger.com",
    "https://www.facebook.com",
    "https://m.facebook.com"
)

private fun createSecureWebChromeClient(
    getCallState: () -> Boolean,
    getUploadState: () -> Boolean,
    resetUploadState: () -> Unit
): WebChromeClient {
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
            filePathCallback: ValueCallback<Array<Uri>>?,
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

// =========================================================================================
// 3. JAVASCRIPT CORE ENGINES (ASSISTIVETOUCH, AI, ACCESSIBILITY CLEANER, FILTER, REELS PLAY)
// =========================================================================================

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

  // 2. DOM Blockers (CSS INJECTION - ZERO LAG)
  var css Core = '' +
    'div[data-testid="mw_top_banner"], ' +
    'div[aria-label*="Get the Messenger app"], div[aria-label*="Sử dụng ứng dụng Messenger"], ' +
    'div[aria-label*="Cài đặt Messenger"], div[aria-label*="Tải ứng dụng Messenger"], ' +
    'div[aria-label*="Open in app"], ' +
    'div[role="dialog"]:has(a[href*="play.google.com/store/apps/details?id=com.facebook.orca"]), ' +
    'a[href*="play.google.com/store/apps/details?id=com.facebook.orca"], ' +
    'a[href*="fb-messenger://"] {' +
    '  display: none !important; opacity: 0 !important; pointer-events: none !important;' +
    '}' +
    '[aria-label="Sponsored"], [data-testid="story-sponsored-label"], [data-ad-comet-preview-id], [data-adunit], [data-sigil="m-feed-voice-subtitle"], div[id^="ad_"] {' +
    '  display: none !important;' +
    '}';

  var styleCore = document.createElement('style');
  styleCore.textContent = cssCore.replace('css Core', 'cssCore');
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
                    console.info('[Nobook] HIDE_COUNTS detected.');
                }
            } catch(e) {}
        }
    });
    return origXhrSend.apply(this, arguments);
  };

  var origFetch = window.fetch;
  window.fetch = async function (input, init) {
    var url = (typeof input === 'string') ? input : (input && input.url) || '';
    
    // BYPASS FETCH CHO DOMAIN AI VÀ LOCALHOST
    if (url.indexOf('googleapis.com') !== -1 || 
        url.indexOf('openai.com') !== -1 || 
        url.indexOf('deepseek.com') !== -1 || 
        url.indexOf('groq.com') !== -1 || 
        url.indexOf('cerebras.ai') !== -1 || 
        url.indexOf('openrouter.ai') !== -1 || 
        url.indexOf('127.0.0.1') !== -1 || 
        url.indexOf('localhost') !== -1) {
        return origFetch.apply(window, arguments);
    }

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
        return await origFetch.call(this, input, init);
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

  // 5. UPLOAD INTENT GATE
  document.addEventListener('click', function(e) {
      var target = e.target.closest ? e.target.closest('input[type="file"], [aria-label*="Photo"], [aria-label*="Video"], [aria-label*="Image"], [aria-label*="Attachment"], [aria-label*="Ảnh/video"], [aria-label*="Thêm ảnh"]') : null;
      if (target && window.UploadStateBridge) {
          window.UploadStateBridge.notifyUploadIntent();
      }
  }, true);

  // 6. RECORD TOP SITES FREQUENCY
  setTimeout(function() {
    try {
      if (window.NobookFeaturesBridge && window.location.href.indexOf('facebook.com') !== -1) {
        var pageTitle = document.title || 'Facebook';
        if (pageTitle.indexOf('Facebook') === -1 && pageTitle.length > 2) {
          window.NobookFeaturesBridge.recordTopSite(pageTitle, window.location.href);
        } else if (window.location.pathname.length > 3) {
          window.NobookFeaturesBridge.recordTopSite(window.location.pathname, window.location.href);
        }
      }
    } catch(e) {}
  }, 3000);

  console.info('[Nobook] Security & Privacy Engine Active.');
})();
"""

private const val ASSISTIVE_TOUCH_AND_AI_SCRIPT = """
(function() {
  if (window.__nobookAssistiveTouchActive) return;
  window.__nobookAssistiveTouchActive = true;

  // Native AI Callback Listener
  window.__nobookAiCallbacks = {};
  window.__nobookAiNativeCallback = function(reqId, success, responseText) {
    if (window.__nobookAiCallbacks[reqId]) {
      window.__nobookAiCallbacks[reqId](success, responseText);
      delete window.__nobookAiCallbacks[reqId];
    }
  };

  // Helper: Text & UID Accessibility Tree Cleaner
  function cleanAccessibilityText(raw) {
    if (!raw) return "";
    return raw
      .replace(/[\u0000-\u001F\u007F-\u009F]/g, "")
      .replace(/\s+/g, " ")
      .replace(/(\n\s*){3,}/g, "\n\n")
      .trim();
  }

  function extractCurrentUID() {
    var m = window.location.href.match(/(?:profile\.php\?id=|\/user\/|facebook\.com\/)([0-9]{5,})/);
    if (m && m[1]) return m[1];
    var cUser = document.cookie.match(/c_user=([0-9]+)/);
    if (cUser && cUser[1]) return cUser[1];
    var profileLinks = document.querySelectorAll('a[href*="profile.php?id="], a[href*="/user/"]');
    for (var i = 0; i < profileLinks.length; i++) {
      var match = profileLinks[i].href.match(/(?:id=|user\/)([0-9]{5,})/);
      if (match && match[1]) return match[1];
    }
    return "";
  }

  // 1. CREATE ASSISTIVETOUCH FLOATING 🤖 BUTTON (Frosted Glass, 20% Idle, Magnetic Snap)
  var trigger = document.createElement('div');
  trigger.id = 'nobook-assistive-touch-btn';
  trigger.innerHTML = '🤖';
  trigger.style.cssText = 
    'position: fixed; top: 65%; left: 12px; width: 48px; height: 48px; border-radius: 50%;' +
    'background: rgba(30, 32, 40, 0.45); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);' +
    'border: 1.5px solid rgba(255, 255, 255, 0.25); box-shadow: 0 8px 32px rgba(0, 0, 0, 0.35);' +
    'display: flex; align-items: center; justify-content: center; font-size: 24px; cursor: move;' +
    'z-index: 999998; opacity: 0.20; transition: opacity 0.3s cubic-bezier(0.25, 1, 0.5, 1); user-select: none; -webkit-user-select: none;';

  var idleTimer = null;
  function resetIdle() {
    trigger.style.opacity = '1.0';
    clearTimeout(idleTimer);
    idleTimer = setTimeout(function() {
      if (!isDragging) trigger.style.opacity = '0.20';
    }, 2800);
  }

  trigger.addEventListener('mouseenter', function() { trigger.style.opacity = '1.0'; });
  trigger.addEventListener('mouseleave', function() { resetIdle(); });

  var isDragging = false;
  var startY, startTop, startX, startLeft;

  function onDragStart(e) {
    isDragging = true;
    resetIdle();
    trigger.style.transition = 'none';
    var touch = e.touches ? e.touches[0] : e;
    startY = touch.clientY;
    startX = touch.clientX;
    startTop = trigger.offsetTop;
    startLeft = trigger.offsetLeft;
  }

  function onDragMove(e) {
    if (!isDragging) return;
    var touch = e.touches ? e.touches[0] : e;
    var newTop = startTop + (touch.clientY - startY);
    var newLeft = startLeft + (touch.clientX - startX);
    
    var maxTop = window.innerHeight - 60;
    var maxLeft = window.innerWidth - 60;
    trigger.style.top = Math.max(10, Math.min(newTop, maxTop)) + 'px';
    trigger.style.left = Math.max(8, Math.min(newLeft, maxLeft)) + 'px';
    trigger.style.right = 'auto';
  }

  function onDragEnd(e) {
    if (!isDragging) return;
    isDragging = false;
    trigger.style.transition = 'left 0.35s cubic-bezier(0.25, 1, 0.5, 1), top 0.35s cubic-bezier(0.25, 1, 0.5, 1), opacity 0.3s ease';
    var rect = trigger.getBoundingClientRect();
    var centerX = rect.left + (rect.width / 2);
    if (centerX < window.innerWidth / 2) {
      trigger.style.left = '12px';
    } else {
      trigger.style.left = (window.innerWidth - rect.width - 12) + 'px';
    }
    resetIdle();
  }

  trigger.addEventListener('mousedown', onDragStart);
  window.addEventListener('mousemove', onDragMove, { passive: true });
  window.addEventListener('mouseup', onDragEnd);

  trigger.addEventListener('touchstart', onDragStart, { passive: true });
  window.addEventListener('touchmove', onDragMove, { passive: true });
  window.addEventListener('touchend', onDragEnd);

  trigger.onclick = function(e) {
    if (Math.abs((trigger.offsetLeft - startLeft)) < 8 && Math.abs((trigger.offsetTop - startTop)) < 8) {
      window.toggleNobookMenu();
    }
  };

  document.body.appendChild(trigger);
  resetIdle();

  // 2. UNIFIED NOBOOK AI & TOOLS MODAL / PANEL
  window.toggleNobookMenu = function() {
    var panel = document.getElementById('nobook-master-panel');
    if (!panel) {
      panel = document.createElement('div');
      panel.id = 'nobook-master-panel';
      panel.style.cssText = 
        'position: fixed; inset: 0; background: rgba(0,0,0,0.65); z-index: 1000000; display: flex; align-items: flex-end; justify-content: center; backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px); transition: opacity 0.25s ease;';

      var sheet = document.createElement('div');
      sheet.id = 'nobook-master-sheet';
      sheet.style.cssText = 
        'width: 100%; max-width: 480px; max-height: 90vh; height: 85vh; background: #1c1e24; color: #fff; border-radius: 20px 20px 0 0; display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 -10px 40px rgba(0,0,0,0.5); font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;';

      sheet.innerHTML = 
        '<div style="padding: 14px 18px; background: #262932; border-bottom: 1px solid rgba(255,255,255,0.1); display: flex; justify-content: space-between; align-items: center;">' +
          '<div style="display: flex; align-items: center; gap: 8px;">' +
            '<span style="font-size: 22px;">🤖</span>' +
            '<strong style="font-size: 16px; letter-spacing: 0.3px;">Nobook Pro AI & Hub</strong>' +
          '</div>' +
          '<span id="nobook-close-panel" style="font-size: 24px; cursor: pointer; color: #8a8d9b; line-height: 1;">&times;</span>' +
        '</div>' +
        '<div style="display: flex; background: #20222a; border-bottom: 1px solid rgba(255,255,255,0.08); font-size: 13px; font-weight: 600;">' +
          '<div class="nb-tab active" data-tab="ai" style="flex: 1; text-align: center; padding: 10px 0; cursor: pointer; color: #4e8cff; border-bottom: 2px solid #4e8cff;">AI Trợ Lý</div>' +
          '<div class="nb-tab" data-tab="uid" style="flex: 1; text-align: center; padding: 10px 0; cursor: pointer; color: #8a8d9b;">UID & Group</div>' +
          '<div class="nb-tab" data-tab="bookmarks" style="flex: 1; text-align: center; padding: 10px 0; cursor: pointer; color: #8a8d9b;">Bookmarks</div>' +
          '<div class="nb-tab" data-tab="topsites" style="flex: 1; text-align: center; padding: 10px 0; cursor: pointer; color: #8a8d9b;">Top Sites</div>' +
          '<div class="nb-tab" data-tab="filters" style="flex: 1; text-align: center; padding: 10px 0; cursor: pointer; color: #8a8d9b;">Bộ Lọc</div>' +
        '</div>' +
        '<div id="nobook-tab-content" style="flex: 1; overflow-y: auto; padding: 14px; background: #16171c;">' +
        '</div>';

      panel.appendChild(sheet);
      document.body.appendChild(panel);

      document.getElementById('nobook-close-panel').onclick = function() {
        panel.style.display = 'none';
      };

      panel.addEventListener('click', function(e) {
        if (e.target === panel) panel.style.display = 'none';
      });

      // Handle Tab Switching
      var tabs = sheet.querySelectorAll('.nb-tab');
      tabs.forEach(function(tab) {
        tab.onclick = function() {
          tabs.forEach(function(t) {
            t.classList.remove('active');
            t.style.color = '#8a8d9b';
            t.style.borderBottom = 'none';
          });
          tab.classList.add('active');
          tab.style.color = '#4e8cff';
          tab.style.borderBottom = '2px solid #4e8cff';
          renderTab(tab.getAttribute('data-tab'));
        };
      });

      renderTab('ai');
    } else {
      panel.style.display = 'flex';
    }
  };

  function renderTab(tabName) {
    var container = document.getElementById('nobook-tab-content');
    if (!container) return;

    if (tabName === 'ai') {
      var savedKey = localStorage.getItem('nobook_ai_key') || '';
      var savedModel = localStorage.getItem('nobook_ai_model') || 'gemini';

      container.innerHTML = 
        '<div style="margin-bottom: 10px; display: flex; gap: 8px;">' +
          '<select id="nb-ai-model" style="flex: 1; background: #262932; color: #fff; border: 1px solid #3c404d; border-radius: 8px; padding: 8px; font-size: 13px;">' +
            '<option value="gemini"' + (savedModel === 'gemini' ? ' selected' : '') + '>Google Gemini 1.5 Flash (Khuyên dùng)</option>' +
            '<option value="gpt"' + (savedModel === 'gpt' ? ' selected' : '') + '>OpenAI GPT-3.5 Turbo</option>' +
            '<option value="deepseek"' + (savedModel === 'deepseek' ? ' selected' : '') + '>DeepSeek AI</option>' +
          '</select>' +
        '</div>' +
        '<div style="margin-bottom: 10px;">' +
          '<input type="password" id="nb-ai-key" value="' + savedKey + '" placeholder="Nhập API Key cá nhân..." style="width: 100%; box-sizing: border-box; background: #262932; color: #fff; border: 1px solid #3c404d; border-radius: 8px; padding: 8px 12px; font-size: 13px;">' +
        '</div>' +
        '<div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 12px;">' +
          '<button id="nb-btn-summarize" style="background: linear-gradient(135deg, #6366f1, #4f46e5); color: #fff; border: none; border-radius: 8px; padding: 10px; font-weight: 600; font-size: 13px; cursor: pointer;">⚡ Tóm tắt bài viết</button>' +
          '<button id="nb-btn-clean-tree" style="background: #374151; color: #fff; border: none; border-radius: 8px; padding: 10px; font-weight: 600; font-size: 13px; cursor: pointer;">🧹 Lọc Text Sạch</button>' +
        '</div>' +
        '<div id="nb-chat-logs" style="height: 230px; overflow-y: auto; background: #0f1015; border-radius: 10px; padding: 10px; font-size: 13px; margin-bottom: 10px; border: 1px solid #232530;">' +
          '<div style="color: #6b7280; text-align: center; margin-top: 80px;">Sẵn sàng trả lời & tóm tắt thông tin...</div>' +
        '</div>' +
        '<div style="display: flex; gap: 6px;">' +
          '<input type="text" id="nb-ai-input" placeholder="Hỏi AI bất kỳ điều gì..." style="flex: 1; background: #262932; color: #fff; border: 1px solid #3c404d; border-radius: 20px; padding: 9px 14px; font-size: 13px; outline: none;">' +
          '<button id="nb-ai-submit" style="background: #2563eb; color: #fff; border: none; border-radius: 20px; padding: 9px 18px; font-weight: bold; cursor: pointer;">Gửi</button>' +
        '</div>';

      document.getElementById('nb-ai-model').onchange = function() {
        localStorage.setItem('nobook_ai_model', this.value);
      };
      document.getElementById('nb-ai-key').onchange = function() {
        localStorage.setItem('nobook_ai_key', this.value.trim());
      };

      document.getElementById('nb-btn-clean-tree').onclick = function() {
        var clean = cleanAccessibilityText(document.body.innerText).slice(0, 3000);
        if (window.ClipboardBridge && window.ClipboardBridge.copyText) {
          window.ClipboardBridge.copyText(clean);
          alert("Đã trích xuất & sao chép Text sạch toàn trang vào Clipboard!");
        } else {
          prompt("Text sạch:", clean);
        }
      };

      document.getElementById('nb-btn-summarize').onclick = function() {
        var clean = "";
        var articles = document.querySelectorAll('div[role="article"]');
        if (articles.length > 0) {
          articles.forEach(function(a, i) {
            if (i < 2) clean += cleanAccessibilityText(a.innerText) + "\n---\n";
          });
        } else {
          clean = cleanAccessibilityText(document.body.innerText).slice(0, 2000);
        }
        document.getElementById('nb-ai-input').value = "Hãy tóm tắt ngắn gọn, làm rõ ý chính và các bình luận quan trọng sau:\n" + clean;
        document.getElementById('nb-ai-submit').click();
      };

      document.getElementById('nb-ai-submit').onclick = function() {
        var input = document.getElementById('nb-ai-input');
        var logs = document.getElementById('nb-chat-logs');
        var key = document.getElementById('nb-ai-key').value.trim();
        var model = document.getElementById('nb-ai-model').value;
        var text = input.value.trim();

        if (!text || !key) {
          alert('Vui lòng nhập đầy đủ API Key và nội dung câu hỏi.');
          return;
        }

        if (logs.innerHTML.indexOf('Sẵn sàng') !== -1) logs.innerHTML = '';

        logs.innerHTML += '<div style="margin-bottom: 8px; text-align: right;"><span style="background: #2563eb; color: #fff; padding: 7px 12px; border-radius: 14px 14px 2px 14px; display: inline-block; max-width: 85%;">' + text.replace(/\n/g, '<br>') + '</span></div>';
        input.value = '';
        logs.scrollTop = logs.scrollHeight;

        var loadId = 'nb-msg-' + Date.now();
        logs.innerHTML += '<div id="' + loadId + '" style="margin-bottom: 8px; text-align: left;"><span style="background: #262932; color: #9ca3af; padding: 7px 12px; border-radius: 14px 14px 14px 2px; display: inline-block;">Đang xử lý qua Native Proxy...</span></div>';
        logs.scrollTop = logs.scrollHeight;

        var reqId = 'req_' + Date.now();
        window.__nobookAiCallbacks[reqId] = function(success, result) {
          var targetEl = document.getElementById(loadId);
          if (!targetEl) return;
          if (success) {
            targetEl.innerHTML = '<span style="background: #1e293b; color: #e2e8f0; padding: 8px 12px; border-radius: 14px 14px 14px 2px; display: inline-block; max-width: 90%; line-height: 1.4;">' + result.replace(/\n/g, '<br>') + '</span>';
          } else {
            targetEl.innerHTML = '<span style="background: #7f1d1d; color: #fca5a5; padding: 8px 12px; border-radius: 14px 14px 14px 2px; display: inline-block;">Lỗi: ' + result + '</span>';
          }
          logs.scrollTop = logs.scrollHeight;
        };

        if (window.NativeAiProxyBridge && window.NativeAiProxyBridge.executeAiRequest) {
          window.NativeAiProxyBridge.executeAiRequest(reqId, model, key, text);
        } else {
          // Fallback fetch
          fetch('https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=' + key, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ contents: [{ parts: [{ text: text }] }] })
          }).then(r => r.json()).then(j => {
            window.__nobookAiNativeCallback(reqId, true, j.candidates[0].content.parts[0].text);
          }).catch(e => {
            window.__nobookAiNativeCallback(reqId, false, e.message);
          });
        }
      };
    } else if (tabName === 'uid') {
      var currentUid = extractCurrentUID();
      container.innerHTML = 
        '<div style="background: #262932; padding: 14px; border-radius: 12px; margin-bottom: 12px;">' +
          '<div style="font-size: 12px; color: #9ca3af; margin-bottom: 4px;">UID Trang / Cá Nhân Hiện Tại:</div>' +
          '<div style="display: flex; gap: 8px; align-items: center;">' +
            '<input type="text" id="nb-current-uid" value="' + (currentUid || "Chưa phát hiện") + '" style="flex: 1; background: #16171c; color: #38bdf8; font-weight: bold; border: 1px solid #3c404d; border-radius: 6px; padding: 8px; font-size: 14px;">' +
            '<button id="nb-copy-uid" style="background: #0ea5e9; color: #fff; border: none; border-radius: 6px; padding: 8px 14px; font-weight: bold; cursor: pointer;">Copy</button>' +
          '</div>' +
        '</div>' +
        '<div style="background: #262932; padding: 14px; border-radius: 12px;">' +
          '<div style="font-size: 14px; font-weight: bold; margin-bottom: 8px;">Tìm Bài Viết Theo UID Trong Nhóm / Page</div>' +
          '<input type="text" id="nb-search-uid" placeholder="Nhập UID cần tìm..." value="' + currentUid + '" style="width: 100%; box-sizing: border-box; background: #16171c; color: #fff; border: 1px solid #3c404d; border-radius: 6px; padding: 8px; margin-bottom: 8px; font-size: 13px;">' +
          '<input type="text" id="nb-group-id" placeholder="Nhập ID Nhóm / Đường dẫn Group (Tùy chọn)..." style="width: 100%; box-sizing: border-box; background: #16171c; color: #fff; border: 1px solid #3c404d; border-radius: 6px; padding: 8px; margin-bottom: 10px; font-size: 13px;">' +
          '<div style="display: flex; gap: 8px;">' +
            '<button id="nb-find-in-group" style="flex: 1; background: #10b981; color: #fff; border: none; border-radius: 6px; padding: 10px; font-weight: bold; cursor: pointer;">Tìm Trong Group</button>' +
            '<button id="nb-find-global" style="flex: 1; background: #6366f1; color: #fff; border: none; border-radius: 6px; padding: 10px; font-weight: bold; cursor: pointer;">Tìm Toàn FB</button>' +
          '</div>' +
        '</div>';

      document.getElementById('nb-copy-uid').onclick = function() {
        var uid = document.getElementById('nb-current-uid').value;
        if (window.ClipboardBridge && window.ClipboardBridge.copyText) {
          window.ClipboardBridge.copyText(uid);
          alert("Đã copy UID: " + uid);
        }
      };

      document.getElementById('nb-find-in-group').onclick = function() {
        var uid = document.getElementById('nb-search-uid').value.trim();
        var grp = document.getElementById('nb-group-id').value.trim();
        if (!uid) { alert("Vui lòng nhập UID!"); return; }
        var targetUrl = grp ? ("https://m.facebook.com/groups/" + grp.replace(/[^0-9a-zA-Z._-]/g, '') + "/search/?q=" + encodeURIComponent(uid)) : ("https://m.facebook.com/search/posts/?q=" + encodeURIComponent(uid));
        window.location.href = targetUrl;
        document.getElementById('nobook-master-panel').style.display = 'none';
      };

      document.getElementById('nb-find-global').onclick = function() {
        var uid = document.getElementById('nb-search-uid').value.trim();
        if (!uid) { alert("Vui lòng nhập UID!"); return; }
        window.location.href = "https://m.facebook.com/search/posts/?q=" + encodeURIComponent(uid);
        document.getElementById('nobook-master-panel').style.display = 'none';
      };
    } else if (tabName === 'bookmarks') {
      var rawBm = window.NobookFeaturesBridge ? window.NobookFeaturesBridge.getBookmarks() : '[]';
      var bmList = JSON.parse(rawBm || '[]');

      var html = 
        '<div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px;">' +
          '<button id="nb-add-bookmark" style="background: #2563eb; color: #fff; border: none; border-radius: 6px; padding: 6px 12px; font-size: 12px; font-weight: bold; cursor: pointer;">+ Lưu Trang Này</button>' +
          '<button id="nb-clean-duplicates" style="background: #dc2626; color: #fff; border: none; border-radius: 6px; padding: 6px 12px; font-size: 12px; font-weight: bold; cursor: pointer;">Dọn Trùng Lặp & Hỏng</button>' +
        '</div>' +
        '<div id="nb-bm-list" style="display: flex; flex-direction: column; gap: 8px;">';

      if (bmList.length === 0) {
        html += '<div style="color: #6b7280; text-align: center; padding: 30px 0;">Chưa có Bookmark nào được lưu.</div>';
      } else {
        bmList.forEach(function(b, idx) {
          html += 
            '<div style="background: #262932; padding: 10px 12px; border-radius: 8px; display: flex; justify-content: space-between; align-items: center;">' +
              '<div style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-right: 8px; cursor: pointer;" onclick="window.location.href=\'' + b.url + '\'">' +
                '<div style="font-weight: 600; font-size: 13px; color: #e2e8f0;">' + b.title + '</div>' +
                '<div style="font-size: 11px; color: #94a3b8;">' + b.url + '</div>' +
              '</div>' +
              '<span style="color: #ef4444; font-size: 16px; cursor: pointer;" onclick="window.deleteBookmark(' + idx + ')">&#128465;</span>' +
            '</div>';
        });
      }
      html += '</div>';
      container.innerHTML = html;

      window.deleteBookmark = function(idx) {
        bmList.splice(idx, 1);
        if (window.NobookFeaturesBridge) window.NobookFeaturesBridge.saveBookmarks(JSON.stringify(bmList));
        renderTab('bookmarks');
      };

      document.getElementById('nb-add-bookmark').onclick = function() {
        var t = document.title || window.location.pathname;
        var u = window.location.href;
        bmList.unshift({ title: t, url: u, time: Date.now() });
        if (window.NobookFeaturesBridge) window.NobookFeaturesBridge.saveBookmarks(JSON.stringify(bmList));
        renderTab('bookmarks');
      };

      document.getElementById('nb-clean-duplicates').onclick = function() {
        var seen = {};
        var unique = [];
        bmList.forEach(function(item) {
          if (!seen[item.url] && item.url.indexOf('facebook.com') !== -1) {
            seen[item.url] = true;
            unique.push(item);
          }
        });
        var removed = bmList.length - unique.length;
        bmList = unique;
        if (window.NobookFeaturesBridge) window.NobookFeaturesBridge.saveBookmarks(JSON.stringify(bmList));
        alert('Đã dọn dẹp thành công ' + removed + ' bookmark trùng lặp / link hỏng!');
        renderTab('bookmarks');
      };
    } else if (tabName === 'topsites') {
      var rawTop = window.NobookFeaturesBridge ? window.NobookFeaturesBridge.getTopSites() : '[]';
      var topSites = JSON.parse(rawTop || '[]');

      var html = '<div style="font-size: 13px; color: #9ca3af; margin-bottom: 10px;">Các Trang, Nhóm & Kênh truy cập nhiều nhất:</div><div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px;">';
      if (topSites.length === 0) {
        html += '<div style="color: #6b7280; grid-column: span 2; text-align: center; padding: 30px 0;">Đang thu thập dữ liệu truy cập thường xuyên...</div>';
      } else {
        topSites.forEach(function(site) {
          html += 
            '<div style="background: #262932; padding: 10px; border-radius: 8px; cursor: pointer; border: 1px solid rgba(255,255,255,0.05);" onclick="window.location.href=\'' + site.url + '\'">' +
              '<div style="font-size: 12px; font-weight: bold; color: #38bdf8; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">' + site.title + '</div>' +
              '<div style="font-size: 10px; color: #9ca3af; margin-top: 4px;">' + site.visits + ' lượt xem</div>' +
            '</div>';
        });
      }
      html += '</div>';
      container.innerHTML = html;
    } else if (tabName === 'filters') {
      var rawKw = window.NobookFeaturesBridge ? window.NobookFeaturesBridge.getSavedKeywords() : '[]';
      var kwList = JSON.parse(rawKw || '[]');

      var html = 
        '<div style="font-size: 13px; margin-bottom: 10px; color: #94a3b8;">Ẩn bài viết chứa từ khóa (Bộ lọc FBPurity style):</div>' +
        '<div style="display: flex; gap: 6px; margin-bottom: 12px;">' +
          '<input type="text" id="nb-new-kw" placeholder="Nhập từ khóa cần ẩn..." style="flex: 1; background: #262932; color: #fff; border: 1px solid #3c404d; border-radius: 6px; padding: 8px; font-size: 13px;">' +
          '<button id="nb-add-kw-btn" style="background: #10b981; color: #fff; border: none; border-radius: 6px; padding: 8px 14px; font-weight: bold; cursor: pointer;">Thêm</button>' +
        '</div>' +
        '<div id="nb-kw-tags" style="display: flex; flex-wrap: wrap; gap: 6px;">';

      kwList.forEach(function(k, idx) {
        html += '<span style="background: #374151; color: #f3f4f6; padding: 4px 10px; border-radius: 12px; font-size: 12px; display: inline-flex; align-items: center; gap: 6px;">' + k + ' <b style="cursor: pointer; color: #f87171;" onclick="window.removeKw(' + idx + ')">&times;</b></span>';
      });
      html += '</div>';
      container.innerHTML = html;

      window.removeKw = function(idx) {
        kwList.splice(idx, 1);
        if (window.NobookFeaturesBridge) window.NobookFeaturesBridge.saveKeywords(JSON.stringify(kwList));
        renderTab('filters');
      };

      document.getElementById('nb-add-kw-btn').onclick = function() {
        var input = document.getElementById('nb-new-kw');
        var val = input.value.trim();
        if (val && kwList.indexOf(val) === -1) {
          kwList.push(val);
          if (window.NobookFeaturesBridge) window.NobookFeaturesBridge.saveKeywords(JSON.stringify(kwList));
          renderTab('filters');
        }
      };
    }
  }

  console.info('[Nobook] AssistiveTouch & Pro Hub Initialized.');
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
      var isMsgIcon = el && el.closest ? el.closest('a[href*="/messages/"], a[aria-label="Messenger"], svg[aria-label="Messenger"], div[data-sigil="messages"]') : null;
      if (isMsgIcon) {
        e.preventDefault();
        e.stopPropagation();
        if (typeof e.stopImmediatePropagation === "function") e.stopImmediatePropagation();
        console.info("[Nobook] Chuyển hướng Messenger Web");
        window.location.href = "https://www.facebook.com/messages/";
        return;
      }

      var link = el && el.closest ? el.closest("a[href]") : null;
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

    console.info("[Nobook] Messenger deep-link guard active");
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
      'extra_params', 'traffic_source', 'share_relation_params', 'aff_trace_key', 'exparams',
      'feature', 'si', 'app', 'emb'
    ];
    var AFF_PREFIXES = ['utm_', 'aff_', 'af_', 'deep_link_', 'track_', 'spm_', 'scm_', 'ad_', 'algo_', 'si_'];
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
        if (window.ClipboardBridge && window.ClipboardBridge.copyText) {
          window.ClipboardBridge.copyText(text);
          return true;
        }
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
      document.querySelectorAll('video:not([controls])').forEach(function (v) {
        v.setAttribute('controls', 'controls');
        v.setAttribute('playsinline', '');
        v.controls = true;
        v.style.pointerEvents = 'auto';
        v.style.zIndex = '10';
        v.style.position = 'relative';
      });
    };

    var runAll = function () {
      addVideoControls();
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

    function getActiveKeywords() {
      try {
        if (window.NobookFeaturesBridge && window.NobookFeaturesBridge.getSavedKeywords) {
          return JSON.parse(window.NobookFeaturesBridge.getSavedKeywords() || '[]');
        }
      } catch (e) {}
      return [];
    }

    var normalize = function (text) {
      return (text || '').toLowerCase();
    };

    var matchesKeyword = function (text, keywords) {
      if (!keywords || keywords.length === 0) return false;
      var norm = normalize(text);
      return keywords.some(function (kw) { 
        return kw && norm.indexOf(kw.toLowerCase()) !== -1; 
      });
    };

    var filterFeed = function () {
      var keywords = getActiveKeywords();
      if (!keywords || keywords.length === 0) return;

      document.querySelectorAll('div[role="article"], div[data-pagelet^="FeedUnit"]').forEach(function (post) {
        var text = post.innerText || '';
        if (matchesKeyword(text, keywords)) {
          post.style.display = 'none';
          post.setAttribute('data-nobook-keyword-filtered', '1');
        }
      });
    };

    filterFeed();
    var observer = new MutationObserver(function () { filterFeed(); });
    observer.observe(document.body, { childList: true, subtree: true });

    console.info('[Nobook] Dynamic Topic keyword filter active');
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

    var observedVideos = new WeakSet();

    function isViewingComments() {
      var inReelsOrWatch = window.location.pathname.indexOf('/watch') !== -1 || 
                           window.location.pathname.indexOf('/reel') !== -1 || 
                           window.location.pathname.indexOf('/videos') !== -1;
      var hasCommentModal = !!document.querySelector('div[role="dialog"], [data-sigil*="comment"], div[aria-label*="Bình luận" i], div[aria-label*="Comments" i]');
      return inReelsOrWatch || hasCommentModal;
    }

    var handleIntersections = function (entries) {
      entries.forEach(function (entry) {
        var video = entry.target;
        var viewingComments = isViewingComments();

        // NẾU ĐANG TRONG TAB REELS / WATCH HOẶC MỞ COMMENT DIALOG -> TIẾP TỤC PHÁT KHÔNG BỊ DỪNG
        if (viewingComments) {
          if (video.paused && video.dataset.nobookUserPaused !== '1') {
            var p = video.play();
            if (p && typeof p.catch === 'function') p.catch(function () {});
          }
          return;
        }

        // LƯỚT BÀI BÌNH THƯỜNG TRÊN FEED: TUÂN THỦ DỪNG KHI NGOÀI VIEWPORT ĐỂ TIẾT KIỆM PIN & CPU
        if (entry.isIntersecting && entry.intersectionRatio >= 0.5) {
          if (video.hasAttribute('data-nobook-paused')) {
            video.removeAttribute('data-nobook-paused');
            video.preload = 'auto';
            if (video.dataset.nobookWasPlaying === '1') {
              var p = video.play();
              if (p && typeof p.catch === 'function') p.catch(function () {});
              if (window.NobookVideoBridge && window.NobookVideoBridge.onVideoPlaying) { 
                try { window.NobookVideoBridge.onVideoPlaying(); } catch (e) {} 
              }
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
          if (window.NobookVideoBridge && window.NobookVideoBridge.onVideoPaused) { 
            try { window.NobookVideoBridge.onVideoPaused(); } catch (e) {} 
          }
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
          v.dataset.nobookUserPaused = '0';
          if (window.NobookVideoBridge && window.NobookVideoBridge.onVideoPlaying) { 
            try { window.NobookVideoBridge.onVideoPlaying(); } catch (e) {} 
          }
        });
        v.addEventListener('pause', function (e) {
          if (!v.hasAttribute('data-nobook-paused')) {
            v.dataset.nobookUserPaused = '1';
          }
          if (window.NobookVideoBridge && window.NobookVideoBridge.onVideoPaused) { 
            try { window.NobookVideoBridge.onVideoPaused(); } catch (e) {} 
          }
        });
      });
    };

    observeVideos();
    var mo = new MutationObserver(function () { observeVideos(); });
    mo.observe(document.body, { childList: true, subtree: true });

    window.__nobookLazyLoadVideos = observeVideos;

    console.info('[Nobook] Background Video Playback & Performance optimization active');
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

// =========================================================================================
// 4. MAIN COMPOSABLE SCREEN & LIFECYCLE MANAGEMENT
// =========================================================================================

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
            context.getSharedPreferences("nobook_prefs", Context.MODE_PRIVATE)
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
                // Stay inside WebView
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
        } else if (!isAutoDesktop && isAutoRevert) {
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
            navigator.evaluateJavaScript(ASSISTIVE_TOUCH_AND_AI_SCRIPT) {}
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
            
            val prefs = context.getSharedPreferences("nobook_prefs", Context.MODE_PRIVATE)
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
            val prefs = context.getSharedPreferences("nobook_prefs", Context.MODE_PRIVATE)
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
                addJavascriptInterface(
                    NativeAiProxyBridge(context) { jsCode ->
                        Handler(Looper.getMainLooper()).post {
                            evaluateJavascript(jsCode, null)
                        }
                    },
                    "NativeAiProxyBridge"
                )
                addJavascriptInterface(
                    NobookFeaturesBridge(context),
                    "NobookFeaturesBridge"
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
                        Context.CONNECTIVITY_SERVICE
                    ) as? ConnectivityManager
                    val activeNetwork = connectivityManager?.activeNetwork
                    val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
                    capabilities == null ||
                        (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                }.getOrDefault(true)
                
                runCatching {
                    settings.loadsImagesAutomatically = !isWeakOrMetered
                }
            }
        }
    )
}
