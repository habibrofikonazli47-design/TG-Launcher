package com.israadev.nuxlauncher.core.device

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import android.util.Log

/**
 * Requests the highest refresh mode up to 120 Hz without changing the panel
 * resolution. Android and the device firmware remain free to cap the rate.
 */
object RefreshRateManager {
    private const val TAG = "TGRefreshRate"
    private const val TARGET_REFRESH_RATE = 120f

    fun apply(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        try {
            val displayManager = activity.getSystemService(DisplayManager::class.java) ?: return
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val modes = display.supportedModes
            if (modes.isEmpty()) return

            val currentMode = display.mode
            val sameResolution = modes.filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }.ifEmpty { modes.toList() }

            // Prefer the fastest supported mode at or below 120 Hz. If the
            // device only exposes modes above 120 Hz, use its lowest mode.
            val atOrBelowTarget = sameResolution.filter {
                it.refreshRate <= TARGET_REFRESH_RATE + 0.5f
            }
            val selectedMode = atOrBelowTarget.maxByOrNull { it.refreshRate }
                ?: sameResolution.minByOrNull { it.refreshRate }
                ?: return

            val params = activity.window.attributes
            if (params.preferredDisplayModeId != selectedMode.modeId) {
                params.preferredDisplayModeId = selectedMode.modeId
                activity.window.attributes = params
            }

            Log.i(TAG, "Requested display mode ${selectedMode.modeId} at ${selectedMode.refreshRate} Hz")
        } catch (error: Exception) {
            // Some vendors restrict app-selected display modes. Keep the app
            // usable and let Android choose the refresh rate in that case.
            Log.w(TAG, "Unable to request a high refresh-rate display mode", error)
        }
    }
}
