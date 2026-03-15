package moe.ouom.wekit.preferences

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener

abstract class WePrefs protected constructor() : SharedPreferences, SharedPreferences.Editor {

    fun getBoolOrFalse(key: String): Boolean {
        return getBoolOrDef(key, false)
    }

    fun getBoolOrDef(key: String, def: Boolean): Boolean {
        return getBoolean(key, def)
    }

    fun getIntOrDef(key: String, def: Int): Int {
        return getInt(key, def)
    }

    abstract fun getString(key: String): String?

    fun getStringOrDef(key: String, def: String): String {
        return getString(key, def)!!
    }

    @JvmName("getStringOrDefNullable")
    fun getStringOrDef(key: String, def: String?): String? {
        return getString(key, def)
    }

    fun getStringSetOrDef(key: String, def: Set<String>): Set<String> {
        return defaultConfig.getStringSet(key, def)!!
    }

    abstract fun getObject(key: String): Any?

    abstract fun getBytes(key: String, defValue: ByteArray?): ByteArray?

    abstract fun getBytesOrDefault(key: String, defValue: ByteArray): ByteArray

    abstract fun putBytes(key: String, value: ByteArray)

    abstract fun save()

    abstract fun putObject(key: String, obj: Any): WePrefs

    fun containsKey(k: String): Boolean {
        return contains(k)
    }

    override fun edit(): SharedPreferences.Editor {
        return this
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: OnSharedPreferenceChangeListener
    ): Unit = TODO()

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: OnSharedPreferenceChangeListener
    ): Unit = TODO()

    abstract val isReadOnly: Boolean

    abstract val isPersistent: Boolean

    companion object {
        const val PREFS_NAME = "wekit_prefs"
        const val CACHE_PREFS_NAME = "wekit_cache"

        val defaultConfig by lazy { MmkvPrefsImpl(PREFS_NAME) }

        fun getBoolOrFalse(key: String): Boolean {
            return defaultConfig.getBoolOrFalse(key)
        }

        fun getStringOrDef(key: String, def: String): String {
            return defaultConfig.getStringOrDef(key, def)
        }

        @JvmName("getStringOrDefNullable")
        fun getStringOrDef(key: String, def: String?): String? {
            return defaultConfig.getStringOrDef(key, def)
        }

        fun getStringSet(key: String, def: Set<String>): Set<String> {
            return defaultConfig.getStringSetOrDef(key, def)
        }

        fun getIntOrDef(key: String, def: Int): Int {
            return defaultConfig.getIntOrDef(key, def)
        }

        fun putString(key: String, value: String) {
            defaultConfig.putString(key, value)
        }

        fun putInt(key: String, value: Int) {
            defaultConfig.putInt(key, value)
        }

        fun putBool(key: String, value: Boolean) {
            defaultConfig.putBoolean(key, value)
        }

        fun putStringSet(key: String, value: Set<String>) {
            defaultConfig.putStringSet(key, value)
        }

        fun remove(key: String) {
            defaultConfig.remove(key)
        }
    }
}
