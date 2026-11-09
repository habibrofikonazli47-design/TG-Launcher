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
    private const val PREFS_NAME = "tg_announcement_prefs"
    private const val KEY_LAST_DISMISSED_ID = "last_dismissed_announcement_id"

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

    /** Mengambil pengumuman dari pengaturan appearance yang dikelola admin TG Launcher. */
    suspend fun fetchAnnouncement(): NuxAnnouncement? = withContext(Dispatchers.IO) {
        if (!NuxConfig.isConfigured) return@withContext null
        try {
            val request = Request.Builder().url("${NuxConfig.SERVER_BASE_URL}/api/app/config").get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val root = JsonParser.parseString(response.body.string()).asJsonObject
                val appearance = root.getAsJsonObject("appearance") ?: return@withContext null
                val title = appearance.get("announcementTitle")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val content = appearance.get("announcementContent")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val item = JsonObject().apply {
                    addProperty("id", (title + "|" + content).hashCode().toString())
                    addProperty("title", title)
                    addProperty("content", content)
                    addProperty("imageUrl", appearance.get("announcementImageUrl")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
                    addProperty("linkUrl", appearance.get("announcementLinkUrl")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
                    addProperty("enabled", appearance.get("announcementEnabled")?.takeUnless { it.isJsonNull }?.asBoolean ?: false)
                    addProperty("targetPlatform", appearance.get("announcementTarget")?.takeUnless { it.isJsonNull }?.asString ?: "all")
                }
                parseAnnouncementJson(item.toString())?.takeIf { isValidForAndroid(it) }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to fetch TG Launcher announcement: ${error.message}")
            null
        }
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
