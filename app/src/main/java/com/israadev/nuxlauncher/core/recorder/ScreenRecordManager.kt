package com.israadev.nuxlauncher.core.recorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.israadev.nuxlauncher.core.account.AccountManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object ScreenRecordManager {

    private val _recordState = MutableStateFlow(RecordState())
    val recordState: StateFlow<RecordState> = _recordState.asStateFlow()

    fun updateState(state: RecordState) {
        _recordState.value = state
    }

    /**
     * Exclusive feature for NUX Premium members
     */
    fun isUserPremium(): Boolean {
        if (AccountManager.isSessionActivated()) return true
        if (AccountManager.launcherUser.value?.isActivated == true) return true
        if (AccountManager.accounts.value.any { it.isActivated }) return true
        if (AccountManager.currentAccount.value?.isActivated == true) return true
        return false
    }

    fun startRecording(
        context: Context,
        resultCode: Int,
        resultData: Intent,
        config: ScreenRecordConfig = ScreenRecordConfig()
    ) {

        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenRecordService.EXTRA_RESOLUTION_HEIGHT, config.resolution.height)
            putExtra(ScreenRecordService.EXTRA_FPS, config.frameRate)
            putExtra(ScreenRecordService.EXTRA_BITRATE, config.bitRate)
            putExtra(ScreenRecordService.EXTRA_RECORD_AUDIO, config.recordAudio)
        }

        ContextCompat.startForegroundService(context, intent)
    }

    fun stopRecording(context: Context) {
        val intent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun getRecordingsDir(): File {
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "NUX-Launcher"
        )
        if (!moviesDir.exists()) {
            moviesDir.mkdirs()
        }
        return moviesDir
    }

    fun getSavedRecordings(): List<File> {
        val dir = getRecordingsDir()
        return dir.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun playRecording(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteRecording(file: File): Boolean {
        return try {
            file.delete()
        } catch (_: Exception) {
            false
        }
    }
}
