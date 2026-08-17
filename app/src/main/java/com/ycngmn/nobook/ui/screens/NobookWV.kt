package com.ycngmn.nobook.ui.screens

import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
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

private val AFFILIATE_PARAM_PREFIXES = listOf("aff_", "utm_", "af_", "deep_link_")
private val AFFILIATE_PARAM_EXACT = setOf(
    "sub_id", "smtt", "is_from_signup", "fbclid", "ttclid", "gclid", "msclkid",
    "pid", "c", "businessId", "is_copy_url", "is_from_webapp", "sender_device",
    "sender_web_id", "enter_method", "share_app_id", "share_link_id", "checksum"
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
        builder.build().toString()
    }.getOrDefault(url)
}

private val MONETIZED_SHORTLINK_HOSTS = setOf(
    "s.shopee.vn", "shope.ee", "vn.shp.ee", "shp.ee",
    "s.lazada.vn", "s.lazada.com", "lzd.co",
    "vt.tiktok.com", "vm.tiktok.com"
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
        val resolved = runCatching {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
            }
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (code in 300..399 && !location.isNullOrBlank()) {
                if (location.startsWith("http", ignoreCase = true)) location
                else Uri.parse(current).buildUpon().encodedPath(location).build().toString()
            } else {
                null
            }
        }.getOrNull()
        if (resolved == null) return current
        current = resolved
    }
    return current
}

private val DEFAULT_SITE_BLOCKLIST = setOf<String>()

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
      'div[role="article"]'
    ]
  };

  const isElementVisible = (element) => {
    const rect = element.getBoundingClientRect();
    return (rect.width > 0 && rect.height > 0 && rect.bottom > 0 &&
      rect.top < (window.innerHeight || document.documentElement.clientHeight) &&
      rect.right > 0 && rect.left < (window.innerWidth || document.documentElement.clientWidth));
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
        if (isElementVisible(element) && (element.src || element.tagName === "VIDEO")) return element;
      }
    }
    return null;
  };

  const downloadMedia = (url) => {
    if (!url || url.indexOf("blob:") === 0) return;
    fetch(url).then(r => r.blob()).then(blob => {
      if (window.DownloadBridge && window.DownloadBridge.downloadBase64File) {
        const reader = new FileReader();
        reader.onloadend = function() {
          if (reader.result) window.DownloadBridge.downloadBase64File(reader.result, blob.type || "image/jpeg");
        };
        reader.readAsDataURL(blob);
      }
    }).catch(err => console.error("Error downloading media:", err));
  };

  const extractAndDownloadMedia = () => {
    const mediaElement = getCurrentMediaElement();
    if (mediaElement && mediaElement.tagName === "VIDEO") { downloadMedia(mediaElement.currentSrc || mediaElement.src); return; }
    if (mediaElement && mediaElement.src) { downloadMedia(mediaElement.currentSrc || mediaElement.src); return; }
  };

  const createDownloadButton = () => {
    const css = `
      #${'$'}{DOWNLOAD_BTN_ID} {
        position: fixed; top: 70px; right: 15px; width: 40px; height: 40px;
        background-color: rgba(0, 0, 0, 0.7); color: white; border-radius: 50%;
        z-index: ${'$'}{CONFIG.buttonZIndex}; border: none; display: none;
        align-items: center; justify-content: center; cursor: pointer;
      }
      #${'$'}{DOWNLOAD_BTN_ID}.visible { display: flex !important; }
    `;
    const style = document.createElement("style");
    style.textContent = css;
    document.head.appendChild(style);
    const btn = document.createElement("button");
    btn.id = DOWNLOAD_BTN_ID;
    btn.addEventListener("click", extractAndDownloadMedia);
    document.body.appendChild(btn);
    return btn;
  };

  const updateButtonVisibility = () => {
    let btn = document.getElementById(DOWNLOAD_BTN_ID);
    if (!btn) btn = createDownloadButton();
    const mediaElement = getCurrentMediaElement();
    if (mediaElement) { btn.classList.add("visible"); } else { btn.classList.remove("visible"); }
  };

  const init = () => {
    updateButtonVisibility();
    const observer = new MutationObserver(() => updateButtonVisibility());
    observer.observe(document.body, { childList: true, subtree: true });
    setInterval(updateButtonVisibility, 1000);
  };

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init); else init();
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
        l.indexOf("com.facebook.orca") !== -1;
    }
    document.addEventListener("click", function (e) {
      var link = e.target && e.target.closest ? e.target.closest("a[href]") : null;
      if (link && isMessengerDeepLink(link.href)) { e.preventDefault(); e.stopPropagation(); }
    }, true);
    var origOpen = window.open;
    window.open = function (url) {
      if (isMessengerDeepLink(url)) return null;
      return origOpen.apply(window, arguments);
    };
  } catch (err) { console.error("[Nobook] Messenger guard injection failed:", err); }
})();
"""

private const val LINK_CLEANER_SCRIPT = """
(function () {
  try {
    if (window.__nobookLinkCleanerActive) return;
    window.__nobookLinkCleanerActive = true;
    var FB_TRACKING_PARAMS = ['fbclid', '__tn__', '__cft__', '__xts__', 'refid', 'ref'];
    function stripParams(urlStr) {
      try {
        var u = new URL(urlStr, window.location.href);
        FB_TRACKING_PARAMS.forEach(function (p) { u.searchParams.delete(p); });
        return u.toString();
      } catch (e) { return urlStr; }
    }
    function scan() {
      document.querySelectorAll('a[href]').forEach(function (a) {
        if (a.dataset.nobookLinkCleaned) return;
        var cleaned = stripParams(a.href);
        if (cleaned !== a.href) { try { a.href = cleaned; } catch (e) {} }
        a.dataset.nobookLinkCleaned = '1';
      });
    }
    scan();
    var observer = new MutationObserver(function () { scan(); });
    observer.observe(document.body, { childList: true, subtree: true });
  } catch (err) { console.error('[Nobook] Link cleaner injection failed:', err); }
})();
"""

private const val TEXT_SELECTION_SCRIPT = """
(function () {
  try {
    if (window.__nobookTextSelectionActive) return;
    window.__nobookTextSelectionActive = true;
    var css = `* { -webkit-user-select: text !important; user-select: text !important; }`;
    var style = document.createElement('style');
    style.textContent = css;
    document.head.appendChild(style);
  } catch (err) { console.error('[Nobook] Text selection injection failed:', err); }
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
    function fixLowContrast(root) {
      var nodes = root.querySelectorAll('textarea, input, [contenteditable]');
      nodes.forEach(function (el) {
        try {
          var cs = window.getComputedStyle(el);
          var lc = luminance(cs.color);
          if (lc !== null && lc < 0.3) { el.style.setProperty('color', '#ffffff', 'important'); }
        } catch (e) {}
      });
    }
    fixLowContrast(document.body);
    var observer = new MutationObserver(function () { fixLowContrast(document.body); });
    observer.observe(document.body, { childList: true, subtree: true });
  } catch (err) { console.error('[Nobook] Contrast guard injection failed:', err); }
})();
"""

private const val UX_EXTRAS_SCRIPT = """
(function () {
  try {
    if (window.__nobookUxExtrasActive) return;
    window.__nobookUxExtrasActive = true;
    document.querySelectorAll('video').forEach(function (v) { if (!v.hasAttribute('controls')) v.setAttribute('controls', 'controls'); });
  } catch (err) { console.error('[Nobook] UX extras injection failed:', err); }
})();
"""

private const val SPONSORED_VI_SCRIPT = """
(function () {
  try {
    if (window.__nobookSponsoredViActive) return;
    window.__nobookSponsoredViActive = true;
    var VI_SPONSORED_KEYWORDS = ['duoc tai tro', 'noi dung duoc tai tro'];
    function hideSponsoredPosts() {
      document.querySelectorAll('span, div[aria-label]').forEach(function (el) {
        var text = (el.textContent || '').toLowerCase();
        if (VI_SPONSORED_KEYWORDS.some(function (kw) { return text.indexOf(kw) !== -1; })) {
          var post = el.closest('div[role="article"]');
          if (post) post.style.display = 'none';
        }
      });
    }
    hideSponsoredPosts();
    var observer = new MutationObserver(function () { hideSponsoredPosts(); });
    observer.observe(document.body, { childList: true, subtree: true });
  } catch (err) { console.error('[Nobook] Vietnamese sponsored filter injection failed:', err); }
})();
"""

private const val TOPIC_KEYWORD_FILTER_SCRIPT = """
(function () {
  try {
    if (window.__nobookTopicFilterActive) return;
    window.__nobookTopicFilterActive = true;
  } catch (err) { console.error('[Nobook] Topic keyword filter injection failed:', err); }
})();
"""

private const val NETWORK_SANITIZER_SCRIPT = """
(function () {
  try {
    if (window.__nobookNetworkSanitizerActive) return;
    window.__nobookNetworkSanitizerActive = true;
    var BLOCKED_NETWORK_PATTERNS = [/an\.facebook\.com/, /pixel\.facebook\.com/];
    var origFetch = window.fetch;
    window.fetch = function (input, init) {
      var url = (typeof input === 'string') ? input : (input && input.url) || '';
      for (var i = 0; i < BLOCKED_NETWORK_PATTERNS.length; i++) {
        if (BLOCKED_NETWORK_PATTERNS[i].test(url)) return Promise.resolve(new Response('{}', { status: 200 }));
      }
      return origFetch.apply(window, arguments);
    };
  } catch (err) { console.error('[Nobook] Network sanitizer injection failed:', err); }
})();
"""

private const val PERFORMANCE_OPTIMIZATION_SCRIPT = """
(function () {
  try {
    if (window.__nobookPerformanceOptActive) return;
    window.__nobookPerformanceOptActive = true;
    var CULL_CSS = `div[role="article"], div[data-pagelet^="FeedUnit"] { content-visibility: auto; contain-intrinsic-size: 600px 400px; }`;
    var style = document.createElement('style');
    style.textContent = CULL_CSS;
    document.head.appendChild(style);
    var observedVideos = new WeakSet();
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        var video = entry.target;
        if (entry.isIntersecting && entry.intersectionRatio > 0.25) {
          video.preload = 'auto';
          if (video.dataset.nobookWasPlaying === '1') { var p = video.play(); if (p && p.catch) p.catch(function(){}); }
        } else {
          if (!video.paused) { video.dataset.nobookWasPlaying = '1'; video.pause(); } else { video.dataset.nobookWasPlaying = '0'; }
          video.preload = 'none';
        }
      });
    }, { root: null, rootMargin: '200px 0px', threshold: [0, 0.25, 0.5] });
    function observeVideos() {
      document.querySelectorAll('video').forEach(function (v) {
        if (observedVideos.has(v)) return;
        observedVideos.add(v);
        io.observe(v);
      });
    }
    observeVideos();
    var mo = new MutationObserver(function () { observeVideos(); });
    mo.observe(document.body, { childList: true, subtree: true });
  } catch (err) { console.error('[Nobook] Performance optimization injection failed:', err); }
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

    val folderPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            context.getSharedPreferences("nobook_prefs", android.content.Context.MODE_PRIVATE).edit().putString("download_folder_uri", uri.toString()).apply()
            Toast.makeText(context, "Da chon thu muc luu tai xuong moi", Toast.LENGTH_SHORT).show()
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
            } else if (isBlockedSite(externalUrl)) {
                Toast.makeText(context, "Nobook: da chan link nay theo danh sach blocklist", Toast.LENGTH_SHORT).show()
            } else {
                val cleanUrl = sanitizeTrackingParams(externalUrl)
                if (isMonetizedShortLink(cleanUrl)) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val resolved = runCatching { resolveFinalUrl(cleanUrl) }.getOrDefault(cleanUrl)
                        val finalUrl = sanitizeTrackingParams(resolved)
                        withContext(Dispatchers.Main) {
                            val intent = Intent(Intent.ACTION_VIEW, finalUrl.toUri())
                            runCatching { context.startActivity(intent) }.onFailure {
                                Toast.makeText(context, resources.getString(R.string.not_supported), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    val intent = Intent(Intent.ACTION_VIEW, cleanUrl.toUri())
                    runCatching { context.startActivity(intent) }.onFailure {
                        Toast.makeText(context, resources.getString(R.string.not_supported), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    )

    LaunchedEffect(navigator) {
        val bundle = state.viewState
        if (bundle == null) { navigator.loadUrl(url) }
    }

    var exitScroll by remember { mutableStateOf(false) }
    BackHandler {
        if (exitScroll) { activity?.finish() } else {
            navigator.evaluateJavaScript("backHandlerNB();") {
                val backHandled = it.removeSurrounding("\"")
                when (backHandled) {
                    "false" -> { if (navigator.canGoBack) { navigator.navigateBack() } else { activity?.finish() } }
                    "exit" -> activity?.finish()
                    "scrolling" -> exitScroll = true
                }
            }
        }
    }

    LaunchedEffect(exitScroll) { if (exitScroll) { delay(800); exitScroll = false } }

    val isDesktop by settingsVM.desktopLayout.collectAsState()
    val isAutoRevert by settingsVM.isRevertDesktop.collectAsState()
    val isAutoDesktop = rememberAutoDesktop()

    LaunchedEffect(Unit) {
        if (isAutoDesktop && !isDesktop) { settingsVM.setRevertDesktop(true); settingsVM.setDesktopLayout(true) }
        else if (!isAutoDesktop && isAutoRevert) { settingsVM.setRevertDesktop(false); settingsVM.setDesktopLayout(false) }
    }

    var isLoading by rememberSaveable { mutableStateOf(true) }
    val isError = state.errorsForCurrentRequest.lastOrNull()?.isFromMainFrame == true

    val viewModel: MainViewModel = viewModel { MainViewModel(resources = resources, settings = settingsVM) }

    val themeColor by viewModel.themeColor
    var isImmersiveMode by rememberSaveable { mutableStateOf(settingsVM.immersiveMode.value) }

    fun setWindow(immersive: Boolean) {
        val window = activity?.window ?: return
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        if (immersive) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            val isLight = ColorUtils.calculateLuminance(themeColor.toArgb()) > 0.5
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.isAppearanceLightStatusBars = isLight
            windowInsetsController.isAppearanceLightNavigationBars = isLight
        }
        isImmersiveMode = immersive
    }

    LaunchedEffect(isImmersiveMode, themeColor.value) { setWindow(isImmersiveMode) }

    val userScripts by viewModel.scripts
    val loadingState = state.loadingState

    LaunchedEffect(loadingState, userScripts) {
        if (loadingState is LoadingState.Finished) { userScripts?.let { scripts -> navigator.evaluateJavaScript(scripts) { isLoading = false } } }
    }

    LaunchedEffect(loadingState) { if (loadingState is LoadingState.Finished) navigator.evaluateJavaScript(ANTI_RELOAD_SCRIPT) {} }
    LaunchedEffect(loadingState) { if (loadingState is LoadingState.Finished) navigator.evaluateJavaScript(STORY_REEL_DOWNLOADER_SCRIPT) {} }
    LaunchedEffect(loadingState) { if (loadingState is LoadingState.Finished) navigator.evaluateJavaScript(MESSENGER_GUARD_SCRIPT) {} }
    LaunchedEffect(loadingState) { if (loadingState is LoadingState.Finished) navigator.evaluateJavaScript(LINK_CLEANER_SCRIPT) {} }
    LaunchedEffect(loadingState) { if (loadingState is LoadingState.Finished) navigator.evaluateJavaScript(TEXT_SELECTION_SCRIPT) {} }
    LaunchedEffect(loadingState) { if (loadingState is LoadingState.Finished) navigator.evaluateJavaScript(CONTRAST_GUARD_SCRIPT) {} }
    LaunchedEffect(loadingState) { if (loadingState is LoadingState.Finished) navigator.evaluateJavaScript(UX_EXTRAS_SCRIPT) {} }
    LaunchedEffect(loadingState) { if (loadingState is LoadingState.Finished) navigator.evaluateJavaScript(SPONSORED_VI_SCRIPT) {} }
    LaunchedEffect(loadingState) { if (loadingState is LoadingState.Finished) navigator.evaluateJavaScript(TOPIC_KEYWORD_FILTER_SCRIPT) {} }
    LaunchedEffect(loadingState) { if (loadingState is LoadingState.Finished) navigator.evaluateJavaScript(NETWORK_SANITIZER_SCRIPT) {} }
    LaunchedEffect(loadingState) { if (loadingState is LoadingState.Finished) navigator.evaluateJavaScript(PERFORMANCE_OPTIMIZATION_SCRIPT) {} }

    if (isError && isLoading) { NetworkErrorDialog { activity?.finish() }; return }

    var settingsToggle by rememberSaveable { mutableStateOf(false) }
    if (settingsToggle) {
        setWindow(false)
        SettingsDialog(
            themeColor = themeColor,
            onDismiss = { setWindow(settingsVM.immersiveMode.value); settingsToggle = false },
            onReload = {
                isLoading = true
                viewModel.setThemeColor(Color.Transparent)
                setWindow(settingsVM.immersiveMode.value)
                viewModel.refresh(resources = resources, settings = settingsVM)
                navigator.reload()
            }
        )
    }

    if (isLoading) { SplashLoading(if (loadingState is LoadingState.Loading) loadingState.progress else 0.8F) }

    var messengerDesktopUaApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.lastLoadedUrl, isDesktop) {
        val currentUrl = state.lastLoadedUrl ?: return@LaunchedEffect
        if (isDesktop) { if (messengerDesktopUaApplied) messengerDesktopUaApplied = false; return@LaunchedEffect }
        val onMessengerPath = isMessengerWebPath(currentUrl)
        if (onMessengerPath && !messengerDesktopUaApplied) {
            messengerDesktopUaApplied = true
            state.nativeWebView.settings.userAgentString = DESKTOP_USER_AGENT
            navigator.reload()
        } else if (!onMessengerPath && messengerDesktopUaApplied) {
            messengerDesktopUaApplied = false
            state.nativeWebView.settings.userAgentString = ""
        }
    }

    LaunchedEffect(isDesktop) { state.nativeWebView.settings.userAgentString = if (isDesktop) DESKTOP_USER_AGENT else "" }

    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { runCatching { state.nativeWebView.onPause(); state.nativeWebView.pauseTimers() } }
                Lifecycle.Event.ON_RESUME -> { runCatching { state.nativeWebView.onResume(); state.nativeWebView.resumeTimers() } }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val barsInsets = WindowInsets.systemBars.asPaddingValues()
    val imeHeight = rememberImeHeight()

    WebView(
        modifier = Modifier.fillMaxSize().background(themeColor).then(
            if (isImmersiveMode) Modifier.padding(bottom = imeHeight)
            else Modifier.padding(top = barsInsets.calculateTopPadding(), bottom = maxOf(barsInsets.calculateBottomPadding(), imeHeight))
        ),
        state = state,
        navigator = navigator,
        platformWebViewParams = fileChooserWebViewParams(),
        captureBackPresses = false,
        onCreated = { webView ->
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            cookieManager.flush()
            state.webSettings.apply {
                isJavaScriptEnabled = true
                androidWebSettings.apply {
                    domStorageEnabled = true
                    hideDefaultVideoPoster = true
                    mediaPlaybackRequiresUserGesture = false
                }
            }
            webView.apply {
                addJavascriptInterface(NobookSettings { settingsToggle = true }, "SettingsBridge")
                addJavascriptInterface(ThemeChange { viewModel.setThemeColor(Color(it)) }, "ThemeBridge")
                addJavascriptInterface(DownloadBridge(context), "DownloadBridge")
                addJavascriptInterface(DownloadFolderBridge(context), "DownloadFolderBridge")
                addJavascriptInterface(ClipboardBridge(context), "ClipboardBridge")
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
            }
        }
    )
}
