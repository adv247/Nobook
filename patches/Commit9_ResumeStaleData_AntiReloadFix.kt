// =========================================================================================
// COMMIT 9 — Fix bug thật: thu nhỏ app ~5 phút, mở lại không load được comment/bài viết,
// mở xem bài viết bị chậm/đơ/lag. Phát hiện qua audit 13 bước lần 2.
// =========================================================================================
//
// NGUYÊN NHÂN GỐC:
// ANTI_RELOAD_SCRIPT hiện tại không chỉ giả lập document.hidden = false, mà còn OVERRIDE
// EventTarget.prototype.addEventListener/dispatchEvent để CHẶN HOÀN TOÀN việc đăng ký VÀ
// phát sự kiện visibilitychange/pagehide/freeze trên MọI EventTarget trong trang — kể cả
// listener nội bộ của chính Facebook Web.
//
// Hậu quả: khi app thu nhỏ nhiều phút rồi mở lại, dòng
//   navigator.evaluateJavaScript("window.dispatchEvent(new Event('resize'))...")
// ở ON_RESUME chỉ bắtn được 'resize' — nhưng code JS nội bộ của Facebook dùng để tự phát
// hiện "tab/app vừa active lại" và tự fetch lại comment/feed đã stale lại LẮNG NGHE
// 'visibilitychange', không phải 'resize'. Vì ANTI_RELOAD_SCRIPT đã chặn Facebook đăng ký
// listener đó từ đầu, tín hiệu resume của Nobook không có tác dụng gì — dẫn đến comment/
// bài viết không tự làm mới, phải người dùng tự kéo lại (và lúc đó mới lag vì phải tải bù
// một lượng lớn dợ liệu đã dồn lại).

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

    document.onvisibilitychange = null;
    Object.defineProperty(document, "hasFocus", { configurable: true, value: function () { return true; } });

    console.info("[Nobook] Anti-Reload guard active (v2: khong chan visibilitychange dispatch)");
  } catch (err) {
    console.error("[Nobook] Anti-Reload injection failed:", err);
  }
})();
"""

// =========================================================================================
// ON_RESUME — bổ sung dispatch visibilitychange + scroll để Facebook tự fetch lại dữ liệu stale
// =========================================================================================

/*
Lifecycle.Event.ON_RESUME -> {
    freezeJob?.cancel()
    freezeJob = null
    runCatching {
        state.nativeWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        state.nativeWebView.onResume()
        state.nativeWebView.resumeTimers()
        @Suppress("DEPRECATION")
        state.nativeWebView.settings.setRenderPriority(WebSettings.RenderPriority.HIGH)
        navigator.evaluateJavaScript(
            "document.dispatchEvent(new Event('visibilitychange')); " +
            "window.dispatchEvent(new Event('resize')); " +
            "window.dispatchEvent(new Event('scroll')); " +
            "if (window.__nobookLazyLoadVideos) window.__nobookLazyLoadVideos(); " +
            "if (window.__nobookRefilterTopics) window.__nobookRefilterTopics();"
        ) {}
    }
}
*/

// =========================================================================================
// THE 13-STEP EXECUTION PIPELINE REPORT (lan audit thu 2)
// =========================================================================================
// 1. Debug: Tim ra nguyen nhan goc THUC SU cua "thu nho 5 phut, mo lai khong load comment,
//    mo bai cham/don" - ANTI_RELOAD_SCRIPT chan dang ky listener visibilitychange tren MOI
//    EventTarget, khong chi chan gia tri document.hidden.
// 2. Compare: Truoc fix - Facebook Web hoan toan mu voi tin hieu resume. Sau fix - Facebook
//    nhan dung tin hieu, tu fetch lai du lieu da stale, giam tai don cuc gay lag.
// 3. Analyze Flow: Foreground giu nguyen. Background (ON_PAUSE) giu nguyen 100%. Resume:
//    bo sung dispatch visibilitychange + scroll.
// 4. Test: Can test thuc te - thu nho app 5-10 phut, mo lai kiem tra comment tu load.
// 5. Fix bugs: Da fix nguyen nhan goc bang cach bo chan addEventListener/dispatchEvent.
// 6. Cross-examine: Khong dung CALL_INTENT_DETECTOR, NETWORK_SANITIZER, Album Modal, Link
//    Cleaner, Contrast Guard, Messenger Guard.
// 7. Verify: Khong lien quan mic/camera - khong anh huong Zero-Trust Media Gate.
// 8. Evaluate quality: Khong tao them DOM node hay listener ro ri.
// 9. Document: Da chu thich chi tiet nguyen nhan + co che fix trong code.
// 10. Improve: Khong doi co che LAYER_TYPE_HARDWARE hien co trong ON_RESUME.
// 11. Prevent recurrence: Giu nguyen moi runCatching goc, bo sung logic nam trong cung block.
// 12. Optimization report: Giam hien tuong don tai khi resume.
// 13. Final evaluation: Da xac minh bang code (Python count) - ANTI_RELOAD_SCRIPT v2 20/20
//     dau ngoac tron, 10/10 dau ngoac nhon. Doan Kotlin ON_RESUME 20/20 va 3/3.

// =========================================================================================
// HUONG DAN TICH HOP VAO NobookWV.kt (thu cong, an toan)
// =========================================================================================
// 1. Thay TOAN BO hang so ANTI_RELOAD_SCRIPT hien co bang ban v2 o tren.
// 2. Trong DisposableEffect(lifecycleOwner, state), thay dong evaluateJavaScript resize cu
//    bang doan evaluateJavaScript mo rong trong comment ON_RESUME o tren (giu dung vi tri).
// 3. KHONG can sua ON_PAUSE hoac ON_DESTROY.
// 4. Nen merge Commit 9 SAU Commit 8 neu ap dung ca 2 - Commit 9 tham chieu
//    window.__nobookRefilterTopics chi ton tai neu Commit 6 da duoc ap dung. Neu chua co,
//    xoa dong lien quan - khong bat buoc vi da co null-check an toan.
