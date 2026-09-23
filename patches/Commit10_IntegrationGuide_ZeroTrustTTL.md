# Hướng dẫn tích hợp Zero-Trust TTL (Commit 1-3) vào NobookWV.kt

File này tổng hợp lại chính xác 3 patch từ PR #3 (`patches/Commit1_UploadStateBridge_Patch.kt`, `Commit2_CallStateBridge_Patch.kt`, `Commit3_LifecycleFlagReset_Patch.kt`) kèm vị trí áp dụng cụ thể trong `NobookWV.kt` hiện tại trên `main`, để người review/áp patch không phải tự dò lại từ đầu.

## 1. Thay `UploadStateBridge`

**Tìm trong `NobookWV.kt`:**
```kotlin
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
```

**Thay bằng nội dung trong `patches/Commit1_UploadStateBridge_Patch.kt`** (khối `UploadStateBridge` có `expiryRunnable` + `UPLOAD_INTENT_TTL_MS = 8_000L` + `cancelPendingExpiry()`).

## 2. Thay `CallStateBridge`

**Tìm trong `NobookWV.kt`:**
```kotlin
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
```

**Thay bằng nội dung trong `patches/Commit2_CallStateBridge_Patch.kt`** (khối `CallStateBridge` có `expiryRunnable` + `CALL_INTENT_TTL_MS = 35_000L` + `forceExpire()`).

## 3. Sửa `createSecureWebChromeClient`

**Tìm:**
```kotlin
private fun createSecureWebChromeClient(
    getCallState: () -> Boolean,
    getUploadState: () -> Boolean,
    resetUploadState: () -> Unit
): WebChromeClient {
    return object : WebChromeClient() {
        ...
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
```

**Đổi thành:** thêm tham số `onUploadConsumed: () -> Unit`, và gọi `onUploadConsumed()` ngay sau `resetUploadState()`. Xem chữ ký đầy đủ trong `Commit1_UploadStateBridge_Patch.kt`.

## 4. Sửa khối `onCreated = { webView -> ... }`

Thêm biến `remember` ở đầu `@Composable fun NobookWebView`:
```kotlin
val callStateBridgeRef = remember { mutableStateOf<CallStateBridge?>(null) }
val uploadStateBridgeRef = remember { mutableStateOf<UploadStateBridge?>(null) }
```

Trong `onCreated`, nơi hiện có:
```kotlin
webView.webChromeClient = createSecureWebChromeClient(
    getCallState = { isUserCalling },
    getUploadState = { isUploadIntent },
    resetUploadState = { isUploadIntent = false }
)
...
webView.apply {
    ...
    addJavascriptInterface(CallStateBridge { isCalling -> isUserCalling = isCalling }, "CallStateBridge")
    addJavascriptInterface(UploadStateBridge { intent -> isUploadIntent = intent }, "UploadStateBridge")
}
```

**Đổi thành mẫu trong `Commit3_LifecycleFlagReset_Patch.kt`:** lưu bridge vào `callStateBridgeRef.value` / `uploadStateBridgeRef.value`, truyền `onUploadConsumed = { uploadBridge.cancelPendingExpiry() }`.

## 5. Sửa nhánh `Lifecycle.Event.ON_PAUSE`

**Tìm trong `DisposableEffect(lifecycleOwner, state)`:**
```kotlin
Lifecycle.Event.ON_PAUSE -> {
    runCatching {
        state.nativeWebView.onPause()
        ...
        state.nativeWebView.setLayerType(View.LAYER_TYPE_NONE, null)
    }
    freezeJob = CoroutineScope(Dispatchers.Main).launch {
        delay(360000)
        runCatching { state.nativeWebView.pauseTimers() }
    }
}
```

**Thêm ngay đầu nhánh** (trước `runCatching { state.nativeWebView.onPause() ... }`):
```kotlin
callStateBridgeRef.value?.forceExpire()
isUploadIntent = false
```

KHÔNG đổi gì ở `ON_RESUME` / `ON_DESTROY` — đã đúng chuẩn Zero-Trust, giữ nguyên 100%.

## Ghi chú kiểm tra sau khi áp patch

- Build thử (`./gradlew assembleDebug`) để bắt lỗi cú pháp do raw string JS lồng nhau.
- Xác nhận `CALL_INTENT_DETECTOR_SCRIPT` phía JS (timeout 30s) không đổi — vẫn là lớp phòng thủ thứ nhất.
- Test thủ công: bấm nút gọi Messenger, kiểm tra camera/mic được cấp quyền đúng lúc và tự thu hồi sau ~35s hoặc khi bấm end call.
- Test thủ công: mở file chooser, xác nhận không mở lại được sau 8s nếu không có tương tác.

---

## Checklist 13 bước thực thi & kiểm chứng trước khi merge

> Quan trọng: checklist này liệt kê những gì **cần đo/kiểm tra thật** trên máy có Android Studio/emulator hoặc thiết bị thật. Không có bước nào được tự động xác nhận — người áp patch (hoặc CI) phải tự chạy và tick tay từng mục, không suy đoán số liệu.

1. **Debug** — Rà soát `NobookWV.kt` sau khi áp patch: tìm crash do sai luồng (`CalledFromWrongThreadException`), và các chỗ có thể rò rỉ pin khi ở nền (timer/observer không hủ). Dùng `adb logcat` khi minimize app 5-10 phút, kiểm tra không có exception lặp lặp lại.
2. **Compare** — So sánh RAM/CPU bằng Android Studio Profiler: trước và sau khi WebView vào nền (`ON_PAUSE`). Lưu ý: code hiện tại **không** tắt `javaScriptEnabled` khi nền (chỉ hạ `RenderPriority` + `pauseTimers()` sau 6 phút) — nếu muốn đạt "Hard Resource Freezing" thật sự (tắt JS hoàn toàn khi nền), đây là thay đổi **khác**, ngoài phạm vi 5 patch TTL ở trên và cần đánh giá riêng (có thể làm mất kết nối real-time của Messenger call).
3. **Analyze Flow** — Vẽ lại luồng `ON_RESUME ↔ ON_PAUSE ↔ ON_DESTROY` và `ExternalRequestInterceptor` sau khi patch, xác nhận không có nhánh nào gọi trùng `forceExpire()`/`cancelPendingExpiry()` hai lần.
4. **Test (0% CPU khi minimize)** — Dùng `adb shell dumpsys cpuinfo | grep nobook` sau 5-10 phút minimize. Ghi nhận số % CPU thật đo được vào PR (không viết "0%" nếu chưa đo). Lưu ý: vì `pauseTimers()` chỉ chạy sau 360000ms (6 phút), CPU sẽ không về 0 ngay lập tức — đây là hành vi đã thiết kế, không phải bug.
5. **Fix bugs** — Xác nhận `VideoPlaybackBridge.onVideoPlaying/onVideoPaused` vẫn gọi qua `mainHandler.post {}` (đã có sẵn trong code hiện tại, không đổi). Xác nhận import `androidx.lifecycle.compose.LocalLifecycleOwner` và `androidx.lifecycle.Lifecycle`/`LifecycleEventObserver` đã có đủ ở đầu file (đã có trong bản hiện tại trên `main`).
6. **Cross-examine (không regress module khác)** — Sau patch, test thủ công riêng từng module: HD Album Downloader (tải 1 ảnh + 1 album nhiều ảnh), Link Cleaner (dán link có `fbclid`), Contrast Guard, Messenger Guard (click link Messenger không bật app ngoài). Bất kỳ module nào lỗi sau patch = fail, không merge.
7. **Verify (camera/mic chỉ cấp khi có call thật)** — Test trên trang không phải `messenger.com`/`facebook.com`: xác nhận `onPermissionRequest` luôn `deny()`. Trên Messenger: bấm nút call, xác nhận cấp quyền; đợi 35s không thao tác gì, xác nhận `forceExpire()` tự thu hồi (thử gọi lại camera sau mốc này phải bị từ chối).
8. **Evaluate quality (dọn bộ nhớ ON_DESTROY)** — Xác nhận nhánh `ON_DESTROY` hiện tại (`stopLoading()`, `loadUrl("about:blank")`, `clearHistory()`, `removeAllViews()`, `destroy()`) không bị 5 patch TTL làm thay đổi — đúng yêu cầu "KHÔNG đổi ON_DESTROY" ở mục 5 phía trên.
9. **Document** — Thêm KDoc/comment ngắn cho `UploadStateBridge`, `CallStateBridge`, `createSecureWebChromeClient` sau khi patch, giải thích rõ cơ chế TTL (tương tự comment đã có sẵn trong `Commit1/Commit2_*.kt`).
10. **Improve (hardware layer động)** — Xác nhận `VideoPlaybackBridge.onPlaybackChanged` vẫn điều khiển `setLayerType(LAYER_TYPE_HARDWARE/NONE)` theo trạng thái video play/pause như code hiện tại — không bị 5 patch TTL đụng chạm.
11. **Prevent recurrence** — Rà lại toàn bộ code mới thêm (UploadStateBridge/CallStateBridge bản TTL) để đảm bảo mọi thao tác với `Handler`/`mainHandler.postDelayed` đều bọc trong `runCatching {}` giống pattern hiện có (đã có sẵn trong `Commit1/Commit2_*.kt`).
12. **Optimization report** — Điền bảng số liệu thật đo được từ bước 2 và 4 vào PR description trước khi merge (CPU %, RAM MB, số network request bị chặn, thời gian pin giả lập) — không điền số ước lượng chưa đo.
13. **Final evaluation** — Chạy `./gradlew assembleDebug` (hoặc GitHub Actions CI nếu repo có workflow build) và xác nhận build thành công, đặc biệt kiểm tra các raw string `"""..."""` chứa JS không bị lệch dấu ngoặc/escape sau khi chèn patch thủ công. Chỉ tick “xong” khi build thật sự pass, không suy đoán.
