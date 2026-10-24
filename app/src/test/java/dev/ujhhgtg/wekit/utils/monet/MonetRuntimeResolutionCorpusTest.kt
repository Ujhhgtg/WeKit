package dev.ujhhgtg.wekit.utils.monet

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import dev.ujhhgtg.wekit.dexkit.DexSqlBridge
import java.io.File

/**
 * Runs the runtime resolution path ([MonetStructureMatcher.resolveAvailable], as used by
 * [MonetResourceResolver]) on local WeChat APKs and cross-checks every role against a reference
 * build confirmed on a device: a role's WeChat-side value (followed through references) should not
 * change between builds, so a changed value marks a probable wrong target.
 *
 * `./gradlew :app:testDebugUnitTest -Pwekit.monetCorpus=true --tests '*MonetRuntimeResolutionCorpusTest'`
 * Override the samples with `-Dwekit.monetRuntime.samples=8078,8078_3180` (files under ~/coding).
 */
@EnabledIfSystemProperty(named = "wekit.monetCorpus", matches = "true")
class MonetRuntimeResolutionCorpusTest {
    @Test
    fun `runtime resolution is complete and consistent with the device-verified build`() {
        val samples = (System.getProperty("wekit.monetRuntime.samples") ?: "8078,8078_3180").split(',')
        val reference = resolve(REFERENCE)
        if (System.getProperty("wekit.monetRuntime.dump") == "true") {
            reference.roles.toSortedMap().forEach { (role, it) -> println("MONET_RUNTIME_ROLE $role ${it.name} ${it.value}") }
        }
        val failures = mutableListOf<String>()
        samples.forEach { sample ->
            val current = resolve(sample)
            println("MONET_RUNTIME $sample resolved=${current.roles.size} unresolved=${current.unresolved}")
            println("MONET_RUNTIME_TINTS $sample ${current.tints}")
            current.unresolved.forEach { failures += "$sample: unresolved $it" }
            current.roles.forEach { (role, signature) ->
                val expected = reference.roles[role] ?: return@forEach
                if (signature.value != expected.value) {
                    println("MONET_RUNTIME_DIFF $sample $role\n  $REFERENCE: ${expected.name} ${expected.value}\n  $sample: ${signature.name} ${signature.value}")
                    failures += "$sample: $role changed"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** [name] is obfuscated and changes between builds; only [value] is compared. */
    private data class Signature(val name: String, val value: String)

    private class Result(val roles: Map<String, Signature>, val unresolved: List<String>, val tints: List<String>)

    private fun resolve(sample: String): Result {
        val apk = File("/home/ujhhgtg/coding/wechat_$sample.apk")
        val graph = MonetApkResourceGraphLoader.load(listOf(apk), "com.tencent.mm")
        // Path-based like `./x dex-test`; the in-memory byte-array bridge crashes natively here.
        val resolution = DexSqlBridge.open(listOf(apk.path)).use { bridge ->
            MonetStructureMatcher.resolveAvailable(graph, { MonetDexEvidenceCollector.collect(bridge, it) })
        }
        val tints = MonetResourceResolver.brandTints(graph, resolution.resolved.values.mapTo(hashSetOf()) { it.id })
        return Result(
            resolution.resolved.mapValues { (_, node) -> Signature("${node.key.type}/${node.key.name}", graph.signature(node)) },
            resolution.unresolved,
            tints.map { "${it.binding.name}:%02x/%s".format(it.lightAlpha, it.nightAlpha?.let { a -> "%02x".format(a) } ?: "-") },
        )
    }

    /** Config → value, with references followed to their final value and XML reduced to its shape. */
    private fun MonetResourceGraph.signature(node: MonetResourceNode): String = node.values
        // Localized strings live in per-build language splits; compare the default only.
        .filter { node.key.type != "string" || it.qualifiers.isEmpty() }
        .joinToString(", ") { "${it.qualifiers.ifEmpty { "default" }}=${value(it.value, 0)}" }

    private fun MonetResourceGraph.value(value: MonetResourceValue, depth: Int): String = when (value) {
        is MonetResourceValue.Literal -> "%08x".format(value.data)
        is MonetResourceValue.Reference -> {
            val target = node(value.resourceId)
            when {
                target == null -> "@%08x".format(value.resourceId)
                depth > 4 -> "@${target.key.type}"
                else -> target.values.firstOrNull { it.qualifiers.isEmpty() }?.let { value(it.value, depth + 1) } ?: "@${target.key.type}"
            }
        }
        is MonetResourceValue.File -> value.structure?.format ?: "file"
        is MonetResourceValue.Text -> "\"${value.value.take(20)}\""
        is MonetResourceValue.Complex -> "complex(${value.items.size})"
    }

    private companion object {
        const val REFERENCE = "8076"
    }
}
