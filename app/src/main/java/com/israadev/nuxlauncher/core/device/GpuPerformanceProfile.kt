package com.israadev.nuxlauncher.core.device

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20

/**
 * Reads the actual OpenGL renderer from a temporary EGL context.
 * This avoids guessing the GPU from the phone model or chipset name.
 * Call from a background thread.
 */
data class GpuInfo(
    val renderer: String,
    val vendor: String,
    val version: String
) {
    val isMaliG57: Boolean
        get() = renderer.contains("Mali-G57", ignoreCase = true) ||
            renderer.contains("Mali G57", ignoreCase = true)

    val isMali: Boolean
        get() = renderer.contains("Mali", ignoreCase = true)
}

object GpuPerformanceProfile {
    fun detect(): GpuInfo? {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        var initialized = false

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null

            val eglVersion = IntArray(2)
            if (!EGL14.eglInitialize(display, eglVersion, 0, eglVersion, 1)) return null
            initialized = true

            val configAttributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            if (!EGL14.eglChooseConfig(
                    display, configAttributes, 0, configs, 0, configs.size,
                    configCount, 0
                ) || configCount[0] == 0
            ) return null

            val config = configs[0] ?: return null
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) return null

            surface = EGL14.eglCreatePbufferSurface(
                display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (surface == EGL14.EGL_NO_SURFACE) return null
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty()
            val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            if (renderer.isBlank()) return null

            return GpuInfo(renderer, vendor, version)
        } catch (_: Throwable) {
            return null
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY && initialized) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface)
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context)
                }
                EGL14.eglTerminate(display)
            }
        }
    }
}
