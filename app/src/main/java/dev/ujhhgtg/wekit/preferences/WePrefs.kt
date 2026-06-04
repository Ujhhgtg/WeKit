package dev.ujhhgtg.wekit.preferences

import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

@Suppress("unused")
abstract class WePrefs protected constructor() : SharedPreferences, SharedPreferences.Editor {

    fun getBoolOrFalse(key: String): Boolean {
        return getBoolean(key, false)
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

        fun getLongOrDef(key: String, def: Long): Long {
            return default.getLong(key, def)
        }

        fun getIntOrDef(key: String, def: Int): Int {
            return default.getInt(key, def)
        }

        fun getFloatOrDef(key: String, def: Float): Float {
            return default.getFloat(key, def)
        }

        fun putString(key: String, value: String) {
            default.putString(key, value)
        }

        fun putInt(key: String, value: Int) {
            default.putInt(key, value)
        }

        fun putBool(key: String, value: Boolean) = default.putBoolean(key, value)

        fun putLong(key: String, value: Long) = default.putLong(key, value)

        fun putFloat(key: String, value: Float) = default.putFloat(key, value)

        fun putStringSet(key: String, value: Set<String>) {
            default.putStringSet(key, value)
        }

        fun containsKey(key: String) = default.containsKey(key)

        fun remove(key: String) = default.remove(key)

        // -- Delegate properties --

        fun prefOption(key: String, default: String): ReadWriteProperty<Any?, String> =
            object : ReadWriteProperty<Any?, String> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): String =
                    getStringOrDef(key, default)

                override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
                    putString(key, value)
                }
            }

        fun prefOption(key: String, default: Int): ReadWriteProperty<Any?, Int> =
            object : ReadWriteProperty<Any?, Int> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): Int =
                    getIntOrDef(key, default)

                override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
                    putInt(key, value)
                }
            }

        fun prefOption(key: String, defValue: Boolean): ReadWriteProperty<Any?, Boolean> =
            object : ReadWriteProperty<Any?, Boolean> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean =
                    default.getBoolean(key, defValue)

                override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
                    putBool(key, value)
                }
            }

        fun prefOption(key: String, default: Long): ReadWriteProperty<Any?, Long> =
            object : ReadWriteProperty<Any?, Long> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): Long =
                    getLongOrDef(key, default)

                override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
                    putLong(key, value)
                }
            }

        fun prefOption(key: String, default: Float): ReadWriteProperty<Any?, Float> =
            object : ReadWriteProperty<Any?, Float> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): Float =
                    getFloatOrDef(key, default)

                override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
                    putFloat(key, value)
                }
            }

        fun prefOption(key: String, default: Set<String>): ReadWriteProperty<Any?, Set<String>> =
            object : ReadWriteProperty<Any?, Set<String>> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): Set<String> =
                    getStringSetOrDef(key, default)

                override fun setValue(
                    thisRef: Any?,
                    property: KProperty<*>,
                    value: Set<String>
                ) {
                    putStringSet(key, value)
                }
            }

        fun prefOption(key: String, defValue: ByteArray): ReadWriteProperty<Any?, ByteArray> =
            object : ReadWriteProperty<Any?, ByteArray> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): ByteArray =
                    default.getBytesOrDefault(key, defValue)

                override fun setValue(
                    thisRef: Any?,
                    property: KProperty<*>,
                    value: ByteArray
                ) {
                    default.putBytes(key, value)
                }
            }

        @JvmName("prefOptionNullable")
        fun prefOption(key: String, defValue: String?): ReadWriteProperty<Any?, String?> =
            object : ReadWriteProperty<Any?, String?> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): String? =
                    getStringOrDef(key, defValue)

                override fun setValue(
                    thisRef: Any?,
                    property: KProperty<*>,
                    value: String?
                ) {
                    default.putString(key, value)
                }
            }

        @JvmName("prefOptionNullableBytes")
        fun prefOption(key: String, defValue: ByteArray?): ReadWriteProperty<Any?, ByteArray?> =
            object : ReadWriteProperty<Any?, ByteArray?> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): ByteArray? =
                    default.getBytes(key, defValue)

                override fun setValue(
                    thisRef: Any?,
                    property: KProperty<*>,
                    value: ByteArray?
                ) {
                    if (value != null) default.putBytes(key, value) else remove(key)
                }
            }

        inline fun <reified T : Any> prefOption(
            key: String,
            defValue: T
        ): ReadWriteProperty<Any?, T> =
            object : ReadWriteProperty<Any?, T> {
                override fun getValue(thisRef: Any?, property: KProperty<*>): T {
                    @Suppress("UNCHECKED_CAST")
                    return (default.getObject(key) as? T) ?: defValue
                }

                override fun setValue(
                    thisRef: Any?,
                    property: KProperty<*>,
                    value: T
                ) {
                    default.putObject(key, value)
                }
            }
    }
}
