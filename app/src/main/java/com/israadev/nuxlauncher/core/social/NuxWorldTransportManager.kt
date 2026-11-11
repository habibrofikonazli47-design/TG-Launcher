package com.israadev.nuxlauncher.core.social

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.israadev.nuxlauncher.core.network.NuxConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Data-only LiveKit transport used by Private World TCP forwarding.
 * The Minecraft TCP bridge is kept separate from the WebView/DataChannel layer.
 */
@SuppressLint("SetJavaScriptEnabled")
object NuxWorldTransportManager {
    private const val TAG = "NuxWorldTransport"
    private const val BRIDGE_URL = "https://appassets.androidplatform.net/assets/livekit/world_transport_bridge.html"
    private const val TOKEN_PATH = "/api/social/world-invites/transport-token"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var webView: WebView? = null
    private var appContext: Context? = null
    private var bridgeReady = CompletableDeferred<Unit>()
    @Volatile private var packetListener: ((ByteArray, String) -> Unit)? = null
    @Volatile private var tcpBridge: NuxWorldTcpBridge? = null

    /** Create the TCP bridge before connecting so incoming packets are not lost. */
    fun prepareTcpBridge(): NuxWorldTcpBridge {
        tcpBridge?.close()
        return NuxWorldTcpBridge(::sendPacket).also { tcpBridge = it }
    }

    fun startHostTcpBridge(lanPort: Int) {
        val bridge = tcpBridge ?: error("TCP bridge belum disiapkan")
        bridge.startHost(lanPort)
    }

    fun startGuestTcpBridge(): Int {
        val bridge = tcpBridge ?: error("TCP bridge belum disiapkan")
        return bridge.startGuest()
    }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _roomIdentity = MutableStateFlow<String?>(null)
    val roomIdentity: StateFlow<String?> = _roomIdentity.asStateFlow()

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(activity: Activity) {
        if (webView != null) {
            attachToActivity(activity)
            return
        }
        appContext = activity.applicationContext
        bridgeReady = CompletableDeferred()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(activity.applicationContext))
            .build()

        val view = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            addJavascriptInterface(WorldBridge(), "AndroidWorldBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == BRIDGE_URL) bridgeReady.complete(Unit)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        _lastError.value = error?.description?.toString() ?: "Bridge LiveKit gagal dimuat"
                        bridgeReady.completeExceptionally(IllegalStateException(_lastError.value))
                    }
                }
            }
        }
        webView = view
        attachToActivity(activity)
        view.loadUrl(BRIDGE_URL)
    }

    private fun attachToActivity(activity: Activity) {
        val view = webView ?: return
        if (view.parent != null) return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        view.alpha = 0.01f
        decor.addView(view, ViewGroup.LayoutParams(1, 1))
    }

    suspend fun connect(
        activity: Activity,
        sessionId: String,
        onPacket: (ByteArray, String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!NuxConfig.isConfigured) error("Server TG Launcher belum dikonfigurasi")
            if (!sessionId.matches(Regex("(?i)^[0-9a-f-]{36}$"))) error("ID sesi dunia tidak valid")
            val authToken = NuxSocialManager.getAuthToken(forceRefresh = true)
                ?: error("Sesi login kedaluwarsa. Silakan login ulang.")

            val body = JSONObject().put("sessionId", sessionId).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(NuxConfig.SERVER_BASE_URL + TOKEN_PATH)
                .header("Authorization", "Bearer $authToken")
                .header("Accept", "application/json")
                .post(body)
                .build()

            val result = client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(raw).optString("error") }.getOrNull()
                    error(message?.takeIf { it.isNotBlank() } ?: "Gagal menyiapkan relay dunia (HTTP ${response.code})")
                }
                JSONObject(raw)
            }
            val serverUrl = result.optString("serverUrl")
            val token = result.optString("token")
            if (serverUrl.isBlank() || token.isBlank()) error("Respons relay dunia tidak lengkap")

            withContext(Dispatchers.Main) {
                packetListener = { payload, sender ->
                    onPacket(payload, sender)
                    tcpBridge?.onPacket(payload, sender)
                }
                _lastError.value = null
                ensureWebView(activity)
                withTimeout(20_000L) { bridgeReady.await() }
                val script = "connectWorldTransport(${JSONObject.quote(serverUrl)},${JSONObject.quote(token)},${JSONObject.quote(sessionId)});"
                webView?.evaluateJavascript(script, null)
            }
            Result.success(Unit)
        } catch (error: Throwable) {
            Log.e(TAG, "connect failed", error)
            _connected.value = false
            _lastError.value = error.message ?: "Koneksi transport dunia gagal"
            Result.failure(error)
        }
    }

    fun sendPacket(payload: ByteArray, targetIdentity: String? = null) {
        if (!_connected.value) return
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        val target = targetIdentity?.takeIf { it.isNotBlank() }?.let(JSONObject::quote) ?: "null"
        val script = "sendWorldPacket(${JSONObject.quote(encoded)},$target);"
        webView?.post { webView?.evaluateJavascript(script, null) }
    }

    fun disconnect() {
        webView?.post { webView?.evaluateJavascript("disconnectWorldTransport();", null) }
        packetListener = null
        tcpBridge?.close()
        tcpBridge = null
        _connected.value = false
        _roomIdentity.value = null
    }

    private class WorldBridge {
        @JavascriptInterface
        fun onWorldConnected(sessionId: String, identity: String) {
            Log.i(TAG, "World transport connected for session $sessionId")
            _connected.value = true
            _roomIdentity.value = identity
            _lastError.value = null
        }

        @JavascriptInterface
        fun onWorldDisconnected() {
            _connected.value = false
            _roomIdentity.value = null
        }

        @JavascriptInterface
        fun onWorldError(message: String) {
            _connected.value = false
            _lastError.value = message.take(300)
            Log.e(TAG, "World transport error: $message")
        }

        @JavascriptInterface
        fun onDataPacket(payloadBase64: String, senderIdentity: String) {
            try {
                val payload = Base64.decode(payloadBase64, Base64.DEFAULT)
                packetListener?.invoke(payload, senderIdentity)
            } catch (error: Throwable) {
                Log.e(TAG, "Invalid world data packet", error)
            }
        }
    }
}
