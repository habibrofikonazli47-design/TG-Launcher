package com.israadev.nuxlauncher.core.recorder

enum class ScreenResolution(val label: String, val height: Int, val defaultBitrate: Int) {
    RES_720P("720p HD", 720, 8_000_000),
    RES_1080P("1080p FHD", 1080, 14_000_000),
    RES_NATIVE("Match Screen", 0, 12_000_000);

    companion object {
        fun fromString(value: String): ScreenResolution {
            return when (value.lowercase()) {
                "1080p" -> RES_1080P
                "native" -> RES_NATIVE
                else -> RES_720P
            }
        }
    }
}

data class ScreenRecordConfig(
    val resolution: ScreenResolution = ScreenResolution.RES_720P,
    val frameRate: Int = 60, // 30 or 60 fps
    val bitRate: Int = 8_000_000,
    val recordAudio: Boolean = true
)

data class RecordState(
    val isRecording: Boolean = false,
    val durationSeconds: Long = 0,
    val outputFilePath: String? = null,
    val lastSavedFile: String? = null,
    val errorMessage: String? = null
) {
    val formattedDuration: String
        get() {
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            return "%02d:%02d".format(minutes, seconds)
        }
}
