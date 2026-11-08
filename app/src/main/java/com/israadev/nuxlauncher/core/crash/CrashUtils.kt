package com.israadev.nuxlauncher.core.crash

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object CrashUtils {

    fun getSignalName(signal: Int): String {
        return when (signal) {
            4 -> "SIGILL (Illegal CPU Instruction)"
            6 -> "SIGABRT (Native Assertion / Abort)"
            7 -> "SIGBUS (Bus Error)"
            8 -> "SIGFPE (Floating Point Exception)"
            9 -> "SIGKILL (Dihentikan OS / Kehabisan RAM)"
            11 -> "SIGSEGV (Illegal Memory Access / Segfault)"
            15 -> "SIGTERM (Permintaan Terminasi)"
            else -> "Signal $signal"
        }
    }

    fun getExitCodeDescription(exitCode: Int): String {
        return when (exitCode) {
            0 -> "Normal Exit (0)"
            1 -> "Mod Initialization Error / Java Exception (1)"
            130 -> "Dihentikan Pengguna (SIGINT 130)"
            134 -> "Native Driver Abort (SIGABRT 134)"
            137 -> "Out of Memory / OOM (SIGKILL 137)"
            139 -> "Crash Driver Grafis / Segfault (SIGSEGV 139)"
            -1 -> "Virtual Machine Bootstrap Failure (-1)"
            else -> "Exit Code $exitCode"
        }
    }

    const val CRASH_LOG_NOTE = "For root cause details, please check the log file. If you cannot resolve the issue on your own, share the log file with someone or a support team who can analyze it. Avoid taking just a screenshot of this dialog, as screenshots lack sufficient technical details to diagnose."

    fun getExitMessage(exitCode: Int, isSignal: Boolean): String {
        return if (isSignal) {
            "JVM digugurkan karena sinyal fatal $exitCode (${getSignalName(exitCode)})."
        } else {
            "JVM process exited with code $exitCode (${getExitCodeDescription(exitCode)})."
        }
    }

    fun shareLogFile(context: Context, file: File) {
        try {
            if (!file.exists()) {
                Toast.makeText(context, "Log file not found.", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Log Crash NUX Launcher - ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "Bagikan Log Crash: ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to share this file.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
