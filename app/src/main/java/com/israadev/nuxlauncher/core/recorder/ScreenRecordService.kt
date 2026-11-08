package com.israadev.nuxlauncher.core.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.israadev.nuxlauncher.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {

    companion object {
        private const val TAG = "NuxScreenRecorder"
        const val CHANNEL_ID = "nux_screen_recorder_channel"
        const val NOTIFICATION_ID = 4040
        const val COMPLETE_NOTIFICATION_ID = 4041

        const val ACTION_START = "com.israadev.nuxlauncher.action.START_RECORDING"
        const val ACTION_STOP = "com.israadev.nuxlauncher.action.STOP_RECORDING"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_RESOLUTION_HEIGHT = "extra_resolution_height"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_RECORD_AUDIO = "extra_record_audio"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    private var outputFile: File? = null
    private var isRecording = false
    private var durationSeconds = 0L
    private var timerJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection session terminated by system")
            stopRecordingInternal()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != 0 && resultData != null) {
                    val targetHeight = intent.getIntExtra(EXTRA_RESOLUTION_HEIGHT, 720)
                    val fps = intent.getIntExtra(EXTRA_FPS, 60)
                    val bitrate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000)
                    val recordAudio = intent.getBooleanExtra(EXTRA_RECORD_AUDIO, true)

                    startRecordingInternal(resultCode, resultData, targetHeight, fps, bitrate, recordAudio)
                } else {
                    Log.e(TAG, "Missing MediaProjection consent intent data")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopRecordingInternal()
            }
        }

        return START_NOT_STICKY
    }

    private fun startRecordingInternal(
        resultCode: Int,
        resultData: Intent,
        targetHeight: Int,
        fps: Int,
        bitrate: Int,
        recordAudio: Boolean
    ) {
        if (isRecording) {
            Log.w(TAG, "Recording already active, ignoring start request")
            return
        }

        try {
            // 1. Android 14 requires startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            // BEFORE calling getMediaProjection()!
            val initialNotification = buildNotification("Initializing recorder...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }

            // 2. Compute display dimensions
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val densityDpi = displayMetrics.densityDpi

            // Determine dimensions keeping aspect ratio (even dimensions mandatory for H.264!)
            val aspect = screenWidth.toFloat() / screenHeight.toFloat()
            val chosenHeight = if (targetHeight > 0) targetHeight else screenHeight
            var recWidth = (chosenHeight * aspect).toInt()
            var recHeight = chosenHeight
            if (recWidth % 2 != 0) recWidth -= 1
            if (recHeight % 2 != 0) recHeight -= 1

            Log.d(TAG, "Recording dimensions: ${recWidth}x${recHeight} @ $fps FPS ($bitrate bps)")

            // 3. Prepare output file in Movies/NUX-Launcher
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "NUX-Launcher"
            )
            if (!moviesDir.exists()) {
                moviesDir.mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            outputFile = File(moviesDir, "NUX_REC_$timeStamp.mp4")

            // 4. Initialize MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                val canRecordAudio = recordAudio &&
                        ContextCompat.checkSelfPermission(this@ScreenRecordService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                var audioAttached = false

                if (canRecordAudio) {
                    try {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        audioAttached = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to attach MIC audio source, proceeding without audio", e)
                    }
                }

                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)

                if (audioAttached) {
                    try {
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioEncodingBitRate(128_000)
                        setAudioSamplingRate(44100)
                    } catch (e: Exception) {
                        Log.w(TAG, "Audio encoder setup skipped", e)
                    }
                }

                setVideoSize(recWidth, recHeight)
                setVideoFrameRate(fps)
                setVideoEncodingBitRate(bitrate)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
            }

            // 5. Get MediaProjection & register callback (MANDATORY on Android 14)
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Failed to obtain MediaProjection session")
            mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            mediaProjection = mp

            // 6. Create VirtualDisplay attached to MediaRecorder surface
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "NUX-ScreenRecorder",
                recWidth,
                recHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder!!.surface,
                null,
                null
            )

            // 7. Start recording
            mediaRecorder?.start()
            isRecording = true
            durationSeconds = 0L

            ScreenRecordManager.updateState(
                RecordState(
                    isRecording = true,
                    durationSeconds = 0,
                    outputFilePath = outputFile?.absolutePath
                )
            )

            // 8. Start duration ticker
            startDurationTicker()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen recording", e)
            cleanupRecording()
            ScreenRecordManager.updateState(
                RecordState(
                    isRecording = false,
                    errorMessage = "Failed to start recording: ${e.message}"
                )
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startDurationTicker() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive && isRecording) {
                delay(1000)
                durationSeconds++
                ScreenRecordManager.updateState(
                    RecordState(
                        isRecording = true,
                        durationSeconds = durationSeconds,
                        outputFilePath = outputFile?.absolutePath
                    )
                )
                // Update notification text with timer
                val notification = buildNotification("Recording Minecraft: ${formatDuration(durationSeconds)}")
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun stopRecordingInternal() {
        if (!isRecording && mediaRecorder == null) {
            stopSelf()
            return
        }

        timerJob?.cancel()
        isRecording = false

        val savedPath = outputFile?.absolutePath

        try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.w(TAG, "MediaRecorder stop threw exception (possibly stopped too quickly)", e)
                }
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up MediaRecorder", e)
        } finally {
            mediaRecorder = null
        }

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VirtualDisplay", e)
        } finally {
            virtualDisplay = null
        }

        try {
            mediaProjection?.apply {
                unregisterCallback(projectionCallback)
                stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection", e)
        } finally {
            mediaProjection = null
        }

        // Notify MediaStore so the new video shows up in Android Gallery immediately!
        outputFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(file.absolutePath),
                    arrayOf("video/mp4")
                ) { path, uri ->
                    Log.i(TAG, "Video indexed into Android Gallery: $path -> $uri")
                }
                showCompleteNotification(file)
            } else {
                try { file.delete() } catch (_: Exception) {}
            }
        }

        ScreenRecordManager.updateState(
            RecordState(
                isRecording = false,
                durationSeconds = durationSeconds,
                lastSavedFile = if (outputFile?.exists() == true && outputFile!!.length() > 0) savedPath else null
            )
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupRecording() {
        try { mediaRecorder?.reset() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null

        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        timerJob?.cancel()
        isRecording = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recorder [Premium]",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "NUX Launcher Screen Recording Service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NUX Screen Recorder")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Recording",
                stopPendingIntent
            )
            .build()
    }

    private fun showCompleteNotification(file: File) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            val uri: Uri = FileProvider.getUriForFile(
                this@ScreenRecordService,
                "${applicationContext.packageName}.provider",
                file
            )
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording Saved!")
            .setContentText("Gameplay video saved to Movies/NUX-Launcher (${file.name})")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(COMPLETE_NOTIFICATION_ID, notification)
    }

    private fun formatDuration(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }

    override fun onDestroy() {
        cleanupRecording()
        super.onDestroy()
    }
}
