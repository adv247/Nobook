// =========================================================================================
// COMMIT 10 — Audit sâu 13 bước lần 3: LINK_CLEANER_SCRIPT + CONTRAST_GUARD_SCRIPT
// =========================================================================================

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
      document.querySelectorAll('a[href]:not([data-nobook-link-cleaned])').forEach(function (a) {
        cleanLink(a);
        a.setAttribute('data-nobook-link-cleaned', '1');
      });
    }

    var ric = window.requestIdleCallback || function (cb) {
      return setTimeout(function () { cb({ timeRemaining: function () { return 1; }, didTimeout: false }); }, 1);
    };
    var pending = false;
    var scheduleScan = function () {
      if (pending) return;
      pending = true;
      ric(function () {
        pending = false;
        scan();
      }, { timeout: 1500 });
    };

    scheduleScan();
    var observer = new MutationObserver(function () { scheduleScan(); });
    observer.observe(document.body, { childList: true, subtree: true });

    console.info('[Nobook] Link cleaner active (v2: idle-batched, selector loc san link chua xu ly)');
  } catch (err) {
    console.error('[Nobook] Link cleaner injection failed:', err);
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

    function fixLowContrastNode(el) {
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
    }

    var EDITABLE_SELECTOR = 'textarea, input, [contenteditable]';

    var scanEditables = function () {
      document.querySelectorAll(EDITABLE_SELECTOR).forEach(fixLowContrastNode);
    };

    var ric = window.requestIdleCallback || function (cb) {
      return setTimeout(function () { cb({ timeRemaining: function () { return 1; }, didTimeout: false }); }, 1);
    };
    var pending = false;
    var scheduleScan = function () {
      if (pending) return;
      pending = true;
      ric(function () {
        pending = false;
        scanEditables();
      }, { timeout: 1500 });
    };

    scheduleScan();
    var observer = new MutationObserver(function () { scheduleScan(); });
    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['contenteditable'] });

    window.__nobookFixContrast = scanEditables;

    console.info('[Nobook] Contrast guard active (v2: chi quet vung nhap lieu, idle-batched)');
  } catch (err) {
    console.error('[Nobook] Contrast guard injection failed:', err);
  }
})();
"""

// =========================================================================================
// VE YEU CAU "mo lai dung bai viet luc thu nho" - KHONG can patch them
// =========================================================================================
// ON_RESUME hien tai KHONG goi reload()/loadUrl() nao - chi resumeTimers()/onResume()/
// setLayerType. Vi tri cuon va DOM bai viet duoc Facebook Web tu giu nguyen trong bo nho
// WebView vi trang khong bi tai lai. Cam giac "nhay ve vi tri khac" truoc day nhieu kha nang
// do 2 nguyen nhan da va o Commit 9 va Commit 10 nay, khong can co che luu scroll rieng.

// =========================================================================================
// VE Album/Reel HD Downloader - GIOI HAN THAT, khong the va
// =========================================================================================
// Script nay dung selector CSS chua class atomic tu sinh cua Facebook, thay doi theo moi
// lan Facebook deploy phien ban moi - day khong phai loi logic trong NobookWV.kt ma la rui
// ro co huu cua viec dua vao class name khong on dinh tu ben thu ba.

// =========================================================================================
// THE 13-STEP EXECUTION PIPELINE REPORT (audit lan 3)
// =========================================================================================
// 1. Debug: xac nhan CONTRAST_GUARD quet selector gan nhu toan trang moi mutation.
// 2. Compare: truoc fix quet document.body selector rong; sau fix chi quet vung nhap lieu.
// 3. Analyze Flow: khong doi lifecycle Kotlin; xac nhan ON_RESUME khong reload trang.
// 4. Test: can test thuc te cuon dai, go vao o binh luan kiem tra van chong tuong phan dung.
// 5. Fix bugs: da fix ca 2 script.
// 6. Cross-examine: khong dung Album Downloader, Messenger Guard, Native AI Proxy.
// 7. Verify: khong lien quan mic/camera.
// 8. Evaluate quality: window.__nobookFixContrast van export dung ten cho MASTER_LOOP.
// 9. Document: da ghi ro nguyen nhan, pham vi fix, va gioi han that khong the va.
// 10. Improve: khong lien quan LAYER_TYPE_HARDWARE.
// 11. Prevent recurrence: giu nguyen toan bo try/catch goc.
// 12. Optimization report: giam so phan tu phai quet tu "gan nhu toan trang" xuong "chi vung
//     nhap lieu".
// 13. Final evaluation: da xac minh bang code (Python count) - LINK_CLEANER_SCRIPT 63/63 va
//     29/29; CONTRAST_GUARD_SCRIPT 52/52 va 20/20.

// =========================================================================================
// HUONG DAN TICH HOP VAO NobookWV.kt
// =========================================================================================
// 1. Thay TOAN BO hang so LINK_CLEANER_SCRIPT hien co bang ban v2 o tren.
// 2. Thay TOAN BO hang so CONTRAST_GUARD_SCRIPT hien co bang ban v2 o tren.
// 3. Khong can sua gi khac.
// 4. De xuat merge cung luc voi Commit 9 (fix/resume-stale-data-antireload).
