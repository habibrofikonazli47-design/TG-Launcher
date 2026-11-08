package com.israadev.nuxlauncher.core.announcement

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.israadev.nuxlauncher.core.network.NuxConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class NuxAnnouncement(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val imageUrl: String = "",
    val linkUrl: String = "",
    val enabled: Boolean = false,
    val targetPlatform: String = "all"
)

object AnnouncementManager {
    private const val TAG = "AnnouncementManager"
    private const val PREFS_NAME = "nux_announcement_prefs"
    private const val KEY_LAST_DISMISSED_ID = "last_dismissed_announcement_id"
    private const val RTDB_BASE = "https://nux-production-default-rtdb.asia-southeast1.firebasedatabase.app"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isDismissed(context: Context, id: String): Boolean {
        if (id.isBlank()) return false
        val prefs = getPrefs(context)
        val dismissedId = prefs.getString(KEY_LAST_DISMISSED_ID, null)
        return dismissedId == id
    }

    fun markDismissed(context: Context, id: String) {
        if (id.isBlank()) return
        getPrefs(context).edit().putString(KEY_LAST_DISMISSED_ID, id).apply()
    }

    /**
     * Mengambil pengumuman aktif dari Firebase Realtime DB atau API Backend.
     * Mengembalikan null jika dinonaktifkan, tidak ditargetkan untuk Android, atau error.
     */
    suspend fun fetchAnnouncement(): NuxAnnouncement? = withContext(Dispatchers.IO) {
        // 1. Coba ambil dari Firebase RTDB
        try {
            val rtdbUrls = listOf(
                "$RTDB_BASE/global/announcements.json",
                "$RTDB_BASE/settings/announcement.json"
            )
            for (url in rtdbUrls) {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body.string()
                        val ann = parseAnnouncementJson(body)
                        if (ann != null && isValidForAndroid(ann)) {
                            Log.d(TAG, "Successfully fetched announcement from Firebase: ${ann.title}")
                            return@withContext ann
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch announcement from Firebase: ${e.message}")
        }

        // 2. Fallback ke REST API Backend Keystore
        if (NuxConfig.isConfigured) {
            try {
                val apiUrl = "${NuxConfig.SERVER_BASE_URL}/api/announcement"
                val reqBuilder = Request.Builder().url(apiUrl).get()
                NuxConfig.applyAuthHeaders(reqBuilder)
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body.string()
                        val ann = parseAnnouncementJson(body)
                        if (ann != null && isValidForAndroid(ann)) {
                            Log.d(TAG, "Successfully fetched announcement from API: ${ann.title}")
                            return@withContext ann
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch announcement from API: ${e.message}")
            }
        }

        null
    }

    private fun isValidForAndroid(ann: NuxAnnouncement): Boolean {
        if (!ann.enabled) return false
        val platform = ann.targetPlatform.trim().lowercase()
        return platform.isEmpty() || platform == "all" || platform == "android"
    }

    private fun parseAnnouncementJson(jsonStr: String): NuxAnnouncement? {
        if (jsonStr.isBlank() || jsonStr == "null") return null
        return try {
            val el = JsonParser.parseString(jsonStr)
            if (!el.isJsonObject) return null
            val obj = el.asJsonObject

            val enabled = when {
                obj.has("enabled") && !obj.get("enabled").isJsonNull -> {
                    val p = obj.get("enabled")
                    if (p.isJsonPrimitive && p.asJsonPrimitive.isBoolean) p.asBoolean
                    else p.asString.equals("true", ignoreCase = true)
                }
                else -> false
            }

            fun getString(key: String): String {
                return if (obj.has(key) && !obj.get(key).isJsonNull) {
                    obj.get(key).asString
                } else ""
            }

            NuxAnnouncement(
                id = getString("id"),
                title = getString("title"),
                content = getString("content"),
                imageUrl = getString("imageUrl"),
                linkUrl = getString("linkUrl"),
                enabled = enabled,
                targetPlatform = getString("targetPlatform")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing announcement JSON", e)
            null
        }
    }
}
