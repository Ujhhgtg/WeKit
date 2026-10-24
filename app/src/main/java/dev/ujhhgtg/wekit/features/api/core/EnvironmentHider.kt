package dev.ujhhgtg.wekit.features.api.core

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
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.loader.environment.EmbeddedOriginApk
import dev.ujhhgtg.wekit.loader.environment.EnvironmentReportPolicy
import dev.ujhhgtg.wekit.loader.environment.NormsgCompatibility
import dev.ujhhgtg.wekit.loader.environment.NormsgReportCodec
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcess
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.reflection.int
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Always-on protection in every host process, installed by the normal feature lifecycle. */
object EnvironmentHider : ApiFeature() {
    override val technicalId = "环境隐藏服务"
    override val nameRes = R.string.feature_environment_hider_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_environment_hider_description
    override val targetProcesses = TargetProcess.entries.toSet()

    private const val TAG = "EnvironmentHider"
    private const val TABLES_SHA256 =
        "50de3af0994ffa0aa410e9e497aa97f45eab16e93beaf0e41542c4de6312b642"

    override fun onEnable() {
        val context = HostInfo.application

        // basic
        installPackageVisibility()
        installSettings()
        installDebug()

        // ClientCheckConsistency
        installConsistencyRootIndicator(context.classLoader)
        installConsistencyLogging()
        installConsistencyCheck(context)

        // ClientCheckHook
        installReport(context, StartupInfo.modulePath)
        hideNativeLibraries()

        WeLogger.i(TAG, "environment hooks installed")
    }

    private fun installConsistencyCheck(context: Context) {
        if (hasNPatchApkRedirect(context.classLoader)) {
            // NPatch owns APK reads in this mode, including when a manual origin.apk exists.
            WeLogger.i(
                TAG,
                "NPatch already handles APK reads; ClientCheckConsistency APK redirection skipped",
            )
            return
        }

        val hostApkPath = context.applicationInfo.sourceDir
        val hostApk = File(hostApkPath)
        val moduleRoot = KnownPaths.moduleRoot.toFile()
        val localOrigin = File(moduleRoot, "origin.apk")
        WeLogger.i(TAG, "ClientCheckConsistency runtime APK: $hostApkPath")

        val originPath =
            try {
                if (localOrigin.isFile) {
                    localOrigin.path
                } else if (
                    hostApk.isFile &&
                        hostApk.canonicalFile.parentFile ==
                            File(context.cacheDir, "lspatch/origin").canonicalFile
                ) {
                    // LSPatch already sets sourceDir to its extracted origin. Opening the installed
                    // APK
                    // can also be redirected by its openat hook, so reuse this original file
                    // directly.
                    WeLogger.i(TAG, "ClientCheckConsistency uses LSPatch's extracted origin APK")
                    hostApkPath
                } else {
                    // PackageManager reports the installed APK even when a loader changes the
                    // process's sourceDir.
                    val installedApk =
                        File(
                            context.packageManager
                                .getApplicationInfo(context.packageName, 0)
                                .sourceDir,
                        )
                    WeLogger.i(TAG, "ClientCheckConsistency installed APK: $installedApk")
                    val cache = File(moduleRoot, "cache/client-check-consistency")
                    (EmbeddedOriginApk.findAndExtract(installedApk, cache)
                            ?: if (installedApk != hostApk)
                                EmbeddedOriginApk.findAndExtract(hostApk, cache)
                            else null)
                        ?.file
                        ?.path
                }
            } catch (error: IOException) {
                WeLogger.e(TAG, "failed to prepare origin APK for ClientCheckConsistency", error)
                null
            }

        if (originPath != null) {
            if (originPath != hostApkPath) {
                installConsistencyArchive(context.classLoader, hostApkPath, originPath)
                WeLogger.i(TAG, "ClientCheckConsistency APK reads redirected to $originPath")
            } else {
                WeLogger.i(
                    TAG,
                    "ClientCheckConsistency already reads the original APK: $originPath",
                )
            }
        } else {
            WeLogger.i(TAG, "no usable origin APK; ClientCheckConsistency APK redirection skipped")
        }
    }

    private fun hasNPatchApkRedirect(hostLoader: ClassLoader): Boolean {
        val name = "top.nkbe.npatch.loader.SigBypass"
        val bypass =
            name.toClassOrNull(ClassLoaders.HYBRID)
                ?: name.toClassOrNull(hostLoader)
                ?: return false
        val reflected = bypass.reflekt()
        val field =
            reflected.firstFieldOrNull {
                this.name = "redirectApkPath"
                type = String::class.java
                modifiers = Modifier.STATIC
            } ?: return false
        val path =
            field.get() as? String?
                ?: return false // No original path when signature bypass is disabled
        fun enabled(state: String): Boolean {
            val flag =
                reflected.firstFieldOrNull {
                    this.name = state
                    type = Boolean::class.java
                    modifiers = Modifier.STATIC
                } ?: return false
            return flag.get() as Boolean
        }
        // Check the installed Java hook and an enabled native APK-open backend, not just NPatch's
        // presence.
        if (
            !enabled("javaIoHooked") ||
                !(enabled("nativeOpenatEnabled") || enabled("seccompRedirectEnabled"))
        )
            return false
        WeLogger.i(TAG, "NPatch APK redirect is active: $path")
        return true
    }

    private fun installConsistencyRootIndicator(loader: ClassLoader) {
        // Verified call edges: Info.q / j.r / k.r -> c$p.ab, shared by client-check replies.
        val candidates =
            listOf($$"WCProbe$Info" to "q", "j" to "r", "k" to "r").mapNotNull { (type, method) ->
                val reflected =
                    "com.tencent.mm.normsg.$type".toClassOrNull(loader)?.reflekt()
                        ?: return@mapNotNull null
                if (
                    reflected.firstMethodOrNull {
                        name = "e"
                        modifiers = Modifier.STATIC
                        parameters(Int::class)
                        returnType = ByteArray::class.java
                    } == null
                )
                    return@mapNotNull null
                reflected.firstMethodOrNull {
                    name = method
                    modifiers = Modifier.STATIC
                    parameters()
                    returnType = Boolean::class.java
                }
            }
        val root = candidates.singleOrNull()
        if (root == null) {
            WeLogger.w(TAG, "client-check root wrapper unavailable or ambiguous")
            return
        }
        root.hookBefore {
            result = false
            WeLogger.d(TAG, "client-check root indicator suppressed")
        }
        WeLogger.i(
            TAG,
            "client-check root suppression installed on ${root.declaringClass.name}.${root.name}",
        )
    }

    private fun installConsistencyLogging() {
        // 8.0.65 uses the SDK facade; later audited hosts call xlog directly.
        val loggers =
            listOf("com.tencent.mars.xlog.Log" to "i", "com.tencent.mm.sdk.platformtools.m2" to "j")
        for ((type, method) in loggers) {
            val logger =
                type.toClassOrNull(ClassLoaders.HOST)?.reflekt()?.firstMethodOrNull {
                    name = method
                    modifiers = Modifier.STATIC
                    parameters(String::class.java, String::class.java, Array<Any>::class.java)
                    returnType = Void.TYPE
                } ?: continue
            logger.hookBefore {
                if (
                    args[0] != "MicroMsg.SysCmdMsgExtension" ||
                        args[1] != "recieve a syscmd_newxml %s subType %s"
                )
                    return@hookBefore
                val values = args[2] as Array<*>
                if (values[1] != "ClientCheckConsistency") return@hookBefore
                val xml = values[0] as String
                WeLogger.logChunkedI(TAG, "ClientCheckConsistency received:\n$xml")
                try {
                    val directory =
                        KnownPaths.moduleRoot.resolve("logs/client-check-consistency").toFile()
                    if (!directory.isDirectory && !directory.mkdirs())
                        throw IOException("cannot create $directory")
                    val dump =
                        File.createTempFile("${System.currentTimeMillis()}-", ".xml", directory)
                    dump.writeText(xml)
                    WeLogger.i(TAG, "ClientCheckConsistency XML dumped to $dump")
                } catch (error: IOException) {
                    WeLogger.e(TAG, "failed to dump ClientCheckConsistency XML", error)
                }
            }
        }
    }

    private data class ConsistencyReadSites(val owner: String, val methods: Set<String>)

    private fun installConsistencyArchive(loader: ClassLoader, hostApk: String, originApk: String) {
        var sites: ConsistencyReadSites? = null
        // If NPatch is only partially initialized, run the fallback before its default-priority
        // redirect.
        ZipFile::class.reflekt()
            .firstConstructor { parameters(String::class) }
            .self
            .hookBefore(priority = 100) {
                if (args[0] != hostApk) return@hookBefore
                val stack = Thread.currentThread().stackTrace
                val reader =
                    sites
                        ?: findConsistencyReadSites(loader, stack)?.also { sites = it }
                        ?: return@hookBefore
                if (stack.none { it.className == reader.owner && it.methodName in reader.methods })
                    return@hookBefore
                // Keep the host's hash/length helpers, target string and final signature
                // construction intact.
                args[0] = originApk
                WeLogger.i(TAG, "ClientCheckConsistency APK read redirected to $originApk")
            }
    }

    private fun findConsistencyReadSites(
        loader: ClassLoader,
        stack: Array<StackTraceElement>,
    ): ConsistencyReadSites? {
        for (frame in stack) {
            val type = frame.className.toClassOrNull(loader) ?: continue
            val reflected = type.reflekt()
            val hash =
                reflected
                    .methods {
                        modifiers = Modifier.STATIC
                        parameters(String::class, Long::class, Long::class)
                        returnType = String::class.java
                    }
                    .singleOrNull() ?: continue
            val length =
                reflected
                    .methods {
                        modifiers = Modifier.STATIC
                        parameters(String::class)
                        returnType = Long::class.java
                    }
                    .singleOrNull() ?: continue
            if (
                reflected
                    .methods {
                        modifiers = Modifier.STATIC
                        parameters(Array<Any>::class.java)
                        returnType = String::class.java
                    }
                    .size != 1
            )
                continue
            val methods = setOf(hash.name, length.name)
            if (frame.methodName in methods) return ConsistencyReadSites(type.name, methods)
        }
        return null
    }

    private fun installDebug() {
        Debug::class.reflekt()
            .firstMethod {
                name = "isDebuggerConnected"
                parameters()
            }
            .hookBefore { result = false }
    }

    private fun installReport(context: Context, modulePath: String) {
        // No native library is loaded by these hooks. Keep tables and native profile validation
        // lazy until aa runs.
        val policy =
            EnvironmentReportPolicy(
                PackageNames.MODULE,
                StartupInfo.payloadEnvironment.bootstrapApk.absolutePath,
                KnownPaths.moduleRoot.toString(),
                File(context.noBackupFilesDir, "wekit-loader").absolutePath,
            )
        val codec by lazy { loadCodec(context.applicationInfo, modulePath) }
        val endpoints = findReportEndpoints(context.classLoader)
        if (endpoints == null) {
            WeLogger.w(
                TAG,
                "normsg wrapper structure unavailable or ambiguous; report interception not installed",
            )
        } else {
            for (method in listOf(endpoints.basic, endpoints.extended)) {
                method.hookAfter {
                    if (throwable != null || result == null) return@hookAfter
                    val original = result as ByteArray
                    // Unknown/malformed payloads keep their original bytes, and produce a
                    // diagnostic.
                    try {
                        val activeCodec = codec ?: return@hookAfter
                        val res = activeCodec.rewrite(original, policy)
                        WeLogger.i(TAG, "report rewritten; ${original.size} -> ${res.size} bytes")
                        result = res
                    } catch (error: Exception) {
                        WeLogger.e(TAG, "report rewrite failed; retaining original payload", error)
                    }
                }
            }
            endpoints.mappings.hookAfter {
                if (throwable == null && result != null) {
                    result = policy.rewriteClientCheckHook(result as String, args[0] as String)
                }
            }
        }
    }

    private data class ReportEndpoints(
        val basic: BaseReflectedMethod,
        val extended: BaseReflectedMethod,
        val mappings: BaseReflectedMethod,
    )

    private fun findReportEndpoints(loader: ClassLoader): ReportEndpoints? {
        // Verified DEX call edges: j/k.e,f -> aa(III); j/k.p -> ac.
        // WCProbe$Info.e,f -> aa(III[B); WCProbe$Info.o -> ac. Select by structure, not version.
        val candidates =
            listOf($$"WCProbe$Info", "j", "k").mapNotNull { name ->
                val type =
                    "com.tencent.mm.normsg.$name".toClassOrNull(loader) ?: return@mapNotNull null
                val reflected = type.reflekt()
                val basic =
                    reflected.firstMethodOrNull {
                        this.name = "e"
                        modifiers = Modifier.STATIC
                        parameters(Int::class)
                        returnType = ByteArray::class.java
                    } ?: return@mapNotNull null
                val extended =
                    reflected.firstMethodOrNull {
                        this.name = "f"
                        modifiers = Modifier.STATIC
                        parameters {
                            it == listOf(int, int, int) ||
                                it == listOf(int, int, int, ByteArray::class.java)
                        }
                        returnType = ByteArray::class.java
                    } ?: return@mapNotNull null
                val mappings =
                    reflected.firstMethodOrNull {
                        name { it == "o" || it == "p" }
                        modifiers = Modifier.STATIC
                        parameters(String::class, Boolean::class)
                        returnType = String::class.java
                    } ?: return@mapNotNull null
                ReportEndpoints(basic, extended, mappings)
            }
        return candidates.singleOrNull()
    }

    private fun hideNativeLibraries() {
        // The bridge can live in a separate bootstrap loader; its @Keep method is optional.
        val hide =
            StartupInfo.hookBridge.reflekt().firstMethodOrNull {
                name = "hideLoadedModuleLibraries"
                parameters()
                returnType = Boolean::class.java
            } ?: return
        runCatching { hide.invoke() as Boolean }
            .onSuccess { hidden ->
                if (hidden) WeLogger.i(TAG, "hid loaded module libraries")
                else WeLogger.w(TAG, "module native-library hiding was incomplete")
            }
            .onFailure { WeLogger.e(TAG, "failed to hide module libraries", it) }
    }

    private fun installSettings() {
        for (type in listOf(Settings.Global::class, Settings.Secure::class)) {
            type
                .reflekt()
                .methods {
                    name = "getInt"
                    parameters { it.size in 2..3 && it[1] == String::class.java }
                }
                .forEach { method ->
                    method.hookBefore {
                        if (args[1] == "adb_enabled" || args[1] == "development_settings_enabled")
                            result = 0
                    }
                }
        }
    }

    private fun installPackageVisibility() {
        val manager = ApplicationPackageManager::class.reflekt()
        manager
            .methods {
                name { it == "getPackageInfo" || it == "getApplicationInfo" }
                parameters {
                    it.isNotEmpty() &&
                        (it[0] == String::class.java || it[0] == VersionedPackage::class.java)
                }
            }
            .forEach { method ->
                method.hookBefore {
                    val pkg =
                        when (val value = args[0]) {
                            is VersionedPackage -> value.packageName
                            else -> value as String?
                        }
                    if (pkg == PackageNames.MODULE)
                        throwable = PackageManager.NameNotFoundException(pkg)
                }
            }
        manager
            .methods {
                name {
                    it in
                        setOf(
                            "queryIntentActivities",
                            "getInstalledApplications",
                            "getInstalledPackages",
                        )
                }
                returnType = List::class.java
            }
            .forEach { method ->
                method.hookAfter {
                    if (throwable != null || result == null) return@hookAfter
                    result =
                        (result as List<*>).filter { entry ->
                            val pkg =
                                when (entry) {
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
        val supported =
            if (extracted.isFile) {
                extracted.inputStream().use(NormsgCompatibility::matchesLibrary)
            } else {
                (listOf(host.sourceDir) + host.splitSourceDirs.orEmpty()).firstNotNullOfOrNull { apk
                    ->
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
        val tables =
            ZipFile(modulePath).use { archive ->
                archive
                    .getInputStream(
                        requireNotNull(archive.getEntry("assets/environment/normsg-tables.bin")),
                    )
                    .use { it.readBytes() }
            }
        check(tables.inputStream().use(::digest) == TABLES_SHA256) {
            "normsg table checksum mismatch"
        }
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
