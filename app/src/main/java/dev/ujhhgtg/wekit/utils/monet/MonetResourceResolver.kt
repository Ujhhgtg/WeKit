package dev.ujhhgtg.wekit.utils.monet

import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Resolves Monet semantic roles to WeChat resource IDs and caches the result per loaded WeChat
 * resource set. The analysis (full ARSC/XML graph + DexKit) is far too heavy to repeat on every
 * theme change, while bindings only change when WeChat's resource APKs do.
 */
object MonetResourceResolver {

    /** Bump when binding extraction changes so stale caches are re-resolved. */
    private const val VERSION = 2

    private val json = Json { ignoreUnknownKeys = true }

    fun fingerprint(apks: List<File>): String = sha256(buildString {
        append("v").append(VERSION).append('\n')
        apks.forEach { append(it.canonicalPath).append(':').append(it.length()).append(':').append(it.lastModified()).append('\n') }
        append(MONET_RULES.toString())
    })

    fun resolve(
        apks: List<File>,
        fingerprint: String,
        dexProvider: MonetDexEvidenceProvider,
        onProgress: (MonetResolveProgress) -> Unit,
    ): MonetBindings {
        onProgress(MonetResolveProgress(MonetResolveStage.LOADING_APKS, "读取微信资源 APK", 0, apks.size))
        val graph = MonetApkResourceGraphLoader.load(apks, PACKAGE_NAME) { detail, _, _ ->
            onProgress(MonetResolveProgress(MonetResolveStage.BUILDING_RESOURCE_GRAPH, detail, null, null))
        }
        val resolution = MonetStructureMatcher.resolveAvailable(graph, dexProvider) { completed, total, detail ->
            onProgress(MonetResolveProgress(MonetResolveStage.RESOLVING_ROLES, detail, completed, total))
        }
        val roleIds = resolution.resolved.values.mapTo(hashSetOf(), MonetResourceNode::id)
        return MonetBindings(
            fingerprint = fingerprint,
            typeNames = graph.typeNames,
            roles = resolution.resolved.mapValues { (_, node) -> graph.binding(node) },
            unresolved = resolution.unresolved,
            tints = brandTints(graph, roleIds),
        )
    }

    /**
     * Colors whose own literal value is WeChat's brand green with alpha. Only plain day/night
     * configs are considered; a color without a night config that is green by default is green at
     * night too. Colors reached through references are covered by their target.
     */
    fun brandTints(graph: MonetResourceGraph, excludedIds: Set<Int>): List<MonetTint> =
        graph.nodes("color").filter { it.id !in excludedIds }.mapNotNull { node ->
            val byQualifier = node.values.associate { it.qualifiers to it.value }
            if (!byQualifier.keys.all { it == "" || it == "-night" }) return@mapNotNull null
            val light = byQualifier[""]?.brandAlpha()
            val night = if ("-night" in byQualifier) byQualifier.getValue("-night").brandAlpha() else light
            if (light == null) null else MonetTint(graph.binding(node), light, night)
        }

    private fun MonetResourceValue.brandAlpha(): Int? {
        if (this !is MonetResourceValue.Literal || valueType != "COLOR_ARGB8") return null
        val argb = data.toInt()
        val alpha = argb ushr 24
        return alpha.takeIf { argb and 0xffffff == BRAND_GREEN && it != 0xff }
    }

    private fun MonetResourceGraph.binding(node: MonetResourceNode) = MonetBinding(
        id = node.id,
        type = node.key.type,
        name = node.key.name,
        qualifiers = node.values.map(MonetConfiguredValue::qualifiers),
        specFlags = specFlags(node.id),
    )

    fun load(dir: File, fingerprint: String): MonetBindings? {
        val file = bindingsFile(dir, fingerprint)
        if (!file.isFile) return null
        return json.decodeFromString<MonetBindings>(file.readText()).takeIf { it.fingerprint == fingerprint }
    }

    fun store(dir: File, bindings: MonetBindings) {
        dir.mkdirs()
        val file = bindingsFile(dir, bindings.fingerprint)
        val temp = File(dir, ".${file.name}.tmp")
        temp.writeText(json.encodeToString(MonetBindings.serializer(), bindings))
        check(temp.renameTo(file)) { "failed to store $file" }
    }

    fun bindingsFile(dir: File, fingerprint: String) = File(dir, "bindings-$fingerprint.json")

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(32)

    private const val PACKAGE_NAME = "com.tencent.mm"
    private const val BRAND_GREEN = 0x07c160
}
