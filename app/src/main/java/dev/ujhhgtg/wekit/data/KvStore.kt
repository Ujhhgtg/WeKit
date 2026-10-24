package dev.ujhhgtg.wekit.data

import android.database.Cursor
import dev.ujhhgtg.wekit.data.entity.PreferenceEntryEntity
import dev.ujhhgtg.wekit.utils.fs.LegacyPaths
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import java.nio.file.Files

/**
 * Global key-value store backed by the unified Room database.
 *
 * The SQL work is deliberately kept small and indexed so host hooks can read synchronously;
 * larger documents and assets use their own repositories. Values from the legacy MMKV file
 * (`filesDir/mmkv/wekit_prefs`) are migrated per key through [MmkvReadonlyReader] on first
 * open, resuming an interrupted migration without overwriting already-migrated keys.
 */
object KvStore {
    private const val TAG = "KvStore"

    /** Room namespace, named after the legacy MMKV instance it migrated. */
    private const val NAMESPACE = "wekit_prefs"

    private val database get() = WeKitDatabase.instance
    private val sql get() = database.openHelper.writableDatabase

    @Volatile
    private var migrationDone = false

    private var migrationFailure: Exception? = null

    /**
     * Defers database opening and the legacy MMKV migration to the first preference access.
     * Feature classes initialize their `prefOption` delegates at class-init time and must stay
     * initializable on the desktop test JVM, where no host environment exists.
     */
    private fun ensureMigrated() {
        if (migrationDone) return
        synchronized(this) {
            if (migrationDone) return
            // Marked before running so migration's own writes cannot re-enter this block.
            migrationDone = true
            try {
                transaction {
                    // A partially completed StringSet write must never leave orphan members.
                    cleanupOrphanStringSetMembers()
                }
                migrateLegacyIfNeeded()
                migrationFailure = null
            } catch (error: Exception) {
                migrationFailure = error
                WeLogger.e(TAG, "failed to migrate legacy preferences; sources retained", error)
            }
        }
    }

    /** Strict prerequisite for converting old settings into a new authoritative domain. */
    fun requireMigrationKeys(keys: Collection<String>) {
        if (migrationFailure != null) migrationDone = false
        ensureMigrated()
        check(migrationFailure == null || keys.all { read(it) != null }) { "Legacy preference migration has not completed" }
        keys.forEach { key ->
            val entry = read(key) ?: return@forEach
            check(entry.exportable) { "Unsupported non-exportable legacy preference: $key" }
            check(getObject(key) != null && !(entry.valueType == TYPE_STRING_SET && entry.valueBlob != null)) {
                "Unreadable legacy preference value for $key"
            }
        }
    }

    // ── Typed read helpers ────────────────────────────────────────────────────

    fun getBoolOrFalse(key: String): Boolean = getBoolean(key, false)

    fun getBoolOrDef(key: String, def: Boolean): Boolean = getBoolean(key, def)

    fun getString(key: String): String? = getString(key, null)

    fun getStringOrDef(key: String, def: String): String = getString(key, def)!!

    @JvmName("getStringOrDefNullable")
    fun getStringOrDef(key: String, def: String?): String? = getString(key, def)

    fun getStringSet(key: String): Set<String>? = getStringSet(key, null)

    fun getStringSetOrDef(key: String, def: Set<String>): Set<String> = getStringSet(key, def)!!

    fun getIntOrDef(key: String, def: Int): Int = getInt(key, def)

    fun getLongOrDef(key: String, def: Long): Long = getLong(key, def)

    fun getFloatOrDef(key: String, def: Float): Float = getFloat(key, def)

    fun contains(key: String): Boolean = read(key) != null

    fun containsKey(key: String): Boolean = contains(key)

    fun getObject(key: String): Any? {
        val entry = read(key) ?: return null
        return decode(entry.valueType, entry.valueText, entry.valueLong, entry.valueDouble, entry.valueBlob, key)
    }

    fun getBytes(key: String, defValue: ByteArray?): ByteArray? = read(key)?.valueBlob ?: defValue

    fun getBytesOrDefault(key: String, defValue: ByteArray): ByteArray = getBytes(key, null) ?: defValue

    // ── Write helpers ─────────────────────────────────────────────────────────

    fun putString(key: String, value: String) {
        write(key, TYPE_STRING, text = value)
    }

    fun putStringSet(key: String, values: Set<String>) {
        ensureMigrated()
        transaction {
            write(key, TYPE_STRING_SET)
            sql.execSQL("DELETE FROM preference_set_members WHERE namespace = ? AND `key` = ?", arrayOf(NAMESPACE, key))
            values.forEach {
                sql.execSQL(
                    "INSERT OR REPLACE INTO preference_set_members(namespace, `key`, member) VALUES (?, ?, ?)",
                    arrayOf(NAMESPACE, key, it),
                )
            }
        }
    }

    fun putInt(key: String, value: Int) = write(key, TYPE_INT, long = value.toLong())

    fun putLong(key: String, value: Long) = write(key, TYPE_LONG, long = value)

    fun putFloat(key: String, value: Float) = write(key, TYPE_FLOAT, double = value.toDouble())

    fun putBool(key: String, value: Boolean) = write(key, TYPE_BOOL, long = if (value) 1L else 0L)

    fun putBytes(key: String, value: ByteArray) = write(key, TYPE_BYTES, blob = value)

    fun putObject(key: String, obj: Any) {
        when (obj) {
            is Boolean -> putBool(key, obj)
            is Int -> putInt(key, obj)
            is Long -> putLong(key, obj)
            is Float, is Double -> putFloat(key, (obj as Number).toFloat())
            is String -> putString(key, obj)
            is Set<*> -> putStringSet(key, obj.filterIsInstance<String>().toSet())
            is ByteArray -> putBytes(key, obj)
            else -> error("unsupported preference type ${obj::class}")
        }
    }

    fun remove(key: String) {
        ensureMigrated()
        transaction {
            sql.execSQL("DELETE FROM preference_entries WHERE namespace = ? AND `key` = ?", arrayOf(NAMESPACE, key))
            sql.execSQL("DELETE FROM preference_set_members WHERE namespace = ? AND `key` = ?", arrayOf(NAMESPACE, key))
        }
    }

    fun clear() {
        ensureMigrated()
        transaction {
            sql.execSQL("DELETE FROM preference_entries WHERE namespace = ?", arrayOf(NAMESPACE))
            sql.execSQL("DELETE FROM preference_set_members WHERE namespace = ?", arrayOf(NAMESPACE))
        }
    }

    // ── Delegate properties ───────────────────────────────────────────────────

    fun prefOption(key: String, default: String): ReadWriteProperty<Any?, String> =
        object : ReadWriteProperty<Any?, String> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): String = getStringOrDef(key, default)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
                putString(key, value)
            }
        }

    fun prefOption(key: String, default: Int): ReadWriteProperty<Any?, Int> =
        object : ReadWriteProperty<Any?, Int> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): Int = getIntOrDef(key, default)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
                putInt(key, value)
            }
        }

    fun prefOption(key: String, defValue: Boolean): ReadWriteProperty<Any?, Boolean> =
        object : ReadWriteProperty<Any?, Boolean> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = getBoolean(key, defValue)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
                putBool(key, value)
            }
        }

    fun prefOption(key: String, default: Long): ReadWriteProperty<Any?, Long> =
        object : ReadWriteProperty<Any?, Long> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): Long = getLongOrDef(key, default)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) {
                putLong(key, value)
            }
        }

    fun prefOption(key: String, default: Float): ReadWriteProperty<Any?, Float> =
        object : ReadWriteProperty<Any?, Float> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): Float = getFloatOrDef(key, default)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
                putFloat(key, value)
            }
        }

    fun prefOption(key: String, default: Set<String>): ReadWriteProperty<Any?, Set<String>> =
        object : ReadWriteProperty<Any?, Set<String>> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): Set<String> =
                getStringSetOrDef(key, default)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: Set<String>) {
                putStringSet(key, value)
            }
        }

    fun prefOption(key: String, defValue: ByteArray): ReadWriteProperty<Any?, ByteArray> =
        object : ReadWriteProperty<Any?, ByteArray> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): ByteArray =
                getBytesOrDefault(key, defValue)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: ByteArray) {
                putBytes(key, value)
            }
        }

    @JvmName("prefOptionNullable")
    fun prefOption(key: String, defValue: String?): ReadWriteProperty<Any?, String?> =
        object : ReadWriteProperty<Any?, String?> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): String? = getStringOrDef(key, defValue)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
                if (value != null) putString(key, value) else remove(key)
            }
        }

    @JvmName("prefOptionNullableBytes")
    fun prefOption(key: String, defValue: ByteArray?): ReadWriteProperty<Any?, ByteArray?> =
        object : ReadWriteProperty<Any?, ByteArray?> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): ByteArray? = getBytes(key, defValue)

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: ByteArray?) {
                if (value != null) putBytes(key, value) else remove(key)
            }
        }

    inline fun <reified T : Any> prefOption(key: String, defValue: T): ReadWriteProperty<Any?, T> =
        object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T {
                @Suppress("UNCHECKED_CAST")
                return getObject(key) as? T ?: defValue
            }

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                putObject(key, value)
            }
        }

    // ── Room implementation ───────────────────────────────────────────────────

    private fun getBoolean(key: String, defValue: Boolean): Boolean =
        read(key)?.valueLong?.let { it != 0L } ?: defValue

    private fun getInt(key: String, defValue: Int): Int {
        val entry = read(key) ?: return defValue
        return entry.valueLong?.toIntOrNull(entry.valueType) ?: defValue
    }

    private fun getLong(key: String, defValue: Long): Long = read(key)?.valueLong ?: defValue

    private fun getFloat(key: String, defValue: Float): Float = read(key)?.valueDouble?.toFloat() ?: defValue

    private fun getString(key: String, defValue: String?): String? = read(key)?.valueText ?: defValue

    private fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        ensureMigrated()
        // Read the type marker and normalized members in one statement.  Separate queries can
        // straddle another process's atomic replacement and return a mixed generation.
        var valueType: String? = null
        val members = LinkedHashSet<String>()
        sql.query(
            "SELECT e.valueType, m.member FROM preference_entries e " +
                    "LEFT JOIN preference_set_members m " +
                    "ON m.namespace = e.namespace AND m.`key` = e.`key` " +
                    "WHERE e.namespace = ? AND e.`key` = ? ORDER BY m.member",
            arrayOf(NAMESPACE, key),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                valueType = cursor.getString(0)
                if (!cursor.isNull(1)) members += cursor.getString(1)
            }
        }
        if (valueType != TYPE_STRING_SET) return defValues
        return members
    }

    private fun read(key: String): PreferenceEntryEntity? {
        ensureMigrated()
        sql.query(
            "SELECT namespace, `key`, valueType, valueText, valueLong, valueDouble, valueBlob, encodingVersion, revision, updatedAt, exportable FROM preference_entries WHERE namespace = ? AND `key` = ?",
            arrayOf(NAMESPACE, key),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return PreferenceEntryEntity(
                namespace = cursor.getString(0), key = cursor.getString(1), valueType = cursor.getString(2),
                valueText = cursor.getStringOrNull(3), valueLong = cursor.getLongOrNull(4), valueDouble = cursor.getDoubleOrNull(5),
                valueBlob = cursor.getBlobOrNull(6), encodingVersion = cursor.getInt(7), revision = cursor.getLong(8), updatedAt = cursor.getLong(9), exportable = cursor.getInt(10) != 0,
            )
        }
    }

    private fun write(key: String, type: String, text: String? = null, long: Long? = null, double: Double? = null, blob: ByteArray? = null) {
        ensureMigrated()
        transaction {
            sql.execSQL(
                "INSERT OR REPLACE INTO preference_entries(namespace, `key`, valueType, valueText, valueLong, valueDouble, valueBlob, encodingVersion, revision, updatedAt, exportable) VALUES (?, ?, ?, ?, ?, ?, ?, 1, COALESCE((SELECT revision + 1 FROM preference_entries WHERE namespace = ? AND `key` = ?), 1), ?, ?)",
                arrayOf(NAMESPACE, key, type, text, long, double, blob, NAMESPACE, key, System.currentTimeMillis(), if (key == "payment_pswd_encdata") 0 else 1),
            )
            if (type != TYPE_STRING_SET) {
                // Replacing a set with a scalar must remove its normalized members in the same
                // transaction as the entry replacement; otherwise stale members survive forever.
                sql.execSQL("DELETE FROM preference_set_members WHERE namespace = ? AND `key` = ?", arrayOf(NAMESPACE, key))
            }
        }
    }

    private fun decode(type: String, text: String?, long: Long?, double: Double?, blob: ByteArray?, key: String): Any? = when (type) {
        TYPE_BOOL -> long?.let { it != 0L }
        TYPE_INT -> long?.toInt()
        TYPE_LONG -> long
        TYPE_FLOAT -> double?.toFloat()
        TYPE_STRING -> text
        TYPE_STRING_SET -> getStringSet(key, emptySet())
        TYPE_BYTES -> blob
        else -> null
    }

    /** Runs a small preference mutation atomically, reusing an enclosing migration transaction. */
    private inline fun <T> transaction(block: () -> T): T {
        if (sql.inTransaction()) return block()
        sql.beginTransaction()
        return try {
            block().also { sql.setTransactionSuccessful() }
        } finally {
            sql.endTransaction()
        }
    }

    private fun cleanupOrphanStringSetMembers() {
        sql.execSQL(
            "DELETE FROM preference_set_members " +
                    "WHERE namespace = ? AND NOT EXISTS (" +
                    "SELECT 1 FROM preference_entries e " +
                    "WHERE e.namespace = preference_set_members.namespace " +
                    "AND e.`key` = preference_set_members.`key` " +
                    "AND e.valueType = ?)",
            arrayOf(NAMESPACE, TYPE_STRING_SET),
        )
    }

    private fun writeLegacy(key: String, type: String, bytes: ByteArray?) {
        sql.execSQL(
            "INSERT OR REPLACE INTO preference_entries(namespace, `key`, valueType, valueBlob, encodingVersion, revision, updatedAt, exportable) VALUES (?, ?, ?, ?, 1, 1, ?, ?)",
            arrayOf(NAMESPACE, key, type, bytes, System.currentTimeMillis(), if (key == "payment_pswd_encdata") 0 else 1),
        )
        if (type != TYPE_STRING_SET) {
            sql.execSQL("DELETE FROM preference_set_members WHERE namespace = ? AND `key` = ?", arrayOf(NAMESPACE, key))
        }
    }

    /**
     * Migrates the legacy MMKV file by key so a previous interrupted/partial migration can
     * resume and does not strand remaining legacy values merely because one Room row already
     * exists. Unknown or undecodable entries are preserved as raw blobs for later inspection.
     */
    private fun migrateLegacyIfNeeded() {
        // A full restore must not import this installation's pre-restore MMKV over the backup.
        if (!LegacyPaths.localSourcesAllowed) return
        val root = HostInfo.application.filesDir.resolve("mmkv")
        val file = root.resolve(NAMESPACE)
        val crcFile = root.resolve("$NAMESPACE.crc")
        if (Files.notExists(file.toPath()) && Files.notExists(crcFile.toPath())) return
        check(file.isFile && crcFile.isFile) {
            "Legacy MMKV data/CRC pair is incomplete"
        }

        val entries = MmkvReadonlyReader.read(file, crcFile)
        transaction {
            entries.forEach { entry ->
                val alreadyMigrated = sql.query(
                    "SELECT 1 FROM preference_entries WHERE namespace = ? AND `key` = ? LIMIT 1",
                    arrayOf(NAMESPACE, entry.key),
                ).use { it.moveToFirst() }
                if (alreadyMigrated) return@forEach

                val value = runCatching { MmkvReadonlyReader.decode(entry) }.getOrElse { error ->
                    WeLogger.w(TAG, "failed to decode legacy MMKV entry ${entry.key}", error)
                    null
                }
                if (value != null) {
                    putObject(entry.key, value)
                } else {
                    writeLegacy(entry.key, MmkvReadonlyReader.typeName(entry.marker) ?: "legacy:unknown", entry.bytes)
                }
            }
            cleanupOrphanStringSetMembers()
        }
    }

    private const val TYPE_BOOL = "bool"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_STRING = "string"
    private const val TYPE_STRING_SET = "string_set"
    private const val TYPE_BYTES = "bytes"
}

private fun Long.toIntOrNull(type: String?): Int? = if (type == "int") toInt() else null
private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
private fun Cursor.getLongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun Cursor.getDoubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
private fun Cursor.getBlobOrNull(index: Int): ByteArray? = if (isNull(index)) null else getBlob(index)
