package com.israadev.nuxlauncher.core.crash

import android.content.Context
import com.israadev.nuxlauncher.core.account.AccountManager
import com.israadev.nuxlauncher.core.auth.AuthService
import com.israadev.nuxlauncher.core.models.LauncherSettings
import com.israadev.nuxlauncher.core.network.RemoteLauncherConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pengelola kuota harian untuk penggunaan fitur AI Crash Analyzer.
 * Default kuota: 5 kali per hari.
 * Otomatis di-reset setiap jam 00:00 WIB (Asia/Jakarta).
 * Disinkronkan dengan Database Firebase (Keystore Backend) dan dicache secara lokal.
 * Jika pengguna memiliki status VIP/Premium atau memasukkan API Key pribadi, kuota menjadi Unlimited (-1).
 */
object AICrashQuotaManager {
    const val DAILY_LIMIT = 5
    private const val PREF_NAME = "nux_ai_quota"
    private const val KEY_DATE = "quota_date"
    private const val KEY_USED_COUNT = "quota_used_count"
    private const val KEY_REMAINING_QUOTA = "quota_remaining"
    private const val KEY_DAILY_LIMIT = "quota_daily_limit"

    /**
     * Mendapatkan string tanggal hari ini dalam format YYYY-MM-DD
     * berdasarkan zona waktu Indonesia Barat (WIB, Asia/Jakarta) sehingga otomatis reset tepat jam 00:00 WIB.
     */
    fun getTodayJakartaDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        return sdf.format(Date())
    }

    /**
     * Memeriksa apakah user menggunakan custom API key sendiri.
     */
    fun isUsingCustomKey(settings: LauncherSettings?): Boolean {
        val customKeyAllowed = RemoteLauncherConfig.isEnabled(
            RemoteLauncherConfig.config.value, "ai", "customApiKeyAllowed"
        )
        return customKeyAllowed && !settings?.aiApiKey.isNullOrBlank()
    }

    private fun getConfiguredDailyLimit(context: Context): Int {
        val ai = RemoteLauncherConfig.config.value?.optJSONObject("ai")
        val key = if (isVipUser()) "premiumDailyQuota" else "freeDailyQuota"
        val configured = ai?.optInt(key, if (isVipUser()) -1 else DAILY_LIMIT)
            ?: if (isVipUser()) -1 else DAILY_LIMIT
        val cached = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DAILY_LIMIT, Int.MIN_VALUE)
        return if (cached != Int.MIN_VALUE) cached else configured
    }

    /**
     * Memeriksa apakah akun pengguna saat ini memiliki hak akses VIP/Unlimited.
     */
    fun isVipUser(): Boolean {
        val user = AccountManager.getActiveUser() ?: AccountManager.launcherUser.value
        return user?.isActivated == true
    }

    /**
     * Sinkronisasi kuota dari database backend.
     * Mengembalikan sisa kuota setelah disinkronkan (-1 jika unlimited).
     */
    suspend fun syncQuotaFromDatabase(context: Context, settings: LauncherSettings?): Int = withContext(Dispatchers.IO) {
        if (isUsingCustomKey(settings) || isVipUser()) {
            return@withContext -1
        }

        val uid = AccountManager.getActiveUser()?.uid?.trim()
            ?: AccountManager.launcherUser.value?.uid?.trim()
            ?: ""

        val today = getTodayJakartaDate()
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        if (uid.isNotBlank()) {
            val result = AuthService.fetchAiQuota(uid)
            result.onSuccess { info ->
                val safeUsed = info.usedCount.coerceAtLeast(0)
                val safeRemaining = if (info.isUnlimited) -1 else info.remainingQuota.coerceAtLeast(0)
                prefs.edit()
                    .putString(KEY_DATE, info.date.ifBlank { today })
                    .putInt(KEY_USED_COUNT, safeUsed)
                    .putInt(KEY_REMAINING_QUOTA, safeRemaining)
                    .putInt(KEY_DAILY_LIMIT, info.dailyLimit)
                    .apply()
                return@withContext safeRemaining
            }
        }

        // Fallback jika belum login, offline, atau database belum tersinkron
        val configuredLimit = getConfiguredDailyLimit(context)
        val savedDate = prefs.getString(KEY_DATE, "") ?: ""
        if (savedDate != today) {
            prefs.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_USED_COUNT, 0)
                .putInt(KEY_REMAINING_QUOTA, configuredLimit)
                .putInt(KEY_DAILY_LIMIT, configuredLimit)
                .apply()
            return@withContext configuredLimit
        }

        val used = prefs.getInt(KEY_USED_COUNT, 0)
        val dailyLimit = prefs.getInt(KEY_DAILY_LIMIT, configuredLimit)
        if (dailyLimit < 0) return@withContext -1
        if (used <= 0) {
            prefs.edit().putInt(KEY_REMAINING_QUOTA, dailyLimit).apply()
            return@withContext dailyLimit
        }

        val cached = prefs.getInt(KEY_REMAINING_QUOTA, -99)
        return@withContext if (cached >= 0) cached else (dailyLimit - used).coerceAtLeast(0)
    }

    /**
     * Mendapatkan jumlah pemakaian AI hari ini (memeriksa reset jam 00:00 WIB).
     */
    fun getUsedCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedDate = prefs.getString(KEY_DATE, "") ?: ""
        val today = getTodayJakartaDate()

        return if (savedDate == today) {
            prefs.getInt(KEY_USED_COUNT, 0)
        } else {
            0
        }
    }

    /**
     * Mendapatkan sisa kuota hari ini.
     * Mengembalikan -1 jika Unlimited (VIP / custom key).
     */
    fun getRemainingQuota(context: Context, settings: LauncherSettings?): Int {
        if (isUsingCustomKey(settings) || isVipUser()) {
            return -1 // Unlimited
        }
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedDate = prefs.getString(KEY_DATE, "") ?: ""
        val today = getTodayJakartaDate()

        val configuredLimit = getConfiguredDailyLimit(context)
        if (savedDate != today) {
            prefs.edit()
                .putString(KEY_DATE, today)
                .putInt(KEY_USED_COUNT, 0)
                .putInt(KEY_REMAINING_QUOTA, configuredLimit)
                .putInt(KEY_DAILY_LIMIT, configuredLimit)
                .apply()
            return configuredLimit
        }

        val dailyLimit = prefs.getInt(KEY_DAILY_LIMIT, configuredLimit)
        if (dailyLimit < 0) return -1
        val used = prefs.getInt(KEY_USED_COUNT, 0)
        if (used <= 0) {
            return dailyLimit
        }

        val cachedRem = prefs.getInt(KEY_REMAINING_QUOTA, -99)
        return if (cachedRem >= 0) {
            cachedRem
        } else {
            (dailyLimit - used).coerceAtLeast(0)
        }
    }

    /**
     * Cek apakah user masih memiliki kuota untuk melakukan analisis AI.
     */
    fun hasQuota(context: Context, settings: LauncherSettings?): Boolean {
        if (isUsingCustomKey(settings) || isVipUser()) return true
        return getRemainingQuota(context, settings) != 0
    }

    /**
     * Mengurangi/mengonsumsi 1 kuota harian baik di database backend maupun cache lokal.
     * Mengembalikan true jika berhasil dikonsumsi, false jika kuota sudah habis.
     */
    suspend fun consumeQuota(context: Context, settings: LauncherSettings?): Boolean = withContext(Dispatchers.IO) {
        if (isUsingCustomKey(settings) || isVipUser()) {
            return@withContext true
        }

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val today = getTodayJakartaDate()
        val savedDate = prefs.getString(KEY_DATE, "") ?: ""

        var currentUsed = if (savedDate == today) {
            prefs.getInt(KEY_USED_COUNT, 0)
        } else {
            0
        }

        // Cek limit lokal sebelum request, mengikuti batas yang ditetapkan admin/server.
        val dailyLimit = getConfiguredDailyLimit(context)
        if (dailyLimit == 0 || (dailyLimit > 0 && currentUsed >= dailyLimit)) {
            return@withContext false
        }

        val uid = AccountManager.getActiveUser()?.uid?.trim()
            ?: AccountManager.launcherUser.value?.uid?.trim()
            ?: ""

        if (uid.isNotBlank()) {
            val result = AuthService.consumeAiQuota(uid)
            if (result.isSuccess) {
                val info = result.getOrNull()!!
                prefs.edit()
                    .putString(KEY_DATE, info.date.ifBlank { today })
                    .putInt(KEY_USED_COUNT, info.usedCount)
                    .putInt(KEY_REMAINING_QUOTA, info.remainingQuota)
                    .putInt(KEY_DAILY_LIMIT, info.dailyLimit)
                    .apply()
                return@withContext info.allowed
            } else {
                val err = result.exceptionOrNull()?.message ?: ""
                if (err.contains("Batas", ignoreCase = true) || err.contains("429")) {
                    prefs.edit()
                        .putString(KEY_DATE, today)
                        .putInt(KEY_USED_COUNT, dailyLimit.coerceAtLeast(0))
                        .putInt(KEY_REMAINING_QUOTA, 0)
                        .putInt(KEY_DAILY_LIMIT, dailyLimit)
                        .apply()
                    return@withContext false
                }
                // Jika kegagalan hanya karena jaringan tidak stabil, izinkan pemakaian secara lokal
            }
        }

        // Fallback / Guest local counter
        currentUsed += 1
        val newRemaining = if (dailyLimit < 0) -1 else (dailyLimit - currentUsed).coerceAtLeast(0)
        prefs.edit()
            .putString(KEY_DATE, today)
            .putInt(KEY_USED_COUNT, currentUsed)
            .putInt(KEY_REMAINING_QUOTA, newRemaining)
            .putInt(KEY_DAILY_LIMIT, dailyLimit)
            .apply()

        return@withContext true
    }
}
