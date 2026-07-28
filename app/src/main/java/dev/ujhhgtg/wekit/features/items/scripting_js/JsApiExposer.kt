package dev.ujhhgtg.wekit.features.items.scripting_js

import android.os.Handler
import android.os.Looper
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.toJsObject
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.WeProtoData
import dev.ujhhgtg.wekit.features.items.scripting_js.JsApiExposer.BOOTSTRAP
import dev.ujhhgtg.wekit.utils.HookHandle
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.asMethod
import dev.ujhhgtg.wekit.utils.reflection.boxed
import dev.ujhhgtg.wekit.utils.reflection.withDexKit
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
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
import java.lang.reflect.Array as JavaArray

/**
 * Installs WeKit's JavaScript API using quickjs-kt bindings.
 *
 * Values cross the JNI boundary only as QuickJS-supported primitives, lists and [Map]s. Java
 * references and JavaScript callbacks are represented by IDs owned by one script runtime, so no
 * QuickJS value is ever retained or called outside its runtime.
 */
object JsApiExposer {
    private const val TAG = "JsApiExposer"
    private const val TAG_LOG_API = "JsApiExposer.LogApi"
    private const val TAG_HTTP_API = "JsApiExposer.HttpApi"
    private const val TAG_WECHAT_API = "JsApiExposer.WeChatApi"
    private const val MAX_CACHE_SIZE_IN_MIB = 500

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /** Calls a callback registered in the owning persistent QuickJS global scope. */
    fun interface CallbackInvoker {
        fun invoke(callbackId: Long, args: List<Any?>): Any?
    }

    /** Per-script API state. It must be discarded together with its QuickJS runtime. */
    class Session internal constructor(
        private val callbacks: CallbackInvoker,
    ) {
        private val nextHandle = AtomicLong(1)
        private val nextInvocation = AtomicLong(1)
        private val handles = ConcurrentHashMap<Long, Any>()
        private val handleIds = Collections.synchronizedMap(IdentityHashMap<Any, Long>())
        private val invocations = ConcurrentHashMap<Long, List<Any?>>()

        fun retain(value: Any): Long = synchronized(handleIds) {
            handleIds[value] ?: nextHandle.getAndIncrement().also { id ->
                handleIds[value] = id
                handles[id] = value
            }
        }

        fun resolve(value: Any?): Any? {
            val map = value as? Map<*, *> ?: return value
            if (map[BRIDGE_KEY] != HANDLE_BRIDGE_TYPE) return value
            val id = (map[HANDLE_KEY] as? Number)?.toLong() ?: return value
            return handles[id] ?: value
        }

        fun enqueueInvocation(args: List<Any?>): Long = nextInvocation.getAndIncrement().also { invocations[it] = args }

        fun takeInvocation(id: Long): List<Any?> =
            invocations.remove(id)?.map { bridgeValue(this, it) } ?: emptyList()

        fun discardInvocation(id: Long) {
            invocations.remove(id)
        }

        fun callback(id: Any?, args: List<Any?>): Any? {
            val callbackId = (id as? Number)?.toLong() ?: return null
            return callbacks.invoke(callbackId, args)
        }

        fun clear() {
            handles.clear()
            handleIds.clear()
            invocations.clear()
        }
    }

    private const val HANDLE_KEY = "__wekitHandle"
    private const val BRIDGE_KEY = "__wekitBridge"
    private const val HANDLE_BRIDGE_TYPE = "javaHandle"

    /** Marks JSON-shaped values that are intentionally transferred as plain JavaScript data. */
    class StructuredValue internal constructor(internal val value: Any?)

    /** Keeps an API-owned JavaScript array while preserving Java object elements as handles. */
    class BridgeList internal constructor(internal val values: List<Any?>)

    fun createSession(callbacks: CallbackInvoker): Session = Session(callbacks)

    fun structuredValue(value: Any?): StructuredValue = StructuredValue(value)

    /**
     * Bind every public namespace with quickjs-kt's DSL. Callback-taking APIs receive a callback
     * ID after [BOOTSTRAP] wraps their public JavaScript facade.
     */
    @OptIn(ExperimentalPathApi::class)
    fun exposeApis(quickJs: QuickJs, session: Session) {
        exposeBridge(quickJs, session)

        quickJs.define("http") {
            function("get") { args ->
                val url = args.string(0) ?: return@function errorResponse("Missing URL")
                try {
                    httpGet(url, args.map(1), args.map(2))
                } catch (e: Exception) {
                    WeLogger.e(TAG_HTTP_API, "http.get failed: $url", e)
                    errorResponse(e.message ?: "Unknown error")
                }
            }
            function("post") { args ->
                val url = args.string(0) ?: return@function errorResponse("Missing URL")
                try {
                    httpPost(url, args.map(1), args.map(2), args.map(3))
                } catch (e: Exception) {
                    WeLogger.e(TAG_HTTP_API, "http.post failed: $url", e)
                    errorResponse(e.message ?: "Unknown error")
                }
            }
            function("download") { args ->
                val url = args.string(0) ?: return@function null
                val filename = args.string(1).takeUnless { it.isNullOrBlank() }
                    ?: "download_${System.currentTimeMillis()}"
                try {
                    val cacheDir = (KnownPaths.moduleCache / "javascript_http_api").createDirsSafe()
                    if (directorySize(cacheDir) / 1024 / 1024 >= MAX_CACHE_SIZE_IN_MIB) {
                        cacheDir.deleteRecursively()
                    }
                    val target = cacheDir.resolve(filename)
                    if (performDownload(url, target)) target.absolutePathString() else null
                } catch (e: Exception) {
                    WeLogger.e(TAG_HTTP_API, "http.download failed: $url", e)
                    null
                }
            }
        }

        quickJs.define("log") {
            function("d") { args -> WeLogger.d(TAG_LOG_API, args.logMessage(session)) }
            function("i") { args -> WeLogger.i(TAG_LOG_API, args.logMessage(session)) }
            function("w") { args -> WeLogger.w(TAG_LOG_API, args.logMessage(session)) }
            function("e") { args -> WeLogger.e(TAG_LOG_API, args.logMessage(session)) }
        }

        quickJs.define("storage") {
            function("get") { args -> storage[args.string(0) ?: return@function null].toJsValue() }
            function("getOrDefault") { args ->
                val key = args.string(0) ?: return@function args.getOrNull(1)
                storage.getOrDefault(key, args.getOrNull(1)).toJsValue()
            }
            function("set") { args ->
                val key = args.string(0) ?: return@function
                if (args.size < 2 || args[1] == null) storage.remove(key) else storage[key] = args[1].storageValue()
                saveStorageToDisk()
            }
            function("clear") { storage.clear(); saveStorageToDisk() }
            function("remove") { args ->
                val key = args.string(0) ?: return@function null
                storage.remove(key).also { saveStorageToDisk() }.toJsValue()
            }
            function("hasKey") { args -> storage.containsKey(args.string(0)) }
            function("keys") { storage.keys.toList() }
            function("size") { storage.size }
            function("isEmpty") { storage.isEmpty() }
        }

        quickJs.define("datetime") {
            function("sleepS") { args -> sleep(args.number(0)?.toLong()?.times(1_000) ?: 0) }
            function("sleepMs") { args -> sleep(args.number(0)?.toLong() ?: 0) }
            function("getCurrentUnixEpoch") { System.currentTimeMillis() / 1_000L }
        }

        quickJs.define("hostinfo") {
            property("application") { getter { bridgeValue(session, HostInfo.application) } }
            property("packageName") { getter { HostInfo.packageName } }
            property("versionCode") { getter { HostInfo.versionCode } }
            property("versionName") { getter { HostInfo.versionName } }
            property("isHostGooglePlay") { getter { HostInfo.isHostGooglePlay } }
        }

        quickJs.define("wechat") {
            function("sendText") { args -> args.sendText() }
            function("sendImage") { args -> args.sendImage() }
            function("sendFile") { args -> args.sendFile() }
            function("sendVoice") { args -> args.sendVoice() }
            function("sendAppMsg") { args -> args.sendAppMsg() }
            function("replyText") { args -> replyTarget("replyText")?.let { target -> args.string(0)?.let { WeMessageApi.sendText(target, it) } } }
            function("replyImage") { args -> replyTarget("replyImage")?.let { target -> args.string(0)?.let { WeMessageApi.sendImage(target, it) } } }
            function("replyFile") { args -> replyTarget("replyFile")?.let { target ->
                args.string(0)?.let { path -> WeMessageApi.sendFile(target, path, args.string(1) ?: path.substringAfterLast('/')) }
            } }
            function("replyVoice") { args -> replyTarget("replyVoice")?.let { target ->
                args.string(0)?.let { path -> WeMessageApi.sendVoice(target, path, args.number(1)?.toInt() ?: 0) }
            } }
            function("replyAppMsg") { args -> replyTarget("replyAppMsg")?.let { target -> args.string(0)?.let { WeMessageApi.sendXmlAppMsg(target, it) } } }
            function("getSelfWxId") { _: Array<Any?> -> WeApi.selfWxId }
            function("getSelfCustomWxId") { _: Array<Any?> -> WeApi.selfCustomWxId }
            function("sendCgi") { args ->
                val uri = args.string(0) ?: return@function
                val cgiId = args.number(1)?.toInt() ?: return@function
                val funcId = args.number(2)?.toInt() ?: return@function
                val routeId = args.number(3)?.toInt() ?: return@function
                val payload = args.string(4) ?: return@function
                val onSuccess = args.number(5)?.toLong()
                val onFailure = args.number(6)?.toLong()
                WePacketHelper.sendCgi(uri, cgiId, funcId, routeId, payload) {
                    onSuccess { bytes ->
                        val json = bytes?.let { WeProtoData.fromBytes(it).toJsonObject().toString() } ?: "{}"
                        onSuccess?.let { session.callback(it, listOf(json)) }
                    }
                    onFailure { _, _, message -> onFailure?.let { session.callback(it, listOf(message)) } }
                }
            }
        }

        quickJs.define("task") {
            function("run") { args ->
                val callbackId = args.number(0)?.toLong() ?: return@function
                thread(name = "JsTask") {
                    runCatching { session.callback(callbackId, emptyList()) }
                        .onFailure { WeLogger.e(TAG, "task.run callback failed", it) }
                }
            }
        }

        exposeReflectionApis(quickJs, session)
        exposeXposedApis(quickJs, session)
        exposeDexKitApis(quickJs, session)
    }

    private fun exposeBridge(quickJs: QuickJs, session: Session) {
        quickJs.define("__wekitBridge") {
            function("takeInvocation") { args -> session.takeInvocation(args.number(0)?.toLong() ?: return@function emptyList<Any>()) }
            function("member") { args -> bridgeMember(session, args) }
            function("setMember") { args -> bridgeSetMember(session, args) }
            function("invokeMember") { args -> bridgeInvokeMember(session, args) }
            function("length") { args -> bridgeLength(session.resolve(args.getOrNull(0))) }
            function("indexGet") { args -> bridgeIndexGet(session, args) }
            function("indexSet") { args -> bridgeIndexSet(session, args) }
            function("toString") { args -> runCatching { session.resolve(args.getOrNull(0))?.toString() ?: "null" }.getOrDefault("<java object>") }
        }
    }

    /**
     * The public TypeScript API accepts JavaScript callbacks. quickjs-kt deliberately does not
     * retain arbitrary JS functions on the Kotlin side, so register them in the owning realm and
     * pass only an ID through the normal DSL bindings.
     */
    const val BOOTSTRAP = """
        (() => {
          const callbacks = new Map();
          const wrappers = new Map();
          const nativeBridge = __wekitBridge;
          const bridgeKey = '__wekitBridge';
          const handleBridgeType = 'javaHandle';
          let nextCallback = 1;

          function wrap(value) {
            if (Array.isArray(value)) return value.map(wrap);
            if (value == null || typeof value !== 'object') return value;
            if (value[bridgeKey] === handleBridgeType) return wrapper(value);
            for (const key of Object.keys(value)) value[key] = wrap(value[key]);
            return value;
          }

          function javaProxy(target) {
            return new Proxy(target, {
              get(target, property, receiver) {
                if (property === Symbol.toStringTag) return 'JavaObject';
                if (property === Symbol.toPrimitive || property === 'toString') {
                  return () => nativeBridge.toString(target);
                }
                if (Reflect.has(target, property)) return Reflect.get(target, property, receiver);
                if (typeof property !== 'string') return undefined;
                if (/^(0|[1-9]\d*)$/.test(property)) {
                  const indexed = nativeBridge.indexGet(target, Number(property));
                  return indexed == null ? undefined : wrap(indexed);
                }
                if (property === 'length') {
                  const length = nativeBridge.length(target);
                  if (length != null) return length;
                }
                const member = nativeBridge.member(target, property);
                if (member == null) return undefined;
                if (member.memberType === 'field') return wrap(member.value);
                if (member.memberType === 'method') {
                  return (...args) => wrap(nativeBridge.invokeMember(target, property, args));
                }
                return undefined;
              },
              set(target, property, value, receiver) {
                if (typeof property !== 'string' || Reflect.has(target, property)) {
                  return Reflect.set(target, property, value, receiver);
                }
                if (/^(0|[1-9]\d*)$/.test(property)) {
                  return nativeBridge.indexSet(target, Number(property), value);
                }
                return nativeBridge.setMember(target, property, value);
              },
            });
          }

          function wrapper(envelope) {
            const id = envelope.__wekitHandle;
            const cached = wrappers.get(id);
            if (cached) return cached;

            const target = {
              [bridgeKey]: handleBridgeType,
              __wekitHandle: id,
              kind: envelope.kind,
            };
            const result = javaProxy(target);
            wrappers.set(id, result);

            for (const key of Object.keys(envelope)) {
              if (key !== bridgeKey && key !== '__wekitHandle' && key !== 'kind') {
                target[key] = wrap(envelope[key]);
              }
            }

            switch (envelope.kind) {
              case 'hook':
                target.unhook = () => nativeUnhook(target);
                break;
              case 'class':
                target.createInstance = (args = []) => wrap(nativeReflect.classCreateInstance(target, args));
                target.getMethods = () => wrap(nativeReflect.classGetMethods(target));
                target.getFields = () => wrap(nativeReflect.classGetFields(target));
                break;
              case 'field':
                target.get = (instance) => wrap(nativeReflect.fieldGet(target, instance));
                target.set = (...args) => nativeReflect.fieldSet(
                  target,
                  args.length > 1 ? args[0] : null,
                  args.length > 1 ? args[1] : args[0],
                );
                break;
              case 'method':
                target.hookBefore = (callback) => xposed.hookBefore(target, callback);
                target.hookAfter = (callback) => xposed.hookAfter(target, callback);
                target.invoke = (instance, args = []) => wrap(nativeReflect.methodInvoke(target, instance, args));
                break;
              case 'constructor':
                target.invoke = (args = []) => wrap(nativeReflect.constructorInvoke(target, args));
                break;
            }
            return result;
          }

          globalThis.__wekitInvokeEntry = (name, invocationId) =>
            globalThis[name]?.(...wrap(nativeBridge.takeInvocation(invocationId)));
          globalThis.__wekitInvokeCallback = (id, invocationId) =>
            callbacks.get(id)?.(...wrap(nativeBridge.takeInvocation(invocationId)));
          globalThis.__wekitCallbackExists = (id) => callbacks.has(id);
          globalThis.__wekitRegisterCallback = (callback) => {
            if (typeof callback !== 'function') return null;
            const id = nextCallback++;
            callbacks.set(id, callback);
            return id;
          };

          const nativeWechat = wechat;
          globalThis.wechat = {
            sendText: (...args) => nativeWechat.sendText(...args),
            sendImage: (...args) => nativeWechat.sendImage(...args),
            sendFile: (...args) => nativeWechat.sendFile(...args),
            sendVoice: (...args) => nativeWechat.sendVoice(...args),
            sendAppMsg: (...args) => nativeWechat.sendAppMsg(...args),
            replyText: (...args) => nativeWechat.replyText(...args),
            replyImage: (...args) => nativeWechat.replyImage(...args),
            replyFile: (...args) => nativeWechat.replyFile(...args),
            replyVoice: (...args) => nativeWechat.replyVoice(...args),
            replyAppMsg: (...args) => nativeWechat.replyAppMsg(...args),
            getSelfWxId: () => nativeWechat.getSelfWxId(),
            getSelfCustomWxId: () => nativeWechat.getSelfCustomWxId(),
            sendCgi(uri, cgiId, funcId, routeId, payload, onSuccess, onFailure) {
              return nativeWechat.sendCgi(uri, cgiId, funcId, routeId, payload,
                __wekitRegisterCallback(onSuccess), __wekitRegisterCallback(onFailure));
            },
          };

          const nativeTask = task;
          globalThis.task = { run: (callback) => nativeTask.run(__wekitRegisterCallback(callback)) };

          const nativeXposed = xposed;
          const nativeHookBefore = nativeXposed.hookBefore;
          const nativeHookAfter = nativeXposed.hookAfter;
          const nativeUnhook = nativeXposed.unhook;

          const nativeReflect = {
            toClass: reflect.toClass,
            findFields: reflect.findFields,
            findMethods: reflect.findMethods,
            findFirstField: reflect.findFirstField,
            findFirstMethod: reflect.findFirstMethod,
            findConstructors: reflect.findConstructors,
            findFirstConstructor: reflect.findFirstConstructor,
            classCreateInstance: reflect.classCreateInstance,
            classGetMethods: reflect.classGetMethods,
            classGetFields: reflect.classGetFields,
            fieldGet: reflect.fieldGet,
            fieldSet: reflect.fieldSet,
            methodInvoke: reflect.methodInvoke,
            constructorInvoke: reflect.constructorInvoke,
          };

          globalThis.xposed = {
            hookBefore(...args) {
              args[args.length - 1] = __wekitRegisterCallback(args[args.length - 1]);
              return wrap(nativeHookBefore(...args));
            },
            hookAfter(...args) {
              args[args.length - 1] = __wekitRegisterCallback(args[args.length - 1]);
              return wrap(nativeHookAfter(...args));
            },
          };

          globalThis.reflect = {
            toClass: (name) => wrap(nativeReflect.toClass(name)),
            findFields(name, inherited, condition) {
              return wrap(nativeReflect.findFields(name, inherited)).filter((field) => condition(field.name, field.type, field.modifiers));
            },
            findMethods(name, inherited, condition) {
              return wrap(nativeReflect.findMethods(name, inherited)).filter((method) => condition(method.name, method.paramTypes, method.returnType, method.modifiers));
            },
            findFirstField(name, inherited, condition) {
              return this.findFields(name, inherited, condition)[0];
            },
            findFirstMethod(name, condition) {
              return this.findMethods(name, false, condition)[0];
            },
            findConstructors(name, publicOnly, condition) {
              return wrap(nativeReflect.findConstructors(name, publicOnly)).filter((constructor) => condition(constructor.name, constructor.paramTypes, constructor.returnType, constructor.modifiers));
            },
            findFirstConstructor(name, publicOnly, condition) {
              return this.findConstructors(name, publicOnly, condition)[0];
            },
          };

          const nativeDexkit = dexkit;
          globalThis.dexkit = {
            findMethod(searcher) {
              const result = wrap(nativeDexkit.findMethod(searcher));
              result.single = () => result.methods.length === 1 ? result.methods[0] : undefined;
              return result;
            },
            findClass(searcher) {
              const result = wrap(nativeDexkit.findClass(searcher));
              result.single = () => result.classes.length === 1 ? result.classes[0] : undefined;
              return result;
            },
          };

          const nativeHostinfo = hostinfo;
          globalThis.hostinfo = {
            get application() { return wrap(nativeHostinfo.application); },
            get packageName() { return nativeHostinfo.packageName; },
            get versionCode() { return nativeHostinfo.versionCode; },
            get versionName() { return nativeHostinfo.versionName; },
            get isHostGooglePlay() { return nativeHostinfo.isHostGooglePlay; },
          };
        })();
    """

    private val currentTalker = ThreadLocal<String?>()

    internal fun beginMessageContext(talker: String): String? = currentTalker.get().also { currentTalker.set(talker) }

    internal fun endMessageContext(previous: String?) {
        if (previous == null) currentTalker.remove() else currentTalker.set(previous)
    }

    private fun replyTarget(api: String): String? = currentTalker.get().also {
        if (it == null) WeLogger.w(TAG_WECHAT_API, "wechat.$api called outside of onMessage; ignored")
    }

    private fun Array<Any?>.string(index: Int): String? = getOrNull(index)?.toString()
    private fun Array<Any?>.number(index: Int): Number? = getOrNull(index) as? Number
    private fun Array<Any?>.map(index: Int): Map<*, *>? = getOrNull(index) as? Map<*, *>
    private fun Array<Any?>.logMessage(session: Session): String = joinToString(" ") { value ->
        session.resolve(value)?.toString() ?: "null"
    }

    private fun Array<Any?>.sendText() {
        string(0)?.let { to -> string(1)?.let { WeMessageApi.sendText(to, it) } }
    }

    private fun Array<Any?>.sendImage() {
        string(0)?.let { to -> string(1)?.let { WeMessageApi.sendImage(to, it) } }
    }

    private fun Array<Any?>.sendFile() {
        string(0)?.let { to -> string(1)?.let { path -> WeMessageApi.sendFile(to, path, string(2) ?: path.substringAfterLast('/')) } }
    }

    private fun Array<Any?>.sendVoice() {
        string(0)?.let { to -> string(1)?.let { path -> WeMessageApi.sendVoice(to, path, number(2)?.toInt() ?: 0) } }
    }

    private fun Array<Any?>.sendAppMsg() {
        string(0)?.let { to -> string(1)?.let { WeMessageApi.sendXmlAppMsg(to, it) } }
    }

    private fun sleep(milliseconds: Long) {
        if (milliseconds <= 0) return
        try {
            Thread.sleep(milliseconds)
        } catch (e: InterruptedException) {
            WeLogger.w(TAG_LOG_API, "datetime.sleep interrupted", e)
            Thread.currentThread().interrupt()
        }
    }

    private class RawHttpResponse(
        val statusCode: Int,
        val body: String,
        val contentType: String,
        val isSuccessful: Boolean,
        val headers: List<Pair<String, String>>,
    )

    private fun <T> runOffMainThread(block: () -> T): T {
        if (Looper.myLooper() != Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        thread(name = "JsHttpThread") { result = runCatching(block) }.join()
        return result!!.getOrThrow()
    }

    private fun executeRequest(request: Request): RawHttpResponse = runOffMainThread {
        httpClient.newCall(request).execute().use { response ->
            RawHttpResponse(
                response.code,
                response.body.string(),
                response.header("Content-Type") ?: "",
                response.isSuccessful,
                response.headers.names().map { it to (response.header(it) ?: "") },
            )
        }
    }

    private fun httpGet(url: String, params: Map<*, *>?, headers: Map<*, *>?): Any {
        val target = if (params == null) url else (url.toHttpUrlOrNull() ?: error("Invalid URL")).newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key.toString(), value?.toString() ?: "") }
        }.build().toString()
        return httpResponse(executeRequest(Request.Builder().url(target).applyHeaders(headers).build()))
    }

    private fun httpPost(url: String, form: Map<*, *>?, json: Map<*, *>?, headers: Map<*, *>?): Any {
        val body = when {
            json != null -> JSONObject(json.mapKeys { it.key.toString() }).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            form != null -> FormBody.Builder().apply { form.forEach { (key, value) -> add(key.toString(), value?.toString() ?: "") } }.build()
            else -> "".toRequestBody(null)
        }
        return httpResponse(executeRequest(Request.Builder().url(url).post(body).applyHeaders(headers).build()))
    }

    private fun Request.Builder.applyHeaders(headers: Map<*, *>?): Request.Builder = apply {
        headers?.forEach { (key, value) -> value?.toString()?.let { addHeader(key.toString(), it) } }
    }

    private fun httpResponse(response: RawHttpResponse): Any {
        val json = response.body.takeIf { response.contentType.contains("application/json", true) }
            ?.let { runCatching { JSONObject(it).toKotlinValue() }.getOrNull() }
        return mapOf(
            "status" to response.statusCode,
            "body" to response.body,
            "ok" to response.isSuccessful,
            "json" to json,
            "headers" to response.headers.toMap(),
        ).toJsObject()
    }

    private fun errorResponse(message: String): Any = mapOf(
        "status" to 0,
        "body" to "",
        "ok" to false,
        "error" to message,
    ).toJsObject()

    @OptIn(ExperimentalPathApi::class)
    private fun directorySize(path: Path): Long = runCatching {
        Files.walk(path).use { files -> files.filter { !Files.isDirectory(it) }.mapToLong { it.fileSize() }.sum() }
    }.getOrDefault(0)

    private fun performDownload(url: String, target: Path): Boolean = runOffMainThread {
        httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@runOffMainThread false
            response.body.byteStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        true
    }

    @Suppress("JavaCollectionWithNullableTypeArgument")
    private val storage = ConcurrentHashMap<String, Any?>()
    private val dataDir by lazy { (KnownPaths.moduleData / "data").createDirsSafe() }
    private val storageFile get() = dataDir.resolve("javascript_storage_api.json")
    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable {
        runCatching {
            val root = buildJsonObject { storage.forEach { (key, value) -> storageToJson(value)?.let { put(key, it) } } }
            storageFile.writeText(DefaultJson.encodeToString(root))
        }.onFailure { WeLogger.e(TAG, "failed to save js storage", it) }
    }

    init { loadStorageFromDisk() }

    private fun saveStorageToDisk() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 500)
    }

    private fun loadStorageFromDisk() {
        runCatching {
            if (storageFile.exists()) {
                DefaultJson.decodeFromString<JsonObject>(storageFile.readText()).forEach { (key, value) ->
                    storage[key] = jsonToStorage(value)
                }
            }
        }.onFailure { WeLogger.e(TAG, "failed to load js storage", it) }
    }

    private fun Any?.storageValue(): Any? = when (this) {
        is Map<*, *> -> entries.associate { it.key.toString() to it.value.storageValue() }
        is List<*> -> map { it.storageValue() }
        is String, is Boolean, is Number, null -> this
        else -> null
    }

    private fun storageToJson(value: Any?): JsonElement? = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject { value.forEach { (key, child) -> storageToJson(child)?.let { put(key.toString(), it) } } }
        is List<*> -> buildJsonArray { value.forEach { storageToJson(it)?.let(::add) } }
        else -> null
    }

    private fun jsonToStorage(value: JsonElement): Any? = when (value) {
        is JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
        is JsonObject -> value.mapValues { jsonToStorage(it.value) }
        is JsonArray -> value.map(::jsonToStorage)
    }

    private fun exposeReflectionApis(quickJs: QuickJs, session: Session) {
        quickJs.define("reflect") {
            function("toClass") { args ->
                args.string(0)?.let { className ->
                    runCatching { classDescriptor(session, className.toClass()) }
                        .onFailure { WeLogger.e(TAG, "reflect.toClass failed for $className", it) }
                        .getOrNull()
                }
            }
            function("findFields") { args ->
                val className = args.string(0) ?: return@function emptyList<Any>()
                val inherited = args.getOrNull(1) as? Boolean ?: false
                runCatching {
                    val clazz = className.toClass()
                    (if (inherited) superclassSequence(clazz) { it.declaredFields } else clazz.declaredFields.asSequence())
                        .map { fieldDescriptor(session, it, clazz) }
                        .toList()
                }.onFailure { WeLogger.e(TAG, "reflect.findFields failed for $className", it) }.getOrDefault(emptyList())
            }
            function("findMethods") { args ->
                val className = args.string(0) ?: return@function emptyList<Any>()
                val inherited = args.getOrNull(1) as? Boolean ?: false
                runCatching {
                    val clazz = className.toClass()
                    (if (inherited) superclassSequence(clazz) { it.declaredMethods } else clazz.declaredMethods.asSequence())
                        .map { methodDescriptor(session, it, clazz) }
                        .toList()
                }.onFailure { WeLogger.e(TAG, "reflect.findMethods failed for $className", it) }.getOrDefault(emptyList())
            }
            function("findFirstField") { args ->
                val className = args.string(0) ?: return@function null
                val inherited = args.getOrNull(1) as? Boolean ?: false
                runCatching {
                    val clazz = className.toClass()
                    (if (inherited) superclassSequence(clazz) { it.declaredFields } else clazz.declaredFields.asSequence())
                        .firstOrNull()
                        ?.let { fieldDescriptor(session, it, clazz) }
                }.getOrNull()
            }
            function("findFirstMethod") { args ->
                val className = args.string(0) ?: return@function null
                runCatching {
                    val clazz = className.toClass()
                    clazz.declaredMethods.firstOrNull()?.let { methodDescriptor(session, it, clazz) }
                }.onFailure { WeLogger.e(TAG, "reflect.findFirstMethod failed for $className", it) }.getOrNull()
            }
            function("findConstructors") { args ->
                val className = args.string(0) ?: return@function emptyList<Any>()
                val publicOnly = args.getOrNull(1) as? Boolean ?: false
                runCatching {
                    val clazz = className.toClass()
                    (if (publicOnly) clazz.constructors.asList() else clazz.declaredConstructors.asList())
                        .map { constructorDescriptor(session, it, clazz) }
                }.onFailure { WeLogger.e(TAG, "reflect.findConstructors failed for $className", it) }.getOrDefault(emptyList())
            }
            function("findFirstConstructor") { args ->
                val className = args.string(0) ?: return@function null
                val publicOnly = args.getOrNull(1) as? Boolean ?: false
                runCatching {
                    val clazz = className.toClass()
                    (if (publicOnly) clazz.constructors.firstOrNull() else clazz.declaredConstructors.firstOrNull())
                        ?.let { constructorDescriptor(session, it, clazz) }
                }.getOrNull()
            }
            function("classCreateInstance") { args ->
                val clazz = session.resolve(args.getOrNull(0)) as? Class<*> ?: return@function null
                val values = args.getOrNull(1) as? List<*> ?: emptyList<Any?>()
                runCatching {
                    val invocation = selectConstructor(session, clazz.declaredConstructors.asSequence(), values)
                        ?: return@runCatching null
                    invocation.constructor.makeAccessible().newInstance(*invocation.arguments.values)
                        .let { bridgeValue(session, it) }
                }.onFailure { WeLogger.e(TAG, "reflect JavaClass.createInstance failed for ${clazz.name}", it) }.getOrNull()
            }
            function("classGetMethods") { args ->
                val clazz = session.resolve(args.getOrNull(0)) as? Class<*> ?: return@function emptyList<Any>()
                clazz.declaredMethods.map { methodDescriptor(session, it, clazz) }
            }
            function("classGetFields") { args ->
                val clazz = session.resolve(args.getOrNull(0)) as? Class<*> ?: return@function emptyList<Any>()
                clazz.declaredFields.map { fieldDescriptor(session, it, clazz) }
            }
            function("fieldGet") { args ->
                val field = session.resolve(args.getOrNull(0)) as? Field ?: return@function null
                runCatching { bridgeValue(session, field.makeAccessible().get(session.resolve(args.getOrNull(1)))) }
                    .onFailure { WeLogger.e(TAG, "reflect field.get failed on ${field.name}", it) }.getOrNull()
            }
            function("fieldSet") { args ->
                val field = session.resolve(args.getOrNull(0)) as? Field ?: return@function
                runCatching {
                    field.makeAccessible().set(
                        session.resolve(args.getOrNull(1)),
                        coerce(session, args.getOrNull(2), field.type),
                    )
                }.onFailure { WeLogger.e(TAG, "reflect field.set failed on ${field.name}", it) }
            }
            function("methodInvoke") { args ->
                val method = session.resolve(args.getOrNull(0)) as? Method ?: return@function null
                val instance = session.resolve(args.getOrNull(1))
                val values = args.getOrNull(2) as? List<*> ?: emptyList<Any?>()
                val invocation = coerceArguments(session, values, method.parameterTypes, method.isVarArgs)
                    ?: return@function methodInvocationError(
                        session,
                        IllegalArgumentException("Arguments do not match ${method.declaringClass.name}.${method.name}"),
                    )
                runCatching {
                    mapOf(
                        "value" to bridgeValue(session, method.makeAccessible().invoke(instance, *invocation.values)),
                        "exception" to false,
                    ).toJsObject()
                }.getOrElse { error ->
                    WeLogger.e(TAG, "reflect method invoke failed on ${method.name}", error)
                    methodInvocationError(session, error)
                }
            }
            function("constructorInvoke") { args ->
                val constructor = session.resolve(args.getOrNull(0)) as? Constructor<*> ?: return@function null
                val values = args.getOrNull(1) as? List<*> ?: emptyList<Any?>()
                val invocation = coerceArguments(session, values, constructor.parameterTypes, constructor.isVarArgs)
                    ?: return@function null
                runCatching {
                    constructor.makeAccessible().newInstance(*invocation.values)
                        .let { bridgeValue(session, it) }
                }.onFailure { WeLogger.e(TAG, "reflect constructor invoke failed", it) }.getOrNull()
            }
        }
    }

    private fun <M> superclassSequence(
        clazz: Class<*>,
        provider: (Class<*>) -> Array<M>,
    ): Sequence<M> = sequence {
        yieldAll(provider(clazz).asSequence())
        var current: Class<*>? = clazz.superclass
        while (current != null && current != Any::class.java) {
            yieldAll(provider(current).asSequence())
            current = current.superclass
        }
    }

    private data class JavaTarget(
        val clazz: Class<*>,
        val instance: Any?,
        val staticOnly: Boolean,
    )

    private fun bridgeMember(session: Session, args: Array<Any?>): Any? {
        val target = session.resolve(args.getOrNull(0))?.asJavaTarget() ?: return null
        val name = args.string(1) ?: return null
        val field = target.fields().firstOrNull { it.name == name }
        if (field != null) {
            return runCatching {
                mapOf(
                    "memberType" to "field",
                    "value" to bridgeValue(session, field.makeAccessible().get(target.instance)),
                ).toJsObject()
            }.onFailure { WeLogger.e(TAG, "java field read failed on ${target.clazz.name}.$name", it) }.getOrNull()
        }
        return if (target.methods().any { it.name == name }) mapOf("memberType" to "method").toJsObject() else null
    }

    private fun bridgeSetMember(session: Session, args: Array<Any?>): Boolean {
        val target = session.resolve(args.getOrNull(0))?.asJavaTarget() ?: return false
        val name = args.string(1) ?: return false
        val field = target.fields().firstOrNull { it.name == name } ?: return false
        val value = coerceValue(session, args.getOrNull(2), field.type) ?: return false
        return runCatching {
            field.makeAccessible().set(target.instance, value.value)
            true
        }.onFailure { WeLogger.e(TAG, "java field write failed on ${target.clazz.name}.$name", it) }.getOrDefault(false)
    }

    private fun bridgeInvokeMember(session: Session, args: Array<Any?>): Any? {
        val target = session.resolve(args.getOrNull(0))?.asJavaTarget() ?: return null
        val name = args.string(1) ?: return null
        val values = args.getOrNull(2) as? List<*> ?: emptyList<Any?>()
        val invocation = selectMethod(session, target, name, values) ?: return null
        return runCatching {
            invocation.method.makeAccessible().invoke(target.instance, *invocation.arguments.values)
                .let { bridgeValue(session, it) }
        }.onFailure { WeLogger.e(TAG, "java method invoke failed on ${target.clazz.name}.$name", it) }.getOrNull()
    }

    private fun bridgeLength(value: Any?): Int? = when {
        value == null -> null
        value.javaClass.isArray -> JavaArray.getLength(value)
        value is List<*> -> value.size
        else -> null
    }

    private fun bridgeIndexGet(session: Session, args: Array<Any?>): Any? {
        val value = session.resolve(args.getOrNull(0)) ?: return null
        val index = args.number(1)?.toInt() ?: return null
        return runCatching {
            when {
                value.javaClass.isArray -> JavaArray.get(value, index)
                value is List<*> -> value[index]
                else -> return@runCatching null
            }.let { bridgeValue(session, it) }
        }.onFailure { WeLogger.e(TAG, "java indexed read failed at $index", it) }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun bridgeIndexSet(session: Session, args: Array<Any?>): Boolean {
        val value = session.resolve(args.getOrNull(0)) ?: return false
        val index = args.number(1)?.toInt() ?: return false
        return runCatching {
            when {
                value.javaClass.isArray -> JavaArray.set(
                    value,
                    index,
                    coerceValue(session, args.getOrNull(2), value.javaClass.componentType!!)?.value
                        ?: return@runCatching false,
                )
                value is MutableList<*> -> (value as MutableList<Any?>)[index] = session.resolve(args.getOrNull(2))
                else -> return@runCatching false
            }
            true
        }.onFailure { WeLogger.e(TAG, "java indexed write failed at $index", it) }.getOrDefault(false)
    }

    private fun Any.asJavaTarget(): JavaTarget = when (this) {
        is Class<*> -> JavaTarget(this, null, staticOnly = true)
        else -> JavaTarget(javaClass, this, staticOnly = false)
    }

    private fun JavaTarget.fields(): Sequence<Field> = superclassSequence(clazz) { it.declaredFields }
        .filter { !staticOnly || Modifier.isStatic(it.modifiers) }

    private fun JavaTarget.methods(): Sequence<Method> = sequence {
        yieldAll(superclassSequence(clazz) { it.declaredMethods })
        if (!staticOnly) yieldAll(Any::class.java.declaredMethods.asSequence())
    }.filter { !staticOnly || Modifier.isStatic(it.modifiers) }

    private fun exposeXposedApis(quickJs: QuickJs, session: Session) {
        quickJs.define("xposed") {
            function("hookBefore") { args -> installHook(session, before = true, args) }
            function("hookAfter") { args -> installHook(session, before = false, args) }
            function("unhook") { args -> (session.resolve(args.getOrNull(0)) as? HookHandle)?.unhook() }
        }
    }

    private fun exposeDexKitApis(quickJs: QuickJs, session: Session) {
        quickJs.define("dexkit") {
            function("findMethod") { args ->
                val searcher = args.map(0) ?: return@function dexMethodResult(session, emptyList())
                runCatching {
                    withDexKit { dexKit ->
                        dexMethodResult(session, dexKit.findMethod {
                            val pkgs = searcher.stringList("searchPackages")
                            if (pkgs.isNotEmpty()) searchPackages(*pkgs.toTypedArray())
                            matcher {
                                searcher.stringOrClass("declaringClass")?.let { declaredClass = it }
                                searcher.string("name")?.let { name = it }
                                searcher.stringOrClass("returnType")?.let { returnType = it }
                                searcher.number("paramCount")?.toInt()?.let { paramCount = it }
                                searcher.stringOrClassList("paramTypes").takeIf { it.isNotEmpty() }?.let { paramTypes(*it.toTypedArray()) }
                                searcher.stringList("usingEqStrings").takeIf { it.isNotEmpty() }?.let { usingEqStrings(*it.toTypedArray()) }
                                searcher.numberList("usingNumbers").takeIf { it.isNotEmpty() }?.let { usingNumbers(*it.toTypedArray()) }
                            }
                        }.toList())
                    }
                }.onFailure { WeLogger.e(TAG, "dexkit.findMethod failed", it) }.getOrElse { dexMethodResult(session, emptyList()) }
            }
            function("findClass") { args ->
                val searcher = args.map(0) ?: return@function dexClassResult(session, emptyList())
                runCatching {
                    withDexKit { dexKit ->
                        dexClassResult(session, dexKit.findClass {
                            val pkgs = searcher.stringList("searchPackages")
                            if (pkgs.isNotEmpty()) searchPackages(*pkgs.toTypedArray())
                            matcher {
                                searcher.string("name")?.let { className = it }
                                searcher.stringOrClass("superclass")?.let { superClass = it }
                                searcher.stringList("usingEqStrings").takeIf { it.isNotEmpty() }?.let { usingEqStrings(*it.toTypedArray()) }
                                searcher.stringList("interfaces").forEach { interfaceName -> addInterface { className = interfaceName } }
                            }
                        }.toList())
                    }
                }.onFailure { WeLogger.e(TAG, "dexkit.findClass failed", it) }.getOrElse { dexClassResult(session, emptyList()) }
            }
        }
    }

    private fun installHook(session: Session, before: Boolean, args: Array<Any?>): Any? {
        val first = args.getOrNull(0)
        val method: Method
        val callbackId: Long
        if (first is Map<*, *>) {
            method = session.resolve(first) as? Method ?: return null
            callbackId = args.number(1)?.toLong() ?: return null
        } else {
            val className = first?.toString() ?: return null
            val methodName = args.string(1) ?: return null
            callbackId = args.number(2)?.toLong() ?: return null
            method = runCatching { className.toClass().methods.firstOrNull { it.name == methodName } }
                .getOrNull() ?: return null
        }
        return try {
            val handle = if (before) {
                method.makeAccessible().hookBeforeDirectly {
                    val callbackResult = session.callback(callbackId, listOf(thisObject, BridgeList(args.toList())))
                    if (callbackResult != null) result = session.resolve(callbackResult)
                }
            } else {
                method.makeAccessible().hookAfterDirectly {
                    val callbackResult = session.callback(callbackId, listOf(thisObject, BridgeList(args.toList()), result))
                    if (callbackResult != null) result = session.resolve(callbackResult)
                }
            }
            handleDescriptor(session, handle)
        } catch (e: Exception) {
            WeLogger.e(TAG, "xposed.hook${if (before) "Before" else "After"} failed", e)
            null
        }
    }

    private fun classDescriptor(session: Session, clazz: Class<*>): Any = mapOf(
        BRIDGE_KEY to HANDLE_BRIDGE_TYPE,
        HANDLE_KEY to session.retain(clazz),
        "kind" to "class",
        "name" to clazz.name,
    ).toJsObject()

    private fun fieldDescriptor(session: Session, field: Field, clazz: Class<*>): Any = mapOf(
        BRIDGE_KEY to HANDLE_BRIDGE_TYPE,
        HANDLE_KEY to session.retain(field),
        "kind" to "field",
        "name" to field.name,
        "clazz" to classDescriptor(session, clazz),
        "type" to classDescriptor(session, field.type),
        "modifiers" to modifierStrings(field.modifiers).asList(),
    ).toJsObject()

    private fun methodDescriptor(session: Session, method: Method, clazz: Class<*>): Any = mapOf(
        BRIDGE_KEY to HANDLE_BRIDGE_TYPE,
        HANDLE_KEY to session.retain(method),
        "kind" to "method",
        "name" to method.name,
        "clazz" to classDescriptor(session, clazz),
        "descriptor" to methodDescriptor(method),
        "paramTypes" to method.parameterTypes.map { classDescriptor(session, it) },
        "returnType" to classDescriptor(session, method.returnType),
        "modifiers" to modifierStrings(method.modifiers).asList(),
    ).toJsObject()

    private fun constructorDescriptor(session: Session, constructor: Constructor<*>, clazz: Class<*>): Any = mapOf(
        BRIDGE_KEY to HANDLE_BRIDGE_TYPE,
        HANDLE_KEY to session.retain(constructor),
        "kind" to "constructor",
        "name" to constructor.name,
        "clazz" to classDescriptor(session, clazz),
        "descriptor" to constructorDescriptor(constructor),
        "paramTypes" to constructor.parameterTypes.map { classDescriptor(session, it) },
        "returnType" to classDescriptor(session, clazz),
        "modifiers" to modifierStrings(constructor.modifiers).asList(),
    ).toJsObject()

    private fun handleDescriptor(session: Session, handle: HookHandle): Any = mapOf(
        BRIDGE_KEY to HANDLE_BRIDGE_TYPE,
        HANDLE_KEY to session.retain(handle),
        "kind" to "hook",
    ).toJsObject()

    private fun bridgeValue(session: Session, value: Any?): Any? = when (value) {
        is StructuredValue -> bridgeStructuredValue(value.value)
        is BridgeList -> value.values.map { bridgeValue(session, it) }
        null, is String, is Number, is Boolean -> value
        is Class<*> -> classDescriptor(session, value)
        is Field -> fieldDescriptor(session, value, value.declaringClass)
        is Method -> methodDescriptor(session, value, value.declaringClass)
        is Constructor<*> -> constructorDescriptor(session, value, value.declaringClass)
        is HookHandle -> handleDescriptor(session, value)
        else -> mapOf(
            BRIDGE_KEY to HANDLE_BRIDGE_TYPE,
            HANDLE_KEY to session.retain(value),
            "kind" to "java",
        ).toJsObject()
    }

    private fun bridgeStructuredValue(value: Any?): Any? = when (value) {
        null, is String, is Number, is Boolean -> value
        is Map<*, *> -> value.entries.associate { it.key.toString() to bridgeStructuredValue(it.value) }.toJsObject()
        is Iterable<*> -> value.map(::bridgeStructuredValue)
        else -> value
    }

    private data class CoercedValue(val value: Any?, val score: Int)

    private data class InvocationArguments(val values: Array<Any?>, val score: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as InvocationArguments

            if (score != other.score) return false
            if (!values.contentEquals(other.values)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = score
            result = 31 * result + values.contentHashCode()
            return result
        }
    }

    private data class MethodInvocation(val method: Method, val arguments: InvocationArguments)

    private data class ConstructorInvocation(
        val constructor: Constructor<*>,
        val arguments: InvocationArguments,
    )

    private fun selectMethod(
        session: Session,
        target: JavaTarget,
        name: String,
        values: List<*>,
    ): MethodInvocation? = target.methods()
        .filter { it.name == name }
        .mapNotNull { method ->
            coerceArguments(session, values, method.parameterTypes, method.isVarArgs)
                ?.let { MethodInvocation(method, it) }
        }
        .minWithOrNull(compareBy { it.arguments.score })

    private fun selectConstructor(
        session: Session,
        constructors: Sequence<Constructor<*>>,
        values: List<*>,
    ): ConstructorInvocation? = constructors
        .mapNotNull { constructor ->
            coerceArguments(session, values, constructor.parameterTypes, constructor.isVarArgs)
                ?.let { ConstructorInvocation(constructor, it) }
        }
        .minWithOrNull(compareBy { it.arguments.score })

    private fun coerceArguments(
        session: Session,
        values: List<*>,
        parameterTypes: Array<Class<*>>,
        isVarArgs: Boolean,
    ): InvocationArguments? {
        if (!isVarArgs && values.size != parameterTypes.size) return null
        val fixedCount = if (isVarArgs) parameterTypes.size - 1 else parameterTypes.size
        if (values.size < fixedCount) return null

        val arguments = arrayOfNulls<Any?>(parameterTypes.size)
        var score = 0
        for (index in 0 until fixedCount) {
            val value = coerceValue(session, values[index], parameterTypes[index]) ?: return null
            arguments[index] = value.value
            score += value.score
        }
        if (!isVarArgs) return InvocationArguments(arguments, score)

        val varArgsType = parameterTypes.last()
        val suppliedArray = values.getOrNull(fixedCount)?.let(session::resolve)
        if (values.size == parameterTypes.size && suppliedArray != null && varArgsType.isInstance(suppliedArray)) {
            arguments[fixedCount] = suppliedArray
            return InvocationArguments(arguments, score)
        }

        val componentType = varArgsType.componentType ?: return null
        val varArgs = JavaArray.newInstance(componentType, values.size - fixedCount)
        for (index in fixedCount until values.size) {
            val value = coerceValue(session, values[index], componentType) ?: return null
            JavaArray.set(varArgs, index - fixedCount, value.value)
            score += value.score
        }
        arguments[fixedCount] = varArgs
        // A non-vararg overload with the same conversions should be preferred.
        return InvocationArguments(arguments, score + 1)
    }

    private fun coerceValue(session: Session, value: Any?, type: Class<*>): CoercedValue? {
        val raw = session.resolve(value) ?: return if (type.isPrimitive) null else CoercedValue(null, 4)

        val boxedType = type.boxed()
        if (boxedType.isInstance(raw)) return CoercedValue(raw, typeDistance(raw.javaClass, boxedType))
        if (raw is Number) {
            when (boxedType) {
                Byte::class.javaObjectType -> return CoercedValue(raw.toByte(), 2)
                Short::class.javaObjectType -> return CoercedValue(raw.toShort(), 2)
                Int::class.javaObjectType -> return CoercedValue(raw.toInt(), 2)
                Long::class.javaObjectType -> return CoercedValue(raw.toLong(), 1)
                Float::class.javaObjectType -> return CoercedValue(raw.toFloat(), 2)
                Double::class.javaObjectType -> return CoercedValue(raw.toDouble(), 1)
                Char::class.javaObjectType -> return CoercedValue(raw.toInt().toChar(), 2)
            }
        }
        if (boxedType == Char::class.javaObjectType && raw is String && raw.length == 1) {
            return CoercedValue(raw[0], 2)
        }
        if (boxedType == String::class.java && raw is Char) return CoercedValue(raw.toString(), 1)
        if (type.isEnum && raw is String) {
            val enum = type.enumConstants?.firstOrNull { (it as Enum<*>).name == raw } ?: return null
            return CoercedValue(enum, 2)
        }
        if (type.isArray && raw is List<*>) {
            val componentType = type.componentType ?: return null
            val array = JavaArray.newInstance(componentType, raw.size)
            var score = 2
            raw.forEachIndexed { index, item ->
                val converted = coerceValue(session, item, componentType) ?: return null
                JavaArray.set(array, index, converted.value)
                score += converted.score
            }
            return CoercedValue(array, score)
        }
        return null
    }

    private fun typeDistance(source: Class<*>, target: Class<*>): Int {
        if (source == target) return 0
        if (target == Any::class.java) return 8
        if (target.isInterface) return 2
        var current: Class<*>? = source
        var distance = 0
        while (current != null && current != target) {
            current = current.superclass
            distance++
        }
        return distance
    }

    private fun methodInvocationError(session: Session, error: Throwable): Any = mapOf(
        "value" to bridgeValue(session, error),
        "exception" to true,
    ).toJsObject()

    private fun coerce(session: Session, value: Any?, type: Class<*>): Any? =
        coerceValue(session, value, type)?.value ?: session.resolve(value)

    private fun modifierStrings(modifiers: Int): Array<String> =
        Modifier.toString(modifiers).takeIf { it.isNotEmpty() }?.split(" ")?.toTypedArray() ?: emptyArray()

    private fun jvmDescriptor(type: Class<*>): String = when {
        type.isPrimitive -> when (type.name) {
            "void" -> "V"; "int" -> "I"; "boolean" -> "Z"; "byte" -> "B"; "short" -> "S"
            "long" -> "J"; "float" -> "F"; "double" -> "D"; "char" -> "C"; else -> error("unknown primitive ${type.name}")
        }
        type.isArray -> "[${jvmDescriptor(type.componentType!!)}"
        else -> "L${type.name.replace('.', '/')};"
    }

    private fun methodDescriptor(method: Method): String =
        "(${method.parameterTypes.joinToString("") { jvmDescriptor(it) }})${jvmDescriptor(method.returnType)}"

    private fun constructorDescriptor(constructor: Constructor<*>): String =
        "(${constructor.parameterTypes.joinToString("") { jvmDescriptor(it) }})V"

    private fun dexMethodResult(session: Session, results: List<MethodData>): Any = mapOf(
        "methods" to results.map { methodDescriptor(session, it.asMethod, it.asMethod.declaringClass) },
    ).toJsObject()

    private fun dexClassResult(session: Session, results: List<ClassData>): Any = mapOf(
        "classes" to results.mapNotNull { result -> runCatching { classDescriptor(session, result.name.toClass()) }.getOrNull() },
    ).toJsObject()

    private fun Map<*, *>.string(name: String): String? = this[name]?.toString()?.takeIf { it.isNotEmpty() }
    private fun Map<*, *>.number(name: String): Number? = this[name] as? Number
    private fun Map<*, *>.stringOrClass(name: String): String? = when (val value = this[name]) {
        is Map<*, *> -> value.string("name")
        else -> value?.toString()
    }
    private fun Map<*, *>.stringList(name: String): List<String> = (this[name] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
    private fun Map<*, *>.stringOrClassList(name: String): List<String> = (this[name] as? List<*>)?.mapNotNull { value ->
        when (value) { is Map<*, *> -> value.string("name"); else -> value?.toString() }
    } ?: emptyList()
    private fun Map<*, *>.numberList(name: String): List<Number> = (this[name] as? List<*>)?.filterIsInstance<Number>() ?: emptyList()

    private fun Any?.toJsValue(): Any? = when (this) {
        is Map<*, *> -> entries.associate { it.key.toString() to it.value.toJsValue() }.toJsObject()
        is List<*> -> map { it.toJsValue() }
        else -> this
    }

    private fun JSONObject.toKotlinValue(): Any = keys().asSequence().associateWith { get(it).toKotlinValue() }.toJsObject()
    private fun JSONArray.toKotlinValue(): List<Any?> = (0 until length()).map { get(it).toKotlinValue() }
    private fun Any?.toKotlinValue(): Any? = when (this) {
        is JSONObject -> toKotlinValue()
        is JSONArray -> toKotlinValue()
        JSONObject.NULL -> null
        else -> this
    }
}
