package moe.ouom.wekit.preferences

import com.tencent.mmkv.MMKV
import moe.ouom.wekit.utils.logging.WeLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

class MmkvPrefsImpl(name: String) : WePrefs() {

    private val mmkvInstance = MMKV.mmkvWithID(name, MMKV.MULTI_PROCESS_MODE)

    val mCacheMap = HashMap<String, MutableMap.MutableEntry<String, Any?>>()

    companion object {
        const val TYPE_SUFFIX = $$"$shadow$type"
        private const val TYPE_BOOL = 0x80 + 2
        private const val TYPE_INT = 0x80 + 4
        private const val TYPE_LONG = 0x80 + 6
        private const val TYPE_FLOAT = 0x80 + 7
        private const val TYPE_STRING = 0x80 + 31
        private const val TYPE_STRING_SET = 0x80 + 32
        private const val TYPE_BYTES = 0x80 + 33
        private const val TYPE_SERIALIZABLE = 0x80 + 41
    }

    inner class VirtEntry(override val key: String) : MutableMap.MutableEntry<String, Any?> {
        override val value: Any? get() = getObject(key)
        override fun setValue(newValue: Any?): Any = putObject(key, newValue!!)
        override fun equals(other: Any?): Boolean = other is VirtEntry && key == other.key
        override fun hashCode(): Int = key.hashCode()
    }

    private val mVirtEntrySet: MutableSet<MutableMap.MutableEntry<String, Any?>> =
        object : AbstractMutableSet<MutableMap.MutableEntry<String, Any?>>() {
            override val size: Int get() = mShadowMap.size
            override fun isEmpty(): Boolean = mShadowMap.isEmpty()

            override fun contains(element: MutableMap.MutableEntry<String, Any?>): Boolean =
                any { it == element }

            override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Any?>> =
                object : MutableIterator<MutableMap.MutableEntry<String, Any?>> {
                    val iterator = mShadowMap.keys.iterator()
                    override fun hasNext(): Boolean = iterator.hasNext()
                    override fun next(): MutableMap.MutableEntry<String, Any?> {
                        val key = iterator.next()
                        return mCacheMap.getOrPut(key) { VirtEntry(key) }
                    }

                    override fun remove() = throw UnsupportedOperationException("entry set")
                }

            override fun add(element: MutableMap.MutableEntry<String, Any?>): Boolean =
                throw UnsupportedOperationException("entry set")
        }

    private val mVirtValues: MutableCollection<Any?> = object : AbstractMutableCollection<Any?>() {
        override val size: Int get() = mShadowMap.size
        override fun isEmpty(): Boolean = mShadowMap.isEmpty()
        override fun contains(element: Any?): Boolean = any { it == element }

        override fun iterator(): MutableIterator<Any?> =
            object : MutableIterator<Any?> {
                val iterator = mShadowMap.keys.iterator()
                override fun hasNext(): Boolean = iterator.hasNext()
                override fun next(): Any? = getObject(iterator.next())
                override fun remove() = throw UnsupportedOperationException("entry set")
            }

        override fun add(element: Any?): Boolean =
            throw UnsupportedOperationException("entry set")
    }

    val mShadowMap: MutableMap<String, Any?> = object : AbstractMutableMap<String, Any?>() {
        override val size: Int get() = entries.size
        override fun isEmpty(): Boolean = size == 0
        override fun containsKey(key: String): Boolean = this@MmkvPrefsImpl.containsKey(key)

        override fun containsValue(value: Any?): Boolean = entries.any { it == value }

        override fun get(key: String): Any? = getObject(key)

        override fun put(key: String, value: Any?): Any? {
            val old = getObject(key)
            putObject(key, value!!)
            return old
        }

        override fun remove(key: String): Any? {
            val obj = getObject(key)
            if (obj != null) mmkvInstance.remove(key)
            return obj
        }

        override fun putAll(from: Map<out String, Any?>) {
            for ((key, value) in from) putObject(key, value!!)
        }

        override fun clear() {
            mmkvInstance.clear()
        }

        override val keys: MutableSet<String>
            get() = (mmkvInstance.allKeys()
                ?.filterNot { it.endsWith(TYPE_SUFFIX) }
                ?.toHashSet()
                ?: hashSetOf()) as MutableSet<String>

        override val values: MutableCollection<Any?> get() = mVirtValues
        override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>> get() = mVirtEntrySet
    }

    override fun getAll(): Map<String, *> = mShadowMap

    override fun getString(key: String): String? = mmkvInstance.getString(key, null)

    override fun getString(key: String, defValue: String?): String? =
        mmkvInstance.getString(key, defValue)

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        mmkvInstance.getStringSet(key, defValues)

    override fun getInt(key: String, defValue: Int): Int = mmkvInstance.getInt(key, defValue)

    override fun getLong(key: String, defValue: Long): Long = mmkvInstance.getLong(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float =
        mmkvInstance.getFloat(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        mmkvInstance.getBoolean(key, defValue)

    override fun contains(key: String): Boolean = mmkvInstance.contains(key)

    override fun getObject(key: String): Any? {
        if (!mmkvInstance.contains(key)) return null
        return when (mmkvInstance.getInt(key + TYPE_SUFFIX, 0)) {
            TYPE_BOOL -> mmkvInstance.getBoolean(key, false)
            TYPE_FLOAT -> mmkvInstance.getFloat(key, 0f)
            TYPE_INT -> mmkvInstance.getInt(key, 0)
            TYPE_LONG -> mmkvInstance.getLong(key, 0L)
            TYPE_STRING -> mmkvInstance.getString(key, null)
            TYPE_STRING_SET -> mmkvInstance.getStringSet(key, null)
            TYPE_BYTES -> mmkvInstance.getBytes(key, null)
            TYPE_SERIALIZABLE -> {
                val bytes = mmkvInstance.getBytes(key, null) ?: return null
                runCatching {
                    ObjectInputStream(ByteArrayInputStream(bytes)).readObject()
                }.onFailure { WeLogger.e(it) }.getOrNull()
            }

            else -> null
        }
    }

    override fun putObject(key: String, obj: Any): WePrefs {
        when (obj) {
            is Float, is Double -> putFloat(key, (obj as Number).toFloat())
            is Long -> putLong(key, obj)
            is Int -> putInt(key, obj)
            is Boolean -> putBoolean(key, obj)
            is String -> putString(key, obj)
            is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, obj as Set<String>)
            is ByteArray -> putBytes(key, obj)
            is Array<*> if obj.isArrayOf<String>() -> {
                @Suppress("UNCHECKED_CAST")
                putStringSet(key, (obj as Array<String>).toHashSet())
            }

            is Serializable -> runCatching {
                val outputStream = ByteArrayOutputStream()
                ObjectOutputStream(outputStream).writeObject(obj)
                mmkvInstance.putBytes(key, outputStream.toByteArray())
                mmkvInstance.putInt(key + TYPE_SUFFIX, TYPE_SERIALIZABLE)
            }.onFailure { throw RuntimeException(it) }

            else -> throw IllegalArgumentException("unsupported type ${obj::class}")
        }
        return this
    }

    override fun putString(key: String, value: String?): WePrefs {
        mmkvInstance.putString(key, value)
        mmkvInstance.putInt(key + TYPE_SUFFIX, TYPE_STRING)
        return this
    }

    override fun putStringSet(key: String, values: Set<String>?): WePrefs {
        mmkvInstance.putStringSet(key, values)
        mmkvInstance.putInt(key + TYPE_SUFFIX, TYPE_STRING_SET)
        return this
    }

    override fun putInt(key: String, value: Int): WePrefs {
        mmkvInstance.putInt(key, value)
        mmkvInstance.putInt(key + TYPE_SUFFIX, TYPE_INT)
        return this
    }

    override fun putLong(key: String, value: Long): WePrefs {
        mmkvInstance.putLong(key, value)
        mmkvInstance.putInt(key + TYPE_SUFFIX, TYPE_LONG)
        return this
    }

    override fun putFloat(key: String, value: Float): WePrefs {
        mmkvInstance.putFloat(key, value)
        mmkvInstance.putInt(key + TYPE_SUFFIX, TYPE_FLOAT)
        return this
    }

    override fun putBoolean(key: String, value: Boolean): WePrefs {
        mmkvInstance.putBoolean(key, value)
        mmkvInstance.putInt(key + TYPE_SUFFIX, TYPE_BOOL)
        return this
    }

    override fun getBytesOrDefault(key: String, defValue: ByteArray): ByteArray =
        mmkvInstance.getBytes(key, defValue)!!

    override fun getBytes(key: String, defValue: ByteArray?): ByteArray? =
        mmkvInstance.getBytes(key, defValue)

    override fun putBytes(key: String, value: ByteArray) {
        mmkvInstance.putBytes(key, value)
        mmkvInstance.putInt(key + TYPE_SUFFIX, TYPE_BYTES)
    }

    override fun remove(key: String): WePrefs {
        mmkvInstance.remove(key)
        mmkvInstance.remove(key + TYPE_SUFFIX)
        return this
    }

    override fun clear(): WePrefs {
        mmkvInstance.clear()
        return this
    }

    override fun save() {
        commit()
    }

    override fun commit(): Boolean = true
    override fun apply() = Unit
    override val isReadOnly: Boolean = false
    override val isPersistent: Boolean = true
}
