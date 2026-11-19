package com.israadev.nuxlauncher.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.israadev.nuxlauncher.core.device.GpuPerformanceProfile
import com.israadev.nuxlauncher.core.instance.Instance
import com.israadev.nuxlauncher.core.mods.NuxModManager
import com.israadev.nuxlauncher.ui.theme.NuxColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class RecipeDevice(val ramMb: Int, val cores: Int, val abi: String, val gpu: String)
private enum class RecipeTier(val label: String, val description: String) {
    LOW("Hemat RAM", "Utamakan penggunaan memori rendah dan stabilitas."),
    BALANCED("Seimbang", "Kombinasi performa dan fitur untuk penggunaan harian."),
    FPS("FPS Tinggi", "Lebih banyak optimasi; penggunaan memori bisa meningkat.")
}
private data class RecipeCandidate(val query: String)

@Composable
fun ModRecipePanel(instance: Instance?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf<RecipeDevice?>(null) }
    var selectedTier by remember { mutableStateOf(RecipeTier.BALANCED) }
    var isPreparing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }

    LaunchedEffect(expanded) {
        if (expanded && device == null) {
            device = withContext(Dispatchers.IO) { detectRecipeDevice(context) }
            device?.let { info ->
                selectedTier = when {
                    info.ramMb < 3500 || info.cores <= 4 -> RecipeTier.LOW
                    info.ramMb >= 7000 && info.cores >= 8 -> RecipeTier.FPS
                    else -> RecipeTier.BALANCED
                }
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .background(NuxColors.SurfaceElevated, RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF278BFF).copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Color(0xFF50D7FF), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("Racikan Mod Otomatis", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Pilih racikan dan unduh semua mod kompatibel sekaligus.", color = NuxColors.GrayNeutral, fontSize = 10.sp)
            }
            Text(if (expanded) "Tutup" else "Buka", color = Color(0xFF50D7FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            if (instance == null) {
                Text("Pilih instance Minecraft terlebih dahulu.", color = NuxColors.GrayNeutral, fontSize = 11.sp)
            } else if (instance.loader.equals("vanilla", true)) {
                Text("Racikan mod memerlukan Fabric atau Quilt. Instance ini menggunakan Vanilla.", color = Color(0xFFFFC56B), fontSize = 11.sp)
            } else {
                device?.let { info ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Memory, null, tint = Color(0xFF50D7FF), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("RAM ${info.ramMb / 1024} GB • ${info.cores} core • ${info.abi}", color = Color.White, fontSize = 10.sp)
                    }
                    Text("GPU: ${info.gpu}", color = NuxColors.GrayNeutral, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Target: Minecraft ${instance.mcVersion} • ${instance.loader}", color = NuxColors.GrayNeutral, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                Spacer(Modifier.height(8.dp))
                RecipeTier.values().forEach { tier ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !isPreparing) { selectedTier = tier }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedTier == tier, onClick = { selectedTier = tier }, enabled = !isPreparing)
                        Column {
                            Text(tier.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(tier.description, color = NuxColors.GrayNeutral, fontSize = 9.sp)
                        }
                    }
                }
                if (total > 0) LinearProgressIndicator(progress = { completed.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                if (progress.isNotBlank()) Text(progress, color = Color(0xFF50D7FF), fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                result?.let { Text(it, color = if (it.startsWith("Selesai")) Color(0xFF72D99A) else Color(0xFFFFC56B), fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp)) }
                Button(
                    onClick = {
                        val detected = device ?: return@Button
                        if (isPreparing) return@Button
                        isPreparing = true
                        result = null
                        completed = 0
                        scope.launch {
                            try {
                                val candidates = recipeCandidates(selectedTier, detected)
                                total = candidates.size
                                val installedNames = mutableListOf<String>()
                                val errors = mutableListOf<String>()
                                for (candidate in candidates) {
                                    progress = "Mencari ${candidate.query}…"
                                    val search = NuxModManager.searchProjects(candidate.query, "mods", instance, "optimization", true, 1, 8)
                                    val hit = search.getOrNull()?.hits?.filter { it.projectType == "mod" }?.maxByOrNull { it.downloads }
                                    if (hit == null) {
                                        errors += candidate.query
                                        completed++
                                        continue
                                    }
                                    val versions = NuxModManager.getCompatibleVersions(hit.projectId, instance, "mods", true).getOrNull().orEmpty()
                                    val version = versions.firstOrNull { it.versionType == "release" && it.files.isNotEmpty() }
                                        ?: versions.firstOrNull { it.files.isNotEmpty() }
                                    if (version == null) {
                                        errors += hit.title
                                        completed++
                                        continue
                                    }
                                    progress = "Mengunduh ${hit.title}…"
                                    val installed = NuxModManager.downloadVersionAndDependencies(
                                        context, instance, version, "mods",
                                        onProgress = { status -> progress = status }
                                    )
                                    if (installed.isSuccess) installedNames.add(hit.title) else errors.add(hit.title)
                                    completed++
                                }
                                result = if (installedNames.isNotEmpty()) {
                                    "Selesai: ${installedNames.size} mod dipasang${if (errors.isNotEmpty()) ". Tidak tersedia/gagal: ${errors.joinToString()}" else "."}"
                                } else "Belum ada mod berhasil dipasang. Periksa koneksi, versi Minecraft, dan loader."
                            } catch (error: Exception) {
                                result = "Gagal: ${error.message ?: "kesalahan tidak diketahui"}"
                            } finally {
                                isPreparing = false
                                progress = ""
                            }
                        }
                    },
                    enabled = !isPreparing && device != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPreparing) "Sedang menyiapkan racikan…" else "Unduh & Pasang Semua Mod")
                }
                Text("Versi game dan loader diperiksa sebelum pemasangan. Kombinasi mod tetap perlu diuji; tidak ada jaminan bebas konflik.", color = NuxColors.GrayNeutral, fontSize = 9.sp)
            }
        }
    }
}

private fun detectRecipeDevice(context: Context): RecipeDevice {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memory = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(memory)
    val gpu = GpuPerformanceProfile.detect()?.renderer?.take(80) ?: "GPU tidak terdeteksi"
    return RecipeDevice(
        ramMb = (memory.totalMem / (1024L * 1024L)).toInt(),
        cores = Runtime.getRuntime().availableProcessors(),
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI,
        gpu = gpu
    )
}

private fun recipeCandidates(tier: RecipeTier, device: RecipeDevice): List<RecipeCandidate> {
    val mods = mutableListOf(RecipeCandidate("Sodium"), RecipeCandidate("Lithium"), RecipeCandidate("FerriteCore"), RecipeCandidate("ImmediatelyFast"))
    if (tier != RecipeTier.LOW && device.ramMb >= 3000) mods += RecipeCandidate("Entity Culling")
    if (tier == RecipeTier.FPS && device.ramMb >= 6000) {
        mods += RecipeCandidate("More Culling")
        mods += RecipeCandidate("Dynamic FPS")
    }
    return mods.distinctBy { it.query }
}
