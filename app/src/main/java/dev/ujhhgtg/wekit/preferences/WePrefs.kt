package dev.ujhhgtg.wekit.preferences

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
        return default.getStringSet(key, def)!!
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
    ) {
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: OnSharedPreferenceChangeListener
    ) {
    }

    abstract val isReadOnly: Boolean

    abstract val isPersistent: Boolean

    companion object {
        const val PREFS_NAME = "wekit_prefs"

        val default by lazy { MmkvPrefsImpl(PREFS_NAME) }

        fun getBoolOrFalse(key: String): Boolean {
            return default.getBoolOrFalse(key)
        }

        fun getString(key: String): String? {
            return default.getString(key)
        }

        fun getStringOrDef(key: String, def: String): String {
            return default.getStringOrDef(key, def)
        }

        @JvmName("getStringOrDefNullable")
        fun getStringOrDef(key: String, def: String?): String? {
            return default.getStringOrDef(key, def)
        }

        fun getStringSetOrDef(key: String, def: Set<String>): Set<String> {
            return default.getStringSetOrDef(key, def)
        }

        fun getStringSet(key: String): Set<String>? {
            return default.getStringSet(key, null)
        }

        fun getLong(key: String, def: Long): Long {
            return default.getLong(key, def)
        }

        fun getIntOrDef(key: String, def: Int): Int {
            return default.getIntOrDef(key, def)
        }

        fun putString(key: String, value: String) {
            default.putString(key, value)
        }

        fun putInt(key: String, value: Int) {
            default.putInt(key, value)
        }

        fun putBool(key: String, value: Boolean) {
            default.putBoolean(key, value)
        }

        fun putLong(key: String, value: Long) {
            default.putLong(key, value)
        }

        fun putFloat(key: String, value: Float) {
            default.putFloat(key, value)
        }

        fun putStringSet(key: String, value: Set<String>) {
            default.putStringSet(key, value)
        }

        fun remove(key: String) {
            default.remove(key)
        }
    }
}
