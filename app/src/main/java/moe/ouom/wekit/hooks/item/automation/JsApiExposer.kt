package moe.ouom.wekit.hooks.item.automation

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import moe.ouom.wekit.hooks.sdk.api.WeMessageApi
import moe.ouom.wekit.utils.io.PathUtils
import moe.ouom.wekit.utils.log.WeLogger
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

object JsApiExposer {
    private const val TAG = "JsApiExposer"
    private const val TAG_LOG_API = "JsApiExposer.LogApi"
    private const val TAG_HTTP_API = "JsApiExposer.HttpApi"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun exposeApis(scope: ScriptableObject) {
        exposeHttpApis(scope)
        exposeLogApis(scope)
        exposeStorageApis(scope)
        exposeTimeApis(scope)
    }

    private const val MAX_CACHE_SIZE_IN_MIB = 500

    @OptIn(ExperimentalPathApi::class)
    private fun exposeHttpApis(scope: ScriptableObject) {
        val httpObj = NativeObject()

        // http.get(url, params?, headers?)
        ScriptableObject.putProperty(httpObj, "get",
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

                    WeLogger.i(TAG_HTTP_API, "http.get invoked: url=$url params=$params headers=$headers")

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
        ScriptableObject.putProperty(httpObj, "post",
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

                    WeLogger.i(TAG_HTTP_API, "http.post invoked: url=$url formData=$formData jsonBody=$jsonBody headers=$headers")

                    return try {
                        httpPost(url, formData, jsonBody, headers)
                    } catch (e: Exception) {
                        WeLogger.e(TAG_HTTP_API, "http.post failed: $url", e)
                        createErrorResponse(e)
                    }
                }
            }
        )

        // http.download(url, filename?) -> { ok: Boolean, path: String }
        ScriptableObject.putProperty(httpObj, "download",
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
                        var cacheDir = PathUtils.moduleCachePath
                        cacheDir = cacheDir!!.resolve("javascript_http_api")

                        if (cacheDir.isDirectory()) {
                            // drop cache if size too large
                            if (cacheDir.fileSize() / 1024 / 1024 >= MAX_CACHE_SIZE_IN_MIB) {
                                WeLogger.w(TAG, "http.download cache size too large, dropping cache...")
                                cacheDir.deleteRecursively()
                            }
                        }
                        cacheDir.createDirectories()

                        val destFile = cacheDir.resolve(filename)

                        val success = performDownload(url, destFile)

                        createDownloadResponse(success, destFile.absolutePathString())
                    } catch (e: Exception) {
                        WeLogger.e(TAG_HTTP_API, "http.download failed: $url", e)
                        createDownloadResponse(false, "")
                    }
                }
            }
        )

        ScriptableObject.putProperty(scope, "http", httpObj)
    }

    private fun createDownloadResponse(ok: Boolean, path: String): NativeObject {
        val res = NativeObject()
        ScriptableObject.putProperty(res, "ok", ok)
        ScriptableObject.putProperty(res, "path", path)
        return res
    }

    private fun performDownload(url: String, destFile: Path): Boolean {
        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false

            @Suppress("UNNECESSARY_SAFE_CALL")
            response.body?.byteStream()?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return true
    }

    private fun httpGet(
        urlString: String,
        params: NativeObject?,
        headers: NativeObject?
    ): NativeObject {
        // Build URL with query parameters
        val finalUrl = if (params != null) {
            val httpUrl = urlString.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid URL")
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

        val response = httpClient.newCall(requestBuilder.build()).execute()
        return createHttpResponse(response)
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
            else -> {
                "".toRequestBody("text/plain; charset=utf-8".toMediaType())
            }
        }

        requestBuilder.post(body)

        // Add headers
        headers?.let { applyHeaders(requestBuilder, it) }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        return createHttpResponse(response)
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

    private fun createHttpResponse(response: okhttp3.Response): NativeObject {
        val cx = Context.getCurrentContext()!!
        val scope = cx.initStandardObjects()

        val statusCode = response.code
        val body = response.body.string()

        val responseObj = NativeObject()
        responseObj.put("status", responseObj, statusCode)
        responseObj.put("body", responseObj, body)
        responseObj.put("ok", responseObj, response.isSuccessful)

        // Try to parse as JSON if content-type indicates JSON
        val contentType = response.header("Content-Type") ?: ""
        if (contentType.contains("application/json", ignoreCase = true) && body.isNotEmpty()) {
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
        response.headers.names().forEach { name ->
            headersObj.put(name, headersObj, response.header(name))
        }
        responseObj.put("headers", responseObj, headersObj)

        response.close()
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
        ScriptableObject.putProperty(logObj, "d",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.d(TAG_LOG_API, msg)
                    return Context.getUndefinedValue()
                }
            }
        )

        // log.i(msg)
        ScriptableObject.putProperty(logObj, "i",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.i(TAG_LOG_API, msg)
                    return Context.getUndefinedValue()
                }
            }
        )

        // log.w(msg)
        ScriptableObject.putProperty(logObj, "w",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.w(TAG_LOG_API, msg)
                    return Context.getUndefinedValue()
                }
            }
        )

        // log.e(msg)
        ScriptableObject.putProperty(logObj, "e",
            object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>
                ): Any {
                    val msg = args.joinToString(" ") { it?.toString() ?: "null" }
                    WeLogger.e(TAG_LOG_API, msg)
                    return Context.getUndefinedValue()
                }
            }
        )

        ScriptableObject.putProperty(scope, "log", logObj)
    }

    private fun exposeTimeApis(scope: ScriptableObject) {
        val timeObj = NativeObject()

        // time.sleepS(seconds)
        ScriptableObject.putProperty(timeObj, "sleepS",
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
                            WeLogger.w(TAG_LOG_API, "Sleep interrupted", e)
                            Thread.currentThread().interrupt()
                        }
                    }
                    return Context.getUndefinedValue()
                }
            }
        )

        // time.sleepMs(milliseconds)
        ScriptableObject.putProperty(timeObj, "sleepMs",
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
                            WeLogger.w(TAG_LOG_API, "Sleep interrupted", e)
                            Thread.currentThread().interrupt()
                        }
                    }
                    return Context.getUndefinedValue()
                }
            }
        )

        // time.getCurrentUnixEpoch()
        ScriptableObject.putProperty(timeObj, "getCurrentUnixEpoch",
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

        ScriptableObject.putProperty(scope, "time", timeObj)
    }

    @Suppress("JavaCollectionWithNullableTypeArgument")
    private val storage = ConcurrentHashMap<String, Any?>()

    private val DATA_DIR_PATH by lazy {
        PathUtils.moduleDataPath!!.resolve("data").apply { createDirectories() }
    }

    private val storageFile get() = DATA_DIR_PATH.resolve("javascript_storage_api.json")

    init {
        loadStorageFromDisk()
    }

    private val gson = Gson()
    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable {
        try {
            storageFile.writeText(gson.toJson(storage))
        } catch (e: Exception) {
            WeLogger.e(TAG, "Failed to save js storage to disk", e)
        }
    }

    private fun loadStorageFromDisk() {
        try {
            if (!storageFile.exists()) return
            val json = storageFile.readText()
            val map = gson.fromJson<Map<String, Any?>>(json, object : TypeToken<Map<String, Any?>>() {}.type)
            map?.forEach { (k, v) -> storage[k] = v }
        } catch (e: Exception) {
            WeLogger.e(TAG, "Failed to load js storage from disk", e)
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
        ScriptableObject.putProperty(storageObj, "get",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return null
                    val value = storage[key]

                    return value ?: Context.getUndefinedValue()
                }
            }
        )

        // storage.getOrDefault(key, defaultValue) -> object
        ScriptableObject.putProperty(storageObj, "getOrDefault",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return args.getOrNull(1)
                    return storage.getOrDefault(key, args.getOrNull(1)) ?: Context.getUndefinedValue()
                }
            }
        )

        // storage.set(key, object)
        ScriptableObject.putProperty(storageObj, "set",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return null
                    val value = args.getOrNull(1)

                    if (value is Undefined) {
                        WeLogger.w(TAG, "js tries to set undefined into cache, removing that key instead")
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
        ScriptableObject.putProperty(storageObj, "clear",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
                    storage.clear()
                    saveStorageToDisk()
                    return null
                }
            }
        )

        // storage.remove(key)
        ScriptableObject.putProperty(storageObj, "remove",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return null
                    storage.remove(key)
                    saveStorageToDisk()
                    return null
                }
            }
        )

        // storage.pop(key) -> object
        ScriptableObject.putProperty(storageObj, "pop",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? {
                    val key = args.getOrNull(0)?.toString() ?: return Context.getUndefinedValue()
                    return (storage.remove(key) ?: Context.getUndefinedValue()).also { saveStorageToDisk() }
                }
            }
        )

        // storage.hasKey(key) -> bool
        ScriptableObject.putProperty(storageObj, "hasKey",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any {
                    val key = args.getOrNull(0)?.toString() ?: return false
                    return storage.containsKey(key)
                }
            }
        )

        // storage.isEmpty() -> bool
        ScriptableObject.putProperty(storageObj, "isEmpty",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any {
                    return storage.isEmpty()
                }
            }
        )

        // storage.keys() -> Array
        ScriptableObject.putProperty(storageObj, "keys",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any {
                    // Converts Kotlin Set to a JS Array
                    return cx.newArray(scope, storage.keys.toTypedArray())
                }
            }
        )

        // storage.size() -> int
        ScriptableObject.putProperty(storageObj, "size",
            object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any {
                    return storage.size
                }
            }
        )

        // Bind the object to the global scope
        ScriptableObject.putProperty(scope, "storage", storageObj)
    }

    fun exposeOnMessageApis(scope: ScriptableObject, talker: String) {
        val api = WeMessageApi.INSTANCE ?: return

        val apiBootstrap = """
            function sendText(to, text)                          { _sendText(to, text); }
            function sendImage(to, path)                         { _sendImage(to, path); }
            function sendFile(to, path, title)                   { _sendFile(to, path, title); }
            function sendVoice(to, path, durationMs)             { _sendVoice(to, path, durationMs); }
            function sendXml(to, content)                        { _sendXml(to, content); }
            // Convenience: reply to the current talker without specifying 'to'
            function replyText(text)                             { _sendText('$talker', text); }
            function replyImage(path)                            { _sendImage('$talker', path); }
            function replyFile(path, title)                      { _sendFile('$talker', path, title); }
            function replyVoice(path, durationMs)                { _sendVoice('$talker', path, durationMs); }
            function replyXml(content)                           { _sendXml('$talker', content); }
        """.trimIndent()

        // Bind the native _send* functions using ScriptableObject
        fun nativeFn(argCount: Int, block: (Array<Any?>) -> Unit) =
            object : BaseFunction() {
                override fun getArity() = argCount
                override fun call(
                    cx: Context, scope: Scriptable,
                    thisObj: Scriptable, args: Array<Any?>,
                ) = block(args).let { Context.getUndefinedValue() }
            }

        ScriptableObject.putProperty(scope, "_sendText",
            nativeFn(2) { args ->
                val to   = args.getOrNull(0)?.toString() ?: return@nativeFn
                val text = args.getOrNull(1)?.toString() ?: return@nativeFn
                api.sendText(to, text)
            }
        )
        ScriptableObject.putProperty(scope, "_sendImage",
            nativeFn(2) { args ->
                val to   = args.getOrNull(0)?.toString() ?: return@nativeFn
                val path = args.getOrNull(1)?.toString() ?: return@nativeFn
                api.sendImage(to, path)
            }
        )
        ScriptableObject.putProperty(scope, "_sendFile",
            nativeFn(3) { args ->
                val to    = args.getOrNull(0)?.toString() ?: return@nativeFn
                val path  = args.getOrNull(1)?.toString() ?: return@nativeFn
                val title = args.getOrNull(2)?.toString() ?: path.substringAfterLast('/')
                api.sendFile(to, path, title)
            }
        )
        ScriptableObject.putProperty(scope, "_sendVoice",
            nativeFn(3) { args ->
                val to         = args.getOrNull(0)?.toString() ?: return@nativeFn
                val path       = args.getOrNull(1)?.toString() ?: return@nativeFn
                val durationMs = (args.getOrNull(2) as? Number)?.toInt() ?: 0
                api.sendVoice(to, path, durationMs)
            }
        )
        ScriptableObject.putProperty(scope, "_sendXml",
            nativeFn(3) { args ->
                val to         = args.getOrNull(0)?.toString() ?: return@nativeFn
                val content    = args.getOrNull(1)?.toString() ?: return@nativeFn
                api.sendXmlAppMsg(to, content)
            }
        )

        val cx = Context.getCurrentContext()!!
        cx.evaluateString(scope, apiBootstrap, "ApiBootstrap", 1, null)
    }
}