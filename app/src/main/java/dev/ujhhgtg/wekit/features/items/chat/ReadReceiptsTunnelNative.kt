package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.loader.utils.NativeLoader
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

internal object ReadReceiptsTunnelNativeParser {
    const val MAX_JSON_BYTES = 512 * 1024
    private const val MAX_TUNNELS = 100
    private const val MAX_AUTHORIZATION_URL_BYTES = 2048
    private const val MAX_ERROR_CHARS = 256
    private const val MAX_ERROR_BYTES = 512
    private val loginFields = setOf(
        "generation",
        "authorizationUrl",
        "state",
        "accountId",
        "error",
        "selectedTunnelId",
        "selectedHostname",
    )
    private val listFields = setOf("generation", "tunnels")
    private val listErrorFields = listFields + "error"
    private val tunnelFields = setOf("id", "name", "hostnames")
    private val accountIdPattern = Regex("^[A-Za-z0-9_-]{1,32}$")
    private val loginNamespacePattern = Regex("^[A-Za-z0-9_-]{43}=$")

    fun parseLoginStatus(rawJson: String): NativeCloudflareLoginStatus? = runCatching {
        require(rawJson.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES)
        val value = (StrictJsonReader.read(rawJson) as? StrictJsonRead.Parsed)?.value as? JsonObject
            ?: error("login status is not an object")
        require(value.keys == loginFields)
        val generation = value.long("generation")
        require(generation > 0)
        val authorizationUrl = value.string("authorizationUrl")
        val state = value.string("state")
        val accountId = value.string("accountId")
        val error = value.string("error")
        val selectedTunnelId = value.string("selectedTunnelId")
        val selectedHostname = value.string("selectedHostname")
        val validatedAuthorizationUrl = authorizationUrl.takeIf(String::isNotEmpty)?.also {
            require(isPinnedAuthorizationUrl(it))
        }
        require(error.isEmpty() || isBoundedError(error))
        val selected = selectedTunnelId.isNotEmpty() || selectedHostname.isNotEmpty()
        require(selectedTunnelId.isNotEmpty() == selectedHostname.isNotEmpty())
        if (selected) {
            require(ExistingTunnel.isCanonicalId(selectedTunnelId))
            require(ReadReceiptsTunnelHostnames.canonicalPublicRoot(selectedHostname) == selectedHostname)
        }
        val loginState = when (state) {
            "WAITING" -> {
                require(validatedAuthorizationUrl != null)
                require(accountId.isEmpty() && error.isEmpty() && !selected)
                CloudflareLoginState(
                    validatedAuthorizationUrl,
                    ReadReceiptsTunnelState.STARTING,
                    null,
                )
            }
            "AUTHORIZED" -> {
                require(validatedAuthorizationUrl != null)
                require(accountIdPattern.matches(accountId) && error.isEmpty())
                CloudflareLoginState(
                    validatedAuthorizationUrl,
                    ReadReceiptsTunnelState.CONNECTED,
                    null,
                )
            }
            "FAILED" -> {
                require(validatedAuthorizationUrl != null)
                require(accountId.isEmpty() && isBoundedError(error) && !selected)
                CloudflareLoginState(
                    validatedAuthorizationUrl,
                    ReadReceiptsTunnelState.FAILED,
                    error,
                )
            }
            "STOPPED" -> {
                require(
                    validatedAuthorizationUrl == null && accountId.isEmpty() && error.isEmpty() &&
                        !selected,
                )
                CloudflareLoginState(null, ReadReceiptsTunnelState.STOPPED, null)
            }
            else -> error("unknown login state")
        }
        NativeCloudflareLoginStatus(
            generation = generation,
            loginState = loginState,
            accountId = accountId,
            selectedTunnelId = selectedTunnelId.takeIf(String::isNotEmpty),
            selectedHostname = selectedHostname.takeIf(String::isNotEmpty),
        )
    }.getOrNull()

    fun parseTunnelList(rawJson: String): NativeExistingTunnelList? = runCatching {
        require(rawJson.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES)
        val value = (StrictJsonReader.read(rawJson) as? StrictJsonRead.Parsed)?.value as? JsonObject
            ?: error("tunnel list is not an object")
        require(value.keys == listFields || value.keys == listErrorFields)
        val generation = value.long("generation")
        require(generation > 0)
        val tunnelValues = value["tunnels"] as? JsonArray ?: error("tunnels is not an array")
        require(tunnelValues.size <= MAX_TUNNELS)
        val tunnels = tunnelValues.map { tunnelValue ->
            val tunnelObject = tunnelValue as? JsonObject ?: error("tunnel is not an object")
            require(tunnelObject.keys == tunnelFields)
            val id = tunnelObject.string("id")
            val name = tunnelObject.string("name")
            val hostnameValues = tunnelObject["hostnames"] as? JsonArray
                ?: error("hostnames is not an array")
            val hostnames = hostnameValues.map { hostname ->
                (hostname as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    ?: error("hostname is not a string")
            }
            val tunnel = ExistingTunnel.create(id, name, hostnames)
                ?: error("invalid tunnel")
            require(tunnel.id == id && tunnel.name == name && tunnel.hostnames == hostnames)
            tunnel
        }
        val error = if (value.keys == listErrorFields) {
            value.string("error").also {
                require(isBoundedError(it) && tunnels.isEmpty())
            }
        } else {
            null
        }
        NativeExistingTunnelList(
            generation = generation,
            tunnels = Collections.unmodifiableList(ArrayList(tunnels)),
            error = error,
        )
    }.getOrNull()

    internal fun isPinnedAuthorizationUrl(value: String): Boolean {
        if (
            value.toByteArray(Charsets.UTF_8).size > MAX_AUTHORIZATION_URL_BYTES ||
            value.any(Char::isISOControl)
        ) {
            return false
        }
        val authorization = value.toHttpUrlOrNull() ?: return false
        if (
            authorization.toString() != value ||
            authorization.scheme != "https" || authorization.host != "dash.cloudflare.com" ||
            authorization.port != 443 || authorization.username.isNotEmpty() ||
            authorization.password.isNotEmpty() || authorization.fragment != null ||
            authorization.encodedPath != "/argotunnel" || authorization.querySize != 1 ||
            authorization.queryParameterName(0) != "callback"
        ) {
            return false
        }
        val callbackValue = authorization.queryParameterValue(0) ?: return false
        val callback = callbackValue.toHttpUrlOrNull() ?: return false
        return callback.toString() == callbackValue && callback.scheme == "https" &&
            callback.host == "login.cloudflareaccess.org" &&
            callback.port == 443 && callback.username.isEmpty() && callback.password.isEmpty() &&
            callback.query == null && callback.fragment == null && callback.pathSegments.size == 1 &&
            loginNamespacePattern.matches(callback.pathSegments.single())
    }

    private fun isBoundedError(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_ERROR_CHARS &&
            value.toByteArray(Charsets.UTF_8).size <= MAX_ERROR_BYTES &&
            value.none(Char::isISOControl)

    private fun JsonObject.string(name: String): String =
        (get(name) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: error("$name is not a string")

    private fun JsonObject.long(name: String): Long =
        (get(name) as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull
            ?: error("$name is not an integer")
}

/** Direct JNI owner for the separately-built Go cloudflared shared library. */
internal object ReadReceiptsTunnelNative {
    private val handle = AtomicLong()
    private val authHandle = AtomicLong()

    @Synchronized
    fun startQuick(origin: String, connectorAuthenticator: String): Result<Unit> {
        ensureLoaded()
        return start {
            nativeStartQuick(authenticatedOrigin(origin, connectorAuthenticator))
        }
    }

    @Synchronized
    fun startToken(
        token: String,
        origin: String,
        connectorAuthenticator: String,
    ): Result<Unit> {
        ensureLoaded()
        return start {
            nativeStartToken(token, authenticatedOrigin(origin, connectorAuthenticator))
        }
    }

    private fun authenticatedOrigin(origin: String, connectorAuthenticator: String): String {
        require(
            connectorAuthenticator.length == 32 && connectorAuthenticator.all {
                it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/'
            },
        ) { "invalid connector authenticator" }
        val parsed = checkNotNull(origin.toHttpUrlOrNull()) { "invalid loopback origin" }
        check(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "loopback origin must not contain credentials"
        }
        return parsed.newBuilder().username(connectorAuthenticator).build().toString()
    }

    /** Replaces only browser authentication; the active connector remains untouched. */
    @Synchronized
    fun beginLogin(): Result<NativeCloudflareLoginStatus> {
        ensureLoaded()
        return runCatching {
            val previous = authHandle.getAndSet(0L)
            if (previous != 0L) {
                check(nativeAuthCancel(previous) == 0) { "browser login replacement failed" }
            }
            val created = nativeAuthBegin()
            check(created != 0L) { "browser login could not be created" }
            authHandle.set(created)
            parseLoginStatus(
                checkNotNull(nativeAuthStatus(created)) { "browser login status is unavailable" },
            )
        }.onFailure {
            val created = authHandle.getAndSet(0L)
            if (created != 0L) nativeAuthCancel(created)
        }
    }

    fun loginStatus(): Result<NativeCloudflareLoginStatus> {
        ensureLoaded()
        return runCatching {
            parseLoginStatus(
                checkNotNull(nativeAuthStatus(requireAuthHandle())) {
                    "browser login status is unavailable"
                },
            )
        }
    }

    /** Intentionally unlocked: a timeout owner must cancel this blocking JNI call from another IO coroutine. */
    fun listExistingTunnels(): Result<NativeExistingTunnelList> {
        ensureLoaded()
        return runCatching {
            parseTunnelList(
                checkNotNull(nativeAuthList(requireAuthHandle())) {
                    "Cloudflare tunnel list is unavailable"
                },
            )
        }
    }

    /**
     * The run token exists only as this private service-facing return value. This remains unlocked so
     * [cancelLogin] can cancel and join an in-flight native selection from another IO coroutine.
     */
    fun selectExistingTunnelForService(tunnelId: String, hostname: String): Result<String> {
        ensureLoaded()
        return runCatching {
            checkNotNull(nativeAuthSelect(requireAuthHandle(), tunnelId, hostname)) {
                "Cloudflare tunnel selection failed"
            }
        }
    }

    @Synchronized
    fun cancelLogin(): Result<Unit> {
        ensureLoaded()
        val owned = authHandle.getAndSet(0L)
        if (owned == 0L) return Result.success(Unit)
        return runCatching {
            check(nativeAuthCancel(owned) == 0) { "browser login cancellation failed" }
        }
    }

    @Synchronized
    private fun requireAuthHandle(): Long =
        authHandle.get().also { check(it != 0L) { "browser login is not active" } }

    private fun parseLoginStatus(rawJson: String): NativeCloudflareLoginStatus =
        checkNotNull(ReadReceiptsTunnelNativeParser.parseLoginStatus(rawJson)) {
            "browser login returned invalid status"
        }

    private fun parseTunnelList(rawJson: String): NativeExistingTunnelList =
        checkNotNull(ReadReceiptsTunnelNativeParser.parseTunnelList(rawJson)) {
            "browser login returned an invalid tunnel list"
        }

    @Synchronized
    private fun start(create: () -> Long): Result<Unit> = runCatching {
        check(handle.get() == 0L) { "tunnel is already active" }
        val created = create()
        check(created != 0L) { "tunnel could not be created" }
        handle.set(created)
    }

    /** Atomically clears ownership before native stop frees the handle. */
    @Synchronized
    fun stop(): Result<Unit> {
        ensureLoaded()
        val owned = handle.getAndSet(0L)
        if (owned == 0L) return Result.success(Unit)
        return runCatching { check(nativeStop(owned) == 0) { "tunnel stop failed" } }
    }

    @Synchronized
    fun status(): ReadReceiptsTunnelStatus {
        ensureLoaded()
        val owned = handle.get()
        if (owned == 0L) return ReadReceiptsTunnelStatus(ReadReceiptsTunnelState.STOPPED)
        return runCatching {
            val value = DefaultJson.parseToJsonElement(nativeStatus(owned)).jsonObject
            val state = when (value.getValue("status").jsonPrimitive.content) {
                "STOPPED" -> ReadReceiptsTunnelState.STOPPED
                "STARTING" -> ReadReceiptsTunnelState.STARTING
                "CONNECTED" -> ReadReceiptsTunnelState.CONNECTED
                "RECONNECTING" -> ReadReceiptsTunnelState.RECONNECTING
                "STOPPING" -> ReadReceiptsTunnelState.STOPPING
                "UNSUPPORTED" -> ReadReceiptsTunnelState.NEEDS_USER_ACTION
                else -> ReadReceiptsTunnelState.FAILED
            }
            val diagnostic = value.getValue("error").jsonPrimitive.content
                .takeIf(String::isNotEmpty)
            if (diagnostic != null) {
                WeLogger.w(
                    TAG,
                    "redacted connector diagnostic (chars=${diagnostic.length}, bytes=${diagnostic.toByteArray().size})",
                )
            }
            ReadReceiptsTunnelStatus(
                state = state,
                publicUrl = value.getValue("url").jsonPrimitive.content.takeIf(String::isNotEmpty),
                errorCode = when (state) {
                    ReadReceiptsTunnelState.FAILED,
                    ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                    -> ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE

                    else -> null
                },
            )
        }.getOrElse {
            WeLogger.w(TAG, "failed to parse connector status (${it.javaClass.simpleName})")
            ReadReceiptsTunnelStatus(
                ReadReceiptsTunnelState.FAILED,
                errorCode = ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE,
            )
        }
    }

    private const val TAG = "ReadReceiptsTunnelNative"

    private fun ensureLoaded() {
        NativeLoader.ensureCloudflaredLoaded()
    }

    private external fun nativeStartQuick(origin: String): Long

    private external fun nativeStartToken(token: String, origin: String): Long

    private external fun nativeStop(handle: Long): Int

    private external fun nativeStatus(handle: Long): String

    private external fun nativeAuthBegin(): Long

    private external fun nativeAuthStatus(handle: Long): String?

    private external fun nativeAuthList(handle: Long): String?

    private external fun nativeAuthSelect(handle: Long, tunnelId: String, hostname: String): String?

    private external fun nativeAuthCancel(handle: Long): Int
}
