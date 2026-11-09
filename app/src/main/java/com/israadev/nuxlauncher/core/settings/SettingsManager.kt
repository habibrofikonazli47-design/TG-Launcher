package com.israadev.nuxlauncher.core.settings

import android.app.ActivityManager
import android.content.Context
import com.google.gson.Gson
import com.israadev.nuxlauncher.core.models.LauncherSettings
import com.israadev.nuxlauncher.core.network.RemoteLauncherConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object SettingsManager {
    private const val ADMIN_DEFAULT_PREFS = "tg_admin_defaults"
    private const val PENDING_ADMIN_RAM_DEFAULT = "pending_admin_ram_default"
    private val gson = Gson()
    private val _settings = MutableStateFlow(LauncherSettings())
    val settings: StateFlow<LauncherSettings> = _settings.asStateFlow()

    private fun getSettingsFile(context: Context): File {
        return File(context.filesDir, "settings.json")
    }

    fun init(context: Context) {
        val file = getSettingsFile(context)
        if (file.exists()) {
            try {
                val json = file.readText()
                val loaded = gson.fromJson(json, LauncherSettings::class.java)
                if (loaded != null) {
                    val prefs = context.getSharedPreferences("nux_settings_mig", Context.MODE_PRIVATE)
                    val migratedAutoAnalyze = prefs.getBoolean("migrated_ai_auto_analyze_default_off", false)
                    val isDeprecatedModel = loaded.aiModel.contains("qwen3.8-27b", ignoreCase = true) ||
                                            loaded.aiModel.contains("nemotron-3-ultra-550b", ignoreCase = true)

                    var finalSettings = loaded
                    var needsSave = false

                    if (!migratedAutoAnalyze) {
                        prefs.edit().putBoolean("migrated_ai_auto_analyze_default_off", true).apply()
                        finalSettings = finalSettings.copy(aiAutoAnalyze = false)
                        needsSave = true
                    }

                    if (isDeprecatedModel || finalSettings.aiModel.isBlank()) {
                        finalSettings = finalSettings.copy(aiModel = com.israadev.nuxlauncher.core.crash.AICrashAnalyzer.DEFAULT_MODEL)
                        needsSave = true
                    }

                    _settings.value = finalSettings
                    if (needsSave) {
                        save(context)
                    }
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // First install: keep a one-time marker so the admin's default RAM can be applied
        // after the remote configuration has been fetched by MainActivity.
        context.getSharedPreferences(ADMIN_DEFAULT_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PENDING_ADMIN_RAM_DEFAULT, true).apply()
        val totalRam = getTotalDeviceMemoryMb(context)
        val defaultRam = when {
            totalRam >= 12000 -> 4096
            totalRam >= 8000 -> 3072
            totalRam >= 6000 -> 2560
            totalRam >= 4000 -> 2048
            else -> 1280
        }
        val initialSettings = LauncherSettings(ramMb = defaultRam)
        _settings.value = initialSettings
        save(context)
    }

    fun updateSettings(context: Context, newSettings: LauncherSettings) {
        context.getSharedPreferences(ADMIN_DEFAULT_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PENDING_ADMIN_RAM_DEFAULT, false).apply()
        _settings.value = newSettings
        save(context)
    }

    /** Apply the admin-selected default RAM once on a fresh install, without overwriting user choices. */
    fun applyAdminDefaultRam(context: Context, requestedDefaultMb: Int) {
        val prefs = context.getSharedPreferences(ADMIN_DEFAULT_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PENDING_ADMIN_RAM_DEFAULT, false)) return

        val totalRam = getTotalDeviceMemoryMb(context)
        val deviceSafeMax = (totalRam * 0.85f).toInt().coerceAtLeast(1024)
        val adminMax = RemoteLauncherConfig.config.value
            ?.optJSONObject("game")?.optInt("maxMemoryMb", 8192) ?: 8192
        val safeMax = minOf(deviceSafeMax, adminMax.coerceAtLeast(512))
        val chosenRam = requestedDefaultMb.coerceIn(512, safeMax)

        _settings.value = _settings.value.copy(ramMb = chosenRam)
        save(context)
        prefs.edit().putBoolean(PENDING_ADMIN_RAM_DEFAULT, false).apply()
    }

    fun resetToDefaults(context: Context) {
        val totalRam = getTotalDeviceMemoryMb(context)
        val deviceDefault = when {
            totalRam >= 8000 -> 3072
            totalRam >= 4000 -> 2048
            else -> 1280
        }
        val adminDefault = RemoteLauncherConfig.config.value
            ?.optJSONObject("game")?.optInt("defaultMemoryMb", deviceDefault) ?: deviceDefault
        val adminMax = RemoteLauncherConfig.config.value
            ?.optJSONObject("game")?.optInt("maxMemoryMb", 8192) ?: 8192
        val safeMax = minOf((totalRam * 0.85f).toInt().coerceAtLeast(1024), adminMax.coerceAtLeast(512))
        val defaultSettings = LauncherSettings(ramMb = adminDefault.coerceIn(512, safeMax))
        context.getSharedPreferences(ADMIN_DEFAULT_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(PENDING_ADMIN_RAM_DEFAULT, false).apply()
        _settings.value = defaultSettings
        save(context)
    }

    private fun save(context: Context) {
        try {
            val file = getSettingsFile(context)
            file.writeText(gson.toJson(_settings.value))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getTotalDeviceMemoryMb(context: Context): Int {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 4096
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            (memInfo.totalMem / (1024 * 1024)).toInt()
        } catch (_: Throwable) {
            4096
        }
    }

    fun getAvailableDeviceMemoryMb(context: Context): Int {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 2048
            val memInfo = ActivityManager.MemoryInfo()
            actManager.getMemoryInfo(memInfo)
            (memInfo.availMem / (1024 * 1024)).toInt()
        } catch (_: Throwable) {
            2048
        }
    }

    fun getCpuCoreCount(): Int {
        return Runtime.getRuntime().availableProcessors()
    }
}
