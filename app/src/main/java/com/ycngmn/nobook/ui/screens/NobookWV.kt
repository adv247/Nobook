package com.ycngmn.nobook.ui.screens

import android.content.Intent
import android.net.Uri
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
 * (never the whole document), capped at MAX_ALBUM_ITEMS, and filtered by
 * minimum rendered/intrinsic size so profile icons, reaction glyphs and
 * unrelated feed thumbnails can never leak into the batch.
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
  const MIN_ORIGINAL_IMAGE_AREA = 500 * 500;
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
          return m[1].replace(/\\\\\//g, '/').replace(/\\\\u0025/g, '%');
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
          bitrateMatch: (s.src.match(/[?&](?:br|bitrate|vencode_tag)=(\\d+)/) || [])[1]
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

  const extractOriginalImageUrlFromPage = () => {
    try {
      const html = document.documentElement.innerHTML;
      let best = null;
      let bestArea = -1;

      const patternsOrdered = [
        /"image":\\{"height":(\\d+),"uri":"([^"]+)","width":(\\d+)\\}/g,
        /"image":\\{"uri":"([^"]+)","width":(\\d+),"height":(\\d+)\\}/g
      ];

      patternsOrdered.forEach((re, idx) => {
        let m;
        while ((m = re.exec(html)) !== null) {
          let uri, w, h;
          if (idx === 0) { h = parseInt(m[1], 10); uri = m[2]; w = parseInt(m[3], 10); }
          else { uri = m[1]; w = parseInt(m[2], 10); h = parseInt(m[3], 10); }
          if (w < MIN_ORIGINAL_SIDE || h < MIN_ORIGINAL_SIDE) continue;
          const area = w * h;
          if (area > bestArea) { bestArea = area; best = uri; }
        }
      });

      if (best) return best.replace(/\\\\\//g, '/').replace(/\\\\u0025/g, '%');
    } catch (e) { /* ignore */ }
    return null;
  };

  const getBestImageSource = (imgEl) => {
    let best = null;
    try {
      if (imgEl.srcset) {
        const candidates = imgEl.srcset.split(',')
          .map(s => s.trim().split(/\\s+/))
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

    const videos = Array.from(container.querySelectorAll("video"))
      .filter(v => isElementVisible(v) || v.readyState > 0);
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
    closeBtn.textContent = "\\u00D7";
    closeBtn.setAttribute("aria-label", "Dong");
    closeBtn.style.cssText =
      "position:absolute;top:8px;right:10px;background:none;border:none;" +
      "color:#aaa;font-size:20px;cursor:pointer;line-height:1;";
    closeBtn.addEventListener("click", closeAlbumModal);

    const title = document.createElement("div");
    title.id = MODAL_ID + "-title";
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
            bgImage.replace(/^url\\(['"](.+)['"]\\)$/, "$1")
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
        if (span && span.textContent.includes('\\u{F196C}')) {
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

    setInterval(processPage, 1000);

    window.addEventListener("scroll", () => {
      requestAnimationFrame(processPage);
    }, { passive: true });
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
"""
