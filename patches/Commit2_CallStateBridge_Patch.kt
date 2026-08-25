// =========================================================================================
// COMMIT 2 — Zero-Trust hardening cho CallStateBridge (native-side TTL, không phụ thuộc JS timeout)
// Thay thế nguyên khối CallStateBridge hiện có trong NobookWV.kt
// =========================================================================================

/**
 * Zero-Trust Media Access bridge (defense-in-depth: cờ tự hết hạn phía NATIVE,
 * không phụ thuộc hoàn toàn vào setTimeout phía JS trong CALL_INTENT_DETECTOR_SCRIPT,
 * vì JS timeout có thể bị 1 script độc hại khác trên trang can thiệp/vô hiệu hóa).
 */
private class CallStateBridge(private val onCallStateChanged: (Boolean) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var expiryRunnable: Runnable? = null

    private val CALL_INTENT_TTL_MS = 35_000L

    @JavascriptInterface
    fun notifyCallIntent(isCalling: Boolean) {
        mainHandler.post {
            expiryRunnable?.let { mainHandler.removeCallbacks(it) }
            expiryRunnable = null

            runCatching { onCallStateChanged(isCalling) }

            if (isCalling) {
                val runnable = Runnable {
                    runCatching { onCallStateChanged(false) }
                }
                expiryRunnable = runnable
                mainHandler.postDelayed(runnable, CALL_INTENT_TTL_MS)
            }
        }
    }

    fun forceExpire() {
        mainHandler.post {
            expiryRunnable?.let { mainHandler.removeCallbacks(it) }
            expiryRunnable = null
            runCatching { onCallStateChanged(false) }
        }
    }
}

// HƯỚNG DẪN TÍCH HỢP:
// 1. Thay khối `private class CallStateBridge(...)` hiện có bằng khối ở trên.
// 2. Giữ nguyên CALL_INTENT_DETECTOR_SCRIPT phía JS (lớp phòng thủ thứ nhất, không đổi).
// 3. Không đổi chữ ký constructor đăng ký JS interface.
// 4. Gọi forceExpire() ở ON_PAUSE (xem Commit3_LifecycleFlagReset_Patch.kt).
