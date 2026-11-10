package com.israadev.nuxlauncher.core.social

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import com.israadev.nuxlauncher.core.auth.AuthUser
import com.israadev.nuxlauncher.core.network.NuxConfig
import com.israadev.nuxlauncher.core.network.RemoteLauncherConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Manajer Audio Voice Room berbasis LiveKit WebRTC
 * Menggunakan WebRTC audio bridge di dalam WebView yang dimuat via WebViewAssetLoader (Secure Context)
 */
@SuppressLint("SetJavaScriptEnabled")
object NuxVoiceManager {
    private const val TAG = "NuxVoiceManager"
    private val TOKEN_API_BASE: String
        get() = if (NuxConfig.isConfigured) "${NuxConfig.SERVER_BASE_URL}/api/social/voice/token" else ""
    private const val BRIDGE_URL = "https://appassets.androidplatform.net/assets/livekit/livekit_bridge.html"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var webView: WebView? = null
    private var appContext: Context? = null
    private var isBridgeReady = false
    private var pendingConnectAction: (() -> Unit)? = null

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers.asStateFlow()

    private val _currentRoomId = MutableStateFlow<String?>(null)
    val currentRoomId: StateFlow<String?> = _currentRoomId.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        if (webView != null) {
            attachToActivityIfNeeded(context)
            return
        }
        scope.launch {
            try {
                val wv = WebView(context)
                initWebView(wv, context)
                webView = wv
            } catch (e: Exception) {
                Log.e(TAG, "init failed", e)
            }
        }
    }

    fun keepAliveInBackground() {
        try {
            webView?.resumeTimers()
        } catch (_: Exception) {}
    }

    private fun attachToActivityIfNeeded(context: Context) {
        val activity = context as? Activity
        if (activity != null && webView != null && webView?.parent == null) {
            val decorView = activity.window?.decorView as? ViewGroup
            if (decorView != null) {
                val params = ViewGroup.LayoutParams(1, 1)
                webView?.alpha = 0.01f
                decorView.addView(webView, params)
                Log.d(TAG, "WebView attached to Activity window decorView")
            }
        }
    }

    private fun initWebView(wv: WebView, context: Context) {
        // Keep remote WebView debugging disabled in production builds.
        // The bridge exposes an Android JavaScript interface and must not be inspectable remotely.

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context.applicationContext))
            .build()

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // Assets are served through WebViewAssetLoader; direct file/content access is unnecessary.
            allowFileAccess = false
            allowContentAccess = false
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                Log.i(TAG, "WebChromeClient onPermissionRequest: ${request?.resources?.joinToString()}")
                try {
                    val activity = context as? Activity
                    val hasMicPermission = activity != null &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            activity,
                            android.Manifest.permission.RECORD_AUDIO
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val audioResource = PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    if (request != null && hasMicPermission && request.resources.contains(audioResource)) {
                        request.grant(arrayOf(audioResource))
                    } else {
                        Log.w(TAG, "Denying WebView media permission: microphone permission is not granted")
                        request?.deny()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error granting WebChromeClient permission", e)
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val msg = consoleMessage?.message() ?: ""
                val line = consoleMessage?.lineNumber() ?: 0
                val src = consoleMessage?.sourceId() ?: ""
                Log.i("LiveKitBridge", "[$src:$line] $msg")
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "LiveKit bridge onPageFinished: $url")
                isBridgeReady = true
                pendingConnectAction?.invoke()
                pendingConnectAction = null
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "LiveKit bridge onReceivedError: ${error?.description} for ${request?.url}")
                if (request?.isForMainFrame == true) {
                    _lastError.value = "Bridge audio gagal dimuat: ${error?.description ?: "kesalahan jaringan"}"
                    _isConnecting.value = false
                    _isConnected.value = false
                }
            }
        }

        wv.addJavascriptInterface(VoiceBridgeInterface(), "AndroidVoiceBridge")

        // Attach to Activity window so WebRTC and Audio have a real window context
        val activity = context as? Activity
        if (activity != null) {
            val decorView = activity.window?.decorView as? ViewGroup
            if (decorView != null && wv.parent == null) {
                val params = ViewGroup.LayoutParams(1, 1)
                wv.alpha = 0.01f
                decorView.addView(wv, params)
                Log.i(TAG, "WebView initially attached to Activity window decorView")
            }
        }

        Log.i(TAG, "Loading LiveKit bridge URL: $BRIDGE_URL")
        wv.loadUrl(BRIDGE_URL)
    }

    /**
     * Terhubung ke voice room TG Launcher setelah memeriksa kebijakan admin dan hak paket.
     */
    fun joinVoiceRoom(roomId: String, user: AuthUser) {
        val config = RemoteLauncherConfig.config.value
        if (!RemoteLauncherConfig.isEnabled(config, "social", "voiceEnabled") ||
            !RemoteLauncherConfig.isEnabled(config, "social", "livekitEnabled")) {
            Log.w(TAG, "Voice rooms are disabled by TG Launcher administrator")
            return
        }
        val canUseVoice = if (user.features.isNotEmpty()) "voice_rooms" in user.features else user.isActivated
        if (!canUseVoice) {
            Log.w(TAG, "Voice rooms are not included in this account plan")
            return
        }
        Log.i(TAG, "joinVoiceRoom called for roomId: $roomId, user: ${user.username}")
        _lastError.value = null
        val context = appContext
        if (context == null) {
            _lastError.value = "Audio TG belum siap. Tutup lalu buka kembali aplikasi."
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _lastError.value = "Izin mikrofon belum diberikan. Aktifkan izin Mikrofon untuk TG Launcher di Pengaturan Android."
            return
        }
        if (TOKEN_API_BASE.isBlank()) {
            Log.w(TAG, "LiveKit token API is not configured in local.properties")
            _lastError.value = "Server Voice TG belum dikonfigurasi."
            return
        }
        _isConnecting.value = true
        _currentRoomId.value = roomId

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Always obtain a fresh Firebase ID token for the LiveKit token endpoint.
                // The AuthUser passed by the UI may contain an expired token from an older session.
                val activeToken = NuxSocialManager.getAuthToken(forceRefresh = true)
                if (activeToken.isNullOrBlank()) {
                    Log.e(TAG, "Unable to refresh Firebase ID token for TG Social voice request")
                    withContext(Dispatchers.Main) { _isConnecting.value = false; _lastError.value = "Sesi login kedaluwarsa. Silakan login ulang." }
                    return@launch
                }

                val payload = JSONObject().apply {
                    put("action", "join")
                    put("roomName", roomId)
                }
                val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url(TOKEN_API_BASE)
                    .header("Authorization", "Bearer $activeToken")
                    .header("Accept", "application/json")
                    .post(requestBody)
                    .build()

                client.newCall(req).execute().use { resp ->
                    val responseBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || responseBody.isEmpty()) {
                        Log.e(TAG, "Failed to get TG LiveKit token: HTTP ${resp.code} body: $responseBody")
                        val serverError = try { JSONObject(responseBody).optString("error").ifBlank { "HTTP ${resp.code}" } } catch (_: Exception) { "HTTP ${resp.code}" }
                        withContext(Dispatchers.Main) { _isConnecting.value = false; _lastError.value = serverError }
                        return@launch
                    }
                    val tokenJson = JSONObject(responseBody)
                    val token = tokenJson.optString("token", "")
                    val serverUrl = tokenJson.optString("serverUrl", "")
                    if (token.isBlank() || serverUrl.isBlank()) {
                        Log.e(TAG, "TG LiveKit token response is incomplete")
                        withContext(Dispatchers.Main) { _isConnecting.value = false; _lastError.value = "Respons server Voice TG tidak lengkap." }
                        return@launch
                    }

                    Log.i(TAG, "TG LiveKit token received. Connecting to TG server...")
                    withContext(Dispatchers.Main) {
                        val connectAction: () -> Unit = {
                            Log.i(TAG, "Invoking connectVoice in WebView...")
                            val args = JSONObject.quote(serverUrl) + "," + JSONObject.quote(token)
                            webView?.evaluateJavascript("connectVoice($args);") { result ->
                                Log.i(TAG, "evaluateJavascript connectVoice evaluated: $result")
                            }
                        }

                        if (isBridgeReady && webView != null) {
                            connectAction()
                        } else {
                            Log.i(TAG, "Bridge not yet ready, saving pendingConnectAction")
                            pendingConnectAction = connectAction
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "joinVoiceRoom error", e)
                withContext(Dispatchers.Main) {
                    _isConnecting.value = false
                    _isConnected.value = false
                    _lastError.value = e.message ?: "Gagal terhubung ke Voice Room."
                }
            }
        }
    }

    /**
     * Putuskan koneksi audio dan keluar dari voice room
     */
    fun leaveVoiceRoom() {
        Log.d(TAG, "leaveVoiceRoom called")
        scope.launch {
            webView?.evaluateJavascript("disconnectVoice();", null)
            _isConnected.value = false
            _isConnecting.value = false
            _activeSpeakers.value = emptySet()
            _currentRoomId.value = null
            _isMuted.value = false
            _lastError.value = null
        }
    }

    /**
     * Atur status bisu (mute/unmute) mikrofon
     */
    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        scope.launch {
            webView?.evaluateJavascript("setMute($muted);", null)
        }
    }

    fun toggleMute() {
        setMuted(!_isMuted.value)
    }

    private class VoiceBridgeInterface {
        @JavascriptInterface
        fun onConnected() {
            Log.i(TAG, "VoiceBridgeInterface: onConnected received from JS!")
            scope.launch {
                _isConnected.value = true
                _isConnecting.value = false
                _lastError.value = null
            }
        }

        @JavascriptInterface
        fun onDisconnected() {
            Log.i(TAG, "VoiceBridgeInterface: onDisconnected received from JS")
            scope.launch {
                _isConnected.value = false
                _isConnecting.value = false
                _activeSpeakers.value = emptySet()
            }
        }

        @JavascriptInterface
        fun onActiveSpeakersChanged(speakersJson: String) {
            scope.launch {
                try {
                    val arr = JSONArray(speakersJson)
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) {
                        set.add(arr.getString(i))
                    }
                    _activeSpeakers.value = set
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing active speakers JSON", e)
                }
            }
        }

        @JavascriptInterface
        fun onError(error: String) {
            Log.e(TAG, "VoiceBridgeInterface: onError received: $error")
            scope.launch {
                _isConnecting.value = false
                _isConnected.value = false
                _lastError.value = error
            }
        }
    }
}
