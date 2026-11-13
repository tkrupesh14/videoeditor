package dev.videoeditor.app.security

import android.content.Context
import android.content.SharedPreferences

/**
 * The few SharedPreferences operations the security stores need, behind an interface so their logic runs in
 * plain JVM tests (android.jar stubs throw off-device).
 */
interface Prefs {
    fun getString(key: String): String?

    fun getLong(key: String): Long?

    /** Applies all [puts] and [removes] atomically and durably. Returns false when the write failed. */
    fun commit(
        puts: Map<String, Any> = emptyMap(),
        removes: Collection<String> = emptyList(),
    ): Boolean
}

/** [Prefs] over a private [SharedPreferences] file, written with `commit()` so writes are durable. */
class SharedPreferencesPrefs(
    private val prefs: SharedPreferences,
) : Prefs {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    override fun commit(
        puts: Map<String, Any>,
        removes: Collection<String>,
    ): Boolean {
        val editor = prefs.edit()
        removes.forEach { editor.remove(it) }
        for ((k, v) in puts) {
            when (v) {
                is String -> editor.putString(k, v)
                is Long -> editor.putLong(k, v)
                else -> throw IllegalArgumentException("unsupported preference type for $k")
            }
        }
        return editor.commit()
    }

    companion object {
        /** Opens a private prefs file by name, without the `.xml` suffix. */
        fun open(
            context: Context,
            fileName: String,
        ): SharedPreferencesPrefs = SharedPreferencesPrefs(context.getSharedPreferences(fileName, Context.MODE_PRIVATE))
    }
}
