// =========================================================================================
// COMMIT 3 — Ép thu hồi cờ Zero-Trust (call/upload) ngay khi app vào ON_PAUSE
// Bổ sung vào DisposableEffect(lifecycleOwner, state) hiện có.
// KHÔNG thay đổi phần freeze WebView / render priority / GPU layer đã đúng.
// =========================================================================================

// --- Thêm biến remember ở đầu @Composable fun NobookWebView ---
// val callStateBridgeRef = remember { mutableStateOf<CallStateBridge?>(null) }
// val uploadStateBridgeRef = remember { mutableStateOf<UploadStateBridge?>(null) }

/*
onCreated = { webView ->
    val callBridge = CallStateBridge { isCalling -> isUserCalling = isCalling }
    val uploadBridge = UploadStateBridge { intent -> isUploadIntent = intent }
    callStateBridgeRef.value = callBridge
    uploadStateBridgeRef.value = uploadBridge

    webView.webChromeClient = createSecureWebChromeClient(
        getCallState = { isUserCalling },
        getUploadState = { isUploadIntent },
        resetUploadState = { isUploadIntent = false },
        onUploadConsumed = { uploadBridge.cancelPendingExpiry() }
    )

    webView.apply {
        addJavascriptInterface(callBridge, "CallStateBridge")
        addJavascriptInterface(uploadBridge, "UploadStateBridge")
    }
}
*/

/*
Lifecycle.Event.ON_PAUSE -> {
    callStateBridgeRef.value?.forceExpire()
    isUploadIntent = false

    runCatching {
        state.nativeWebView.onPause()
        @Suppress("DEPRECATION")
        state.nativeWebView.settings.setRenderPriority(WebSettings.RenderPriority.LOW)
        state.nativeWebView.setLayerType(View.LAYER_TYPE_NONE, null)
    }
    freezeJob = CoroutineScope(Dispatchers.Main).launch {
        delay(360000)
        runCatching {
            state.nativeWebView.pauseTimers()
        }
    }
}
*/

// GHI CHÚ:
// - ON_RESUME và ON_DESTROY giữ nguyên 100% như bản gốc — không có lỗ hổng ở đó.
// - isUploadIntent = false trực tiếp là an toàn vì onShowFileChooser luôn kiểm tra lại
//   getUploadState() tại thời điểm gọi.
// - callStateBridgeRef.value?.forceExpire() tự invoke trên main thread, an toàn từ observer.
