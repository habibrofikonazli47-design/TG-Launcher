package com.israadev.nuxlauncher.core.auth

import com.israadev.nuxlauncher.BuildConfig

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.israadev.nuxlauncher.core.account.AccountManager
import com.israadev.nuxlauncher.core.network.NuxConfig

private fun JsonElement?.asStringOrNull(): String? {
    return if (this != null && !this.isJsonNull) {
        try { this.asString } catch (_: Exception) { null }
    } else null
}

private fun JsonElement?.asLongOrNull(): Long? {
    return if (this != null && !this.isJsonNull) {
        try { this.asLong } catch (_: Exception) { null }
    } else null
}

private fun JsonElement?.asBooleanOrDefault(default: Boolean = false): Boolean {
    return if (this != null && !this.isJsonNull) {
        try { this.asBoolean } catch (_: Exception) { default }
    } else default
}

private fun JsonElement?.asIntOrDefault(default: Int = 0): Int {
    return if (this != null && !this.isJsonNull) {
        try { this.asInt } catch (_: Exception) { default }
    } else default
}

data class AuthUser(
    val uid: String = "",
    val email: String = "",
    val username: String = "",
    val photoURL: String = "",
    val platform: String = "android",
    val isActivated: Boolean = false,
    val tier: String = "unactivated",
    val activatedAt: Long? = null,
    val expiresAt: String? = null,
    val redeemedKey: String? = null,
    val features: List<String> = emptyList(),
    val limits: Map<String, Int> = emptyMap(),
    val idToken: String = "",
    val refreshToken: String = ""
)

data class LoginResult(
    val success: Boolean,
    val isActivated: Boolean,
    val user: AuthUser,
    val message: String? = null
)

data class RegisterResult(
    val success: Boolean,
    val isActivated: Boolean,
    val user: AuthUser,
    val message: String? = null
)

data class ActivateResult(
    val success: Boolean,
    val isActivated: Boolean,
    val tier: String = "lifetime",
    val expiresAt: String = "lifetime",
    val message: String = ""
)

data class SubscriptionInfo(
    val isActivated: Boolean = false,
    val tier: String = "unactivated",
    val activatedAt: Long? = null,
    val expiresAt: String? = null,
    val redeemedKey: String? = null
)

data class AiQuotaInfo(
    val date: String = "",
    val usedCount: Int = 0,
    val remainingQuota: Int = 5,
    val dailyLimit: Int = 5,
    val isUnlimited: Boolean = false,
    val allowed: Boolean = true
)

object AuthService {
    private const val ENTITLEMENTS_BASE_URL = "https://tg-launcher-admin-sigma.vercel.app"
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Endpoint backend TG Launcher (diinjeksi dari local.properties via NuxConfig)
    // Authentication and Premium activation must always reach the canonical Admin API first.
    // SERVER_BASE_URL may be unset in older/local builds, so it cannot be the only auth host.
    private val CANDIDATE_BASES: List<String>
        get() = (listOf(ENTITLEMENTS_BASE_URL) + NuxConfig.CANDIDATE_BASES).distinct()

    suspend fun postApi(endpoint: String, jsonBody: String, bearerToken: String? = null): Result<String> = withContext(Dispatchers.IO) {
        if (CANDIDATE_BASES.isEmpty()) {
            return@withContext Result.failure(Exception("Server autentikasi belum dikonfigurasi. Pastikan 'nux.server.url' diisi di local.properties."))
        }
        var lastError: Exception? = null

        for (base in CANDIDATE_BASES) {
            try {
                val url = "$base$endpoint"
                val body = jsonBody.toRequestBody(jsonMediaType)
                val reqBuilder = Request.Builder()
                    .url(url)
                    .post(body)
                if (!bearerToken.isNullOrBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $bearerToken")
                }
                NuxConfig.applyAuthHeaders(reqBuilder, includeBearerIfEmpty = bearerToken.isNullOrBlank())
                val request = reqBuilder.build()

                val response = client.newCall(request).execute()
                val responseBody = response.body.string()

                // Cek pesan error dari backend JSON terlebih dahulu (baik HTTP 200 maupun status kode lainnya)
                try {
                    val errorJson = gson.fromJson(responseBody, JsonObject::class.java)
                    if (errorJson != null) {
                        val remSec = errorJson.get("remainingSeconds").asIntOrDefault(0)
                        if (remSec > 0) {
                            OtpCooldownManager.markOtpSent(null, remSec)
                        }
                        val errorStr = errorJson.get("error").asStringOrNull()
                        if (!errorStr.isNullOrBlank()) {
                            return@withContext Result.failure(Exception(errorStr))
                        }
                        val isSuccess = errorJson.get("success").asBooleanOrDefault(true)
                        if (!isSuccess) {
                            val msg = errorJson.get("message").asStringOrNull() ?: "Request rejected by server."
                            return@withContext Result.failure(Exception(msg))
                        }
                    }
                } catch (_: Exception) {}

                if (response.isSuccessful) {
                    return@withContext Result.success(responseBody)
                }

                // Tangani kode error gateway Cloudflare (502, 503, 504, 520, 521, 522, 524, 530)
                if (response.code in listOf(502, 503, 504, 520, 521, 522, 524, 530)) {
                    return@withContext Result.failure(
                        Exception("Server is busy or experiencing gateway issues (code: ${response.code}). Please try again shortly.")
                    )
                }

                lastError = Exception("Server merespon kode: ${response.code}")
            } catch (e: Exception) {
                lastError = Exception(
                    when (e) {
                        is java.net.SocketTimeoutException -> "Koneksi ke server timeout. Periksa koneksi internet kamu dan coba lagi."
                        is java.net.UnknownHostException -> "Cannot connect to authentication server. Please check your internet connection."
                        else -> e.message ?: "Failed to connect to authentication server"
                    }
                )
            }
        }

        Result.failure(lastError ?: Exception("Failed to reach authentication server"))
    }

    /**
     * Panggilan synchronous / blocking cepat (dengan timeout pendek) untuk memastikan
     * presence status (seperti offline / online) sempat terkirim sebelum process di-kill.
     */
    fun postApiSync(endpoint: String, jsonBody: String, timeoutSec: Long = 2): Result<String> {
        if (CANDIDATE_BASES.isEmpty()) return Result.failure(Exception("No server base"))
        val quickClient = client.newBuilder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .writeTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        for (base in CANDIDATE_BASES) {
            try {
                val url = "$base$endpoint"
                val body = jsonBody.toRequestBody(jsonMediaType)
                val reqBuilder = Request.Builder().url(url).post(body)
                NuxConfig.applyAuthHeaders(reqBuilder, includeBearerIfEmpty = true)
                val response = quickClient.newCall(reqBuilder.build()).execute()
                val bodyStr = response.body.string()
                response.close()
                return Result.success(bodyStr)
            } catch (_: Exception) {
                // Coba basis berikutnya
            }
        }
        return Result.failure(Exception("postApiSync failed"))
    }

    suspend fun getApi(endpoint: String, bearerToken: String? = null): Result<String> = withContext(Dispatchers.IO) {
        if (CANDIDATE_BASES.isEmpty()) {
            return@withContext Result.failure(Exception("Server autentikasi belum dikonfigurasi. Pastikan 'nux.server.url' diisi di local.properties."))
        }
        var lastError: Exception? = null

        for (base in CANDIDATE_BASES) {
            try {
                val url = "$base$endpoint"
                val reqBuilder = Request.Builder()
                    .url(url)
                    .get()
                if (!bearerToken.isNullOrBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $bearerToken")
                }
                NuxConfig.applyAuthHeaders(reqBuilder, includeBearerIfEmpty = bearerToken.isNullOrBlank())
                val request = reqBuilder.build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                try {
                    val json = gson.fromJson(responseBody, JsonObject::class.java)
                    if (json != null) {
                        val errorStr = json.get("error").asStringOrNull()
                        val isSuccess = json.get("success").asBooleanOrDefault(true)
                        if (!isSuccess && !errorStr.isNullOrBlank()) {
                            return@withContext Result.failure(Exception(errorStr))
                        }
                    }
                } catch (_: Exception) {}

                if (response.isSuccessful) {
                    return@withContext Result.success(responseBody)
                }

                lastError = Exception("Server merespon kode: ${response.code}")
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Failed to reach authentication server"))
    }

    /**
     * Sinkronkan paket Free/Premium dari database TG Launcher Admin.
     * Token Firebase diverifikasi ulang oleh server; jika layanan entitlement gagal,
     * sesi login lama tetap dipertahankan dan error tidak memblokir launcher.
     */
    suspend fun syncEntitlements(user: AuthUser): AuthUser = withContext(Dispatchers.IO) {
        if (user.idToken.isBlank()) return@withContext user
        try {
            val request = Request.Builder()
                .url("$ENTITLEMENTS_BASE_URL/api/app/entitlements")
                .header("Authorization", "Bearer ${user.idToken}")
                .header("X-TG-App-Version", BuildConfig.VERSION_NAME)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 403 || response.code == 426) {
                        return@withContext user.copy(isActivated = false, tier = "blocked", features = emptyList(), limits = emptyMap())
                    }
                    if (response.code == 401) {
                        return@withContext user.copy(isActivated = false, tier = "free", features = listOf("base_launcher"), limits = mapOf("instances" to 3, "accounts" to 2, "ai_daily" to 5))
                    }
                    return@withContext user
                }
                val obj = gson.fromJson(body, JsonObject::class.java) ?: return@withContext user
                if (!obj.get("success").asBooleanOrDefault(false)) return@withContext user
                val features = try {
                    obj.getAsJsonArray("features")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null } ?: user.features
                } catch (_: Exception) { user.features }
                val limits = try {
                    obj.getAsJsonObject("limits")?.entrySet()?.associate { entry ->
                        entry.key to try { entry.value.asInt } catch (_: Exception) { -1 }
                    } ?: user.limits
                } catch (_: Exception) { user.limits }
                user.copy(
                    isActivated = obj.get("isActivated").asBooleanOrDefault(user.isActivated),
                    tier = obj.get("plan").asStringOrNull() ?: user.tier,
                    expiresAt = obj.get("expiresAt").asStringOrNull(),
                    features = features,
                    limits = limits
                )
            }
        } catch (_: Exception) {
            user
        }
    }

    /**
     * Mengambil status kuota harian AI analisis log dari database server.
     */
    suspend fun fetchAiQuota(uid: String): Result<AiQuotaInfo> {
        val encodedUid = try { java.net.URLEncoder.encode(uid.trim(), "UTF-8") } catch (_: Exception) { uid.trim() }
        val endpoint = "/api/auth/android/ai-quota?uid=$encodedUid"
        return getApi(endpoint, bearerToken = AccountManager.getActiveUser()?.idToken).mapCatching { jsonStr ->
            val obj = gson.fromJson(jsonStr, JsonObject::class.java)
            AiQuotaInfo(
                date = obj.get("date").asStringOrNull() ?: "",
                usedCount = obj.get("usedCount").asIntOrDefault(0),
                remainingQuota = obj.get("remainingQuota").asIntOrDefault(5),
                dailyLimit = obj.get("dailyLimit").asIntOrDefault(5),
                isUnlimited = obj.get("isUnlimited").asBooleanOrDefault(false),
                allowed = obj.get("allowed").asBooleanOrDefault(true)
            )
        }
    }

    /**
     * Mengonsumsi 1 kuota harian AI analisis log di database server.
     */
    suspend fun consumeAiQuota(uid: String): Result<AiQuotaInfo> {
        val payload = JsonObject().apply {
            addProperty("uid", uid.trim())
        }
        return postApi("/api/auth/android/ai-quota", payload.toString(), bearerToken = AccountManager.getActiveUser()?.idToken).mapCatching { jsonStr ->
            val obj = gson.fromJson(jsonStr, JsonObject::class.java)
            AiQuotaInfo(
                date = obj.get("date").asStringOrNull() ?: "",
                usedCount = obj.get("usedCount").asIntOrDefault(0),
                remainingQuota = obj.get("remainingQuota").asIntOrDefault(4),
                dailyLimit = obj.get("dailyLimit").asIntOrDefault(5),
                isUnlimited = obj.get("isUnlimited").asBooleanOrDefault(false),
                allowed = obj.get("allowed").asBooleanOrDefault(true)
            )
        }
    }

    /**
     * 1. Kirim Kode OTP Verifikasi (Tipe: "register" atau "reset_password")
     */
    suspend fun sendOtp(email: String, username: String = "", type: String = "register"): Result<String> {
        val payload = JsonObject().apply {
            addProperty("email", email.trim().lowercase())
            addProperty("username", username.trim())
            addProperty("type", type)
        }
        val result = postApi("/api/auth/send-otp", payload.toString())
        return result.mapCatching { json ->
            val obj = gson.fromJson(json, JsonObject::class.java)
            obj.get("message").asStringOrNull() ?: "Kode verifikasi 6-digit telah dikirim ke email kamu."
        }
    }

    /**
     * 2. Registrasi Akun Android Baru (Langsung tanpa OTP)
     */
    suspend fun register(
        email: String,
        username: String,
        password: String
    ): Result<RegisterResult> {
        val payload = JsonObject().apply {
            addProperty("email", email.trim().lowercase())
            addProperty("username", username.trim())
            addProperty("password", password)
        }

        val result = postApi("/api/auth/android/register", payload.toString())
        return result.mapCatching { json ->
            val obj = gson.fromJson(json, JsonObject::class.java)
            val userObj = if (obj.has("user") && obj.get("user")?.isJsonObject == true) obj.getAsJsonObject("user") else null
            val isActivated = obj.get("isActivated").asBooleanOrDefault(false)
            val user = AuthUser(
                uid = userObj?.get("uid").asStringOrNull() ?: "",
                email = userObj?.get("email").asStringOrNull() ?: email,
                username = userObj?.get("username").asStringOrNull() ?: username,
                photoURL = userObj?.get("photoURL").asStringOrNull() ?: "",
                platform = "android",
                isActivated = isActivated,
                tier = userObj?.get("tier").asStringOrNull() ?: "free",
                expiresAt = userObj?.get("expiresAt").asStringOrNull(),
                idToken = obj.get("idToken").asStringOrNull() ?: "",
                refreshToken = obj.get("refreshToken").asStringOrNull() ?: ""
            )
            RegisterResult(
                success = obj.get("success").asBooleanOrDefault(true),
                isActivated = user.isActivated,
                user = user,
                message = obj.get("message").asStringOrNull()
            )
        }
    }

    /**
     * 3. Login Akun Android (Mengecek platform & status aktivasi)
     */
    suspend fun login(email: String, password: String): Result<LoginResult> {
        val payload = JsonObject().apply {
            addProperty("email", email.trim().lowercase())
            addProperty("password", password)
        }

        val result = postApi("/api/auth/android/login", payload.toString())
        return result.mapCatching { json ->
            val obj = gson.fromJson(json, JsonObject::class.java)
            val isActivated = obj.get("isActivated").asBooleanOrDefault(false)
            val userObj = if (obj.has("user") && obj.get("user")?.isJsonObject == true) obj.getAsJsonObject("user") else null
            val idToken = obj.get("idToken").asStringOrNull() ?: ""
            val refreshToken = obj.get("refreshToken").asStringOrNull() ?: ""
            val user = AuthUser(
                uid = userObj?.get("uid").asStringOrNull() ?: "",
                email = userObj?.get("email").asStringOrNull() ?: email,
                username = userObj?.get("username").asStringOrNull() ?: email.substringBefore("@"),
                photoURL = userObj?.get("photoURL").asStringOrNull() ?: "",
                platform = "android",
                isActivated = isActivated,
                tier = userObj?.get("tier").asStringOrNull() ?: "unactivated",
                activatedAt = userObj?.get("activatedAt").asLongOrNull(),
                expiresAt = userObj?.get("expiresAt").asStringOrNull(),
                redeemedKey = userObj?.get("redeemedKey").asStringOrNull(),
                idToken = idToken,
                refreshToken = refreshToken
            )
            LoginResult(
                success = obj.get("success").asBooleanOrDefault(true),
                isActivated = isActivated,
                user = user,
                message = obj.get("message").asStringOrNull()
            )
        }
    }

    /**
     * Refresh Firebase ID Token menggunakan Refresh Token
     */
    suspend fun refreshIdToken(refreshToken: String): String? = withContext(Dispatchers.IO) {
        if (refreshToken.isBlank()) return@withContext null
        try {
            val apiKey = "AIzaSyDIqvaTHjuwNboBXcVDoY1jJYntS0ECsJ0"
            val refreshUrl = "https://securetoken.googleapis.com/v1/token?key=$apiKey"
            val formBody = okhttp3.FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()
            val req = Request.Builder()
                .url(refreshUrl)
                .post(formBody)
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (resp.isSuccessful && body.isNotEmpty()) {
                val json = gson.fromJson(body, JsonObject::class.java)
                val idToken = json?.get("id_token")?.asString
                val rotatedRefreshToken = json?.get("refresh_token")?.asString
                if (!idToken.isNullOrBlank()) {
                    // Firebase rotates refresh tokens. Persist the NEW refresh token
                    // together with the ID token; reusing the old token causes the
                    // next refresh to fail and Premium activation to report an invalid session.
                    AccountManager.updateTokens(
                        idToken,
                        rotatedRefreshToken?.takeIf { it.isNotBlank() } ?: refreshToken
                    )
                    return@withContext idToken
                }
            }
        } catch (_: Exception) {}
        null
    }

    /**
     * Dapatkan ID Token & Refresh Token baru dari server backend berdasarkan UID pengguna
     */
    suspend fun requestSessionToken(uid: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext null
        val activeUser = AccountManager.getActiveUser()
        val payload = JsonObject().apply {
            addProperty("uid", uid.trim())
            if (!activeUser?.refreshToken.isNullOrBlank()) {
                addProperty("refreshToken", activeUser?.refreshToken)
            }
        }
        val res = postApi("/api/auth/android/session-token", payload.toString(), bearerToken = activeUser?.idToken)
        res.getOrNull()?.let { json ->
            try {
                val obj = gson.fromJson(json, JsonObject::class.java)
                val idToken = obj.get("idToken")?.asString
                val refreshToken = obj.get("refreshToken")?.asString ?: activeUser?.refreshToken
                if (!idToken.isNullOrBlank()) {
                    return@withContext Pair(idToken, refreshToken ?: "")
                }
            } catch (_: Exception) {}
        }
        null
    }


    /**
     * 4. Aktivasi Akun Menggunakan Key Lisensi
     */
    suspend fun activateLicense(
        uid: String,
        licenseKey: String,
        userToken: String? = null,
        context: Context
    ): Result<ActivateResult> = withContext(Dispatchers.IO) {
        val cleanKey = licenseKey.trim().uppercase()
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        if (androidId.isBlank()) return@withContext Result.failure(Exception("Identitas perangkat tidak tersedia."))
        val deviceId = MessageDigest.getInstance("SHA-256")
            .digest("${BuildConfig.APPLICATION_ID}:$androidId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(120)
        val payload = JsonObject().apply {
            addProperty("uid", uid.trim())
            addProperty("licenseKey", cleanKey)
            addProperty("deviceId", deviceId)
            addProperty("deviceName", deviceName)
        }

        // Resolve a fresh Firebase session before activation. The active account can
        // outlive its short-lived ID token, so never rely on the token captured by the UI.
        val activeUser = AccountManager.getActiveUser()
        if (activeUser != null && activeUser.uid != uid) {
            return@withContext Result.failure(Exception("Akun aktif berubah. Silakan buka ulang sesi akun."))
        }

        suspend fun obtainFreshActivationToken(): String? {
            val latest = AccountManager.getActiveUser()
            val latestRefreshToken = latest?.refreshToken.orEmpty()
            if (latestRefreshToken.isNotBlank()) {
                val refreshed = refreshIdToken(latestRefreshToken)
                if (!refreshed.isNullOrBlank()) return refreshed
            }

            // Also ask the auth backend for a new session when the local refresh token
            // is absent/expired. This covers older persisted sessions and rotated tokens.
            val serverSession = requestSessionToken(uid)
            if (serverSession != null && serverSession.first.isNotBlank()) {
                AccountManager.updateTokens(
                    serverSession.first,
                    serverSession.second.ifBlank { latestRefreshToken }
                )
                return serverSession.first
            }
            return null
        }

        val suppliedToken = userToken?.takeIf { it.isNotBlank() }
        val storedToken = activeUser?.idToken?.takeIf { it.isNotBlank() }
        val freshToken = obtainFreshActivationToken()
        var activeToken = freshToken ?: storedToken ?: suppliedToken
        if (activeToken.isNullOrBlank()) {
            return@withContext Result.failure(Exception("Sesi akun tidak tersedia. Silakan login ulang."))
        }

        // First activation attempt with the newest available token.
        val payloadJson = payload.toString()
        var result = postApi("/api/auth/android/activate", payloadJson, bearerToken = activeToken)

        // If the API specifically rejects the Firebase session, refresh/request a new
        // token once and retry. Do not retry invalid keys, device limits, or other errors.
        val firstError = result.exceptionOrNull()?.message.orEmpty()
        if (firstError.contains("Sesi akun tidak valid", ignoreCase = true) ||
            firstError.contains("Server merespon kode: 401", ignoreCase = true)) {
            val retryToken = obtainFreshActivationToken()
            if (!retryToken.isNullOrBlank() && retryToken != activeToken) {
                activeToken = retryToken
                result = postApi("/api/auth/android/activate", payloadJson, bearerToken = activeToken)
            }
        }

        result.fold(
            onSuccess = { json ->
                try {
                    val obj = gson.fromJson(json, JsonObject::class.java)
                    val isSuccess = obj.get("success").asBooleanOrDefault(false)
                    val isActivated = obj.get("isActivated").asBooleanOrDefault(false)
                    val errorMsg = obj.get("error").asStringOrNull()
                        ?: if (!isSuccess) obj.get("message").asStringOrNull() else null

                    if (!isSuccess || !isActivated || errorMsg != null) {
                        return@withContext Result.failure(
                            Exception(errorMsg ?: "License key is invalid or already registered to another account.")
                        )
                    }

                    val tier = obj.get("tier").asStringOrNull() ?: "lifetime"
                    val expiresAt = obj.get("expiresAt").asStringOrNull() ?: "lifetime"
                    val msg = obj.get("message").asStringOrNull() ?: "Activation successful!"
                    Result.success(
                        ActivateResult(
                            success = true,
                            isActivated = true,
                            tier = tier,
                            expiresAt = expiresAt,
                            message = msg
                        )
                    )
                } catch (e: Exception) {
                    Result.failure(e)
                }
            },
            onFailure = { apiErr ->
                val message = apiErr.message.orEmpty()
                if (message.contains("Sesi akun tidak valid", ignoreCase = true) ||
                    message.contains("Server merespon kode: 401", ignoreCase = true)) {
                    Result.failure(Exception("Sesi login sudah kedaluwarsa dan server belum dapat memperbaruinya. Silakan keluar lalu login kembali, kemudian aktifkan Premium lagi."))
                } else {
                    Result.failure(apiErr)
                }
            }
        )
    }

    /**
     * Dapatkan rincian paket langganan aktif dari backend TG Launcher
     */
    suspend fun fetchSubscriptionInfo(uid: String, idToken: String? = null): SubscriptionInfo? = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext null
        val activeUser = AccountManager.getActiveUser()
        if (activeUser == null || activeUser.uid != uid) return@withContext null
        val synced = syncEntitlements(activeUser.copy(idToken = idToken?.takeIf { it.isNotBlank() } ?: activeUser.idToken))
        SubscriptionInfo(
            isActivated = synced.isActivated,
            tier = synced.tier,
            activatedAt = synced.activatedAt,
            expiresAt = synced.expiresAt,
            redeemedKey = synced.redeemedKey
        )
    }

    /**
     * 5. Kirim Link Reset Password Menggunakan Layanan Email Bawaan Firebase
     */
    suspend fun sendPasswordResetEmail(email: String): Result<String> {
        val payload = JsonObject().apply {
            addProperty("email", email.trim().lowercase())
        }

        val result = postApi("/api/auth/reset-password", payload.toString())
        return result.mapCatching { json ->
            val obj = gson.fromJson(json, JsonObject::class.java)
            obj.get("message")?.asString
                ?: "Password reset link has been sent to your email. Please check your Inbox or SPAM folder."
        }
    }

    suspend fun resetPassword(email: String, code: String = "", newPassword: String = ""): Result<String> {
        return sendPasswordResetEmail(email)
    }

    /**
     * 6. Perbarui Profil Akun (Username & Foto Profil)
     */
    suspend fun updateProfile(
        uid: String,
        newUsername: String? = null,
        photoURL: String? = null
    ): Result<AuthUser> {
        val payload = JsonObject().apply {
            addProperty("uid", uid.trim())
            if (newUsername != null) addProperty("newUsername", newUsername.trim())
            if (photoURL != null) addProperty("photoURL", photoURL.trim())
        }

        val activeUser = AccountManager.getActiveUser()
        var activeToken = activeUser?.idToken
        val refreshToken = activeUser?.refreshToken
        if (!refreshToken.isNullOrBlank()) {
            val refreshed = refreshIdToken(refreshToken)
            if (!refreshed.isNullOrBlank()) {
                activeToken = refreshed
            }
        }

        val result = postApi("/api/auth/android/update-profile", payload.toString(), bearerToken = activeToken)
        return result.mapCatching { json ->
            val obj = gson.fromJson(json, JsonObject::class.java)
            val userObj = obj.getAsJsonObject("user")
            val updatedUser = AuthUser(
                uid = userObj?.get("uid")?.asString ?: uid,
                username = userObj?.get("username")?.asString ?: (newUsername ?: ""),
                photoURL = userObj?.get("photoURL")?.asString ?: (photoURL ?: "")
            )
            // Synchronize with shared_social public profile
            com.israadev.nuxlauncher.core.social.NuxSocialManager.syncProfileToPublic()
            updatedUser
        }
    }

    /**
     * 7. Unggah Foto Profil (Multi-tier: Update-Server -> ImgBB Backup)
     */
    suspend fun uploadProfileImage(context: Context, imageUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bytes: ByteArray = context.contentResolver.openInputStream(imageUri)?.use { input ->
                val originalBitmap = BitmapFactory.decodeStream(input)
                if (originalBitmap != null) {
                    val maxDimension = 1024
                    val width = originalBitmap.width
                    val height = originalBitmap.height
                    val scaledBitmap = if (width > maxDimension || height > maxDimension) {
                        val ratio = width.toFloat() / height.toFloat()
                        val newWidth = if (width >= height) maxDimension else (maxDimension * ratio).toInt()
                        val newHeight = if (height > width) maxDimension else (maxDimension / ratio).toInt()
                        Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
                    } else {
                        originalBitmap
                    }
                    val baos = ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                    baos.toByteArray()
                } else {
                    null
                }
            } ?: return@withContext Result.failure(Exception("Failed to read image file from gallery"))

            val requestBody = bytes.toRequestBody("image/jpeg".toMediaType())

            // 1. Upload ke Server Produksi NUX jika dikonfigurasi
            val uploadServerUrl = NuxConfig.UPLOAD_IMAGE_URL
            if (uploadServerUrl.isNotBlank()) {
                try {
                    val multipartBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("image", "avatar_${System.currentTimeMillis()}.jpg", requestBody)
                        .build()

                    val reqBuilder = Request.Builder()
                        .url(uploadServerUrl)
                        .post(multipartBody)
                    NuxConfig.applyAuthHeaders(reqBuilder)
                    val req = reqBuilder.build()

                    client.newCall(req).execute().use { resp ->
                        val respStr = resp.body?.string() ?: ""
                        if (resp.isSuccessful) {
                            val json = gson.fromJson(respStr, JsonObject::class.java)
                            val url = json?.get("url")?.asString
                            if (!url.isNullOrEmpty()) {
                                return@withContext Result.success(url)
                            }
                        } else {
                            android.util.Log.w("AuthService", "Server upload returned ${resp.code}: $respStr, falling back to ImgBB")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AuthService", "Server upload failed: ${e.message}, falling back to ImgBB")
                }
            }

            // Fallback darurat ke ImgBB jika server utama tidak aktif, menolak akses (401), atau gagal
            try {
                val imgbbBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "avatar_${System.currentTimeMillis()}.jpg", requestBody)
                    .build()

                val imgbbReq = Request.Builder()
                    .url("https://api.imgbb.com/1/upload?key=c3257ef84dcc0d3a9e26f50c15579f06")
                    .post(imgbbBody)
                    .build()

                client.newCall(imgbbReq).execute().use { imgbbResp ->
                    val imgbbStr = imgbbResp.body?.string() ?: ""
                    if (imgbbResp.isSuccessful) {
                        val json = gson.fromJson(imgbbStr, JsonObject::class.java)
                        val dataObj = json?.getAsJsonObject("data")
                        val url = dataObj?.get("url")?.asString ?: dataObj?.get("display_url")?.asString
                        if (!url.isNullOrEmpty()) {
                            return@withContext Result.success(url)
                        }
                    } else {
                        android.util.Log.w("AuthService", "ImgBB returned ${imgbbResp.code}: $imgbbStr")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("AuthService", "ImgBB upload error: ${e.message}")
            }

            return@withContext Result.failure(Exception("Failed to upload profile picture. Please check your internet connection."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
