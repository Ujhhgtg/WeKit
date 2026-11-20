package dev.ujhhgtg.wekit.data.structured

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LegacyConversationCollectionsTest {
    @Test
    fun `absent groups seed presets but an explicitly empty document only retains all`() {
        val absent = LegacyConversationCollections.groups(null, seed = 100).items
        val empty = LegacyConversationCollections.groups("[]", seed = 100).items
        assertEquals(5, absent.size)
        assertEquals(5, absent.map { it.id }.distinct().size)
        assertEquals(listOf(ConversationGroup(id = "wekit_group_all")), empty)
        assertEquals(absent.first(), empty.single())
    }

    @Test
    fun `existing all position and duplicate member order survive cleanup`() {
        val result = LegacyConversationCollections.groups("""[
          {"id":"wekit_group_custom","name":"work","members":["b","a","b"," ",""]},
          {"id":"wekit_group_all","name":"everything"}
        ]""")
        assertEquals(listOf("wekit_group_custom", "wekit_group_all"), result.items.map { it.id })
        assertEquals(listOf("b", "a", "b"), result.items.first().members)
        assertEquals(2, result.filteredMembers)
        assertEquals(0, result.filteredParents)
    }

    @Test
    fun `legacy preset label conversion leaves custom names and SQL untouched`() {
        val result = LegacyConversationCollections.groups("""[
          {"id":"wekit_group_1","name":"未读","type":"PRESET_UNREAD"},
          {"id":"wekit_group_2","name":"my unread","type":"PRESET_UNREAD"},
          {"id":"wekit_group_3","name":"query","type":"SQL","selectFields":" r.username ","whereClause":" x = 1 "}
        ]""").items
        assertEquals("", result[1].name)
        assertEquals(BuiltInGroupLabel.UNREAD, result[1].builtInLabel)
        assertEquals("my unread", result[2].name)
        assertEquals(null, result[2].builtInLabel)
        assertEquals(" r.username ", result[3].selectFields)
        assertEquals(" x = 1 ", result[3].whereClause)
    }

    @Test
    fun `invalid parents are counted while missing all is inserted without reseeding presets`() {
        val result = LegacyConversationCollections.groups("""[
          {"id":"","name":"ignored"},
          {"id":"other_id","name":"ignored"},
          {"id":"wekit_group_empty"},
          {"id":"wekit_group_custom","name":"custom"}
        ]""")
        assertEquals(3, result.filteredParents)
        assertEquals(listOf("wekit_group_all", "wekit_group_custom"), result.items.map { it.id })
    }

    @Test
    fun `duplicate parent IDs fail rather than losing one of their member lists`() {
        assertThrows(IllegalArgumentException::class.java) {
            LegacyConversationCollections.groups("""[
              {"id":"wekit_group_1","name":"one","members":["a"]},
              {"id":"wekit_group_1","name":"two","members":["b"]}
            ]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LegacyConversationCollections.folders("""[
              {"id":"wekit_folder_1","name":"one"},
              {"id":"wekit_folder_1","name":"two"}
            ]""")
        }
    }

    @Test
    fun `folder migration retains signed 64 bit pin flags and repeated ordered members`() {
        val result = LegacyConversationCollections.folders("""[
          {"id":"wekit_folder_1","name":"first","pinFlag":-9223372036854775808,"members":["z","x","z",""]},
          {"id":"wekit_folder_2","name":"second","pinFlag":72057594037927936},
          {"id":"bad","name":"ignored"}
        ]""")
        assertEquals(Long.MIN_VALUE, result.items[0].pinFlag)
        assertEquals(1L shl 56, result.items[1].pinFlag)
        assertNotEquals(result.items[0].pinFlag, result.items[1].pinFlag)
        assertEquals(listOf("z", "x", "z"), result.items[0].members)
        assertEquals(1, result.filteredMembers)
        assertEquals(1, result.filteredParents)
    }

    @Test
    fun `unknown enum values and corrupt JSON fail instead of being interpreted as defaults`() {
        assertThrows(Exception::class.java) {
            LegacyConversationCollections.groups("""[{"id":"wekit_group_1","name":"one","type":"FUTURE"}]""")
        }
        assertThrows(Exception::class.java) {
            LegacyConversationCollections.groups("""[{"id":"wekit_group_1","builtInLabel":"FUTURE"}]""")
        }
        assertThrows(Exception::class.java) { LegacyConversationCollections.folders("{broken") }
    }
}
