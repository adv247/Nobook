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
