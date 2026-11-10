package com.israadev.nuxlauncher.core.social

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.israadev.nuxlauncher.core.account.AccountManager
import com.israadev.nuxlauncher.core.auth.AuthService
import com.israadev.nuxlauncher.core.auth.AuthUser
import com.israadev.nuxlauncher.core.network.NuxConfig
import com.israadev.nuxlauncher.core.network.RemoteLauncherConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Manajer sosial lintas platform TG Launcher (Android & Windows)
 * Sinkronisasi data real-time via Firebase Realtime Database (RTDB)
 * Node shared_social: public_profiles, friends, chats, chat_meta, typing, voice_rooms
 */
object NuxSocialManager {
    private const val TAG = "NuxSocialManager"
    private const val RTDB_BASE = "https://nux-production-default-rtdb.asia-southeast1.firebasedatabase.app"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- State Flows ---
    private val _friends = MutableStateFlow<List<NuxFriend>>(emptyList())
    val friends: StateFlow<List<NuxFriend>> = _friends.asStateFlow()

    private val _activeChatFriend = MutableStateFlow<NuxFriend?>(null)
    val activeChatFriend: StateFlow<NuxFriend?> = _activeChatFriend.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<NuxChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<NuxChatMessage>> = _chatMessages.asStateFlow()

    private val _isFriendTyping = MutableStateFlow(false)
    val isFriendTyping: StateFlow<Boolean> = _isFriendTyping.asStateFlow()

    private val _voiceRooms = MutableStateFlow<List<NuxVoiceRoom>>(emptyList())
    val voiceRooms: StateFlow<List<NuxVoiceRoom>> = _voiceRooms.asStateFlow()

    private val _activeVoiceRoom = MutableStateFlow<NuxVoiceRoom?>(null)
    val activeVoiceRoom: StateFlow<NuxVoiceRoom?> = _activeVoiceRoom.asStateFlow()

    private val _voiceMessages = MutableStateFlow<List<NuxVoiceMessage>>(emptyList())
    val voiceMessages: StateFlow<List<NuxVoiceMessage>> = _voiceMessages.asStateFlow()

    private val _searchResults = MutableStateFlow<List<NuxUserProfile>>(emptyList())
    val searchResults: StateFlow<List<NuxUserProfile>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _onlineRecommendations = MutableStateFlow<List<NuxUserProfile>>(emptyList())
    val onlineRecommendations: StateFlow<List<NuxUserProfile>> = _onlineRecommendations.asStateFlow()

    private val _isLoadingRecommendations = MutableStateFlow(false)
    val isLoadingRecommendations: StateFlow<Boolean> = _isLoadingRecommendations.asStateFlow()

    // Jobs
    private var syncJob: Job? = null
    private var chatJob: Job? = null
    private var voiceRoomSyncJob: Job? = null
    private var typingJob: Job? = null
    private var presenceHeartbeatJob: Job? = null

    // Cache
    private data class CachedProfile(val profile: NuxUserProfile, val timestamp: Long)
    private val profileCache = ConcurrentHashMap<String, CachedProfile>()

    fun getCurrentUser(): AuthUser? = AccountManager.launcherUser.value

    @Volatile var serverTimeOffset: Long = 0L
    private var lastTimeOffsetSync: Long = 0L

    suspend fun syncServerTimeOffset() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastTimeOffsetSync < 60_000L && serverTimeOffset != 0L) return@withContext
        try {
            val res = AuthService.getApi("/api/presence")
            res.onSuccess { jsonStr ->
                val json = JSONObject(jsonStr)
                if (json.has("serverTime")) {
                    val sTime = json.getLong("serverTime")
                    serverTimeOffset = sTime - System.currentTimeMillis()
                    lastTimeOffsetSync = System.currentTimeMillis()
                    Log.d(TAG, "Server time offset updated: ${serverTimeOffset}ms")
                }
            }
        } catch (_: Exception) {}
    }

    fun notifyGameExitSync(explicitUid: String? = null) {
        isInGame = false
        val targetUid = explicitUid ?: getCurrentUser()?.uid ?: return
        try {
            val payload = org.json.JSONObject().apply {
                put("uid", targetUid)
                put("status", "online")
                put("platform", "android")
            }
            AuthService.postApiSync("/api/presence", payload.toString(), timeoutSec = 2)
        } catch (_: Throwable) {}
    }

    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0L

    suspend fun getAuthToken(forceRefresh: Boolean = false): String? {
        val user = getCurrentUser() ?: return null
        val now = System.currentTimeMillis()

        // 1. Return cached in-memory token if still valid and not forcing refresh
        if (!forceRefresh && !cachedToken.isNullOrBlank() && now < tokenExpiry) {
            return cachedToken
        }

        // 2. Try Google's securetoken API using user.refreshToken
        val refreshToken = user.refreshToken
        if (refreshToken.isNotBlank()) {
            val freshIdToken = AuthService.refreshIdToken(refreshToken)
            if (!freshIdToken.isNullOrBlank()) {
                cachedToken = freshIdToken
                tokenExpiry = now + 50 * 60 * 1000L // 50 menit
                AccountManager.updateTokens(freshIdToken, refreshToken)
                return freshIdToken
            }
        }

        // 3. Request fresh session tokens from keystore backend via user.uid (Always works for registered users)
        if (user.uid.isNotBlank()) {
            val pair = AuthService.requestSessionToken(user.uid)
            if (pair != null && pair.first.isNotBlank()) {
                cachedToken = pair.first
                tokenExpiry = now + 50 * 60 * 1000L
                AccountManager.updateTokens(pair.first, pair.second)
                return pair.first
            }
        }

        // 4. Fallback to existing user.idToken if present and not expired
        if (!forceRefresh && user.idToken.isNotBlank()) {
            cachedToken = user.idToken
            tokenExpiry = now + 20 * 60 * 1000L
            return user.idToken
        }

        return null
    }

    private fun buildUrl(path: String, token: String?): String {
        return if (!token.isNullOrEmpty()) {
            "$RTDB_BASE/$path.json?auth=$token"
        } else {
            "$RTDB_BASE/$path.json"
        }
    }

    /** Admin-controlled social switches. Fail closed until remote config is available. */
    private fun isSocialFeatureEnabled(key: String): Boolean {
        val adminEnabled = RemoteLauncherConfig.isEnabled(RemoteLauncherConfig.config.value, "social", key, false)
        if (!adminEnabled) return false
        if (key.contains("voice", ignoreCase = true) || key.contains("room", ignoreCase = true)) {
            return AccountManager.getActiveUser()?.features?.contains("voice_rooms") == true
        }
        return true
    }


    // =========================================================
    // 1. PRESENCE & SINKRONISASI
    // =========================================================

    fun startSync() {
        startPresenceHeartbeat()
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            try {
                syncServerTimeOffset()
                syncProfileToPublic()
                updateMyPresence()
            } catch (e: Exception) {
                Log.w(TAG, "Initial sync failed", e)
            }

            var loopCount = 0
            while (isActive) {
                try {
                    if (loopCount % 20 == 0) {
                        syncServerTimeOffset()
                    }
                    loopCount++
                    fetchFriends()
                    fetchVoiceRooms()
                } catch (e: Exception) {
                    Log.w(TAG, "Sync loop error", e)
                }
                delay(3500)
            }
        }
    }

    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        stopPresenceHeartbeat()
        closeChat()
    }

    private var isInGame = false

    fun setInGame(inGame: Boolean) {
        isInGame = inGame
        if (inGame) {
            startPresenceHeartbeat()
            scope.launch {
                try {
                    updateMyPresence("in_game")
                } catch (_: Exception) {}
            }
        } else {
            scope.launch {
                try {
                    updateMyPresence("online")
                } catch (_: Exception) {}
            }
        }
    }

    fun onAppForeground() {
        val user = getCurrentUser() ?: return
        startPresenceHeartbeat()
        scope.launch {
            try {
                val status = if (isInGame) "in_game" else "online"
                updateMyPresence(status)
            } catch (_: Exception) {}
        }
    }

    fun onAppBackground() {
        if (isInGame || _activeVoiceRoom.value != null || NuxVoiceManager.isConnected.value) {
            Log.d(TAG, "App in background but user is in-game or in voice room. Keeping presence alive.")
            if (isInGame) {
                scope.launch {
                    try {
                        updateMyPresence("in_game")
                    } catch (_: Exception) {}
                }
            }
            return
        }
        stopPresenceHeartbeat(setOffline = true)
    }

    fun startPresenceHeartbeat() {
        if (presenceHeartbeatJob?.isActive == true) return
        presenceHeartbeatJob = scope.launch {
            while (isActive) {
                try {
                    val status = if (isInGame) "in_game" else "online"
                    updateMyPresence(status)
                } catch (_: Exception) {}
                delay(15_000L) // 15 detik heartbeat
            }
        }
    }

    fun stopPresenceHeartbeat(setOffline: Boolean = true) {
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = null
        if (setOffline) {
            scope.launch {
                try {
                    updateMyPresence("offline")
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun syncProfileToPublic() = withContext(Dispatchers.IO) {
        val user = getCurrentUser() ?: return@withContext
        val token = getAuthToken()
        val now = System.currentTimeMillis()

        val payload = JSONObject().apply {
            put("uid", user.uid)
            put("username", user.username)
            put("usernameLower", user.username.lowercase())
            put("photoURL", user.photoURL)
            put("platform", "android")
            put("isAndroid", true)
            put("status", "online")
            put("lastOnline", now)
            put("verified", false)
            put("isPremium", user.features.contains("premium_badge"))
            put("updatedAt", now)
        }.toString().toRequestBody(JSON_MEDIA)

        try {
            val activeToken = getAuthToken(forceRefresh = false) ?: return@withContext
            AuthService.getApi("/api/social/profile", bearerToken = activeToken)
            val profilePayload = JSONObject().apply {
                put("username", user.username)
                put("avatarUrl", user.photoURL)
                put("bio", "")
            }.toString()
            val result = AuthService.postApi("/api/social/profile", profilePayload, bearerToken = activeToken)
            if (result.isFailure) Log.w(TAG, "TG Social profile sync failed", result.exceptionOrNull())
        } catch (e: Exception) {
            Log.w(TAG, "TG Social profile sync failed", e)
        }
    }

    suspend fun updateMyPresence(forcedStatus: String? = null) = withContext(Dispatchers.IO) {
        val user = getCurrentUser() ?: return@withContext
        val token = getAuthToken()
        val now = System.currentTimeMillis()
        val status = forcedStatus ?: "online"

        val payload = JSONObject().apply {
            put("status", status)
            put("lastOnline", now)
            put("platform", "android")
            put("isAndroid", true)
        }.toString().toRequestBody(JSON_MEDIA)

        try {
            val activeToken = getAuthToken(forceRefresh = false) ?: return@withContext
            val backendPayload = JSONObject().put("status", status).toString()
            val result = AuthService.postApi("/api/social/presence", backendPayload, bearerToken = activeToken)
            if (result.isFailure) Log.w(TAG, "TG Social presence update failed", result.exceptionOrNull())
        } catch (e: Exception) {
            Log.w(TAG, "TG Social presence update failed", e)
        }
    }

    // =========================================================
    // 2. PERTEMANAN (FRIENDS)
    // =========================================================

    suspend fun fetchFriends() = withContext(Dispatchers.IO) {
        val user = getCurrentUser() ?: return@withContext
        try {
            val token = getAuthToken(forceRefresh = false) ?: return@withContext
            val result = AuthService.getApi("/api/social/friends", bearerToken = token)
            val body = result.getOrElse { throw it }
            val root = JSONObject(body)
            val items = root.optJSONArray("items") ?: org.json.JSONArray()
            val now = System.currentTimeMillis()
            val friendList = mutableListOf<NuxFriend>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val requester = item.optString("requester_id")
                val addressee = item.optString("addressee_id")
                val uid = if (requester == user.uid) addressee else requester
                val rawStatus = item.optString("status", "pending")
                val status = when {
                    rawStatus == "accepted" -> "accepted"
                    rawStatus == "pending" && addressee == user.uid -> "pending_received"
                    rawStatus == "pending" -> "pending_sent"
                    else -> rawStatus
                }
                val presence = item.optString("presence", "offline")
                val lastSeen = item.optString("last_seen_at", "")
                val lastOnline = try { java.time.Instant.parse(lastSeen).toEpochMilli() } catch (_: Exception) { null }
                val fresh = lastOnline != null && now - lastOnline in -30_000L..85_000L
                val online = fresh && (presence == "online" || presence == "in_game")
                friendList.add(NuxFriend(
                    uid = uid,
                    username = item.optString("username", "Pengguna"),
                    photoUrl = item.optString("avatar_url", ""),
                    status = status,
                    isOnline = online,
                    isInGame = online && presence == "in_game",
                    lastOnline = lastOnline,
                    presenceStatus = if (online) presence else "offline",
                    platform = item.optString("platform", "android"),
                    isAndroid = item.optString("platform", "android") == "android"
                ))
            }
            _friends.value = friendList.sortedWith(
                compareByDescending<NuxFriend> { it.isOnline }
                    .thenBy { it.username.lowercase() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "TG Social fetchFriends failed", e)
        }
    }

    suspend fun fetchUserProfile(uid: String, token: String?): NuxUserProfile? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = profileCache[uid]
        if (cached != null && (now - cached.timestamp) < 10_000L) {
            return@withContext cached.profile
        }

        try {
            // 1. shared_social/public_profiles/{uid}
            var url = buildUrl("shared_social/public_profiles/$uid", token)
            var resp = client.newCall(Request.Builder().url(url).get().build()).execute()
            var body = resp.body?.string() ?: ""
            resp.close()

            // 2. Fallback android/users/{uid}/profile
            if (!resp.isSuccessful || body.isEmpty() || body == "null") {
                url = buildUrl("android/users/$uid/profile", token)
                resp = client.newCall(Request.Builder().url(url).get().build()).execute()
                body = resp.body?.string() ?: ""
                resp.close()
            }

            // 3. Fallback windows/users/{uid}/profile
            if (!resp.isSuccessful || body.isEmpty() || body == "null") {
                url = buildUrl("windows/users/$uid/profile", token)
                resp = client.newCall(Request.Builder().url(url).get().build()).execute()
                body = resp.body?.string() ?: ""
                resp.close()
            }

            // 4. Fallback legacy users/{uid}/profile
            if (!resp.isSuccessful || body.isEmpty() || body == "null") {
                url = buildUrl("users/$uid/profile", token)
                resp = client.newCall(Request.Builder().url(url).get().build()).execute()
                body = resp.body?.string() ?: ""
                resp.close()
            }

            if (body.isNotEmpty() && body != "null") {
                val json = JSONObject(body)
                val uname = json.optString("username", "")
                var photo = json.optString("photoURL", json.optString("photoUrl", ""))
                val status = json.optString("status", "offline")
                val lastOnline = if (json.has("lastOnline")) json.optLong("lastOnline") else null
                val platform = json.optString("platform", "windows")
                val isAndroid = json.optBoolean("isAndroid", platform.equals("android", ignoreCase = true))
                val isVerified = json.optBoolean("verified", false) ||
                        json.optBoolean("isVerified", false) ||
                        json.optString("badge", "").equals("verified", ignoreCase = true)
                val isPremium = json.optBoolean("isPremium", false) ||
                        json.has("subscription") && json.optJSONObject("subscription")?.has("tier") == true
                val inGameInstance = if (json.has("inGameInstance") && !json.isNull("inGameInstance")) json.optString("inGameInstance") else null

                // Fallback avatar jika photo kosong: Cek akun Minecraft user di Firebase
                if (photo.isBlank()) {
                    try {
                        val userUrl = buildUrl("users/$uid", token)
                        val uResp = client.newCall(Request.Builder().url(userUrl).get().build()).execute()
                        val uBody = uResp.body?.string() ?: ""
                        uResp.close()
                        if (uBody.isNotEmpty() && uBody != "null") {
                            val uJson = JSONObject(uBody)
                            if (uJson.has("accounts")) {
                                val accs = uJson.optJSONArray("accounts")
                                if (accs != null && accs.length() > 0) {
                                    for (i in 0 until accs.length()) {
                                        val acc = accs.optJSONObject(i) ?: continue
                                        val mcUuid = acc.optString("uuid", "")
                                        val mcUname = acc.optString("username", "")
                                        if (mcUuid.isNotEmpty()) {
                                            photo = "https://mc-heads.net/avatar/$mcUuid/100"
                                            break
                                        } else if (mcUname.isNotEmpty()) {
                                            photo = "https://mc-heads.net/avatar/$mcUname/100"
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    // Fallback umum menggunakan username
                    if (photo.isBlank() && uname.isNotBlank()) {
                        photo = "https://mc-heads.net/avatar/$uname/100"
                    }

                    // Sinkronkan photoURL kembali ke shared_social/public_profiles/$uid
                    if (photo.isNotBlank()) {
                        try {
                            val syncPayload = JSONObject().apply { put("photoURL", photo) }.toString().toRequestBody(JSON_MEDIA)
                            val syncReq = Request.Builder()
                                .url(buildUrl("shared_social/public_profiles/$uid", token))
                                .patch(syncPayload)
                                .build()
                            client.newCall(syncReq).execute().close()
                        } catch (_: Exception) {}
                    }
                }

                if (uname.isNotEmpty()) {
                    val prof = NuxUserProfile(
                        uid = uid,
                        username = uname,
                        photoURL = photo,
                        status = status,
                        lastOnline = lastOnline,
                        platform = platform,
                        isAndroid = isAndroid,
                        isVerified = isVerified,
                        isPremium = isPremium,
                        inGameInstance = inGameInstance
                    )
                    profileCache[uid] = CachedProfile(prof, now)
                    return@withContext prof
                }
            }
        } catch (_: Exception) {}
        null
    }

    suspend fun searchUser(query: String) = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) {
            _searchResults.value = emptyList()
            return@withContext
        }
        _isSearching.value = true
        try {
            val token = getAuthToken() ?: throw Exception("Sesi login kedaluwarsa")
            val encoded = java.net.URLEncoder.encode(q, "UTF-8")
            val result = AuthService.getApi("/api/social/search?q=$encoded&limit=30", bearerToken = token)
            val root = JSONObject(result.getOrElse { throw it })
            val items = root.optJSONArray("items") ?: org.json.JSONArray()
            val results = mutableListOf<NuxUserProfile>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val status = item.optString("presence", "offline")
                val lastSeen = try { java.time.Instant.parse(item.optString("last_seen_at")).toEpochMilli() } catch (_: Exception) { null }
                results.add(NuxUserProfile(
                    uid = item.optString("user_id"),
                    username = item.optString("username", "TG Player"),
                    photoURL = item.optString("avatar_url", ""),
                    status = status,
                    lastOnline = lastSeen,
                    platform = item.optString("platform", "android"),
                    isAndroid = item.optString("platform", "android") == "android"
                ))
            }
            _searchResults.value = results
        } catch (e: Exception) {
            Log.w(TAG, "TG Social user search failed", e)
            _searchResults.value = emptyList()
        } finally {
            _isSearching.value = false
        }
    }

    /**
     * Mengambil rekomendasi pengguna yang sedang online (maksimal 50 user, acak/shuffled).
     * Jika online < 50, tampilkan semua user yang sedang online.
     * Jika tidak ada yang online, tampilkan pengguna terdaftar aktif secara acak.
     */
    suspend fun fetchOnlineRecommendations(maxLimit: Int = 50) = withContext(Dispatchers.IO) {
        _isLoadingRecommendations.value = true
        try {
            val token = getAuthToken() ?: throw Exception("Sesi login kedaluwarsa")
            fun parseProfiles(body: String): List<NuxUserProfile> {
                val items = JSONObject(body).optJSONArray("items") ?: org.json.JSONArray()
                return (0 until items.length()).mapNotNull { i ->
                    val item = items.optJSONObject(i) ?: return@mapNotNull null
                    val status = item.optString("presence", "offline")
                    val lastSeen = try { java.time.Instant.parse(item.optString("last_seen_at")).toEpochMilli() } catch (_: Exception) { null }
                    NuxUserProfile(
                        uid = item.optString("user_id"),
                        username = item.optString("username", "TG Player"),
                        photoURL = item.optString("avatar_url", ""),
                        status = status,
                        lastOnline = lastSeen,
                        platform = item.optString("platform", "android"),
                        isAndroid = item.optString("platform", "android") == "android"
                    )
                }
            }
            val online = AuthService.getApi("/api/social/search?online=true&limit=${maxLimit.coerceIn(1, 50)}", bearerToken = token)
                .getOrElse { throw it }.let(::parseProfiles)
            val candidates = if (online.isNotEmpty()) online else {
                AuthService.getApi("/api/social/search?limit=${maxLimit.coerceIn(1, 50)}", bearerToken = token)
                    .getOrElse { throw it }.let(::parseProfiles)
            }
            _onlineRecommendations.value = candidates.shuffled().take(maxLimit.coerceIn(1, 50))
        } catch (e: Exception) {
            Log.w(TAG, "TG Social recommendations failed", e)
            _onlineRecommendations.value = emptyList()
        } finally {
            _isLoadingRecommendations.value = false
        }
    }

    fun shuffleCurrentRecommendations() {
        if (_onlineRecommendations.value.isNotEmpty()) {
            _onlineRecommendations.value = _onlineRecommendations.value.shuffled()
        }
    }

    suspend fun sendFriendRequest(targetUid: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (getCurrentUser() == null) return@withContext Result.failure(Exception("Belum login"))
        val token = getAuthToken() ?: return@withContext Result.failure(Exception("Sesi login kedaluwarsa"))
        val payload = JSONObject().put("action", "request").put("targetUserId", targetUid).toString()
        AuthService.postApi("/api/social/friends", payload, bearerToken = token).map { Unit }.also {
            if (it.isSuccess) fetchFriends()
        }
    }

    suspend fun acceptFriendRequest(targetUid: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = getCurrentUser() ?: return@withContext Result.failure(Exception("Belum login"))
        val token = getAuthToken() ?: return@withContext Result.failure(Exception("Sesi login kedaluwarsa"))
        try {
            val response = AuthService.getApi("/api/social/friends", bearerToken = token).getOrElse { throw it }
            val items = JSONObject(response).optJSONArray("items") ?: org.json.JSONArray()
            var friendshipId = 0
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("requester_id") == targetUid &&
                    item.optString("addressee_id") == user.uid &&
                    item.optString("status") == "pending") {
                    friendshipId = item.optInt("id")
                    break
                }
            }
            if (friendshipId <= 0) return@withContext Result.failure(Exception("Permintaan teman tidak ditemukan"))
            val payload = JSONObject().put("action", "accept").put("id", friendshipId).toString()
            AuthService.postApi("/api/social/friends", payload, bearerToken = token).map { Unit }.also {
                if (it.isSuccess) fetchFriends()
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun removeFriend(targetUid: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = getCurrentUser() ?: return@withContext Result.failure(Exception("Belum login"))
        val token = getAuthToken() ?: return@withContext Result.failure(Exception("Sesi login kedaluwarsa"))
        try {
            val response = AuthService.getApi("/api/social/friends", bearerToken = token).getOrElse { throw it }
            val items = JSONObject(response).optJSONArray("items") ?: org.json.JSONArray()
            var friendshipId = 0
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val matches = (item.optString("requester_id") == user.uid && item.optString("addressee_id") == targetUid) ||
                    (item.optString("requester_id") == targetUid && item.optString("addressee_id") == user.uid)
                if (matches) { friendshipId = item.optInt("id"); break }
            }
            if (friendshipId <= 0) return@withContext Result.failure(Exception("Pertemanan tidak ditemukan"))
            val payload = JSONObject().put("action", "remove").put("id", friendshipId).toString()
            AuthService.postApi("/api/social/friends", payload, bearerToken = token).map { Unit }.also {
                if (it.isSuccess) fetchFriends()
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun blockUser(targetUid: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = getCurrentUser() ?: return@withContext Result.failure(Exception("Belum login"))
        val token = getAuthToken() ?: return@withContext Result.failure(Exception("Sesi login kedaluwarsa"))
        try {
            val response = AuthService.getApi("/api/social/friends", bearerToken = token).getOrElse { throw it }
            val items = JSONObject(response).optJSONArray("items") ?: org.json.JSONArray()
            var friendshipId = 0
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val matches = (item.optString("requester_id") == user.uid && item.optString("addressee_id") == targetUid) ||
                    (item.optString("requester_id") == targetUid && item.optString("addressee_id") == user.uid)
                if (matches) { friendshipId = item.optInt("id"); break }
            }
            if (friendshipId <= 0) return@withContext Result.failure(Exception("Relasi pengguna tidak ditemukan"))
            val payload = JSONObject().put("action", "block").put("id", friendshipId).toString()
            val result = AuthService.postApi("/api/social/friends", payload, bearerToken = token).map { Unit }
            if (result.isSuccess && _activeChatFriend.value?.uid == targetUid) closeChat()
            if (result.isSuccess) fetchFriends()
            result
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun unblockUser(targetUid: String): Result<Unit> = withContext(Dispatchers.IO) {
        removeFriend(targetUid)
    }

    // =========================================================
    // 3. DIRECT CHAT 1-ON-1
    // =========================================================

    fun openChat(friend: NuxFriend) {
        _activeChatFriend.value = friend
        val myUid = getCurrentUser()?.uid ?: return
        val friendUid = friend.uid
        val chatId = if (myUid < friendUid) "dm_${myUid}_${friendUid}" else "dm_${friendUid}_${myUid}"
        chatJob?.cancel()
        chatJob = scope.launch {
            while (isActive) {
                fetchChatMessages(chatId)
                _isFriendTyping.value = false
                delay(1800)
            }
        }
    }

    fun closeChat() {
        chatJob?.cancel()
        chatJob = null
        _activeChatFriend.value = null
        _chatMessages.value = emptyList()
        _isFriendTyping.value = false
    }

    private suspend fun fetchChatMessages(chatId: String) = withContext(Dispatchers.IO) {
        try {
            val token = getAuthToken() ?: return@withContext
            val encoded = java.net.URLEncoder.encode(chatId, "UTF-8")
            val result = AuthService.getApi("/api/social/messages?conversationId=$encoded", bearerToken = token)
            val root = JSONObject(result.getOrElse { throw it })
            val items = root.optJSONArray("messages") ?: org.json.JSONArray()
            val friend = _activeChatFriend.value
            val me = getCurrentUser()
            val messages = mutableListOf<NuxChatMessage>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val senderId = item.optString("sender_id")
                val timestamp = try {
                    java.time.Instant.parse(item.optString("created_at")).toEpochMilli()
                } catch (_: Exception) { 0L }
                val image = item.optString("attachment_url").takeIf { it.isNotBlank() }
                messages.add(NuxChatMessage(
                    id = item.optString("id"),
                    senderId = senderId,
                    senderName = if (senderId == me?.uid) me.username else friend?.username.orEmpty(),
                    senderPhotoURL = if (senderId == me?.uid) me.photoURL else friend?.photoUrl.orEmpty(),
                    text = item.optString("body"),
                    timestamp = timestamp,
                    imageUrl = image
                ))
            }
            _chatMessages.value = messages.sortedBy { it.timestamp }
        } catch (e: Exception) {
            Log.w(TAG, "TG Social chat sync failed", e)
        }
    }

    private suspend fun fetchFriendTypingStatus(chatId: String, friendUid: String) = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        try {
            val url = buildUrl("shared_social/typing/$chatId/$friendUid", token)
            val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            _isFriendTyping.value = body.trim() == "true"
        } catch (_: Exception) {
            _isFriendTyping.value = false
        }
    }

    fun setMyTyping(isTyping: Boolean) {
        val myUid = getCurrentUser()?.uid ?: return
        val friend = _activeChatFriend.value ?: return
        val chatId = if (myUid < friend.uid) "${myUid}_${friend.uid}" else "${friend.uid}_${myUid}"

        typingJob?.cancel()
        typingJob = scope.launch {
            val token = getAuthToken()
            try {
                val url = buildUrl("shared_social/typing/$chatId/$myUid", token)
                val req = if (isTyping) {
                    Request.Builder().url(url).put("true".toRequestBody(JSON_MEDIA)).build()
                } else {
                    Request.Builder().url(url).delete().build()
                }
                client.newCall(req).execute().close()

                if (isTyping) {
                    delay(2500)
                    val resetReq = Request.Builder().url(url).delete().build()
                    client.newCall(resetReq).execute().close()
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun sendChatMessage(
        text: String,
        imageBytes: ByteArray? = null,
        replyTo: NuxChatReply? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val user = getCurrentUser() ?: return@withContext Result.failure(Exception("Belum login"))
        val friend = _activeChatFriend.value ?: return@withContext Result.failure(Exception("Chat room is not open"))
        val token = getAuthToken() ?: return@withContext Result.failure(Exception("Sesi login kedaluwarsa"))
        var uploadedUrl: String? = null
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            val uploadRes = uploadChatImage(imageBytes)
            if (uploadRes.isFailure) return@withContext Result.failure(uploadRes.exceptionOrNull() ?: Exception("Upload gagal"))
            uploadedUrl = uploadRes.getOrNull()
        }
        if (text.isBlank() && uploadedUrl.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Pesan tidak boleh kosong"))
        }
        val payload = JSONObject().apply {
            put("targetUserId", friend.uid)
            put("message", text.trim())
            if (!uploadedUrl.isNullOrBlank()) put("attachmentUrl", uploadedUrl)
        }.toString()
        val result = AuthService.postApi("/api/social/messages", payload, bearerToken = token)
        result.fold(
            onSuccess = {
                fetchChatMessages(if (user.uid < friend.uid) "dm_${user.uid}_${friend.uid}" else "dm_${friend.uid}_${user.uid}")
                Result.success(Unit)
            },
            onFailure = { Result.failure(it) }
        )
    }

    private suspend fun uploadChatImage(bytes: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val remoteConfig = RemoteLauncherConfig.config.value
            if (!RemoteLauncherConfig.isEnabled(remoteConfig, "social", "uploadsEnabled")) {
                return@withContext Result.failure(Exception("Upload lampiran dinonaktifkan oleh administrator TG Launcher."))
            }
            val maxUploadMb = remoteConfig
                ?.optJSONObject("social")?.optInt("maximumUploadMb", 20)?.coerceIn(1, 100) ?: 20
            val maxUploadBytes = maxUploadMb.toLong() * 1024L * 1024L
            if (bytes.size.toLong() > maxUploadBytes) {
                return@withContext Result.failure(Exception("Ukuran lampiran melebihi batas admin (${maxUploadMb} MB)."))
            }

            val requestBody = bytes.toRequestBody("image/jpeg".toMediaType())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "chat_${System.currentTimeMillis()}.jpg", requestBody)
                .build()

            // Primary: NuxConfig.UPLOAD_IMAGE_URL
            val uploadUrl = NuxConfig.UPLOAD_IMAGE_URL
            if (uploadUrl.isNotBlank()) {
                val reqBuilder = Request.Builder()
                    .url(uploadUrl)
                    .post(multipart)
                NuxConfig.applyAuthHeaders(reqBuilder)
                val req = reqBuilder.build()

                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                resp.close()

                if (resp.isSuccessful) {
                    val json = gson.fromJson(body, JsonObject::class.java)
                    val url = json?.get("url")?.asString
                    if (!url.isNullOrEmpty()) return@withContext Result.success(url)
                }
            }
        } catch (_: Exception) {}

        // Fallback: ImgBB
        try {
            val requestBody = bytes.toRequestBody("image/jpeg".toMediaType())
            val imgbbBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "chat_${System.currentTimeMillis()}.jpg", requestBody)
                .build()

            val imgbbReq = Request.Builder()
                .url("https://api.imgbb.com/1/upload?key=c3257ef84dcc0d3a9e26f50c15579f06")
                .post(imgbbBody)
                .build()

            val imgbbResp = client.newCall(imgbbReq).execute()
            val imgbbStr = imgbbResp.body?.string() ?: ""
            imgbbResp.close()

            if (imgbbResp.isSuccessful) {
                val json = gson.fromJson(imgbbStr, JsonObject::class.java)
                val dataObj = json?.getAsJsonObject("data")
                val url = dataObj?.get("url")?.asString ?: dataObj?.get("display_url")?.asString
                if (!url.isNullOrEmpty()) return@withContext Result.success(url)
            }
        } catch (_: Exception) {}

        Result.failure(Exception("Failed to upload image attachment."))
    }

    // =========================================================
    // 4. VOICE ROOMS
    // =========================================================

    suspend fun fetchVoiceRooms() = withContext(Dispatchers.IO) {
        if (!isSocialFeatureEnabled("voiceEnabled")) {
            _voiceRooms.value = emptyList()
            return@withContext
        }
        val token = getAuthToken() ?: return@withContext
        try {
            // Supabase is the source of truth for room metadata. Firebase RTDB
            // remains only for transient participant presence and room chat.
            val response = AuthService.getApi("/api/social/voice/token", bearerToken = token)
            val root = JSONObject(response.getOrElse { throw it })
            val items = root.optJSONArray("rooms") ?: org.json.JSONArray()
            val rooms = mutableListOf<NuxVoiceRoom>()
            val now = System.currentTimeMillis()

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val roomId = item.optString("id", "")
                if (roomId.isBlank()) continue

                var roomState: JSONObject? = null
                try {
                    val roomUrl = buildUrl("shared_social/voice_rooms/$roomId", token)
                    val roomBody = client.newCall(Request.Builder().url(roomUrl).get().build()).execute().use {
                        it.body?.string().orEmpty()
                    }
                    if (roomBody.isNotBlank() && roomBody != "null") roomState = JSONObject(roomBody)
                } catch (_: Exception) {}

                val participantsJson = roomState?.optJSONObject("participants")
                val participants = mutableMapOf<String, NuxParticipant>()
                if (participantsJson != null) {
                    for (pUid in participantsJson.keys()) {
                        val pObj = participantsJson.optJSONObject(pUid) ?: continue
                        val pLastSeen = pObj.optLong("lastSeen", 0L)
                        val staleAndroid = pObj.optString("platform") == "android" &&
                            pLastSeen > 0L && now - pLastSeen > 60_000L
                        if (staleAndroid) {
                            try {
                                val delUrl = buildUrl("shared_social/voice_rooms/$roomId/participants/$pUid", token)
                                client.newCall(Request.Builder().url(delUrl).delete().build()).execute().close()
                                val rootDelUrl = buildUrl("voice_rooms/$roomId/participants/$pUid", token)
                                client.newCall(Request.Builder().url(rootDelUrl).delete().build()).execute().close()
                            } catch (_: Exception) {}
                            continue
                        }
                        participants[pUid] = NuxParticipant(
                            uid = pUid,
                            username = pObj.optString("username", "Peserta"),
                            photoURL = pObj.optString("photoURL", pObj.optString("photoUrl", "")),
                            isVerified = pObj.optBoolean("verified", false) || pObj.optBoolean("isVerified", false),
                            isPremium = pObj.optBoolean("isPremium", false),
                            platform = pObj.optString("platform", "android"),
                            isAndroid = pObj.optBoolean("isAndroid", true)
                        )
                    }
                }

                val createdAt = try {
                    java.time.Instant.parse(item.optString("created_at")).toEpochMilli()
                } catch (_: Exception) {
                    roomState?.optLong("createdAt", 0L) ?: 0L
                }
                rooms.add(
                    NuxVoiceRoom(
                        id = roomId,
                        name = item.optString("room_name", roomState?.optString("name", "Voice Room") ?: "Voice Room"),
                        hostUid = item.optString("created_by", roomState?.optString("hostUid", "") ?: ""),
                        hostName = item.optString("host_name", roomState?.optString("hostName", "Host") ?: "Host"),
                        password = roomState?.optString("password", "") ?: "",
                        maxUsers = item.optInt("max_participants", roomState?.optInt("maxUsers", 5) ?: 5),
                        createdAt = createdAt,
                        participants = participants
                    )
                )
            }

            _voiceRooms.value = rooms.filter { it.participantCount > 0 }
            val active = _activeVoiceRoom.value
            if (active != null) {
                _voiceRooms.value.find { it.id == active.id }?.let { _activeVoiceRoom.value = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Supabase TG Voice Room sync failed", e)
        }
    }

    suspend fun createVoiceRoom(name: String, password: String, maxUsers: Int): Result<NuxVoiceRoom> = withContext(Dispatchers.IO) {
        if (!isSocialFeatureEnabled("voiceEnabled")) return@withContext Result.failure(Exception("Voice Room dinonaktifkan oleh administrator TG Launcher."))
        val user = getCurrentUser() ?: return@withContext Result.failure(Exception("Belum login"))
        var token = getAuthToken()
        if (token.isNullOrBlank()) token = getAuthToken(forceRefresh = true)
        if (token.isNullOrBlank()) return@withContext Result.failure(Exception("Session expired. Please log in again."))

        val now = System.currentTimeMillis()
        try {
            // Create the canonical room record through the authenticated TG API;
            // the returned Supabase ID is reused for transient RTDB participants.
            val createPayload = JSONObject().apply {
                put("action", "create")
                put("roomName", name.trim().ifBlank { "Voice Room" })
                put("visibility", "friends")
                put("maxParticipants", maxUsers.coerceIn(2, 50))
            }.toString()
            val createResult = AuthService.postApi("/api/social/voice/token", createPayload, bearerToken = token)
            val createJson = JSONObject(createResult.getOrElse { throw it })
            val roomId = createJson.optString("roomId", "").ifBlank {
                val livekitRoomName = createJson.optString("roomName", "")
                if (livekitRoomName.startsWith("tg-social-")) livekitRoomName.removePrefix("tg-social-") else ""
            }
            if (roomId.isBlank()) throw Exception("Server tidak mengembalikan ID Voice Room.")

            val participant = JSONObject().apply {
                put("uid", user.uid)
                put("username", user.username)
                put("photoURL", user.photoURL)
                put("platform", "android")
                put("isAndroid", true)
                put("lastSeen", now)
            }
            val roomPayload = JSONObject().apply {
                put("name", name.trim().ifBlank { "Voice Room" })
                put("hostUid", user.uid)
                put("hostName", user.username)
                put("creatorPlatform", "android")
                put("password", password.trim())
                put("maxUsers", maxUsers.coerceIn(2, 20))
                put("createdAt", now)
                put("participants", JSONObject().put(user.uid, participant))
            }.toString().toRequestBody(JSON_MEDIA)

            val roomUrl = buildUrl("shared_social/voice_rooms/$roomId", token)
            client.newCall(Request.Builder().url(roomUrl).put(roomPayload).build()).execute().use {
                if (!it.isSuccessful) throw Exception("Gagal menyiapkan peserta Voice Room (${it.code}).")
            }
            // Keep the legacy root mirror for older TG clients while Supabase
            // remains authoritative for the Admin Panel and room availability.
            try {
                val rootUrl = buildUrl("voice_rooms/$roomId", token)
                client.newCall(Request.Builder().url(rootUrl).put(roomPayload).build()).execute().close()
            } catch (_: Exception) {}

            val newRoom = NuxVoiceRoom(
                id = roomId,
                name = name.trim().ifBlank { "Voice Room" },
                hostUid = user.uid,
                hostName = user.username,
                password = password.trim(),
                maxUsers = maxUsers.coerceIn(2, 20),
                createdAt = now,
                participants = mapOf(
                    user.uid to NuxParticipant(
                        uid = user.uid,
                        username = user.username,
                        photoURL = user.photoURL,
                        platform = "android",
                        isAndroid = true
                    )
                )
            )
            joinVoiceRoomInternal(newRoom)
            fetchVoiceRooms()
            Result.success(newRoom)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinVoiceRoom(room: NuxVoiceRoom, inputPassword: String = ""): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isSocialFeatureEnabled("voiceEnabled")) return@withContext Result.failure(Exception("Voice Room dinonaktifkan oleh administrator TG Launcher."))
        val user = getCurrentUser() ?: return@withContext Result.failure(Exception("Belum login"))
        var token = getAuthToken()

        if (room.isLocked && room.password != inputPassword) {
            return@withContext Result.failure(Exception("Password voice room salah!"))
        }

        if (room.isFull && !room.participants.containsKey(user.uid)) {
            return@withContext Result.failure(Exception("Voice room is already full!"))
        }

        try {
            val partPayload = JSONObject().apply {
                put("uid", user.uid)
                put("username", user.username)
                put("photoURL", user.photoURL)
                put("platform", "android")
                put("isAndroid", true)
                put("lastSeen", System.currentTimeMillis())
            }

            var req = Request.Builder()
                .url(buildUrl("shared_social/voice_rooms/${room.id}/participants/${user.uid}", token))
                .put(partPayload.toString().toRequestBody(JSON_MEDIA))
                .build()
            var resp = client.newCall(req).execute()

            if (resp.code == 401) {
                resp.close()
                token = getAuthToken(forceRefresh = true)
                if (!token.isNullOrBlank()) {
                    req = Request.Builder()
                        .url(buildUrl("shared_social/voice_rooms/${room.id}/participants/${user.uid}", token))
                        .put(partPayload.toString().toRequestBody(JSON_MEDIA))
                        .build()
                    resp = client.newCall(req).execute()
                }
            }

            val isSuccess = resp.isSuccessful
            val code = resp.code
            resp.close()

            if (!isSuccess) {
                return@withContext Result.failure(Exception("Failed to join room ($code)"))
            }

            joinVoiceRoomInternal(room)
            fetchVoiceRooms()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun joinVoiceRoomInternal(room: NuxVoiceRoom) {
        closeChat()
        _activeVoiceRoom.value = room

        voiceRoomSyncJob?.cancel()
        voiceRoomSyncJob = scope.launch {
            while (isActive) {
                fetchVoiceRoomMessages(room.id)
                fetchVoiceRooms()

                val curU = getCurrentUser()
                val tok = getAuthToken()
                if (curU != null) {
                    try {
                        val hbUrl = buildUrl("shared_social/voice_rooms/${room.id}/participants/${curU.uid}", tok)
                        val hbBody = JSONObject().apply {
                            put("lastSeen", System.currentTimeMillis())
                        }.toString().toRequestBody(JSON_MEDIA)
                        client.newCall(Request.Builder().url(hbUrl).patch(hbBody).build()).execute().close()
                    } catch (_: Exception) {}
                }

                delay(2000)
            }
        }

        val user = getCurrentUser()
        if (user != null) {
            NuxVoiceManager.joinVoiceRoom(room.id, user)
        }
    }

    fun leaveVoiceRoom() {
        scope.launch {
            try {
                NuxVoiceManager.leaveVoiceRoom()
            } catch (_: Exception) {}

            val user = getCurrentUser()
            val room = _activeVoiceRoom.value
            val token = getAuthToken()

            voiceRoomSyncJob?.cancel()
            voiceRoomSyncJob = null
            _activeVoiceRoom.value = null
            _voiceMessages.value = emptyList()

            if (user != null && room != null) {
                try {
                    val req = Request.Builder()
                        .url(buildUrl("shared_social/voice_rooms/${room.id}/participants/${user.uid}", token))
                        .delete()
                        .build()
                    client.newCall(req).execute().close()

                    try {
                        val rootPartReq = Request.Builder()
                            .url(buildUrl("voice_rooms/${room.id}/participants/${user.uid}", token))
                            .delete()
                            .build()
                        client.newCall(rootPartReq).execute().close()
                    } catch (_: Exception) {}

                    // Hapus room jika kosong
                    val checkUrl = buildUrl("shared_social/voice_rooms/${room.id}/participants", token)
                    val checkResp = client.newCall(Request.Builder().url(checkUrl).get().build()).execute()
                    val checkBody = checkResp.body?.string() ?: ""
                    checkResp.close()

                    if (checkBody.isEmpty() || checkBody == "null" || checkBody == "{}" || checkBody == "[]") {
                        val delReq = Request.Builder()
                            .url(buildUrl("shared_social/voice_rooms/${room.id}", token))
                            .delete()
                            .build()
                        client.newCall(delReq).execute().close()

                        try {
                            val rootDelReq = Request.Builder()
                                .url(buildUrl("voice_rooms/${room.id}", token))
                                .delete()
                                .build()
                            client.newCall(rootDelReq).execute().close()
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }

            fetchVoiceRooms()
        }
    }

    private suspend fun fetchVoiceRoomMessages(roomId: String) = withContext(Dispatchers.IO) {
        val token = getAuthToken()
        try {
            val url = buildUrl("shared_social/voice_rooms/$roomId/messages", token)
            val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
            val body = resp.body?.string() ?: ""
            resp.close()

            if (!resp.isSuccessful || body.isEmpty() || body == "null") {
                _voiceMessages.value = emptyList()
                return@withContext
            }

            val json = JSONObject(body)
            val msgs = mutableListOf<NuxVoiceMessage>()
            for (key in json.keys()) {
                val item = json.getJSONObject(key)
                msgs.add(
                    NuxVoiceMessage(
                        id = key,
                        senderId = item.optString("senderId", ""),
                        senderName = item.optString("senderName", "Peserta"),
                        senderPhotoURL = item.optString("senderPhotoURL", ""),
                        text = item.optString("text", ""),
                        timestamp = item.optLong("timestamp", 0L),
                        isVerified = item.optBoolean("isVerified", false)
                    )
                )
            }
            msgs.sortBy { it.timestamp }
            _voiceMessages.value = msgs
        } catch (_: Exception) {}
    }

    suspend fun sendVoiceRoomMessage(text: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isSocialFeatureEnabled("voiceEnabled")) return@withContext Result.failure(Exception("Voice Room dinonaktifkan oleh administrator TG Launcher."))
        val user = getCurrentUser() ?: return@withContext Result.failure(Exception("Belum login"))
        val room = _activeVoiceRoom.value ?: return@withContext Result.failure(Exception("Not in a voice room"))
        var token = getAuthToken()

        val payload = JSONObject().apply {
            put("senderId", user.uid)
            put("senderName", user.username)
            put("senderPhotoURL", user.photoURL)
            put("text", text.trim())
            put("timestamp", System.currentTimeMillis())
        }

        try {
            var req = Request.Builder()
                .url(buildUrl("shared_social/voice_rooms/${room.id}/messages", token))
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            var resp = client.newCall(req).execute()

            // Auto retry on 401 Unauthorized
            if (resp.code == 401) {
                resp.close()
                token = getAuthToken(forceRefresh = true)
                if (!token.isNullOrBlank()) {
                    req = Request.Builder()
                        .url(buildUrl("shared_social/voice_rooms/${room.id}/messages", token))
                        .post(payload.toString().toRequestBody(JSON_MEDIA))
                        .build()
                    resp = client.newCall(req).execute()
                }
            }

            val isSuccess = resp.isSuccessful
            val code = resp.code
            resp.close()

            if (!isSuccess) {
                return@withContext Result.failure(Exception("Failed to send room message (HTTP $code)"))
            }

            fetchVoiceRoomMessages(room.id)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
