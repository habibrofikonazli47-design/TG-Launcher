package com.israadev.nuxlauncher.core.renderer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.israadev.nuxlauncher.core.utils.NuxVersionUtils

/**
 * Resolves the optional cross-generation graphics profile.
 *
 * Hybrid is a version-aware API selection, not simultaneous GL/Vulkan rendering:
 * legacy Minecraft stays on OpenGL; Minecraft 26.2+ uses native Vulkan only when
 * Android reports Vulkan 1.1+ hardware support. Otherwise it safely falls back to OpenGL.
 */
object GraphicsApiManager {
    private const val HYBRID_API = "HYBRID"
    private const val VULKAN_MIN_VERSION = (1 shl 22) or (1 shl 12)

    fun hasVulkan11OrNewer(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return runCatching {
            val pm = context.packageManager
            val feature = pm.systemAvailableFeatures
                ?.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            val version = feature?.version ?: 0
            version >= VULKAN_MIN_VERSION &&
                (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
                 pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION))
        }.getOrDefault(false)
    }

    fun resolve(context: Context, requestedApi: String, minecraftVersion: String): String {
        if (!requestedApi.equals(HYBRID_API, ignoreCase = true)) {
            return requestedApi.uppercase().takeIf { it in setOf("DEFAULT", "OPENGL", "VULKAN") } ?: "DEFAULT"
        }
        val versionParts = NuxVersionUtils.parseVersionParts(minecraftVersion)
        val supportsModernApi = versionParts.size >= 2 &&
            (versionParts[0] > 26 || (versionParts[0] == 26 && versionParts[1] >= 2))
        if (!supportsModernApi) return "OPENGL"
        return if (hasVulkan11OrNewer(context)) "VULKAN" else "OPENGL"
    }
}
