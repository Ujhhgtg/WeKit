package dev.ujhhgtg.wekit.utils.monet

import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.ColorTarget
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.ColorValue
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.DrawableTarget
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.XmlAttribute
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.XmlNode
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter.XmlValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

class MonetRuntimePackageWriterTest {
    private val typeNames = listOf("attr", "drawable", "mipmap", "layout", "anim", "color", "string", "style")

    @Test
    fun `entries keep WeChat IDs names and mirror every declared config`() {
        val color = MonetBinding(0x7f06_0010, "color", "BW_100", listOf("", "-night"), 0)
        val lightOnly = MonetBinding(0x7f06_0003, "color", "Brand", listOf("-v31"), 0x400)
        val bubble = MonetBinding(0x7f02_0a17, "drawable", "chatto_bg", listOf("-xxhdpi"), 0x100)
        val nightOnly = MonetBinding(0x7f06_0020, "color", "list_bg", listOf(""), 0)
        val output = write(
            colors = listOf(
                ColorTarget(color, ColorValue.Reference(0x0106_0070), ColorValue.Literal(0xff112233.toInt())),
                ColorTarget(lightOnly, ColorValue.Literal(0xff445566.toInt()), null),
                ColorTarget(nightOnly, null, ColorValue.Reference(0x0106_0097)),
            ),
            drawables = listOf(DrawableTarget(bubble, shape(0xff0000ff.toInt()), shape(0xff00ff00.toInt()))),
        )

        ZipFile(output).use { zip ->
            val table = zip.getInputStream(zip.getEntry("resources.arsc")).use(TableBlock::load)
            val pkg = table.pickOne()!!
            assertEquals(0x7f, pkg.id)
            assertEquals("com.tencent.mm", pkg.name)
            typeNames.forEachIndexed { index, name -> assertEquals(name, pkg.typeNameOf(index + 1)) }

            assertEquals(setOf("", "-night"), pkg.configs(color.id))
            assertEquals("BW_100", pkg.getResource(color.id).name)
            val colorEntries = pkg.getResource(color.id).asSequence().associateBy { it.resConfig.qualifiers }
            assertEquals(0x0106_0070, colorEntries.getValue("").resValue.data)
            assertEquals(0xff112233.toInt(), colorEntries.getValue("-night").resValue.data)

            // Light-only targets keep exactly WeChat's configs; no night twin is invented.
            assertEquals(setOf("-v31"), pkg.configs(lightOnly.id))
            assertEquals(0x400, pkg.getResource(lightOnly.id).get().specFlag.integer and 0x400)

            // Night-only (`unknown` light side): no default entry, so WeChat keeps its light value.
            assertEquals(setOf("-night"), pkg.configs(nightOnly.id))

            // Density-only drawable: the config itself plus a night twin that outranks it.
            assertEquals(setOf("-xxhdpi", "-night-xxhdpi"), pkg.configs(bubble.id))
            assertEquals("chatto_bg", pkg.getResource(bubble.id).name)
            val flags = pkg.getResource(bubble.id).get().specFlag.integer
            assertEquals(0x1100, flags and 0x1100, "density and uiMode spec flags")
            pkg.getResource(bubble.id).asSequence().forEach { entry ->
                val path = entry.resValue.valueAsString
                assertTrue(path.startsWith("res/mtest/drawable") && path.endsWith("/chatto_bg.xml"), path)
                assertTrue(zip.getEntry(path) != null, "missing $path")
            }

            val written = pkg.getResources().asSequence().filterNot { it.isEmpty }.map { it.resourceId }.toSet()
            assertEquals(setOf(color.id, lightOnly.id, nightOnly.id, bubble.id), written)
            assertEquals(ZipEntry.STORED, zip.getEntry("resources.arsc").method)
        }
    }

    @Test
    fun `every rule renders into one writable table`() {
        val byType = MONET_RULES.groupBy(MonetSemanticRule::type)
        val roles = byType.flatMap { (type, rules) ->
            val typeId = typeNames.indexOf(type) + 1
            rules.mapIndexed { index, rule ->
                rule.id to MonetBinding(0x7f00_0000 or (typeId shl 16) or index, type, "target_$index", listOf("", "-xxhdpi"), 0)
            }
        }.toMap()
        val bindings = MonetBindings("test", typeNames, roles, emptyList())
        val framework = { name: String -> 0x0106_0000 or (name.hashCode() and 0xfff) }
        val palette = MonetThemeRenderer.palette(framework)
        MonetBubbleStyle.entries.forEach { bubble ->
            val out = MonetThemeRenderer.render(bindings, palette, MonetThemeRenderer.Options(bubble, true, false), framework)
            assertTrue(out.drawables.isNotEmpty() && out.colors.isNotEmpty())
            assertTrue(out.colors.none { it.binding == roles.getValue("main.tab.background") }, "tab bar is left to WeChat")
            MonetThemeRenderer.ERROR_ROLES.forEach { role ->
                assertTrue(out.colors.none { it.binding == roles.getValue(role) }, "error red $role stays WeChat's by default")
            }
            val withErrors = MonetThemeRenderer.render(bindings, palette, MonetThemeRenderer.Options(bubble, true, true), framework)
            assertEquals(
                MonetThemeRenderer.ERROR_ROLES.map(roles::getValue).toSet(),
                (withErrors.colors.map { it.binding } - out.colors.map { it.binding }.toSet()).toSet(),
                "the error switch adds exactly the error roles",
            )
            val file = write(out.colors, out.drawables)
            ZipFile(file).use { zip ->
                val pkg = zip.getInputStream(zip.getEntry("resources.arsc")).use(TableBlock::load).pickOne()!!
                out.colors.forEach { color ->
                    // `unknown` light sides must not override WeChat's light value.
                    assertEquals(color.light != null, pkg.configs(color.binding.id).containsAll(listOf("", "-xxhdpi")), color.binding.name)
                }
                out.drawables.forEach { drawable ->
                    assertTrue(pkg.configs(drawable.binding.id).containsAll(listOf("", "-xxhdpi")), drawable.binding.name)
                }
            }
        }
    }

    @Test
    fun `unresolved roles are skipped instead of failing the render`() {
        val bindings = MonetBindings("test", typeNames, emptyMap(), MONET_RULES.map { it.id })
        val framework = { name: String -> 0x0106_0000 or (name.hashCode() and 0xfff) }
        val options = MonetThemeRenderer.Options(MonetBubbleStyle.PRO, true, true)
        val out = MonetThemeRenderer.render(bindings, MonetThemeRenderer.palette(framework), options, framework)
        assertTrue(out.colors.isEmpty() && out.drawables.isEmpty())
    }

    @Test
    fun `translucent brand green becomes primary tints that keep non-green configs`() {
        fun color(id: Int, name: String, vararg values: Pair<String, Long>) = MonetResourceNode(
            0x7f06_0000 or id,
            MonetResourceKey("color", name),
            values.map { (qualifiers, argb) -> MonetConfiguredValue(qualifiers, MonetResourceValue.Literal("COLOR_ARGB8", argb)) },
        )
        val graph = MonetResourceGraph(
            listOf(
                color(1, "highlight", "" to 0x4d07c160L),
                color(2, "dayOnly", "" to 0x4d07c160L, "-night" to 0x4dffffffL),
                color(3, "opaque", "" to 0xff07c160L),
                color(4, "other", "" to 0x4d123456L),
                color(5, "role", "" to 0x1a07c160L),
            ),
            typeNames = typeNames,
        )
        val tints = MonetResourceResolver.brandTints(graph, excludedIds = setOf(0x7f06_0005))
        assertEquals(listOf("highlight" to 0x4d, "dayOnly" to 0x4d), tints.map { it.binding.name to it.lightAlpha })
        assertEquals(listOf(0x4d, null), tints.map { it.nightAlpha })

        val bindings = MonetBindings("test", typeNames, emptyMap(), emptyList(), tints)
        val framework = { name: String -> 0x0106_0000 or (name.hashCode() and 0xfff) }
        val out = MonetThemeRenderer.render(
            bindings, MonetThemeRenderer.palette(framework), MonetThemeRenderer.Options(MonetBubbleStyle.MODERN, false, false), framework,
        )
        ZipFile(write(out.colors, out.drawables)).use { zip ->
            val pkg = zip.getInputStream(zip.getEntry("resources.arsc")).use(TableBlock::load).pickOne()!!
            // Default-only green gets a night twin; the white night config stays WeChat's.
            assertEquals(setOf("", "-night"), pkg.configs(0x7f06_0001))
            assertEquals(setOf(""), pkg.configs(0x7f06_0002))
            pkg.getResource(0x7f06_0001).asSequence().forEach { entry ->
                val path = entry.resValue.valueAsString
                assertTrue(path.contains("/color") && zip.getEntry(path) != null, path)
            }
        }
    }

    private fun PackageBlock.configs(id: Int): Set<String> =
        getResource(id).asSequence().map { it.resConfig.qualifiers }.toSet()

    private fun shape(color: Int) = XmlNode(
        "shape",
        children = listOf(XmlNode("solid", listOf(XmlAttribute("color", 0x010101a5, XmlValue.Color(color))))),
    )

    private fun write(colors: List<ColorTarget>, drawables: List<DrawableTarget>): File {
        val output = File(createTempDirectory("monet-runtime").toFile(), "runtime.apk")
        MonetRuntimePackageWriter.write(output, typeNames, colors, drawables, "mtest")
        return output
    }
}
