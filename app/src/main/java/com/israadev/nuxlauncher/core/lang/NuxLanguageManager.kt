package com.israadev.nuxlauncher.core.lang

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.israadev.nuxlauncher.core.settings.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStreamReader
import java.util.Locale

/**
 * Metadata model for an available language pack
 */
data class LanguageInfo(
    val code: String,
    val name: String,
    val flag: String = "🌐",
    val author: String = ""
)

/**
 * Universal JSON-based i18n Language Engine for NUX Launcher.
 * Automatically discovers all language json files in assets lang folder.
 */
object NuxLanguageManager {

    private const val TAG = "NuxLanguageManager"
    private const val LANG_FOLDER = "lang"
    const val DEFAULT_LANGUAGE_CODE = "id-ID"
    const val FALLBACK_LANGUAGE_CODE = "en-US"

    private val _availableLanguages = MutableStateFlow<List<LanguageInfo>>(emptyList())
    val availableLanguages: StateFlow<List<LanguageInfo>> = _availableLanguages.asStateFlow()

    private val _currentLanguage = MutableStateFlow(
        LanguageInfo(code = DEFAULT_LANGUAGE_CODE, name = "Bahasa Indonesia", flag = "🇮🇩")
    )
    val currentLanguage: StateFlow<LanguageInfo> = _currentLanguage.asStateFlow()

    // Version counter to trigger Compose recomposition when language switches
    private val _languageVersion = MutableStateFlow(0L)
    val languageVersion: StateFlow<Long> = _languageVersion.asStateFlow()

    private val currentStringMap = HashMap<String, String>()
    private val fallbackStringMap = HashMap<String, String>()
    private var isInitialized = false

    fun init(context: Context) {
        try {
            discoverAvailableLanguages(context)

            // 1. Load fallback dictionary (en-US)
            loadDictionaryIntoMap(context, FALLBACK_LANGUAGE_CODE, fallbackStringMap)

            // 2. Determine target language from persistent settings
            val savedLangCode = SettingsManager.settings.value.language.ifBlank { DEFAULT_LANGUAGE_CODE }
            applyLanguageInternal(context, savedLangCode)

            isInitialized = true
            Log.i(TAG, "Language system initialized. Active: ${_currentLanguage.value.code}, Available: ${_availableLanguages.value.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NuxLanguageManager", e)
        }
    }

    /**
     * Scans assets lang directory to build list of available languages
     */
    private fun discoverAvailableLanguages(context: Context) {
        val list = mutableListOf<LanguageInfo>()
        try {
            val files = context.assets.list(LANG_FOLDER) ?: emptyArray()
            for (filename in files) {
                if (filename.endsWith(".json", ignoreCase = true)) {
                    val code = filename.removeSuffix(".json")
                    val info = parseLanguageMetadata(context, filename, code)
                    list.add(info)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan assets lang folder", e)
        }

        // If scanning failed or empty, provide standard defaults
        if (list.isEmpty()) {
            list.add(LanguageInfo("id-ID", "Bahasa Indonesia", "🇮🇩"))
            list.add(LanguageInfo("en-US", "English (US)", "🇺🇸"))
        } else {
            // Sort so id-ID and en-US are on top, then alphabetical
            list.sortWith(compareBy({ it.code != "id-ID" }, { it.code != "en-US" }, { it.name }))
        }

        _availableLanguages.value = list
    }

    private fun parseLanguageMetadata(context: Context, filename: String, defaultCode: String): LanguageInfo {
        return try {
            context.assets.open("$LANG_FOLDER/$filename").use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val root = JsonParser.parseReader(reader).asJsonObject
                    if (root.has("_meta")) {
                        val meta = root.getAsJsonObject("_meta")
                        LanguageInfo(
                            code = meta.get("code")?.asString ?: defaultCode,
                            name = meta.get("name")?.asString ?: defaultCode,
                            flag = meta.get("flag")?.asString ?: "🌐",
                            author = meta.get("author")?.asString ?: ""
                        )
                    } else {
                        LanguageInfo(code = defaultCode, name = defaultCode, flag = "🌐")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading metadata from $filename", e)
            LanguageInfo(code = defaultCode, name = defaultCode, flag = "🌐")
        }
    }

    /**
     * Switches the active language dynamically at runtime
     */
    fun setLanguage(context: Context, code: String) {
        applyLanguageInternal(context, code)
        // Persist to launcher settings
        val currentSettings = SettingsManager.settings.value
        if (currentSettings.language != code) {
            SettingsManager.updateSettings(context, currentSettings.copy(language = code))
        }
    }

    private fun applyLanguageInternal(context: Context, code: String) {
        loadDictionaryIntoMap(context, code, currentStringMap)
        val info = _availableLanguages.value.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: LanguageInfo(code = code, name = code, flag = "🌐")
        _currentLanguage.value = info
        _languageVersion.value = System.currentTimeMillis()
    }

    private fun loadDictionaryIntoMap(context: Context, code: String, targetMap: HashMap<String, String>) {
        targetMap.clear()
        val filename = "$code.json"
        try {
            context.assets.open("$LANG_FOLDER/$filename").use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    val root = JsonParser.parseReader(reader).asJsonObject
                    flattenJsonObject("", root, targetMap)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load language file $filename", e)
        }
    }

    /**
     * Recursively flattens JSON hierarchy so settings -> title becomes settings.title,
     * while also storing title if it does not conflict
     */
    private fun flattenJsonObject(prefix: String, obj: JsonObject, targetMap: HashMap<String, String>) {
        for ((key, value) in obj.entrySet()) {
            if (key == "_meta") continue

            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"

            when {
                value.isJsonObject -> {
                    flattenJsonObject(fullKey, value.asJsonObject, targetMap)
                }
                value.isJsonPrimitive -> {
                    val strValue = value.asString
                    targetMap[fullKey] = strValue
                    // Also store by leaf key if not collision
                    if (!targetMap.containsKey(key)) {
                        targetMap[key] = strValue
                    }
                }
            }
        }
    }

    /**
     * Synchronous translation method
     */
    fun getString(key: String, vararg args: Any): String {
        val raw = currentStringMap[key]
            ?: fallbackStringMap[key]
            ?: key

        return if (args.isNotEmpty()) {
            try {
                String.format(Locale.getDefault(), raw, *args)
            } catch (e: Exception) {
                raw
            }
        } else {
            raw
        }
    }
}

/**
 * Composable helper function that observes language changes and returns localized string
 */
@Composable
fun tr(key: String, vararg args: Any): String {
    // Observing languageVersion triggers recomposition across all active UI trees
    val version by NuxLanguageManager.languageVersion.collectAsState()
    return if (version >= 0) {
        NuxLanguageManager.getString(key, *args)
    } else {
        NuxLanguageManager.getString(key, *args)
    }
}

/**
 * Synchronous non-composable translation helper (for toasts, event handlers, non-composable lambdas)
 */
fun trStr(key: String, vararg args: Any): String = NuxLanguageManager.getString(key, *args)

