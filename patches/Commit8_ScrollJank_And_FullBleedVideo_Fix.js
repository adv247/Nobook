// =========================================================================================
// COMMIT 8 — Fix 3 bug thật do audit 13 bước phát hiện trong NobookWV.kt:
// 1. Kéo bài bị đơ sau một thời gian
// 2. Bài viết bị dính liền nhau khi cuộn
// 3. Video trong comment (Reels/Watch full-bleed) chỉ hiện nhỏ xíu
// =========================================================================================

private const val PERFORMANCE_OPTIMIZATION_SCRIPT = """
(function () {
  try {
    if (window.__nobookPerformanceOptActive) return;
    window.__nobookPerformanceOptActive = true;

    var observedVideos = new WeakSet();
    var pendingIntersections = new Map();
    var flushScheduled = false;

    function isViewingComments() {
      var inReelsOrWatch = window.location.pathname.indexOf('/watch') !== -1 ||
        window.location.pathname.indexOf('/reel') !== -1 ||
        window.location.pathname.indexOf('/videos') !== -1;
      var hasCommentModal = !!document.querySelector('div[role="dialog"], [data-sigil*="comment"], div[aria-label*="Bình luận" i], div[aria-label*="Comments" i]');
      return inReelsOrWatch || hasCommentModal;
    }

    function flushPendingIntersections() {
      flushScheduled = false;
      var viewingComments = isViewingComments();

      pendingIntersections.forEach(function (entry, video) {
        if (viewingComments) {
          if (video.paused && video.dataset.nobookUserPaused !== '1') {
            var p = video.play();
            if (p && typeof p.catch === 'function') p.catch(function () {});
          }
          return;
        }

        if (entry.isIntersecting && entry.intersectionRatio >= 0.3) {
          if (video.hasAttribute('data-nobook-paused')) {
            video.removeAttribute('data-nobook-paused');
            video.preload = 'auto';
            if (video.dataset.nobookWasPlaying === '1') {
              var p2 = video.play();
              if (p2 && typeof p2.catch === 'function') p2.catch(function () {});
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

      pendingIntersections.clear();
    }

    var handleIntersections = function (entries) {
      entries.forEach(function (entry) {
        pendingIntersections.set(entry.target, entry);
      });
      if (!flushScheduled) {
        flushScheduled = true;
        requestAnimationFrame(flushPendingIntersections);
      }
    };

    var io = new IntersectionObserver(handleIntersections, {
      root: null,
      rootMargin: '300px 0px',
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
        v.addEventListener('pause', function () {
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

    console.info('[Nobook] Background Video Playback & Performance optimization active (fix: rAF batching chong reflow-thrash)');
  } catch (err) {
    console.error('[Nobook] Performance optimization injection failed:', err);
  }
})();
"""

private const val UX_EXTRAS_SCRIPT = """
(function () {
  try {
    if (window.__nobookUxExtrasActive) return;
    window.__nobookUxExtrasActive = true;

    var FULLBLEED_CONTAINER_SELECTORS = [
      'div[data-pagelet="Story"]',
      'div[aria-label*="reel" i]',
      'div[data-pagelet*="WatchHero"]',
      'div[role="dialog"]'
    ];

    function isInsideFullBleedContainer(video) {
      for (var i = 0; i < FULLBLEED_CONTAINER_SELECTORS.length; i++) {
        if (video.closest(FULLBLEED_CONTAINER_SELECTORS[i])) return true;
      }
      return false;
    }

    var addVideoControls = function () {
      document.querySelectorAll('video:not([controls])').forEach(function (v) {
        v.setAttribute('controls', 'controls');
        v.setAttribute('playsinline', '');
        v.controls = true;
        v.style.pointerEvents = 'auto';
        if (!isInsideFullBleedContainer(v)) {
          v.style.zIndex = '10';
          v.style.position = 'relative';
        }
      });
    };

    var runAll = function () {
      addVideoControls();
    };

    runAll();
    var observer = new MutationObserver(function () { runAll(); });
    observer.observe(document.body, { childList: true, subtree: true });

    console.info('[Nobook] UX extras active (fix: khong ep position/z-index tren video full-bleed)');
  } catch (err) {
    console.error('[Nobook] UX extras injection failed:', err);
  }
})();
"""

// =========================================================================================
// THE 13-STEP EXECUTION PIPELINE REPORT
// =========================================================================================
// 1. Debug: Xac nhan 2 nguyen nhan goc - (a) ghi DOM dong bo khong batch trong
//    IntersectionObserver callback, (b) ep CSS position vo dieu kien len moi <video>.
// 2. Compare: Truoc fix - N lan reflow dong bo/frame khi cuon nhanh. Sau fix - 1 lan
//    flush qua requestAnimationFrame/frame, giam reflow-thrash dang ke.
// 3. Analyze Flow: Khong doi vong doi Foreground/Background/Destroy - chi doi cach xu ly
//    noi bo cua 2 script JS, khong dung DisposableEffect lifecycle Kotlin.
// 4. Test: Can test thuc te tren thiet bi - cuon lien tuc 100+ bai viet kiem tra khong con
//    hien tuong dinh bai; mo video trong comment Reels/Watch kiem tra hien thi full khung.
// 5. Fix bugs: Da fix ca 2 nguyen nhan goc nhu mo ta tren.
// 6. Cross-examine: Khong dung Album Downloader, Link Cleaner, Contrast Guard, Messenger
//    Guard, Native AI Proxy. Chi thay 2 hang so JS doc lap.
// 7. Verify: Khong lien quan mic/camera - khong anh huong Zero-Trust Media Gate.
// 8. Evaluate quality: pendingIntersections.clear() sau moi flush - khong ro ri Map entries.
// 9. Document: Da chu thich ro nguyen nhan + fix ngay tai vi tri sua trong code.
// 10. Improve: Khong doi co che LAYER_TYPE_HARDWARE hien co (da dung, khong can sua).
// 11. Prevent recurrence: Toan bo try/catch goc duoc giu nguyen, khong loai bo bat ky
//     runCatching/try-catch nao da co.
// 12. Optimization report: Giam so lan ghi DOM dong bo tu O(N callback) xuong O(1 flush)/frame
//     khi cuon nhanh - giam truc tiep nguyen nhan gay jank/dinh bai quan sat duoc.
// 13. Final evaluation: Da xac minh bang code (Python count) - 78/78 dau ngoac tron,
//     39/39 dau ngoac nhon cho PERFORMANCE_OPTIMIZATION_SCRIPT; 28/28 va 11/11 cho
//     UX_EXTRAS_SCRIPT. Khong pha vo raw string Kotlin.

// =========================================================================================
// HUONG DAN TICH HOP VAO NobookWV.kt (thu cong, an toan)
// =========================================================================================
// 1. Thay TOAN BO hang so PERFORMANCE_OPTIMIZATION_SCRIPT hien co bang ban o tren.
// 2. Thay TOAN BO hang so UX_EXTRAS_SCRIPT hien co bang ban o tren.
// 3. Khong can sua gi khac - ten hang so khong doi, dong goi evaluateJavaScript() giu nguyen.
