package com.ycngmn.nobook.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
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

/**
 * Universal URL Sanitizer & Anti-Affiliate Engine (ClearURLs-inspired).
 *
 * [AFFILIATE_PARAM_PREFIXES] matches any query parameter whose name *starts*
 * with one of these prefixes (covers utm_source, aff_id, spm_url, etc. in a
 * single rule instead of enumerating every vendor's exact variant).
 */
private val AFFILIATE_PARAM_PREFIXES = listOf(
    "aff_", "utm_", "af_", "deep_link_", "track_", "spm_", "scm_", "ad_", "algo_"
)

/**
 * [AFFILIATE_PARAM_EXACT] matches full query-parameter names for tracking /
 * affiliate identifiers that do not follow a common prefix convention,
 * grouped by the platform that introduces them (Global analytics, Taobao/
 * Tmall/1688/AliExpress/Xianyu, Amazon, Google Search/Maps, Reddit/X, and
 * Shopee/Lazada). Kept as exact matches (not prefixes) to avoid stripping
 * unrelated short parameter names like a legitimate "t" used by other sites.
 */
private val AFFILIATE_PARAM_EXACT = setOf(
    // Global & analytics
    "sub_id", "smtt", "is_from_signup", "fbclid", "ttclid", "gclid", "msclkid",
    "yclid", "igshid", "_hsenc", "_openstat", "mc_cid", "mc_eid",
    "pid", "c", "businessId", "is_copy_url", "is_from_webapp", "sender_device",
    "sender_web_id", "enter_method", "share_app_id", "share_link_id", "checksum",
    // Taobao / Tmall / 1688 / AliExpress / Xianyu
    "tk", "spm", "scm", "pvid", "bxsign", "algo_pvid", "algo_expid", "btsid",
    "ws_ab_test", "sk", "sourceType", "suid", "share_crt_v", "un", "shareurl",
    // Amazon
    "tag", "linkCode", "ascsubtag", "creative", "camp", "creativeASIN", "ref_",
    "pf_rd_r", "pf_rd_p", "pf_rd_m", "pf_rd_s", "pf_rd_t", "pf_rd_i",
    "pd_rd_r", "pd_rd_w", "pd_rd_wg", "qid", "sr",
    // Google Search & Google Maps
    "ved", "usg", "sa", "ei", "g_ep", "g_st", "source", "source_id", "entry", "coh",
    // Reddit & X/Twitter
    "context", "rdt", "s", "t", "ref_src", "ref_url",
    // Shopee & Lazada
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

private const val STORY_REEL_DOWNLOADER_SCRIPT = """
/*
 * Script to add a global download button for any visible video/image on
 * Facebook (feed, stories, reels, highlights, photo viewer). Extracts the
 * true original/HD quality media (Relay JSON payload > HTML5
 * source/srcset parsing > rendered src fallback), strips lossy Facebook
 * CDN compression params (stp=) and refuses to hand blob: URLs to the
 * download pipeline. When the post containing the tapped media has 2+
 * valid images/videos (an album/grid), a lightweight modal lets the user
 * choose between downloading just the media currently in view or the
 * whole album as a sequential batch queue (400ms spacing between fetches
 * to avoid RAM/I-O contention).
 *
 * Album collection is scoped strictly to the tapped post's own container
 * (never the whole document via innerHTML scanning), capped at
 * MAX_ALBUM_ITEMS, and filtered by minimum rendered/intrinsic size so
 * profile icons, reaction glyphs and unrelated feed thumbnails can never
 * leak into the batch.
 *
 * Size checks use intrinsic media dimensions (videoWidth/naturalWidth) in
 * addition to the rendered CSS rect, because Desktop layout mode renders
 * Facebook at desktop CSS width inside a phone-sized viewport, shrinking
 * on-screen rect sizes well below fixed pixel thresholds even though the
 * media is fully visible.
 *
 * Polling via setInterval was removed: processPage() is now driven by the
 * shared MASTER_LOOP_SCRIPT (requestIdleCallback) plus a MutationObserver
 * for structural DOM changes, to avoid choking the JS main thread.
 * Original Author: @YeiversonYurgaky
 */
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
],
storyIndicators: [
'div[data-sigil="story-viewer"]',
'div[data-sigil="story-popup-header"]',
'div[data-sigil="story-tray-item"]',
".story_body_container",
".story_viewer",
".story-container",
'div[aria-label*="highlight"]',
'div[aria-label*="Highlight"]',
'div.x1ey2m1c.x9f619.xds687c.x17qophe.x10l6tqk.x13vifvy[role="presentation"]',
'div[data-pagelet="ProfilePhoto"]'
]
};

const debugLog = (...args) => CONFIG.debug && console.log("[ContentDownloader]", ...args);

const MIN_RENDERED_PX = 40;
const MIN_INTRINSIC_PX = 50;

const isLargeEnough = (element) => {
const rect = element.getBoundingClientRect();
if (rect.width > MIN_RENDERED_PX && rect.height > MIN_RENDERED_PX) return true;
if (element.tagName === "VIDEO") {
return (element.videoWidth || 0) > MIN_INTRINSIC_PX && (element.videoHeight || 0) > MIN_INTRINSIC_PX;
}
if (element.tagName === "IMG") {
return (element.naturalWidth || 0) > MIN_INTRINSIC_PX && (element.naturalHeight || 0) > MIN_INTRINSIC_PX;
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
return m[1].replace(/\\\//g, '/').replace(/\\u0025/g, '%');
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

if (best) return best.replace(/\\\//g, '/').replace(/\\u0025/g, '%');
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
.filter(p => p[0]);
let bestW = -1;
candidates.forEach(([srcUrl, size]) => {
const w = parseInt((size || '').replace('w', ''), 10) || 0;
if (w > bestW) { bestW = w; best = srcUrl; }
});
}
} catch (e) { /* ignore */ }

const current = imgEl.currentSrc || imgEl.src;
const chosen = best || current;
return stripFacebookCdnParams(chosen);
};

const downloadMedia = (url) => {
if (!url || url.indexOf("blob:") === 0) {
console.error("[Nobook] Cannot download blob/empty URL directly:", url);
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
if (best) urls.push(best);
});

const images = Array.from(container.querySelectorAll('img[src*="fbcdn"]'))
.filter(img => !img.src.includes("data:image"))
.filter(img => isLargeEnough(img));
images.forEach(img => {
const src = getBestImageSource(img);
if (src) urls.push(src);
});

const unique = Array.from(new Set(urls.filter(Boolean)));
return unique.slice(0, MAX_ALBUM_ITEMS);
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
title.textContent = "Phat hien bai viet co " + mediaCount + " anh/video";
title.style.cssText = "font-size:15px;font-weight:600;margin:4px 24px 16px 0;";

const btnCurrent = document.createElement("button");
btnCurrent.textContent = "Tai anh/video dang xem (Ban goc)";
btnCurrent.style.cssText =
"display:block;width:100%;padding:11px;margin-bottom:8px;border:none;" +
"border-radius:8px;background:#3a3a3c;color:#fff;font-size:13px;cursor:pointer;";
btnCurrent.addEventListener("click", () => { closeAlbumModal(); downloadCurrent(); });

const btnAll = document.createElement("button");
btnAll.textContent = "Tai toan bo Album (" + mediaCount + " tep goc)";
btnAll.style.cssText =
"display:block;width:100%;padding:11px;border:none;border-radius:8px;" +
"background:rgba(24,119,242,0.95);color:#fff;font-size:13px;cursor:pointer;";
btnAll.addEventListener("click", () => {
btnAll.disabled = true;
btnCurrent.disabled = true;
btnAll.style.opacity = "0.6";
btnCurrent.style.opacity = "0.6";
downloadAll((done, total) => {
title.textContent = "Dang tai " + done + "/" + total + " tep...";
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
const albumUrls = postContainer ? collectPostMediaUrls(postContainer) : [];

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
img.src !== lastDownloadedUrl
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
downloadMedia(imageUrl);
lastDownloadedUrl = imageUrl;
return;
}
}

const fallback = extractPlayableUrlFromPage() || extractOriginalImageUrlFromPage();
if (fallback) {
downloadMedia(stripFacebookCdnParams(fallback));
lastDownloadedUrl = fallback;
return;
}

debugLog("No media content found to download");
};

if (albumUrls.length >= 2) {
showAlbumChoiceModal(
albumUrls.length,
downloadCurrentSingle,
(onProgress) => downloadAllSequentially(albumUrls, onProgress)
);
return;
}

downloadCurrentSingle();
};

const createDownloadButton = () => {
const css = `
#${'$'}{DOWNLOAD_BTN_ID} {
position: fixed;
top: 70px;
right: 15px;
width: 40px;
height: 40px;
background-color: rgba(0, 0, 0, 0.7);
color: white;
border-radius: 50%;
z-index: ${'$'}{CONFIG.buttonZIndex};
border: none;
display: none;
align-items: center;
justify-content: center;
font-size: 20px;
box-shadow: 0 2px 5px rgba(0,0,0,0.3);
cursor: pointer;
background-image: url('data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 -960 960 960" fill="white"><path d="M480-320 280-520l56-58 104 104v-326h80v326l104-104 56 58-200 200ZM240-160q-33 0-56.5-23.5T160-240v-120h80v120h480v-120h80v120q0 33-23.5 56.5T720-160H240Z"/></svg>');
background-repeat: no-repeat;
background-position: center;
background-size: 24px;
}
#${'$'}{DOWNLOAD_BTN_ID}.visible {
display: flex !important;
}
`;

const style = document.createElement("style");
style.textContent = css;
document.head.appendChild(style);

const btn = document.createElement("button");
btn.id = DOWNLOAD_BTN_ID;
btn.setAttribute("aria-label", "Download content (giu de chon thu muc luu)");

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

const hideOpenAppButtons = (root = document) => {
const buttons = root.querySelectorAll('div[role="button"]');
buttons.forEach(button => {
const flAcDiv = button.querySelector('div.fl.ac');
if (flAcDiv) {
const span = flAcDiv.querySelector('span');
if (span && span.textContent.includes('\u{F196C}')) {
button.style.display = 'none';
}
}
});
};

const updateButtonVisibility = () => {
let btn = document.getElementById(DOWNLOAD_BTN_ID);
if (!btn) btn = createDownloadButton();

hideOpenAppButtons();

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
/*
 * Universal URL Sanitizer - Tier 1 (client-side DOM cleaner).
 * Unwraps one level of redirector wrapping (Facebook l.php?u=, Google
 * /url?q=, Zalo /redirect?target=, and generic redirect/dest/destination
 * params) then strips the ClearURLs-style tracking/affiliate parameter set
 * directly on <a> tags before a click is dispatched, so the browser never
 * even sees the tagged URL. A MutationObserver re-scans dynamically loaded
 * links (infinite feed scroll, lazy-rendered e-commerce cards) so freshly
 * inserted anchors get sanitized too. Mirrors the native Tier 2 pipeline
 * (sanitizeTrackingParams / resolveFinalUrl) in Kotlin.
 */
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

console.info('[Nobook] Link cleaner active (ClearURLs-style tracking + affiliate + redirect-wrapper stripping)');
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

console.info('[Nobook] Text selection + copy-all (format-preserving) active');
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

var hideAppBanners = function () {
document.querySelectorAll('div[role="button"]').forEach(function (btn) {
var label = (btn.getAttribute('aria-label') || btn.textContent || '').toLowerCase();
if (label.indexOf('use app') !== -1 || label.indexOf('get app') !== -1 ||
label.indexOf('open in app') !== -1 || label.indexOf('mo trong app') !== -1 ||
label.indexOf('tai app') !== -1) {
var container = btn.closest('div[role="dialog"]') || btn.parentElement;
if (container) container.style.display = 'none';
}
});
document.querySelectorAll('a[href*="itunes.apple.com"], a[href*="play.google.com/store"]').forEach(function (a) {
var wrap = a.closest('div[role="dialog"]') || a.parentElement;
if (wrap) wrap.style.display = 'none';
});
};

var addVideoControls = function () {
document.querySelectorAll('video').forEach(function (v) {
if (!v.hasAttribute('controls')) {
v.setAttribute('controls', 'controls');
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
hideAppBanners();
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

private const val NETWORK_SANITIZER_SCRIPT = """
(function () {
try {
if (window.__nobookNetworkSanitizerActive) return;
window.__nobookNetworkSanitizerActive = true;

var UI_SELECTORS_TO_REMOVE = [
'[aria-label="Sponsored"]',
'[data-testid="story-sponsored-label"]',
'[data-ad-comet-preview-id]',
'[data-adunit]',
'[data-sigil="m-feed-voice-subtitle"]',
'div[id^="ad_"]'
];

var BLOCKED_NETWORK_PATTERNS = [
/an\.facebook\.com/,
/pixel\.facebook\.com/,
/graph\.facebook\.com\/v\d+\/\d+\/activities/,
/audience_network/
];

var sanitizeDOM = function () {
UI_SELECTORS_TO_REMOVE.forEach(function (sel) {
try {
document.querySelectorAll(sel).forEach(function (el) {
var root = el.closest('div[role="article"]') || el.closest('[data-pagelet]') || el;
root.style.display = 'none';
});
} catch (e) { /* ignore selector errors */ }
});
};

var origXhrOpen = XMLHttpRequest.prototype.open;
XMLHttpRequest.prototype.open = function (method, url) {
for (var i = 0; i < BLOCKED_NETWORK_PATTERNS.length; i++) {
if (BLOCKED_NETWORK_PATTERNS[i].test(url)) {
console.info('[Nobook] Blocked tracking XHR:', url);
arguments[1] = 'about:blank';
break;
}
}
return origXhrOpen.apply(this, arguments);
};

var origFetch = window.fetch;
window.fetch = function (input, init) {
var url = (typeof input === 'string') ? input : (input && input.url) || '';
for (var i = 0; i < BLOCKED_NETWORK_PATTERNS.length; i++) {
if (BLOCKED_NETWORK_PATTERNS[i].test(url)) {
console.info('[Nobook] Blocked tracking fetch:', url);
return Promise.resolve(new Response('{}', { status: 200 }));
}
}
return origFetch.apply(window, arguments);
};

sanitizeDOM();
var observer = new MutationObserver(function () { sanitizeDOM(); });
observer.observe(document.body, { childList: true, subtree: true });

window.__nobookSanitizeNetwork = sanitizeDOM;

console.info('[Nobook] Network/DOM sanitizer active');
} catch (err) {
console.error('[Nobook] Network sanitizer injection failed:', err);
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

console.info('[Nobook] Performance optimization (DOM culling + video IntersectionObserver, <50% pause) active');
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
if (window.__nobookSanitizeNetwork) window.__nobookSanitizeNetwork();
if (window.__nobookLazyLoadVideos) window.__nobookLazyLoadVideos();
} catch (e) {
console.error('[Nobook] Master Loop Error', e);
}
}, { timeout: 2000 });
setTimeout(masterNobookLoop, 1500);
}

masterNobookLoop();
console.info('[Nobook] Master idle loop active (unified polling, 1.5s tick)');
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
                // Stay inside the WebView: do not jump to the native Messenger
                // app or the Play Store install prompt.
            } else if (isBlockedSite(externalUrl)) {
                Toast.makeText(
                    context,
                    "Nobook: da chan link nay theo danh sach blocklist",
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

    // allow exiting while scrolling to top.
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
    // Manual handling to fix visual & padding bug on settings dialog.
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
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(ANTI_RELOAD_SCRIPT) {}
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(STORY_REEL_DOWNLOADER_SCRIPT) {}
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(MESSENGER_GUARD_SCRIPT) {}
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(LINK_CLEANER_SCRIPT) {}
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(TEXT_SELECTION_SCRIPT) {}
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(CONTRAST_GUARD_SCRIPT) {}
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(UX_EXTRAS_SCRIPT) {}
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(SPONSORED_VI_SCRIPT) {}
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(TOPIC_KEYWORD_FILTER_SCRIPT) {}
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(NETWORK_SANITIZER_SCRIPT) {}
        }
    }

    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(PERFORMANCE_OPTIMIZATION_SCRIPT) {}
        }
    }

    // Injected last: orchestrates all the exposed __nobook* processing
    // functions from the scripts above via a single requestIdleCallback
    // tick (see MASTER_LOOP_SCRIPT), replacing 3-4 independent
    // setInterval loops with one shared, main-thread-friendly cadence.
    LaunchedEffect(loadingState) {
        if (loadingState is LoadingState.Finished) {
            navigator.evaluateJavaScript(MASTER_LOOP_SCRIPT) {}
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
            state.nativeWebView.settings.userAgentString = DESKTOP_USER_AGENT
            navigator.reload()
        } else if (!onMessengerPath && messengerDesktopUaApplied) {
            messengerDesktopUaApplied = false
            state.nativeWebView.settings.userAgentString = ""
        }
    }

    LaunchedEffect(isDesktop) {
        val userAgent = if (isDesktop) DESKTOP_USER_AGENT else ""
        state.nativeWebView.settings.userAgentString = userAgent
    }

    // Pause/resume the WebView's rendering, JS timers and media playback
    // when the app goes to background/foreground, to avoid wasting CPU,
    // battery and network while Nobook is not visible to the user.
    // RenderPriority is also lowered while backgrounded (HIGH only while
    // visible/foreground) so Chromium deprioritizes a WebView the user
    // cannot currently see.
    //
    // FIX (crash on rotate/theme change): destroy() must only run when the
    // hosting Activity is truly finishing (activity?.isFinishing == true),
    // not merely backgrounded. ON_DESTROY also fires on configuration
    // changes (screen rotation, theme switch) while the Activity itself
    // survives via retained state; destroying the native WebView there
    // left `state` pointing at a dead WebView that Compose then tried to
    // recompose into, crashing on the next frame.
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    runCatching {
                        state.nativeWebView.onPause()
                        state.nativeWebView.pauseTimers()
                        @Suppress("DEPRECATION")
                        state.nativeWebView.settings.setRenderPriority(WebSettings.RenderPriority.LOW)
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    runCatching {
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
                            state.nativeWebView.clearHistory()
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

    // Dynamic hardware layer: the GPU compositing layer is only needed
    // while a video is actually decoding/playing (costs VRAM otherwise).
    // PERFORMANCE_OPTIMIZATION_SCRIPT notifies this bridge on play/pause of
    // any video element so the layer type can drop back to NONE for
    // plain feed scrolling and only pay the hardware-layer cost while
    // video playback is active. VideoPlaybackBridge already hops back to
    // the main thread internally (see its KDoc), so it is safe to call
    // setLayerType directly from this callback.
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

    // needed to consume extra padding when keyboard is open
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

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            cookieManager.flush()

            state.webSettings.apply {
                isJavaScriptEnabled = true

                androidWebSettings.apply {
                    //isDebugInspectorInfoEnabled = true
                    domStorageEnabled = true
                    hideDefaultVideoPoster = true
                    mediaPlaybackRequiresUserGesture = false
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

                // Static hardware layer is the safe default: it is
                // immediately swapped to NONE by VideoPlaybackBridge above
                // once the page reports no video is currently playing,
                // avoiding a GL-surface re-creation on first paint.
                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false

                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                // Memory & cache management: prefer cache over network when
                // valid, and hint Chromium to prioritize this WebView's
                // rendering while it is the foreground/visible instance.
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                @Suppress("DEPRECATION")
                runCatching {
                    settings.setRenderPriority(WebSettings.RenderPriority.HIGH)
                }

                // Network-aware image loading: on a metered/weak connection
                // (mobile data without an unrestricted/validated broadband
                // capability), skip auto-loading images until the user
                // scrolls to them, to cut initial data usage and memory
                // pressure from decoded bitmaps. On Wi-Fi/validated
                // broadband, load normally.
                //
                // FIX (crash on missing/denied ACCESS_NETWORK_STATE):
                // getNetworkCapabilities() can throw SecurityException on
                // some OEM builds or restricted profiles even when the
                // permission is declared in the manifest but revoked by
                // the user/OS. The whole probe is now wrapped in
                // runCatching so a failed check degrades to "assume
                // metered" (safer default) instead of crashing the app.
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
                settings.loadsImagesAutomatically = !isWeakOrMetered
            }
        }
    )
}
