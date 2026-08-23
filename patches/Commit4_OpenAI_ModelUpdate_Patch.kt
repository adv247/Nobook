// =========================================================================================
// COMMIT 4 — Cập nhật model OpenAI mới nhất + hỗ trợ Base URL tùy chỉnh (reverse proxy/OpenRouter)
// Thay thế nguyên hàm callOpenAiNative hiện có trong NobookWV.kt (bên trong NativeAiProxyBridge)
// =========================================================================================

private fun callOpenAiNative(
    modelName: String,
    key: String,
    prompt: String,
    customBaseUrl: String = ""
): String {
    val endpoint = if (customBaseUrl.isNotBlank()) {
        customBaseUrl.trimEnd('/')
    } else {
        "https://api.openai.com/v1/chat/completions"
    }
    val url = URL(endpoint)
    var conn: HttpURLConnection? = null
    try {
        conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Authorization", "Bearer $key")
        }

        val openAiModel = when {
            modelName.equals("gpt-4o-mini", ignoreCase = true) -> "gpt-4o-mini"
            modelName.equals("gpt-4-turbo", ignoreCase = true) -> "gpt-4-turbo"
            modelName.equals("gpt-4o", ignoreCase = true) -> "gpt-4o"
            modelName.contains("gpt-4", ignoreCase = true) -> "gpt-4o"
            modelName.isNotBlank() -> modelName
            else -> "gpt-3.5-turbo"
        }

        val requestPayload = JSONObject().apply {
            put("model", openAiModel)
            val messagesArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }
            put("messages", messagesArr)
        }

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestPayload.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseStr = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }

        val json = JSONObject(responseStr)
        if (json.has("error")) {
            val errObj = json.optJSONObject("error")
            val errMsg = errObj?.optString("message", "OpenAI Error") ?: "OpenAI Error"
            val friendlyMsg = when (code) {
                401 -> "API Key không hợp lệ hoặc đã bị thu hồi ($errMsg)"
                429 -> "Đã chạm giới hạn Rate Limit / hết Quota ($errMsg)"
                else -> errMsg
            }
            throw Exception(friendlyMsg)
        }

        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            return choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: ""
        }
        return "Không có phản hồi từ OpenAI."
    } catch (e: java.net.SocketTimeoutException) {
        throw Exception("Kết nối OpenAI quá thời gian chờ (timeout) — kiểm tra mạng/VPN.")
    } finally {
        conn?.disconnect()
    }
}

// HƯỚNG DẪN TÍCH HỢP:
// 1. Thay nguyên hàm `callOpenAiNative(...)` hiện có bằng hàm ở trên.
// 2. Trong `executeAiRequest`, đổi `else -> callOpenAiNative(model, apiKey, promptJson)`
//    thành `else -> callOpenAiNative(model, apiKey, promptJson, customBaseUrl)`, với
//    `customBaseUrl` là tham số MỚI của executeAiRequest (default "").
// 3. Phía JS: thêm input `nb-ai-base-url` và truyền thêm tham số thứ 5 khi gọi
//    window.NativeAiProxyBridge.executeAiRequest(reqId, model, key, text, baseUrl).
// 4. Thêm option gpt-4o-mini và gpt-4-turbo vào select#nb-ai-model.
// LƯU Ý: JavascriptInterface khớp method theo tên + số lượng tham số — bước 2 và 3 phải đi kèm nhau.
