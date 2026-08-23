package dev.ujhhgtg.wekit.extensions.monet

import android.annotation.SuppressLint
import android.content.res.Resources
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.PackageBlock
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequest
import dev.ujhhgtg.wekit.extensions.monet.api.MonetLogLevel
import java.io.File
import java.util.zip.ZipFile

/**
 * Rewrites the template RRO against the resources of the WeChat APK described by [request].
 * Unknown hosts keep only live values matching the generic table and discover obfuscated semantic
 * colors from literal ARGB values in resources.arsc.
 */
internal class MonetOverlayBuilder(
    private val request: MonetGenerationRequest,
    private val tables: MonetTables,
    private val templateApk: File,
    private val log: (MonetLogLevel, String, Throwable?) -> Unit,
) {
    private val hostRes = request.resources
    private val hostPkg = request.packageName
    private val frameworkIdCache = HashMap<String, Int>()

    data class Result(
        val outputApk: File,
        val kept: Int,
        val pruned: Int,
        val added: Int,
    )

    fun build(outputApk: File): Result {
        val apk = ApkModule.loadApkFile(templateApk)
        val pkg = apk.tableBlock.pickOne()
            ?: error("overlay template has no resource package")
        val table = resolveTable()
        var kept = 0
        var pruned = 0
        var added = 0

        val templateColorNames = collectColorNames(pkg)
        for (name in templateColorNames) {
            val rule = table.colors[name]
            if (rule == null) {
                if (pruneColor(pkg, name)) pruned++
                continue
            }
            if (verifyLiveValue(name, rule)) {
                kept++
            } else if (pruneColor(pkg, name)) {
                pruned++
            }
        }

        for ((name, rule) in table.colors) {
            if (name in templateColorNames) continue
            if (!verifyLiveValue(name, rule)) continue
            if (addColor(pkg, name, rule)) added++
        }

        apk.apkSignatureBlock = null
        outputApk.parentFile?.mkdirs()
        apk.writeApk(outputApk)
        log(
            MonetLogLevel.INFO,
            "overlay built: kept=$kept pruned=$pruned added=$added -> $outputApk",
            null,
        )
        return Result(outputApk, kept, pruned, added)
    }

    private fun resolveTable(): MonetVersionTable {
        val versionCode = request.versionCode.toString()
        tables.versions[versionCode]?.let {
            log(
                MonetLogLevel.INFO,
                "using exact table for versionCode=$versionCode (${it.colors.size} colors)",
                null,
            )
            return it
        }
        log(
            MonetLogLevel.WARN,
            "no exact table for versionCode=$versionCode, building from generic + brandByValue",
            null,
        )
        return buildGenericTable()
    }

    private fun buildGenericTable(): MonetVersionTable {
        val colors = HashMap<String, MonetColorRule>()
        colors.putAll(tables.generic)
        colors.putAll(discoverColorsByValue())
        return MonetVersionTable(colors)
    }

    private fun discoverColorsByValue(): Map<String, MonetColorRule> {
        if (tables.brandByValue.isEmpty() && tables.surfByPair.isEmpty()) return emptyMap()
        val brandByValue = HashMap<Long, MonetColorRule>()
        for ((value, rule) in tables.brandByValue) {
            normalizeColor(value)?.let { brandByValue[it] = rule }
        }
        val surfByPair = HashMap<Pair<Long, Long>, MonetColorRule>()
        for ((key, rule) in tables.surfByPair) {
            val parts = key.split('|')
            if (parts.size != 2) continue
            val light = normalizeColor(parts[0]) ?: continue
            val night = normalizeColor(parts[1]) ?: continue
            surfByPair[light to night] = rule
        }

        val result = HashMap<String, MonetColorRule>()
        val hostArsc = loadHostColorArgb() ?: return result
        for ((name, lightNight) in hostArsc) {
            val (light, night) = lightNight
            brandByValue[light]?.let { result[name] = it }
            if (name !in result) {
                surfByPair[light to night]?.let { result[name] = it }
            }
        }
        log(
            MonetLogLevel.INFO,
            "discovered ${result.size} colored names by value from host arsc",
            null,
        )
        return result
    }

    /** Loads only resources.arsc rather than the full host APK. */
    private fun loadHostColorArgb(): Map<String, Pair<Long, Long>>? {
        return runCatching {
            val arscBytes = ZipFile(request.sourceApkPath).use { zip ->
                val entry = zip.getEntry("resources.arsc")
                    ?: error("resources.arsc not found in ${request.sourceApkPath}")
                zip.getInputStream(entry).use { it.readBytes() }
            }
            val table = com.reandroid.arsc.chunk.TableBlock.load(arscBytes.inputStream())
            val light = HashMap<String, Long>()
            val night = HashMap<String, Long>()
            for (pkg in table.listPackages()) {
                val resources = pkg.getResources("color")
                while (resources.hasNext()) {
                    val resource = resources.next()
                    val name = resource.name ?: continue
                    val entries = pkg.getEntries(resource.resourceId)
                    while (entries.hasNext()) {
                        val entry = entries.next() ?: continue
                        if (entry.isNull) continue
                        if (entry.valueType != com.reandroid.arsc.value.ValueType.COLOR_ARGB8) {
                            continue
                        }
                        val argb = entry.resValue.data.toLong() and 0xFFFFFFFFL
                        val qualifiers = entry.resConfig?.qualifiers.orEmpty()
                        if (qualifiers.contains("-night")) {
                            night[name] = argb
                        } else if (qualifiers.isEmpty()) {
                            light[name] = argb
                        }
                    }
                }
            }
            val result = HashMap<String, Pair<Long, Long>>()
            for ((name, value) in light) {
                result[name] = value to (night[name] ?: value)
            }
            result
        }.onFailure {
            log(MonetLogLevel.WARN, "failed to read host arsc for color discovery", it)
        }.getOrNull()
    }

    private fun collectColorNames(pkg: PackageBlock): Set<String> {
        val names = LinkedHashSet<String>()
        val resources = pkg.getResources("color")
        while (resources.hasNext()) {
            val resource = resources.next()
            val name = resource.name ?: continue
            names.add(name)
        }
        return names
    }

    private fun verifyLiveValue(name: String, rule: MonetColorRule): Boolean {
        val id = hostColorId(name) ?: return false
        val expected = rule.expectedValue ?: return true
        val expectedArgb = normalizeColor(expected) ?: return true
        val live = runCatching { hostRes.getColor(id, null) }.getOrNull() ?: return false
        return live.toLong() and 0xFFFFFFFFL == expectedArgb
    }

    @SuppressLint("DiscouragedApi")
    private fun hostColorId(name: String): Int? {
        val id = hostRes.getIdentifier(name, "color", hostPkg)
        return if (id != 0) id else null
    }

    private fun normalizeColor(value: String): Long? {
        val hex = value.trim().removePrefix("#")
        val full = when (hex.length) {
            6 -> "ff$hex"
            8 -> hex
            else -> return null
        }
        return full.toLongOrNull(16)
    }

    private fun pruneColor(pkg: PackageBlock, name: String): Boolean {
        val resource = pkg.getResource("color", name) ?: return false
        var any = false
        val entries = pkg.getEntries(resource.resourceId)
        while (entries.hasNext()) {
            val entry = entries.next() ?: continue
            if (!entry.isNull) {
                entry.isNull = true
                any = true
            }
        }
        return any
    }

    private fun addColor(pkg: PackageBlock, name: String, rule: MonetColorRule): Boolean {
        val lightId = frameworkColorId(rule.light) ?: return false
        val nightId = frameworkColorId(rule.night)
        val entry = pkg.getOrCreate("", "color", name) ?: return false
        entry.setValueAsReference(lightId)
        if (nightId != null && rule.night != rule.light) {
            val nightEntry = pkg.getOrCreate("-night", "color", name) ?: return true
            nightEntry.setValueAsReference(nightId)
        }
        return true
    }

    @SuppressLint("DiscouragedApi")
    private fun frameworkColorId(token: String): Int? {
        if (!token.startsWith("@android:color/")) return null
        val name = token.removePrefix("@android:color/")
        frameworkIdCache[name]?.let { return it }
        val id = Resources.getSystem().getIdentifier(name, "color", "android")
        if (id == 0) {
            log(MonetLogLevel.WARN, "cannot resolve framework color: $name", null)
            return null
        }
        frameworkIdCache[name] = id
        return id
    }
}
