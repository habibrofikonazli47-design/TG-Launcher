package com.israadev.nuxlauncher.core.device

import android.app.ActivityManager
import android.content.Context
import com.israadev.nuxlauncher.core.models.Instance
import com.israadev.nuxlauncher.core.mods.NuxModManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceModRecipe(
    val id: String,
    val title: String,
    val description: String,
    val minimumRamGb: Int,
    val projectIds: List<Pair<String, String>>
)

data class DeviceModProfile(
    val totalRamGb: Int,
    val availableRamGb: Int,
    val gpuName: String,
    val recipe: DeviceModRecipe
)

/**
 * Conservative, client-side performance recipes. Renderer replacement mods are
 * intentionally excluded: the launcher must not silently install Sodium/Iris on
 * Android where the active graphics backend may not support their combination.
 */
object DeviceModRecipeManager {
    private val lowMemory = DeviceModRecipe(
        id = "android_light",
        title = "Android Hemat RAM",
        description = "Paket ringan untuk HP dengan RAM 4 GB atau kurang. Fokus pada optimasi sisi server dan penggunaan memori.",
        minimumRamGb = 2,
        projectIds = listOf(
            "Lithium" to "gvQqBUqZ",
            "FerriteCore" to "uXXizFIs",
            "Dynamic FPS" to "LQ3K71Q1"
        )
    )

    private val balanced = DeviceModRecipe(
        id = "android_balanced",
        title = "Android Seimbang",
        description = "Untuk HP RAM 6 GB. Mengurangi beban memori dan pekerjaan latar tanpa mengganti renderer.",
        minimumRamGb = 6,
        projectIds = listOf(
            "Lithium" to "gvQqBUqZ",
            "FerriteCore" to "uXXizFIs",
            "Dynamic FPS" to "LQ3K71Q1",
            "ImmediatelyFast" to "5ZwdcRci"
        )
    )

    private val highMemory = DeviceModRecipe(
        id = "android_plus",
        title = "Android Performa Plus",
        description = "Untuk HP RAM 8 GB ke atas. Berisi optimasi memori dan performa tambahan; tetap tanpa mod pengganti renderer.",
        minimumRamGb = 8,
        projectIds = listOf(
            "Lithium" to "gvQqBUqZ",
            "FerriteCore" to "uXXizFIs",
            "Dynamic FPS" to "LQ3K71Q1",
            "ImmediatelyFast" to "5ZwdcRci",
            "ModernFix" to "nmDcB62a"
        )
    )

    suspend fun detect(context: Context): DeviceModProfile = withContext(Dispatchers.IO) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val totalBytes = memoryInfo.totalMem.coerceAtLeast(1L)
        val availableBytes = memoryInfo.availMem.coerceAtLeast(0L)
        val totalGb = (totalBytes / (1024.0 * 1024.0 * 1024.0)).toInt().coerceAtLeast(1)
        val availableGb = (availableBytes / (1024.0 * 1024.0 * 1024.0)).toInt()
        val gpu = GpuPerformanceProfile.detect()?.renderer ?: "GPU tidak terdeteksi"
        val isConservativeGpu = gpu.contains("Mali-G57", ignoreCase = true) ||
            gpu.contains("Mali G57", ignoreCase = true)
        val recipe = when {
            isConservativeGpu -> lowMemory
            totalGb >= 8 -> highMemory
            totalGb >= 6 -> balanced
            else -> lowMemory
        }
        DeviceModProfile(totalGb, availableGb, gpu, recipe)
    }

    suspend fun install(
        context: Context,
        instance: Instance,
        recipe: DeviceModRecipe,
        onProgress: (String) -> Unit = {}
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            if (!instance.loader.equals("fabric", ignoreCase = true)) {
                return@withContext Result.failure(
                    IllegalStateException("Racikan otomatis saat ini disiapkan untuk Fabric. Pilih instance Fabric agar mod tidak salah dipasang.")
                )
            }
            val installed = NuxModManager.getInstalledItems(context, instance, "mods")
            val installedProjectIds = installed.map { it.id.lowercase() }.toSet()
            val installedNames = installed.map { it.name.lowercase() }.toSet()
            val completed = mutableListOf<String>()
            val skipped = mutableListOf<String>()

            recipe.projectIds.forEach { (name, projectId) ->
                if (projectId.lowercase() in installedProjectIds || name.lowercase() in installedNames) {
                    skipped += name
                    return@forEach
                }

                onProgress("Memeriksa kompatibilitas $name...")
                val versionsResult = NuxModManager.getCompatibleVersions(
                    projectId = projectId,
                    instance = instance,
                    itemType = "mods",
                    filterByGameVersion = true
                )
                val versions = versionsResult.getOrElse {
                    skipped += "$name (gagal memeriksa)"
                    return@forEach
                }
                val selected = versions.firstOrNull { it.versionType.equals("release", true) }
                    ?: versions.firstOrNull()
                if (selected == null || selected.files.isEmpty()) {
                    skipped += "$name (tidak kompatibel)"
                    return@forEach
                }

                onProgress("Mengunduh $name...")
                val result = NuxModManager.downloadVersionAndDependencies(
                    context = context,
                    instance = instance,
                    version = selected,
                    itemType = "mods",
                    onProgress = onProgress
                )
                if (result.isSuccess) completed += name
                else skipped += "$name (unduhan gagal)"
            }

            val summary = buildList {
                addAll(completed)
                if (skipped.isNotEmpty()) add("DILEWATI: " + skipped.joinToString())
            }
            Result.success(summary)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
