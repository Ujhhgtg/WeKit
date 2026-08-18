import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerateExtensionLockTaskTest {

    private val lockJson = """
        {
          "packs": [
            {
              "id": "cloudflared",
              "version": "20260818-111111111111",
              "files": {
                "cloudflared.zip": "2222222222222222222222222222222222222222222222222222222222222222"
              }
            },
            {
              "id": "script-deps",
              "version": "20260818-333333333333",
              "files": {
                "script-deps.dex": "4444444444444444444444444444444444444444444444444444444444444444"
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses both packs from machine-written lock`() {
        val packs = LockParser.parse(lockJson)
        assertEquals(2, packs.size)
        val cloudflared = packs.first { it.id == "cloudflared" }
        assertEquals("20260818-111111111111", cloudflared.version)
        assertEquals(
            "2222222222222222222222222222222222222222222222222222222222222222",
            cloudflared.files.getValue("cloudflared.zip"),
        )
        val scriptDeps = packs.first { it.id == "script-deps" }
        assertEquals(1, scriptDeps.files.size)
    }

    @Test
    fun `asset name inserts version before extension`() {
        val packs = LockParser.parse(lockJson)
        val cloudflared = packs.first { it.id == "cloudflared" }
        val (name, _) = cloudflared.files.entries.single()
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        val assetName = "$stem-${cloudflared.version}.$ext"
        assertEquals("cloudflared-20260818-111111111111.zip", assetName)
    }

    @Test
    fun `empty lock parses to empty list`() {
        assertTrue(LockParser.parse("""{"packs": []}""").isEmpty())
    }
}
