package dev.ujhhgtg.wekit.extensions.monettest

import dev.ujhhgtg.wekit.extensions.monet.MonetResourceKey
import dev.ujhhgtg.wekit.extensions.monet.MonetResourceValue
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MonetDecodedResourceGraphLoaderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `decoded graph preserves public ids colors qualifiers and xml edges`() {
        write(
            "values/public.xml",
            """
            <resources>
                <public type="color" name="accent" id="0x7f060001" />
                <public type="drawable" name="bubble" id="0x7f080001" />
                <public type="layout" name="message" id="0x7f0d0001" />
            </resources>
            """.trimIndent(),
        )
        write("values/colors.xml", "<resources><color name=\"accent\">#112233</color></resources>")
        write("values-night/colors.xml", "<resources><color name=\"accent\">#ff445566</color></resources>")
        write(
            "drawable/bubble.xml",
            """
            <shape xmlns:android="http://schemas.android.com/apk/res/android">
                <solid android:color="@color/accent" />
            </shape>
            """.trimIndent(),
        )
        write(
            "layout/message.xml",
            """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:background="@drawable/bubble" />
            """.trimIndent(),
        )

        val decoded = MonetDecodedResourceGraphLoader.load(tempDir)
        val color = requireNotNull(decoded.graph.node(MonetResourceKey("color", "accent")))
        val bubble = requireNotNull(decoded.graph.node(MonetResourceKey("drawable", "bubble")))
        val message = requireNotNull(decoded.graph.node(MonetResourceKey("layout", "message")))

        assertEquals(0x7f060001, color.id)
        assertEquals(
            listOf("", "night"),
            color.values.map { it.qualifiers },
        )
        assertEquals(
            MonetResourceValue.Literal("COLOR_RGB8", 0xff112233),
            color.values.single { it.qualifiers.isEmpty() }.value,
        )
        assertEquals(setOf(color.id), decoded.graph.outgoing(bubble.id))
        assertEquals(setOf(bubble.id), decoded.graph.outgoing(message.id))
        assertTrue(bubble.id in decoded.graph.incoming(color.id))
        assertTrue(message.id in decoded.graph.incoming(bubble.id))
        assertFalse(decoded.binaryXmlShapesComparable)
        assertTrue(decoded.limitations.any { "attribute resource IDs" in it })
    }

    @Test
    fun `duplicate public resource ids fail closed`() {
        write(
            "values/public.xml",
            """
            <resources>
                <public type="color" name="first" id="0x7f060001" />
                <public type="color" name="second" id="0x7f060001" />
            </resources>
            """.trimIndent(),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            MonetDecodedResourceGraphLoader.load(tempDir)
        }

        assertTrue(error.message.orEmpty().contains("duplicate public resource ID"))
    }

    @Test
    fun `decoded null sentinel keeps compiled zero reference semantics`() {
        write(
            "values/public.xml",
            "<resources><public type=\"drawable\" name=\"empty\" id=\"0x7f080001\" /></resources>",
        )
        write(
            "drawable/empty.xml",
            """
            <shape xmlns:android="http://schemas.android.com/apk/res/android"
                android:foreground="@null" />
            """.trimIndent(),
        )

        val decoded = MonetDecodedResourceGraphLoader.load(tempDir)
        val empty = requireNotNull(decoded.graph.node(MonetResourceKey("drawable", "empty")))

        assertEquals(setOf(0), decoded.graph.outgoing(empty.id))
    }

    @Test
    fun `decoded decompiler xml tolerates an unbound local namespace prefix`() {
        write(
            "values/public.xml",
            "<resources><public type=\"layout\" name=\"card\" id=\"0x7f0d0001\" /></resources>",
        )
        write(
            "layout/card.xml",
            """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <androidx.cardview.widget.CardView app:cardCornerRadius="4dp" />
            </FrameLayout>
            """.trimIndent(),
        )

        val decoded = MonetDecodedResourceGraphLoader.load(tempDir)

        assertTrue(decoded.graph.xmlOwners().isNotEmpty())
        assertTrue(decoded.limitations.any { "unbound namespace prefixes" in it })
    }

    @Test
    fun `decoded numeric reference resolves through authoritative public ids`() {
        write(
            "values/public.xml",
            """
            <resources>
                <public type="color" name="accent" id="0x7f060001" />
                <public type="drawable" name="known" id="0x7f080001" />
            </resources>
            """.trimIndent(),
        )
        write("values/colors.xml", "<resources><color name=\"accent\">#112233</color></resources>")
        write(
            "drawable/known.xml",
            """
            <shape xmlns:android="http://schemas.android.com/apk/res/android">
                <solid android:color="@0x7f060001" />
            </shape>
            """.trimIndent(),
        )

        val decoded = MonetDecodedResourceGraphLoader.load(tempDir)
        val accent = requireNotNull(decoded.graph.node(MonetResourceKey("color", "accent")))
        val known = requireNotNull(decoded.graph.node(MonetResourceKey("drawable", "known")))

        assertEquals(setOf(accent.id), decoded.graph.outgoing(known.id))
    }

    @Test
    fun `decoded dangling numeric reference fails closed`() {
        write(
            "values/public.xml",
            "<resources><public type=\"drawable\" name=\"dangling\" id=\"0x7f080001\" /></resources>",
        )
        write(
            "drawable/dangling.xml",
            """
            <shape xmlns:android="http://schemas.android.com/apk/res/android">
                <solid android:color="@0x7f060099" />
            </shape>
            """.trimIndent(),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            MonetDecodedResourceGraphLoader.load(tempDir)
        }

        assertTrue(error.message.orEmpty().contains("absent from values/public.xml"))
    }

    private fun write(relativePath: String, text: String) {
        val output = tempDir.resolve(relativePath)
        val parent = requireNotNull(output.parentFile)
        require(parent.mkdirs() || parent.isDirectory)
        output.writeText(text)
    }
}
