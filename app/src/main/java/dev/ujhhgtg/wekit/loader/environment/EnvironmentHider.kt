package dev.ujhhgtg.wekit.loader.environment

import android.app.ApplicationPackageManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.VersionedPackage
import android.os.Debug
import android.provider.Settings
import dev.ujhhgtg.reflekt.reflected.BaseReflectedMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.entry.zygisk.ArtHookBridge
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.int
import java.io.File
import java.io.InputStream
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Process-local protection, installed after attachBaseContext and before host onCreate. */
object EnvironmentHider {
    private const val TAG = "EnvironmentHider"
    private const val TABLES_SHA256 = "50de3af0994ffa0aa410e9e497aa97f45eab16e93beaf0e41542c4de6312b642"
    private var installed = false

    fun install(context: Context, modulePath: String) {
        if (installed) return
        val policy = EnvironmentReportPolicy(
            PackageNames.MODULE, modulePath, File(context.filesDir, "wekit").path,
        )
        installPackageVisibility()
        installSettings()
        Debug::class.reflekt().firstMethod { name = "isDebuggerConnected"; parameters() }
            .hookBeforeDirectly { result = false }

        // No native library is loaded by these hooks. Keep tables and native profile validation lazy until aa runs.
        val codec by lazy { loadCodec(context.applicationInfo, modulePath) }
        val endpoints = findReportEndpoints(context.classLoader)
        if (endpoints == null) {
            WeLogger.w(TAG, "normsg wrapper structure unavailable or ambiguous; report interception not installed")
        } else {
            for (method in listOf(endpoints.basic, endpoints.extended)) {
                method.hookAfterDirectly {
                    if (throwable != null || result == null) return@hookAfterDirectly
                    val original = result as ByteArray
                    // Unknown/malformed payloads keep their original bytes, and produce a diagnostic.
                    try {
                        val activeCodec = codec ?: return@hookAfterDirectly
                        val res = activeCodec.rewrite(original, policy)
                        WeLogger.i(TAG, "report rewritten; ${original.size} -> ${res.size} bytes")
                        result = res
                    } catch (error: Exception) {
                        WeLogger.e(TAG, "report rewrite failed; retaining original payload", error)
                    }
                }
            }
            endpoints.mappings.hookAfterDirectly {
                if (throwable == null && result != null) {
                    result = policy.rewriteClientCheckHook(result as String, args[0] as String)
                }
            }
        }
        installed = true
        WeLogger.i(TAG, "early environment hooks installed")
    }

    private data class ReportEndpoints(
        val basic: BaseReflectedMethod,
        val extended: BaseReflectedMethod,
        val mappings: BaseReflectedMethod,
    )

    private fun findReportEndpoints(loader: ClassLoader): ReportEndpoints? {
        // Verified DEX call edges: j/k.e,f -> aa(III); j/k.p -> ac.
        // WCProbe$Info.e,f -> aa(III[B); WCProbe$Info.o -> ac. Select by structure, not version.
        val candidates = listOf($$"WCProbe$Info", "j", "k").mapNotNull { name ->
            val type = "com.tencent.mm.normsg.$name".toClassOrNull(loader) ?: return@mapNotNull null
            val reflected = type.reflekt()
            val basic = reflected.firstMethodOrNull {
                this.name = "e"
                modifiers = Modifier.STATIC
                parameters(Int::class)
                returnType = ByteArray::class.java
            } ?: return@mapNotNull null
            val extended = reflected.firstMethodOrNull {
                this.name = "f"
                modifiers = Modifier.STATIC
                parameters { it == listOf(int, int, int) || it == listOf(int, int, int, ByteArray::class.java) }
                returnType = ByteArray::class.java
            } ?: return@mapNotNull null
            val mappings = reflected.firstMethodOrNull {
                name { it == "o" || it == "p" }
                modifiers = Modifier.STATIC
                parameters(String::class, Boolean::class)
                returnType = String::class.java
            } ?: return@mapNotNull null
            ReportEndpoints(basic, extended, mappings)
        }
        return candidates.singleOrNull()
    }

    fun afterNativeLoad(bridge: IHookBridge) {
        if (bridge !is ArtHookBridge) return
        runCatching { bridge.hideLoadedModuleLibraries() }
            .onSuccess { hidden ->
                if (hidden) WeLogger.i(TAG, "hid loaded module libraries")
                else WeLogger.w(TAG, "module native-library hiding was incomplete")
            }
            .onFailure { WeLogger.e(TAG, "failed to hide module libraries", it) }
    }

    private fun installSettings() {
        for (type in listOf(Settings.Global::class, Settings.Secure::class)) {
            type.reflekt().methods {
                name = "getInt"
                parameters { it.size in 2..3 && it[1] == String::class.java }
            }.forEach { method ->
                method.hookBeforeDirectly {
                    if (args[1] == "adb_enabled" || args[1] == "development_settings_enabled") result = 0
                }
            }
        }
    }

    private fun installPackageVisibility() {
        val manager = ApplicationPackageManager::class.reflekt()
        manager.methods {
            name { it == "getPackageInfo" || it == "getApplicationInfo" }
            parameters { it.isNotEmpty() && (it[0] == String::class.java || it[0] == VersionedPackage::class.java) }
        }.forEach { method ->
            method.hookBeforeDirectly {
                val pkg = when (val value = args[0]) {
                    is VersionedPackage -> value.packageName
                    else -> value as String?
                }
                if (pkg == PackageNames.MODULE) throwable = PackageManager.NameNotFoundException(pkg)
            }
        }
        manager.methods {
            name { it in setOf("queryIntentActivities", "getInstalledApplications", "getInstalledPackages") }
            returnType = List::class.java
        }.forEach { method ->
            method.hookAfterDirectly {
                if (throwable != null || result == null) return@hookAfterDirectly
                result = (result as List<*>).filter { entry ->
                    val pkg = when (entry) {
                        is ResolveInfo -> entry.activityInfo.packageName
                        is ApplicationInfo -> entry.packageName
                        is PackageInfo -> entry.packageName
                        else -> error("unexpected PackageManager list entry")
                    }
                    pkg != PackageNames.MODULE
                }
            }
        }
    }

    private fun loadCodec(host: ApplicationInfo, modulePath: String): NormsgReportCodec? {
        val extracted = File(host.nativeLibraryDir, "libwechatnormsg.so")
        val supported = if (extracted.isFile) {
            extracted.inputStream().use(NormsgCompatibility::matchesLibrary)
        } else {
            (listOf(host.sourceDir) + host.splitSourceDirs.orEmpty()).firstNotNullOfOrNull { apk ->
                ZipFile(apk).use { archive ->
                    archive.getEntry("lib/arm64-v8a/libwechatnormsg.so")?.let { entry ->
                        archive.getInputStream(entry).use(NormsgCompatibility::matchesLibrary)
                    }
                }
            } == true
        }
        if (!supported) {
            WeLogger.w(TAG, "unverified normsg codec profile; encoded reports will be retained")
            return null
        }
        val tables = ZipFile(modulePath).use { archive ->
            archive.getInputStream(requireNotNull(archive.getEntry("assets/environment/normsg-tables.bin"))).use { it.readBytes() }
        }
        check(tables.inputStream().use(::digest) == TABLES_SHA256) { "normsg table checksum mismatch" }
        return NormsgReportCodec(tables)
    }

    private fun digest(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16384)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
