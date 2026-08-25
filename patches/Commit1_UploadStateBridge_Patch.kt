// =========================================================================================
// COMMIT 1 — Zero-Trust hardening cho UploadStateBridge (auto-expiry chống bypass file chooser)
// Thay thế nguyên khối UploadStateBridge hiện có trong NobookWV.kt
// =========================================================================================

/**
 * Intent bridge for File Chooser (Zero-Trust: cờ tự hết hạn để chống bị lợi dụng
 * bởi script không liên quan sau khi người dùng đã bấm nút tải file 1 lần).
 */
private class UploadStateBridge(private val onUploadIntentChanged: (Boolean) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var expiryRunnable: Runnable? = null

    // Thời gian tối đa cờ upload intent được coi là hợp lệ sau khi người dùng bấm nút.
    // Nếu onShowFileChooser không được trigger trong khoảng này, cờ tự thu hồi.
    private val UPLOAD_INTENT_TTL_MS = 8_000L

    @JavascriptInterface
    fun notifyUploadIntent() {
        mainHandler.post {
            // Hủy runnable hết hạn cũ (nếu có) trước khi đặt lại, tránh race condition
            expiryRunnable?.let { mainHandler.removeCallbacks(it) }

            runCatching { onUploadIntentChanged(true) }

            val runnable = Runnable {
                runCatching { onUploadIntentChanged(false) }
            }
            expiryRunnable = runnable
            mainHandler.postDelayed(runnable, UPLOAD_INTENT_TTL_MS)
        }
    }

    /**
     * Gọi khi onShowFileChooser đã tiêu thụ cờ (thành công hoặc bị chặn).
     * Hủy timer hết hạn đang chờ để tránh gọi callback thừa.
     */
    fun cancelPendingExpiry() {
        mainHandler.post {
            expiryRunnable?.let { mainHandler.removeCallbacks(it) }
            expiryRunnable = null
        }
    }
}

// =========================================================================================
// onShowFileChooser — cập nhật để gọi cancelPendingExpiry() khi tiêu thụ cờ
// =========================================================================================

private fun createSecureWebChromeClient(
    getCallState: () -> Boolean,
    getUploadState: () -> Boolean,
    resetUploadState: () -> Unit,
    onUploadConsumed: () -> Unit // <-- MỚI: dùng để hủy timer hết hạn phía UploadStateBridge
): WebChromeClient {
    return object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            // Giữ nguyên logic WebRTC hiện có (không đổi trong Commit 1)
        }

        override fun onShowFileChooser(
            webView: android.webkit.WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            if (!getUploadState()) {
                filePathCallback?.onReceiveValue(null)
                return true
            }
            resetUploadState()
            onUploadConsumed() // Hủy timer hết hạn vì cờ đã được tiêu thụ đúng cách
            return false
        }
    }
}

// =========================================================================================
// Đăng ký trong onCreated của WebView (cập nhật tham số mới)
// =========================================================================================
/*
onCreated = { webView ->
    val uploadBridge = UploadStateBridge { intent -> isUploadIntent = intent }

    webView.webChromeClient = createSecureWebChromeClient(
        getCallState = { isUserCalling },
        getUploadState = { isUploadIntent },
        resetUploadState = { isUploadIntent = false },
        onUploadConsumed = { uploadBridge.cancelPendingExpiry() }
    )

    webView.apply {
        addJavascriptInterface(uploadBridge, "UploadStateBridge")
    }
}
*/

// =========================================================================================
// HƯỚNG DẪN TÍCH HỢP VÀO NobookWV.kt (thủ công, an toàn)
// =========================================================================================
// 1. Trong NobookWV.kt, tìm khối `private class UploadStateBridge(...)` hiện có
//    (gần comment "Intent bridge for File Chooser") và thay bằng khối UploadStateBridge
//    ở trên (thêm expiryRunnable + UPLOAD_INTENT_TTL_MS + cancelPendingExpiry()).
// 2. Tìm hàm `createSecureWebChromeClient(...)` hiện có và thêm tham số
//    `onUploadConsumed: () -> Unit`, rồi gọi `onUploadConsumed()` ngay sau `resetUploadState()`
//    bên trong `onShowFileChooser`.
// 3. Trong `onCreated = { webView -> ... }`, đổi lời gọi `createSecureWebChromeClient(...)`
//    và `addJavascriptInterface(UploadStateBridge { ... }, "UploadStateBridge")`
//    theo mẫu ở phần "Đăng ký trong onCreated" bên trên.
// 4. KHÔNG thay đổi logic ON_PAUSE / ON_RESUME / ON_DESTROY — đã đúng chuẩn Zero-Trust.
