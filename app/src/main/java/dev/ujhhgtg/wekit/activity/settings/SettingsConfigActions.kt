package dev.ujhhgtg.wekit.activity.settings

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Shared configuration I/O used by both settings engines. */
object SettingsConfigActions {
    fun export(platformContext: Context, localizedContext: () -> Context) {
        TransparentActivity.launch(platformContext) {
            val exportLauncher = registerForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val exportJson = DefaultJson.encodeToString(buildJsonObject {
                        for ((key, value) in WePrefs.default.getAll()) {
                            when (value) {
                                is Boolean -> put(key, value)
                                is Int -> put(key, value)
                                is Long -> put(key, value)
                                is Float -> put(key, value)
                                is Double -> put(key, value)
                                is String -> put(key, value)
                                is Set<*> -> put(key, buildJsonArray {
                                    @Suppress("UNCHECKED_CAST")
                                    (value as Set<String>).forEach(::add)
                                })
                                null -> put(key, JsonNull)
                            }
                        }
                    })
                    runCatching {
                        platformContext.contentResolver.openOutputStream(uri, "w")!!.use { stream ->
                            stream.writer().use { it.write(exportJson) }
                        }
                    }.onFailure {
                        showToastSuspend(localizedContext().getString(R.string.config_export_failed))
                        WeLogger.e("WePrefs", "failed to export", it)
                    }.onSuccess {
                        showToastSuspend(localizedContext().getString(R.string.config_export_success))
                    }
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            exportLauncher.launch("wekit_prefs_backup.json")
        }
    }

    fun importFromDocument(platformContext: Context, localizedContext: () -> Context) {
        TransparentActivity.launch(platformContext) {
            val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val jsonString = platformContext.contentResolver
                            .openInputStream(uri)
                            ?.use { it.reader().readText() }
                            ?: return@launch
                        val jsonObject = DefaultJson.parseToJsonElement(jsonString).jsonObject
                        for ((key, element) in jsonObject) {
                            when (element) {
                                is JsonNull -> WePrefs.default.remove(key)
                                is JsonPrimitive -> when {
                                    element.isString -> WePrefs.default.putString(key, element.content)
                                    element.booleanOrNull != null &&
                                        (element.content == "true" || element.content == "false") ->
                                        WePrefs.putBool(key, element.boolean)

                                    element.longOrNull != null && element.intOrNull == null ->
                                        WePrefs.putLong(key, element.long)

                                    element.intOrNull != null -> WePrefs.putInt(key, element.int)
                                    element.floatOrNull != null -> WePrefs.putFloat(key, element.float)
                                }
                                is JsonArray -> WePrefs.default.putStringSet(
                                    key,
                                    element.mapTo(HashSet()) { it.jsonPrimitive.content },
                                )
                                else -> Unit
                            }
                        }
                    }.onFailure {
                        showToastSuspend(localizedContext().getString(R.string.config_import_failed))
                        WeLogger.e("WePrefs", "failed to import", it)
                    }.onSuccess {
                        showToastSuspend(localizedContext().getString(R.string.config_import_success))
                    }
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            importLauncher.launch(arrayOf("application/json"))
        }
    }

    fun clear() {
        WePrefs.default.clear()
    }
}
