package com.israadev.nuxlauncher.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.israadev.nuxlauncher.core.network.NuxConfig
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AndroidUpdateInfo(
    val code: Int,
    val version: String,
    val localVersion: String,
    val localCode: Long,
    val createdAt: String,
    val downloadUrl: String,
    val fileName: String,
    val fileSize: Long,
    val sha256: String,
    val changelog: String,
    val isUpdateAvailable: Boolean,
    val isMandatory: Boolean
)

object UpdateManager {
    private val UPDATE_ENDPOINT: String
        get() = NuxConfig.UPDATE_ENDPOINT

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .build()

    suspend fun checkForUpdate(context: Context): Result<AndroidUpdateInfo> = withContext(Dispatchers.IO) {
        if (UPDATE_ENDPOINT.isBlank()) {
            return@withContext Result.failure(Exception("Server pembaruan belum dikonfigurasi."))
        }
        try {
            val req = Request.Builder()
                .url(UPDATE_ENDPOINT)
                .header("User-Agent", "TG-Launcher-Android")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("Server HTTP ${resp.code}"))
                val rootObj = JsonParser.parseString(resp.body.string()).asJsonObject
                val remoteCode = rootObj.get("code")?.takeUnless { it.isJsonNull }?.asInt ?: 1
                val remoteVersion = rootObj.get("version")?.takeUnless { it.isJsonNull }?.asString?.trim()?.removePrefix("v") ?: "1.0.0"
                val forceUpdate = rootObj.get("force_update")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
                val minimumVersion = rootObj.get("minimum_supported_version")?.takeUnless { it.isJsonNull }?.asString?.trim()?.removePrefix("v") ?: "1.0.0"
                val createdAt = rootObj.get("created_at")?.takeUnless { it.isJsonNull }?.asString ?: ""

                var downloadUrl = ""
                var fileName = "TG-Launcher-Android.apk"
                var fileSize = 0L
                var sha256 = ""
                val files = rootObj.getAsJsonArray("files")
                if (files != null && files.size() > 0) {
                    val file = files[0].asJsonObject
                    downloadUrl = file.get("uri")?.takeUnless { it.isJsonNull }?.asString?.let(NuxConfig::getDownloadUrl) ?: ""
                    fileName = file.get("file_name")?.takeUnless { it.isJsonNull }?.asString ?: fileName
                    fileSize = file.get("size")?.takeUnless { it.isJsonNull }?.asLong ?: 0L
                    sha256 = file.get("sha256")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                        .substringAfter("sha256:", file.get("sha256")?.takeUnless { it.isJsonNull }?.asString.orEmpty())
                        .lowercase(Locale.ROOT)
                }

                var changelog = rootObj.getAsJsonObject("default_body")?.get("markdown")
                    ?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                if (changelog.isBlank()) changelog = "Pembaruan resmi TG Launcher tersedia."

                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                val localCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                val localVersion = (packageInfo.versionName ?: "1.0.0").trim().removePrefix("v")
                val newer = remoteCode > localCode || compareVersions(remoteVersion, localVersion) > 0
                val belowMinimum = forceUpdate && compareVersions(localVersion, minimumVersion) < 0
                val enabled = rootObj.get("update_available")?.takeUnless { it.isJsonNull }?.asBoolean ?: true
                val available = enabled && (newer || belowMinimum)

                Result.success(AndroidUpdateInfo(
                    code = remoteCode,
                    version = remoteVersion,
                    localVersion = localVersion,
                    localCode = localCode,
                    createdAt = createdAt,
                    downloadUrl = downloadUrl,
                    fileName = fileName,
                    fileSize = fileSize,
                    sha256 = sha256,
                    changelog = changelog,
                    isUpdateAvailable = available,
                    isMandatory = forceUpdate && available
                ))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /**
     * Download the release inside TG Launcher, verify its SHA-256, then hand it
     * to Android's package installer. Android still owns the final consent step.
     */
    suspend fun downloadAndInstall(
        context: Context,
        update: AndroidUpdateInfo
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = Uri.parse(update.downloadUrl)
            if (url.scheme != "https") return@withContext Result.failure(Exception("Tautan update harus menggunakan HTTPS."))
            if (!update.sha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
                return@withContext Result.failure(Exception("Checksum SHA-256 tidak tersedia. Update dibatalkan demi keamanan."))
            }

            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeName = update.fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            val tempFile = File(updateDir, "$safeName.part")
            val apkFile = File(updateDir, safeName)
            val request = Request.Builder().url(update.downloadUrl).header("User-Agent", "TG-Launcher-Android").get().build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("Unduhan gagal: HTTP ${response.code}"))
                val body = response.body ?: return@withContext Result.failure(Exception("Server tidak mengirim file APK."))
                if (update.fileSize > 0 && body.contentLength() > 0 && body.contentLength() != update.fileSize) {
                    return@withContext Result.failure(Exception("Ukuran file update tidak cocok dengan metadata server."))
                }
                body.byteStream().use { input ->
                    tempFile.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val actualHash = tempFile.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (!actualHash.equals(update.sha256, ignoreCase = true)) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Verifikasi update gagal (SHA-256 tidak cocok)."))
            }
            if (apkFile.exists()) apkFile.delete()
            if (!tempFile.renameTo(apkFile)) return@withContext Result.failure(Exception("Tidak dapat menyiapkan file instalasi."))

            val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun compareVersions(first: String, second: String): Int {
        val a = first.split('.').mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        val b = second.split('.').mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }
}
