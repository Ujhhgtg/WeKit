package dev.ujhhgtg.wekit.features.items.scripting_js

import android.os.Handler
import android.os.Looper
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.toClass
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.WeProtoData
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.asMethod
import dev.ujhhgtg.wekit.utils.reflection.DexKit
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.lang.reflect.Field
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

object JsApiExposer {
    private val TAG = This.Class.simpleName
    private const val TAG_LOG_API = "JsApiExposer.LogApi"
    private const val TAG_HTTP_API = "JsApiExposer.HttpApi"
    private const val TAG_XPOSED_API = "JsApiExposer.XposedApi"
    private const val TAG_REFLECT_API = "JsApiExposer.ReflectApi"
    private const val TAG_DEXKIT_API = "JsApiExposer.DexKitApi"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun exposeApis(scope: ScriptableObject, talker: String? = null) {
        exposeHttpApis(scope)
        exposeLogApis(scope)
        exposeStorageApis(scope)
        exposeDateTimeApis(scope)
        exposeXposedApis(scope)
        exposeReflectApis(scope)
        exposeDexKitApis(scope)
        exposeTaskApis(scope)
        exposeHostInfoApis(scope)
        exposeWeChatApis(scope, talker)
    }

    private const val MAX_CACHE_SIZE_IN_MIB = 500

    @OptIn(ExperimentalPathApi::class)
    private fun exposeHttpApis(scope: ScriptableObject) {
        val httpObj = NativeObject()

        // http.get(url, params?, headers?)
        ScriptableObject.putProperty(
            httpObj, "get",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val url = args.getOrNull(0)?.toString() ?: return null
                    val params = args.getOrNull(1) as? NativeObject
                    val headers = args.getOrNull(2) as? NativeObject

                    WeLogger.i(
                        TAG_HTTP_API,
                        "http.get invoked: url=$url params=$params headers=$headers"
                    )

                    return try {
                        httpGet(url, params, headers)
                    } catch (e: Exception) {
                        WeLogger.e(TAG_HTTP_API, "http.get failed: $url", e)
                        createErrorResponse(e)
                    }
                }
            }
        )

        // http.post(url, form_data_body?, json_body?, headers?)
        ScriptableObject.putProperty(
            httpObj, "post",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val url = args.getOrNull(0)?.toString() ?: return null
                    val formData = args.getOrNull(1) as? NativeObject
                    val jsonBody = args.getOrNull(2) as? NativeObject
                    val headers = args.getOrNull(3) as? NativeObject

                    WeLogger.i(
                        TAG_HTTP_API,
                        "http.post invoked: url=$url formData=$formData jsonBody=$jsonBody headers=$headers"
                    )

                    return try {
                        httpPost(url, formData, jsonBody, headers)
                    } catch (e: Exception) {
                        WeLogger.e(TAG_HTTP_API, "http.post failed: $url", e)
                        createErrorResponse(e)
                    }
                }
            }
        )

        // http.download(url, filename?) -> path: String?
        ScriptableObject.putProperty(
            httpObj, "download",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val url = args.getOrNull(0)?.toString() ?: return null
                    var filename = args.getOrNull(1)?.toString()

                    WeLogger.i(TAG_HTTP_API, "http.download invoked: url=$url filename=$filename")

                    if (filename.isNullOrBlank()) {
                        filename = "download_${System.currentTimeMillis()}"
                        WeLogger.i(TAG_HTTP_API, "no filename provided, using default: $filename")
                    }

                    return try {
                        val cacheDir = (KnownPaths.moduleCache / "javascript_http_api").createDirsSafe()

                        // drop cache if total size of files exceeds limit
                        val totalBytes = try {
                            java.nio.file.Files.walk(cacheDir)
                                .filter { !java.nio.file.Files.isDirectory(it) }
                                .mapToLong { it.fileSize() }
                                .sum()
                        } catch (_: Exception) {
                            0L
                        }
                        if (totalBytes / 1024 / 1024 >= MAX_CACHE_SIZE_IN_MIB) {
                            WeLogger.w(
                                TAG,
                                "http.download cache size too large, dropping cache..."
                            )
                            cacheDir.deleteRecursively()
                        }

                        val destFile = cacheDir.resolve(filename)

                        if (performDownload(url, destFile)) destFile.absolutePathString() else null
                    } catch (e: Exception) {
                        WeLogger.e(TAG_HTTP_API, "http.download failed: $url", e)
                        null
                    }
                }
            }
        )

        ScriptableObject.putProperty(scope, "http", httpObj)
    }

    /**
     * Runs blocking network I/O off the main thread and blocks the caller until it finishes.
     *
     * The JS `http` API is synchronous, but the JS hooks (e.g. onMessage via the DB insert
     * listener) may be invoked on the main thread. Performing socket I/O there throws
     * [android.os.NetworkOnMainThreadException]. Executing the I/O on a worker thread and joining
     * avoids the exception while preserving the synchronous contract. When already off the main
     * thread (e.g. packet interceptor threads), the block runs inline.
     */
    private fun <T> runOffMainThread(block: () -> T): T {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return block()
        }

        var result: Result<T>? = null
        val worker = thread(name = "JsHttpThread") {
            result = runCatching(block)
        }
        worker.join()
        return result!!.getOrThrow()
    }

    /** Plain holder for response data read off the main thread; converted to JS on the JS thread. */
    private class RawHttpResponse(
        val statusCode: Int,
        val body: String,
        val contentType: String,
        val isSuccessful: Boolean,
        val headers: List<Pair<String, String>>,
    )

    private fun executeRequest(request: Request): RawHttpResponse = runOffMainThread {
        httpClient.newCall(request).execute().use { response ->
            RawHttpResponse(
                statusCode = response.code,
                body = response.body.string(),
                contentType = response.header("Content-Type") ?: "",
                isSuccessful = response.isSuccessful,
                headers = response.headers.names().map { it to (response.header(it) ?: "") },
            )
        }
    }

    private fun performDownload(url: String, destFile: Path): Boolean = runOffMainThread {
        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runOffMainThread false

            @Suppress("UNNECESSARY_SAFE_CALL")
            response.body?.byteStream()?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        true
    }

    private fun httpGet(
        urlString: String,
        params: NativeObject?,
        headers: NativeObject?
    ): NativeObject {
        // Build URL with query parameters
        val finalUrl = if (params != null) {
            val httpUrl =
                urlString.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid URL")
            val builder = httpUrl.newBuilder()
            params.keys.forEach { key ->
                val value = params[key]?.toString() ?: ""
                builder.addQueryParameter(key.toString(), value)
            }
            builder.build().toString()
        } else urlString

        val requestBuilder = Request.Builder().url(finalUrl)

        // Add headers
        headers?.let { applyHeaders(requestBuilder, it) }

        return createHttpResponse(executeRequest(requestBuilder.build()))
    }

    private fun httpPost(
        urlString: String,
        formData: NativeObject?,
        jsonBody: NativeObject?,
        headers: NativeObject?
    ): NativeObject {
        val requestBuilder = Request.Builder().url(urlString)

        // Build request body
        val body = when {
            jsonBody != null -> {
                val json = nativeObjectToJson(jsonBody)
                json.toRequestBody("application/json; charset=utf-8".toMediaType())
            }

            formData != null -> {
                val formBuilder = FormBody.Builder()
                formData.keys.forEach { key ->
                    val value = formData[key]?.toString() ?: ""
                    formBuilder.add(key.toString(), value)
                }
                formBuilder.build()
            }

            // No body — use empty RequestBody without content-type
            else -> "".toRequestBody(null)
        }

        requestBuilder.post(body)

        // Add headers
        headers?.let { applyHeaders(requestBuilder, it) }

        return createHttpResponse(executeRequest(requestBuilder.build()))
    }

    private fun applyHeaders(requestBuilder: Request.Builder, headers: NativeObject) {
        headers.keys.forEach { key ->
            val value = headers[key]?.toString()
            if (value != null) {
                requestBuilder.addHeader(key.toString(), value)
            }
        }
    }

    private fun nativeObjectToJson(obj: NativeObject): String {
        val jsonObject = JSONObject()
        obj.keys.forEach { key ->
            val value = obj[key]
            jsonObject.put(key.toString(), convertJsValue(value))
        }
        return jsonObject.toString()
    }

    private fun convertJsValue(value: Any?): Any? {
        return when (value) {
            is NativeObject -> {
                val json = JSONObject()
                value.keys.forEach { key ->
                    json.put(key.toString(), convertJsValue(value[key]))
                }
                json
            }

            is NativeArray -> {
                val array = org.json.JSONArray()
                for (i in 0 until value.length) {
                    array.put(convertJsValue(value[i]))
                }
                array
            }

            is Number, is String, is Boolean -> value
            null -> JSONObject.NULL
            else -> value.toString()
        }
    }

    private fun createHttpResponse(response: RawHttpResponse): NativeObject {
        val cx = Context.getCurrentContext()!!
        val scope = cx.initStandardObjects()

        val body = response.body

        val responseObj = NativeObject()
        responseObj.put("status", responseObj, response.statusCode)
        responseObj.put("body", responseObj, body)
        responseObj.put("ok", responseObj, response.isSuccessful)

        // Try to parse as JSON if content-type indicates JSON
        if (response.contentType.contains("application/json", ignoreCase = true) && body.isNotEmpty()) {
            try {
                val jsonObj = cx.evaluateString(scope, "($body)", "response", 1, null)
                responseObj.put("json", responseObj, jsonObj)
            } catch (e: Exception) {
                // If parsing fails, json will be undefined
                WeLogger.w(TAG, "Failed to parse JSON response body", e)
            }
        }

        // Convert headers to JS object
        val headersObj = NativeObject()
        response.headers.forEach { (name, value) ->
            headersObj.put(name, headersObj, value)
        }
        responseObj.put("headers", responseObj, headersObj)

        return responseObj
    }

    private fun createErrorResponse(e: Exception): NativeObject {
        val response = NativeObject()
        response.put("status", response, 0)
        response.put("body", response, "")
        response.put("ok", response, false)
        response.put("error", response, e.message ?: "Unknown error")
        return response
    }

    private fun exposeLogApis(scope: ScriptableObject) {
        val logObj = NativeObject()

        // log.d(msg)
        ScriptableObject.putProperty(
            logObj, "d",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.d(TAG_LOG_API, msg)
                    return Undefined.instance
                }
            }
        )

        // log.i(msg)
        ScriptableObject.putProperty(
            logObj, "i",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.i(TAG_LOG_API, msg)
                    return Undefined.instance
                }
            }
        )

        // log.w(msg)
        ScriptableObject.putProperty(
            logObj, "w",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.w(TAG_LOG_API, msg)
                    return Undefined.instance
                }
            }
        )

        // log.e(msg)
        ScriptableObject.putProperty(
            logObj, "e",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.e(TAG_LOG_API, msg)
                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(scope, "log", logObj)
    }

    private fun exposeDateTimeApis(scope: ScriptableObject) {
        val dtObj = NativeObject()

        ScriptableObject.putProperty(
            dtObj, "sleepS",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val seconds = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                    if (seconds > 0) {
                        try {
                            Thread.sleep(seconds * 1000)
                        } catch (e: InterruptedException) {
                            WeLogger.w(TAG_LOG_API, "datetime.sleep interrupted", e)
                            Thread.currentThread().interrupt()
                        }
                    }
                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(
            dtObj, "sleepMs",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val ms = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
                    if (ms > 0) {
                        try {
                            Thread.sleep(ms)
                        } catch (e: InterruptedException) {
                            WeLogger.w(TAG_LOG_API, "datetime.sleep interrupted", e)
                            Thread.currentThread().interrupt()
                        }
                    }
                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(
            dtObj, "getCurrentUnixEpoch",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    return System.currentTimeMillis() / 1000
                }
            }
        )

        ScriptableObject.putProperty(scope, "datetime", dtObj)
    }

    @Suppress("JavaCollectionWithNullableTypeArgument")
    private val storage = ConcurrentHashMap<String, Any?>()

    private val DATA_DIR_PATH by lazy { (KnownPaths.moduleData / "data").createDirsSafe() }

    private val storageFile get() = DATA_DIR_PATH.resolve("javascript_storage_api.json")

    init {
        loadStorageFromDisk()
    }

    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable {
        runCatching {
            val json = buildJsonObject {
                storage.forEach { (key, value) ->
                    storageValueToJson(value)?.let { put(key, it) }
                }
            }
            storageFile.writeText(DefaultJson.encodeToString(json))
        }.onFailure { WeLogger.e(TAG, "failed to save js storage to disk", it) }
    }

    private fun storageValueToJson(value: Any?): JsonElement? = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is NativeObject -> {
            buildJsonObject {
                value.keys.forEach { key ->
                    val child = storageValueToJson(value[key])
                    if (child != null) put(key.toString(), child)
                }
            }
        }

        is NativeArray -> {
            buildJsonArray {
                for (i in 0 until value.length) {
                    val child = storageValueToJson(value[i])
                    if (child != null) add(child)
                }
            }
        }

        else -> null // skip functions, wrappers, etc.
    }

    private fun loadStorageFromDisk() {
        runCatching {
            if (!storageFile.exists()) return
            val root = DefaultJson.decodeFromString<JsonObject>(storageFile.readText())
            root.forEach { (k, v) ->
                storage[k] = jsonToStorageValue(v)
            }
        }.onFailure { WeLogger.e(TAG, "failed to load js storage from disk", it) }
    }

    private fun jsonToStorageValue(element: JsonElement): Any? = when (element) {
        is JsonNull -> null

        is JsonPrimitive -> when {
            element.isString -> element.content
            else -> element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
        }

        is JsonObject -> {
            val map = LinkedHashMap<String, Any?>(element.size)
            element.forEach { (k, v) -> map[k] = jsonToStorageValue(v) }
            map
        }

        is JsonArray -> {
            val list = ArrayList<Any?>(element.size)
            element.forEach { list.add(jsonToStorageValue(it)) }
            list
        }
    }

    // prevent blocking js execution if the file grows too large, but that would be a misuse of this API anyway
    private fun saveStorageToDisk() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 500)
    }

    private fun exposeStorageApis(scope: ScriptableObject) {
        val storageObj = NativeObject()

        // storage.get(key) -> object
        ScriptableObject.putProperty(
            storageObj, "get",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return null
                    val value = storage[key]

                    return value?.let { Context.javaToJS(it, scope, cx) } ?: Undefined.instance
                }
            }
        )

        // storage.getOrDefault(key, defaultValue) -> object
        ScriptableObject.putProperty(
            storageObj, "getOrDefault",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return args.getOrNull(1)
                    val value = storage.getOrDefault(key, args.getOrNull(1))
                    return value?.let { Context.javaToJS(it, scope, cx) } ?: Undefined.instance
                }
            }
        )

        // storage.set(key, object)
        ScriptableObject.putProperty(
            storageObj, "set",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return null
                    val value = args.getOrNull(1)

                    if (value is Undefined) {
                        WeLogger.w(
                            TAG,
                            "js tries to set undefined into cache, removing that key instead"
                        )
                        storage.remove(key)
                    } else {
                        storage[key] = value
                    }

                    saveStorageToDisk()
                    return null
                }
            }
        )

        // storage.clear()
        ScriptableObject.putProperty(
            storageObj, "clear",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    storage.clear()
                    saveStorageToDisk()
                    return Undefined.instance
                }
            }
        )

        // storage.remove(key)
        ScriptableObject.putProperty(
            storageObj, "remove",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return Undefined.instance
                    val removed = storage.remove(key)
                    saveStorageToDisk()
                    return removed?.let { Context.javaToJS(it, scope, cx) } ?: Undefined.instance
                }
            }
        )

        // storage.hasKey(key) -> bool
        ScriptableObject.putProperty(
            storageObj, "hasKey",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val key = args.getOrNull(0)?.toString() ?: return false
                    return storage.containsKey(key)
                }
            }
        )

        // storage.isEmpty() -> bool
        ScriptableObject.putProperty(
            storageObj, "isEmpty",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    return storage.isEmpty()
                }
            }
        )

        // storage.keys() -> Array
        ScriptableObject.putProperty(
            storageObj, "keys",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    // Converts Kotlin Set to a JS Array
                    return cx.newArray(scope, storage.keys.toTypedArray<Any>())
                }
            }
        )

        // storage.size() -> int
        ScriptableObject.putProperty(
            storageObj, "size",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    return storage.size
                }
            }
        )

        ScriptableObject.putProperty(scope, "storage", storageObj)
    }

    private fun exposeHostInfoApis(scope: ScriptableObject) {
        val hostObj = NativeObject()

        ScriptableObject.putProperty(hostObj, "application", HostInfo.application)
        ScriptableObject.putProperty(hostObj, "packageName", HostInfo.packageName)
        ScriptableObject.putProperty(hostObj, "versionCode", HostInfo.versionCode)
        ScriptableObject.putProperty(hostObj, "versionName", HostInfo.versionName)
        ScriptableObject.putProperty(hostObj, "isHostGooglePlay", HostInfo.isHostGooglePlay)

        ScriptableObject.putProperty(scope, "hostinfo", hostObj)
    }

    private fun exposeWeChatApis(scope: ScriptableObject, talker: String? = null) {
        val weObj = NativeObject()

        fun NativeObject.putAction(name: String, action: (Array<Any?>) -> Unit) {
            ScriptableObject.putProperty(this, name, object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    action(args)
                    return Undefined.instance
                }
            })
        }

        weObj.putAction("sendText") { args ->
            val to = args.getOrNull(0)?.toString()
            val text = args.getOrNull(1)?.toString()
            if (to != null && text != null) WeMessageApi.sendText(to, text)
        }
        weObj.putAction("sendImage") { args ->
            val to = args.getOrNull(0)?.toString()
            val path = args.getOrNull(1)?.toString()
            if (to != null && path != null) WeMessageApi.sendImage(to, path)
        }
        weObj.putAction("sendFile") { args ->
            val to = args.getOrNull(0)?.toString()
            val path = args.getOrNull(1)?.toString()
            if (to != null && path != null) {
                val title = args.getOrNull(2)?.toString() ?: path.substringAfterLast('/')
                WeMessageApi.sendFile(to, path, title)
            }
        }
        weObj.putAction("sendVoice") { args ->
            val to = args.getOrNull(0)?.toString()
            val path = args.getOrNull(1)?.toString()
            if (to != null && path != null) {
                val durationMs = (args.getOrNull(2) as? Number)?.toInt() ?: 0
                WeMessageApi.sendVoice(to, path, durationMs)
            }
        }
        weObj.putAction("sendAppMsg") { args ->
            val to = args.getOrNull(0)?.toString()
            val content = args.getOrNull(1)?.toString()
            if (to != null && content != null) WeMessageApi.sendXmlAppMsg(to, content)
        }
        ScriptableObject.putProperty(
            weObj, "sendCgi",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any? {
                    val uri = args.getOrNull(0)?.toString() ?: return Undefined.instance
                    val cgiId = (args.getOrNull(1) as? Number)?.toInt() ?: return Undefined.instance
                    val funcId = (args.getOrNull(2) as? Number)?.toInt() ?: return Undefined.instance
                    val routeId = (args.getOrNull(3) as? Number)?.toInt() ?: return Undefined.instance
                    val jsonPayload = args.getOrNull(4)?.toString() ?: return Undefined.instance
                    val onSuccess = args.getOrNull(5) as? org.mozilla.javascript.Function ?: return Undefined.instance
                    val onFailure = args.getOrNull(6) as? org.mozilla.javascript.Function ?: return Undefined.instance

                    WePacketHelper.sendCgi(
                        uri, cgiId, funcId, routeId, jsonPayload
                    ) {
                        onSuccess { bytes ->
                            val json = bytes?.let { WeProtoData.fromBytes(it).toJsonObject().toString() } ?: "{}"
                            onSuccess.call(cx, scope, thisObj, arrayOf(json))
                        }
                        onFailure { _, _, errMsg ->
                            onFailure.call(cx, scope, thisObj, arrayOf(errMsg))
                        }
                    }

                    return Undefined.instance
                }
            }
        )
        if (talker != null) {
            weObj.putAction("replyText") { args ->
                val text = args.getOrNull(0)?.toString()
                if (text != null) WeMessageApi.sendText(talker, text)
            }
            weObj.putAction("replyImage") { args ->
                val path = args.getOrNull(0)?.toString()
                if (path != null) WeMessageApi.sendImage(talker, path)
            }
            weObj.putAction("replyFile") { args ->
                val path = args.getOrNull(0)?.toString()
                if (path != null) {
                    val title = args.getOrNull(1)?.toString() ?: path.substringAfterLast('/')
                    WeMessageApi.sendFile(talker, path, title)
                }
            }
            weObj.putAction("replyVoice") { args ->
                val path = args.getOrNull(0)?.toString()
                if (path != null) {
                    val durationMs = (args.getOrNull(1) as? Number)?.toInt() ?: 0
                    WeMessageApi.sendVoice(talker, path, durationMs)
                }
            }
            weObj.putAction("replyAppMsg") { args ->
                val content = args.getOrNull(0)?.toString()
                if (content != null) WeMessageApi.sendXmlAppMsg(talker, content)
            }
        }
        ScriptableObject.putProperty(weObj, "getSelfWxId", object : BaseFunction() {
            override fun call(
                cx: Context?,
                scope: Scriptable?,
                thisObj: Scriptable?,
                args: Array<Any?>?
            ): Any {
                return WeApi.selfWxId
            }
        })
        ScriptableObject.putProperty(weObj, "getSelfCustomWxId", object : BaseFunction() {
            override fun call(
                cx: Context?,
                scope: Scriptable?,
                thisObj: Scriptable?,
                args: Array<Any?>?
            ): Any {
                return WeApi.selfCustomWxId
            }
        })

        ScriptableObject.putProperty(scope, "wechat", weObj)
    }

    private fun exposeXposedApis(scope: ScriptableObject) {
        val xposedObj = NativeObject()

        ScriptableObject.putProperty(
            xposedObj, "hookBefore",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    // Support two overloads:
                    //   hookBefore(javaMethod: JavaMethod, hookFunc: Function)
                    //   hookBefore(className: string, methodName: string, hookFunc: Function)
                    val first = args.getOrNull(0)
                    if (first is NativeObject) {
                        val methodProp = try {
                            Context.jsToJava(
                                getProperty(first, "__method"),
                                Method::class.java
                            ) as? Method
                        } catch (_: Exception) {
                            null
                        }
                        if (methodProp != null) {
                            // JavaMethod overload
                            val hookFunc = args.getOrNull(1) as? org.mozilla.javascript.Function
                                ?: return Undefined.instance
                            try {
                                val unhook = methodProp.makeAccessible().hookBeforeDirectly {
                                    val cx = Context.enter()
                                    val scope = cx.init()
                                    val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                                    val jsArgs = cx.newArray(scope, this.args)
                                    val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs))
                                    if (hookResult != null && hookResult !is Undefined) {
                                        result = hookResult
                                    }
                                }
                                return createHookHandle(unhook)
                            } catch (e: Exception) {
                                WeLogger.e(TAG_XPOSED_API, "xposed.hookBefore (JavaMethod) failed", e)
                            }
                        }
                        return Undefined.instance
                    }

                    // Original (className, methodName, hookFunc) overload
                    val className = first?.toString() ?: return Undefined.instance
                    val methodName = args.getOrNull(1)?.toString() ?: return Undefined.instance
                    val hookFunc = args.getOrNull(2) as? org.mozilla.javascript.Function ?: return Undefined.instance

                    try {
                        val clazz = className.toClass()
                        val method = clazz.methods.firstOrNull { it.name == methodName }
                        if (method == null) {
                            WeLogger.e(TAG_XPOSED_API, "xposed.hookBefore: no method named $methodName in $className")
                            return Undefined.instance
                        }
                        val unhook = method.hookBeforeDirectly {
                            val cx = Context.enter()
                            val scope = cx.init()
                            val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                            val jsArgs = cx.newArray(scope, this.args)
                            val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs))
                            if (hookResult != null && hookResult !is Undefined) {
                                result = hookResult
                            }
                        }
                        return createHookHandle(unhook)
                    } catch (e: Exception) {
                        WeLogger.e(TAG_XPOSED_API, "xposed.hookBefore failed", e)
                    }
                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(
            xposedObj, "hookAfter",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    // Support two overloads:
                    //   hookBefore(javaMethod: JavaMethod, hookFunc: Function)
                    //   hookBefore(className: string, methodName: string, hookFunc: Function)
                    val first = args.getOrNull(0)
                    if (first is NativeObject) {
                        val methodProp = try {
                            Context.jsToJava(
                                getProperty(first, "__method"),
                                Method::class.java
                            ) as? Method
                        } catch (_: Exception) {
                            null
                        }
                        if (methodProp != null) {
                            // JavaMethod overload
                            val hookFunc = args.getOrNull(1) as? org.mozilla.javascript.Function
                                ?: return Undefined.instance
                            try {
                                val unhook = methodProp.makeAccessible().hookAfterDirectly {
                                    val cx = Context.enter()
                                    val scope = cx.init()
                                    val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                                    val jsArgs = cx.newArray(scope, this.args)
                                    val jsResult = result?.let { Context.javaToJS(it, scope, cx) }
                                        ?: Undefined.instance
                                    val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs, jsResult))
                                    if (hookResult != null && hookResult !is Undefined) {
                                        result = hookResult
                                    }
                                }
                                return createHookHandle(unhook)
                            } catch (e: Exception) {
                                WeLogger.e(TAG_XPOSED_API, "xposed.hookAfter (JavaMethod) failed", e)
                            }
                        }
                        return Undefined.instance
                    }

                    // Original (className, methodName, hookFunc) overload
                    val className = first?.toString() ?: return Undefined.instance
                    val methodName = args.getOrNull(1)?.toString() ?: return Undefined.instance
                    val hookFunc = args.getOrNull(2) as? org.mozilla.javascript.Function ?: return Undefined.instance

                    try {
                        val clazz = className.toClass()
                        val method = clazz.methods.firstOrNull { it.name == methodName }
                        if (method == null) {
                            WeLogger.e(TAG_XPOSED_API, "xposed.hookAfter: no method named $methodName in $className")
                            return Undefined.instance
                        }
                        val unhook = method.hookAfterDirectly {
                            val cx = Context.enter()
                            val scope = cx.init()
                            val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                            val jsArgs = cx.newArray(scope, this.args)
                            val jsResult = result?.let { Context.javaToJS(it, scope, cx) }
                                ?: Undefined.instance
                            val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs, jsResult))
                            if (hookResult != null && hookResult !is Undefined) {
                                result = hookResult
                            }
                        }
                        return createHookHandle(unhook)
                    } catch (e: Exception) {
                        WeLogger.e(TAG_XPOSED_API, "xposed.hookAfter failed", e)
                    }
                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(scope, "xposed", xposedObj)
    }

    private fun exposeTaskApis(scope: ScriptableObject) {
        val taskObj = NativeObject()

        ScriptableObject.putProperty(
            taskObj, "run",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val func = args.getOrNull(0) as? org.mozilla.javascript.Function
                        ?: return Undefined.instance

                    thread(name = "JsTaskThread") {
                        val cx = Context.enter()
                        try {
                            val scope = cx.init()
                            func.call(cx, scope, thisObj, emptyArray())
                        } catch (e: Exception) {
                            WeLogger.e(TAG, "task.run failed", e)
                        } finally {
                            Context.exit()
                        }
                    }

                    return Undefined.instance
                }
            }
        )

        ScriptableObject.putProperty(scope, "task", taskObj)
    }

    // --- Reflection API ---

    private fun getJvmDescriptor(type: Class<*>): String = when {
        type.isPrimitive -> when (type.name) {
            "void" -> "V"
            "int" -> "I"
            "boolean" -> "Z"
            "byte" -> "B"
            "short" -> "S"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            "char" -> "C"
            else -> error("Unknown primitive: ${type.name}")
        }

        type.isArray -> "[" + getJvmDescriptor(type.componentType!!)
        else -> "L${type.name.replace('.', '/')};"
    }

    private fun getMethodDescriptor(method: Method): String {
        val params = method.parameterTypes.joinToString("") { getJvmDescriptor(it) }
        return "($params)${getJvmDescriptor(method.returnType)}"
    }

    private fun getModifierStrings(mods: Int): Array<Any> =
        Modifier.toString(mods).split(" ").toTypedArray()

    private fun createJavaClassObject(clazz: Class<*>): NativeObject {
        val obj = NativeObject()

        ScriptableObject.putProperty(obj, "name", clazz.name)

        ScriptableObject.putProperty(obj, "createInstance", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                val jsArgs = args.getOrNull(0) as? NativeArray? ?: return Undefined.instance
                val javaArgs = Array(jsArgs.length.toInt()) { i ->
                    val jsVal = jsArgs[i]
                    try {
                        Context.jsToJava(jsVal, Any::class.java)
                    } catch (_: Exception) {
                        jsVal
                    }
                }
                return try {
                    @Suppress("UNCHECKED_CAST")
                    val instance = (clazz as Class<Any>).createInstance(*javaArgs)
                    Context.javaToJS(instance, scope, cx) ?: Undefined.instance
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect JavaClass.createInstance failed for ${clazz.name}", e)
                    Undefined.instance
                }
            }
        })

        ScriptableObject.putProperty(obj, "getMethods", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                val wrappers = clazz.declaredMethods.map {
                    createJavaMethodObject(it, clazz, cx, scope)
                }
                return cx.newArray(scope, wrappers.toTypedArray<Any>())
            }
        })

        ScriptableObject.putProperty(obj, "getFields", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                val wrappers = clazz.declaredFields.map {
                    createJavaFieldObject(it, clazz, cx, scope)
                }
                return cx.newArray(scope, wrappers.toTypedArray<Any>())
            }
        })

        return obj
    }

    private fun createJavaFieldObject(
        field: Field,
        clazz: Class<*>,
        cx: Context,
        scope: Scriptable
    ): NativeObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "name", field.name)
        ScriptableObject.putProperty(obj, "clazz", createJavaClassObject(clazz))
        ScriptableObject.putProperty(obj, "type", createJavaClassObject(field.type))
        ScriptableObject.putProperty(
            obj, "modifiers",
            cx.newArray(scope, getModifierStrings(field.modifiers))
        )

        ScriptableObject.putProperty(obj, "get", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                return try {
                    val instance = args.getOrNull(0)
                    val value = field.makeAccessible().get(if (instance is Undefined) null else instance)
                    Context.javaToJS(value, scope, cx) ?: Undefined.instance
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect field.get failed on ${clazz.name}.${field.name}", e)
                    Undefined.instance
                }
            }
        })

        ScriptableObject.putProperty(obj, "set", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                return try {
                    field.makeAccessible()
                    if (args.size >= 2) {
                        val instance = args[0]
                        val value = Context.jsToJava(args[1], field.type)
                        field.set(if (instance is Undefined) null else instance, value)
                    } else {
                        val value = Context.jsToJava(args.getOrNull(0), field.type)
                        field.set(null, value)
                    }
                    Undefined.instance
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect field.set failed on ${clazz.name}.${field.name}", e)
                    Undefined.instance
                }
            }
        })

        return obj
    }

    private fun createHookHandle(unhook: XC_MethodHook.Unhook): NativeObject {
        val handle = NativeObject()
        ScriptableObject.putProperty(handle, "unhook", object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any {
                unhook.unhook()
                return Undefined.instance
            }
        })
        return handle
    }

    private fun createJavaMethodObject(
        method: Method,
        clazz: Class<*>,
        cx: Context,
        scope: Scriptable
    ): NativeObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "name", method.name)
        ScriptableObject.putProperty(obj, "clazz", createJavaClassObject(clazz))
        ScriptableObject.putProperty(obj, "descriptor", getMethodDescriptor(method))
        ScriptableObject.putProperty(
            obj, "paramTypes",
            cx.newArray(scope, method.parameterTypes.map { createJavaClassObject(it) }.toTypedArray<Any>())
        )
        ScriptableObject.putProperty(obj, "returnType", createJavaClassObject(method.returnType))
        ScriptableObject.putProperty(
            obj, "modifiers",
            cx.newArray(scope, getModifierStrings(method.modifiers))
        )

        // Store hidden references for the xposed.* overload that accepts JavaMethod
        ScriptableObject.putProperty(obj, "__method", method)

        ScriptableObject.putProperty(obj, "hookBefore", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                val hookFunc = args.getOrNull(0) as? org.mozilla.javascript.Function
                    ?: return Undefined.instance
                try {
                    val unhook = method.makeAccessible().hookBeforeDirectly {
                        val cx = Context.enter()
                        val scope = cx.init()
                        val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                        val jsArgs = cx.newArray(scope, this.args)
                        val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs))
                        if (hookResult != null && hookResult !is Undefined) {
                            result = hookResult
                        }
                    }
                    return createHookHandle(unhook)
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect method hookBefore failed on ${clazz.name}.${method.name}", e)
                }
                return Undefined.instance
            }
        })

        ScriptableObject.putProperty(obj, "hookAfter", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                val hookFunc = args.getOrNull(0) as? org.mozilla.javascript.Function
                    ?: return Undefined.instance
                try {
                    val unhook = method.makeAccessible().hookAfterDirectly {
                        val cx = Context.enter()
                        val scope = cx.init()
                        val jsThis = thisObject?.let { Context.javaToJS(it, scope, cx) }
                        val jsArgs = cx.newArray(scope, this.args)
                        val jsResult = result?.let { Context.javaToJS(it, scope, cx) }
                        val hookResult = hookFunc.call(cx, scope, thisObj, arrayOf(jsThis, jsArgs, jsResult))
                        if (hookResult != null && hookResult !is Undefined) {
                            result = hookResult
                        }
                    }
                    return createHookHandle(unhook)
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect method hookAfter failed on ${clazz.name}.${method.name}", e)
                }
                return Undefined.instance
            }
        })

        ScriptableObject.putProperty(obj, "invoke", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                val instance = args.getOrNull(0)
                val jsArgs = args.getOrNull(1)

                val paramTypes = method.parameterTypes
                val javaArgs: Array<Any?> = if (jsArgs is NativeArray) {
                    Array(paramTypes.size) { i ->
                        if (i < jsArgs.length.toInt()) {
                            try {
                                Context.jsToJava(jsArgs[i], paramTypes[i])
                            } catch (e: Exception) {
                                WeLogger.w(
                                    TAG_REFLECT_API,
                                    "reflect method invoke arg conversion failed on $className.${method.name} arg[$i]: ${jsArgs[i]}",
                                    e
                                )
                                null
                            }
                        } else {
                            null
                        }
                    }
                } else {
                    emptyArray()
                }

                val resultObj = NativeObject()

                try {
                    val result = method.makeAccessible().invoke(
                        if (instance is Undefined) null else instance,
                        *javaArgs
                    )
                    resultObj.put("value", resultObj, Context.javaToJS(result, scope, cx) ?: Undefined.instance)
                    resultObj.put("exception", resultObj, false)
                } catch (e: Exception) {
                    WeLogger.e(
                        TAG_REFLECT_API,
                        "reflect method invoke failed on ${clazz.name}.${method.name}",
                        e
                    )
                    resultObj.put("value", resultObj, Context.javaToJS(e, scope, cx) ?: Undefined.instance)
                    resultObj.put("exception", resultObj, true)
                }

                return resultObj
            }
        })

        return obj
    }

    private fun exposeReflectApis(scope: ScriptableObject) {
        val reflectObj = NativeObject()

        ScriptableObject.putProperty(reflectObj, "findFields", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val className = args.getOrNull(0)?.toString() ?: return cx.newArray(scope, emptyArray<Any>())
                val superclassFlag = Context.toBoolean(args.getOrNull(1))
                val condition = args.getOrNull(2) as? org.mozilla.javascript.Function
                    ?: return cx.newArray(scope, emptyArray<Any>())
                return try {
                    val clazz = className.toClass()
                    val fields = if (superclassFlag) clazz.fields.toList() else clazz.declaredFields.toList()
                    val matches = fields.filter { field ->
                        val modStrs = getModifierStrings(field.modifiers)
                        val jsModStrs = cx.newArray(scope, modStrs)
                        val condResult = condition.call(
                            cx, scope, thisObj,
                            arrayOf(field.name, createJavaClassObject(field.type), jsModStrs)
                        )
                        Context.toBoolean(condResult)
                    }
                    val wrappers = matches.map { createJavaFieldObject(it, clazz, cx, scope) }
                    cx.newArray(scope, wrappers.toTypedArray<Any>())
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect.findFields failed for $className", e)
                    cx.newArray(scope, emptyArray<Any>())
                }
            }
        })

        ScriptableObject.putProperty(reflectObj, "findMethods", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val className = args.getOrNull(0)?.toString() ?: return cx.newArray(scope, emptyArray<Any>())
                val superclassFlag = Context.toBoolean(args.getOrNull(1))
                val condition = args.getOrNull(2) as? org.mozilla.javascript.Function
                    ?: return cx.newArray(scope, emptyArray<Any>())
                return try {
                    val clazz = className.toClass()
                    val methods = if (superclassFlag) clazz.methods.toList() else clazz.declaredMethods.toList()
                    val matches = methods.filter { method ->
                        val jsParamTypes = cx.newArray(scope, method.parameterTypes.map { createJavaClassObject(it) }.toTypedArray<Any>())
                        val modStrs = getModifierStrings(method.modifiers)
                        val jsModStrs = cx.newArray(scope, modStrs)
                        val condResult = condition.call(
                            cx, scope, thisObj,
                            arrayOf(method.name, jsParamTypes, createJavaClassObject(method.returnType), jsModStrs)
                        )
                        Context.toBoolean(condResult)
                    }
                    val wrappers = matches.map { createJavaMethodObject(it, clazz, cx, scope) }
                    cx.newArray(scope, wrappers.toTypedArray<Any>())
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect.findMethods failed for $className", e)
                    cx.newArray(scope, emptyArray<Any>())
                }
            }
        })

        ScriptableObject.putProperty(reflectObj, "toClass", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val className = args.getOrNull(0)?.toString() ?: return Undefined.instance
                return try {
                    val clazz = className.toClass()
                    createJavaClassObject(clazz)
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect.toClass failed for $className", e)
                    Undefined.instance
                }
            }
        })

        ScriptableObject.putProperty(reflectObj, "findFirstMethod", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val className = args.getOrNull(0)?.toString() ?: return Undefined.instance
                val condition = args.getOrNull(1) as? org.mozilla.javascript.Function ?: return Undefined.instance
                return try {
                    val clazz = className.toClass()
                    for (method in clazz.declaredMethods) {
                        val jsParamTypes = cx.newArray(scope, method.parameterTypes.map { createJavaClassObject(it) }.toTypedArray<Any>())
                        val modStrs = getModifierStrings(method.modifiers)
                        val jsModStrs = cx.newArray(scope, modStrs)
                        val condResult = condition.call(
                            cx, scope, thisObj,
                            arrayOf(method.name, jsParamTypes, createJavaClassObject(method.returnType), jsModStrs)
                        )
                        if (Context.toBoolean(condResult)) {
                            return createJavaMethodObject(method, clazz, cx, scope)
                        }
                    }
                    Undefined.instance
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect.findFirstMethod failed for $className", e)
                    Undefined.instance
                }
            }
        })

        ScriptableObject.putProperty(reflectObj, "findFirstField", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val className = args.getOrNull(0)?.toString() ?: return Undefined.instance
                val superclassFlag = Context.toBoolean(args.getOrNull(1))
                val condition = args.getOrNull(2) as? org.mozilla.javascript.Function ?: return Undefined.instance
                return try {
                    val clazz = className.toClass()
                    val fields = if (superclassFlag) clazz.fields.toList() else clazz.declaredFields.toList()
                    for (field in fields) {
                        val modStrs = getModifierStrings(field.modifiers)
                        val jsModStrs = cx.newArray(scope, modStrs)
                        val condResult = condition.call(
                            cx, scope, thisObj,
                            arrayOf(field.name, createJavaClassObject(field.type), jsModStrs)
                        )
                        if (Context.toBoolean(condResult)) {
                            return createJavaFieldObject(field, clazz, cx, scope)
                        }
                    }
                    Undefined.instance
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect.findFirstField failed for $className", e)
                    Undefined.instance
                }
            }
        })

        ScriptableObject.putProperty(reflectObj, "findConstructors", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val className = args.getOrNull(0)?.toString() ?: return cx.newArray(scope, emptyArray<Any>())
                val superclassFlag = Context.toBoolean(args.getOrNull(1))
                val condition = args.getOrNull(2) as? org.mozilla.javascript.Function ?: return cx.newArray(scope, emptyArray<Any>())
                return try {
                    val clazz = className.toClass()
                    val constructors = if (superclassFlag) clazz.constructors.toList() else clazz.declaredConstructors.toList()
                    val matches = constructors.filter { constructor ->
                        val jsParamTypes = cx.newArray(scope, constructor.parameterTypes.map { createJavaClassObject(it) }.toTypedArray<Any>())
                        val modStrs = getModifierStrings(constructor.modifiers)
                        val jsModStrs = cx.newArray(scope, modStrs)
                        val condResult = condition.call(
                            cx, scope, thisObj,
                            arrayOf(constructor.name, jsParamTypes, createJavaClassObject(clazz), jsModStrs)
                        )
                        Context.toBoolean(condResult)
                    }
                    val wrappers = matches.map { createJavaConstructorObject(it, clazz, cx, scope) }
                    cx.newArray(scope, wrappers.toTypedArray<Any>())
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect.findConstructors failed for $className", e)
                    cx.newArray(scope, emptyArray<Any>())
                }
            }
        })

        ScriptableObject.putProperty(reflectObj, "findFirstConstructor", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val className = args.getOrNull(0)?.toString() ?: return Undefined.instance
                val superclassFlag = Context.toBoolean(args.getOrNull(1))
                val condition = args.getOrNull(2) as? org.mozilla.javascript.Function ?: return Undefined.instance
                return try {
                    val clazz = className.toClass()
                    val constructors = if (superclassFlag) clazz.constructors.toList() else clazz.declaredConstructors.toList()
                    for (constructor in constructors) {
                        val jsParamTypes = cx.newArray(scope, constructor.parameterTypes.map { createJavaClassObject(it) }.toTypedArray<Any>())
                        val modStrs = getModifierStrings(constructor.modifiers)
                        val jsModStrs = cx.newArray(scope, modStrs)
                        val condResult = condition.call(
                            cx, scope, thisObj,
                            arrayOf(constructor.name, jsParamTypes, createJavaClassObject(clazz), jsModStrs)
                        )
                        if (Context.toBoolean(condResult)) {
                            return createJavaConstructorObject(constructor, clazz, cx, scope)
                        }
                    }
                    Undefined.instance
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect.findFirstConstructor failed for $className", e)
                    Undefined.instance
                }
            }
        })

        ScriptableObject.putProperty(scope, "reflect", reflectObj)
    }

    private fun getStringProperty(obj: NativeObject, key: String): String? {
        return when (val v = ScriptableObject.getProperty(obj, key)) {
            is String -> v.takeIf { it.isNotEmpty() }
            is NativeObject -> {
                val name = ScriptableObject.getProperty(v, "name")
                (name as? String)?.takeIf { it.isNotEmpty() }
            }

            else -> null
        }
    }

    private fun getStringArrayProperty(obj: NativeObject, key: String): Array<String>? {
        val v = ScriptableObject.getProperty(obj, key)
        if (v !is NativeArray || v.length.toInt() == 0) return null
        return (0 until v.length.toInt()).map { i ->
            when (val elem = v[i]) {
                is String -> elem
                is NativeObject -> {
                    val name = ScriptableObject.getProperty(elem, "name")
                    name?.toString() ?: ""
                }

                else -> elem?.toString() ?: ""
            }
        }.toTypedArray()
    }

    private fun getIntProperty(obj: NativeObject, key: String): Int? {
        val v = ScriptableObject.getProperty(obj, key)
        return (v as? Number)?.toInt()
    }

    private fun getNumberArrayProperty(obj: NativeObject, key: String): Array<Number>? {
        val v = ScriptableObject.getProperty(obj, key)
        if (v !is NativeArray || v.length.toInt() == 0) return null
        val nums = (0 until v.length.toInt()).mapNotNull { v[it] as? Number }
        if (nums.isEmpty()) return null
        return nums.toTypedArray()
    }

    private fun createDexMethodResult(
        methodDataList: List<MethodData>,
        cx: Context,
        scope: Scriptable
    ): NativeObject {
        val result = NativeObject()
        val methods = methodDataList.map { md ->
            val method = md.asMethod
            createJavaMethodObject(method, method.declaringClass, cx, scope)
        }
        val jsArray = cx.newArray(scope, methods.toTypedArray<Any>())
        ScriptableObject.putProperty(result, "methods", jsArray)

        ScriptableObject.putProperty(result, "single", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                return when (methods.size) {
                    1 -> methods[0]
                    else -> Undefined.instance
                }
            }
        })
        return result
    }

    private fun createDexClassResult(
        classDataList: List<ClassData>,
        cx: Context,
        scope: Scriptable
    ): NativeObject {
        val result = NativeObject()
        val classList = classDataList.mapNotNull { cd ->
            try {
                createJavaClassObject(cd.name.toClass())
            } catch (e: Exception) {
                WeLogger.w(TAG_DEXKIT_API, "failed to load class ${cd.name}", e)
                null
            }
        }
        val jsArray = cx.newArray(scope, classList.toTypedArray<Any>())
        ScriptableObject.putProperty(result, "classes", jsArray)

        ScriptableObject.putProperty(result, "single", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                return when (classList.size) {
                    1 -> classList[0]
                    else -> Undefined.instance
                }
            }
        })
        return result
    }

    private fun getConstructorDescriptor(constructor: Constructor<*>): String {
        val params = constructor.parameterTypes.joinToString("") { getJvmDescriptor(it) }
        return "($params)V"
    }

    private fun createJavaConstructorObject(
        constructor: Constructor<*>,
        clazz: Class<*>,
        cx: Context,
        scope: Scriptable
    ): NativeObject {
        val obj = NativeObject()
        ScriptableObject.putProperty(obj, "name", constructor.name)
        ScriptableObject.putProperty(obj, "clazz", createJavaClassObject(clazz))
        ScriptableObject.putProperty(obj, "descriptor", getConstructorDescriptor(constructor))
        ScriptableObject.putProperty(
            obj, "paramTypes",
            cx.newArray(scope, constructor.parameterTypes.map { createJavaClassObject(it) }.toTypedArray<Any>())
        )
        ScriptableObject.putProperty(obj, "returnType", createJavaClassObject(constructor.declaringClass))
        ScriptableObject.putProperty(
            obj, "modifiers",
            cx.newArray(scope, getModifierStrings(constructor.modifiers))
        )

        ScriptableObject.putProperty(obj, "invoke", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any? {
                val jsArgs = args.getOrNull(0) as? NativeArray ?: return Undefined.instance
                val javaArgs = Array(constructor.parameterTypes.size) { i ->
                    if (i < jsArgs.length.toInt()) {
                        try { Context.jsToJava(jsArgs[i], constructor.parameterTypes[i]) }
                        catch (_: Exception) { jsArgs[i] }
                    } else null
                    }
                return try {
                    val instance = constructor.makeAccessible().newInstance(*javaArgs)
                    Context.javaToJS(instance, scope, cx) ?: Undefined.instance
                } catch (e: Exception) {
                    WeLogger.e(TAG_REFLECT_API, "reflect constructor invoke failed on ${clazz.name}", e)
                    Undefined.instance
                }
            }
        })

        return obj
    }

    private fun exposeDexKitApis(scope: ScriptableObject) {
        val dexkitObj = NativeObject()

        ScriptableObject.putProperty(dexkitObj, "findMethod", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                val searcher = args.getOrNull(0) as? NativeObject
                    ?: return NativeObject()
                return try {
                    val results = DexKit.findMethod {
                        val pkgs = getStringArrayProperty(searcher, "searchPackages")
                        if (pkgs != null) searchPackages(*pkgs)
                        matcher {
                            getStringProperty(searcher, "declaringClass")?.let { declaredClass = it }
                            getStringProperty(searcher, "name")?.let { name = it }
                            getStringProperty(searcher, "returnType")?.let { returnType = it }
                            getIntProperty(searcher, "paramCount")?.let { paramCount = it }
                            getStringArrayProperty(searcher, "paramTypes")?.let { paramTypes(*it) }
                            getStringArrayProperty(searcher, "usingEqStrings")?.let { usingEqStrings(*it) }
                            getNumberArrayProperty(searcher, "usingNumbers")?.let { usingNumbers(*it) }
                        }
                    }
                    createDexMethodResult(results.toList(), cx, scope)
                } catch (e: Exception) {
                    WeLogger.e(TAG_DEXKIT_API, "dexkit.findMethod failed", e)
                    createDexMethodResult(emptyList(), cx, scope)
                }
            }
        })

        ScriptableObject.putProperty(dexkitObj, "findClass", object : BaseFunction() {
            override fun call(
                cx: Context,
                scope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>
            ): Any {
                val searcher = args.getOrNull(0) as? NativeObject
                    ?: return NativeObject()
                return try {
                    val results = DexKit.findClass {
                        val pkgs = getStringArrayProperty(searcher, "searchPackages")
                        if (pkgs != null) searchPackages(*pkgs)
                        matcher {
                            getStringProperty(searcher, "name")?.let { className = it }
                            getStringProperty(searcher, "superclass")?.let { superClass = it }
                            getStringArrayProperty(searcher, "usingEqStrings")?.let { usingEqStrings(*it) }
                            getStringArrayProperty(searcher, "interfaces")?.forEach { ifaceName ->
                                addInterface { className = ifaceName }
                            }
                        }
                    }
                    createDexClassResult(results.toList(), cx, scope)
                } catch (e: Exception) {
                    WeLogger.e(TAG_DEXKIT_API, "dexkit.findClass failed", e)
                    createDexClassResult(emptyList(), cx, scope)
                }
            }
        })

        ScriptableObject.putProperty(scope, "dexkit", dexkitObj)
    }
}
