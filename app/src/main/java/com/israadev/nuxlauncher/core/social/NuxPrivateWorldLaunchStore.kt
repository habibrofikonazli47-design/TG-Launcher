package com.israadev.nuxlauncher.core.social

import android.content.Context

data class PendingPrivateWorldLaunch(
    val sessionId: String,
    val role: String,
    val minecraftVersion: String
)

/** One-shot handoff from the Social screen to the next Minecraft launch. */
object NuxPrivateWorldLaunchStore {
    private const val PREFS = "tg_private_world_launch"
    private const val KEY_SESSION = "session_id"
    private const val KEY_ROLE = "role"
    private const val KEY_VERSION = "minecraft_version"

    fun save(context: Context, sessionId: String, role: String, minecraftVersion: String = "") {
        require(sessionId.matches(Regex("(?i)^[0-9a-f-]{36}$"))) { "ID sesi dunia tidak valid" }
        require(role == "host" || role == "guest") { "Peran Private World tidak valid" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SESSION, sessionId)
            .putString(KEY_ROLE, role)
            .putString(KEY_VERSION, minecraftVersion.take(32))
            .apply()
    }

    fun peek(context: Context): PendingPrivateWorldLaunch? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val session = prefs.getString(KEY_SESSION, null)?.takeIf { it.matches(Regex("(?i)^[0-9a-f-]{36}$")) }
            ?: return null
        val role = prefs.getString(KEY_ROLE, null)?.takeIf { it == "host" || it == "guest" }
            ?: return null
        return PendingPrivateWorldLaunch(session, role, prefs.getString(KEY_VERSION, "").orEmpty())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
