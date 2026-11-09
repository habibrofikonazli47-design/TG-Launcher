package com.israadev.nuxlauncher.core.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Remote feature flags published by the TG Launcher admin panel. */
object RemoteLauncherConfig {
    private val _config = MutableStateFlow<JSONObject?>(null)
    val config: StateFlow<JSONObject?> = _config.asStateFlow()

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val base = NuxConfig.SERVER_BASE_URL.trimEnd('/')
        if (base.isBlank()) return@withContext false
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$base/api/app/config").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (connection.responseCode !in 200..299) return@withContext false
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            _config.value = JSONObject(body)
            true
        } catch (error: Exception) {
            Log.w("TGRemoteConfig", "Unable to refresh remote launcher configuration", error)
            false
        } finally { connection?.disconnect() }
    }

    fun isEnabled(config: JSONObject?, section: String, key: String, default: Boolean = true): Boolean =
        config?.optJSONObject(section)?.optBoolean(key, default) ?: default

    fun text(config: JSONObject?, section: String, key: String, default: String = ""): String =
        config?.optJSONObject(section)?.optString(key, default) ?: default

    fun maintenanceEnabled(config: JSONObject?): Boolean =
        config?.optJSONObject("general")?.optBoolean("maintenanceMode", false) ?: false
}