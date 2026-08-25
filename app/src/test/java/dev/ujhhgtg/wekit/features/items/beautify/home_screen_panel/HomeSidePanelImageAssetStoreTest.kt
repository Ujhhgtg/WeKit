package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HomeSidePanelImageAssetStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun failedCommitRollsBackPromotedFilesAndKeepsDraft() {
        val store = HomeSidePanelImageAssetStore(tempDir)
        writeFixture(draftAsset(SESSION_ID, NEW_ID), byteArrayOf(1, 2, 3))
        val prepared = store.prepareCommit(
            sessionId = SESSION_ID,
            oldAssetIds = setOf(OLD_ID),
            newAssetIds = setOf(NEW_ID),
        ).getOrThrow()

        assertTrue(formalAsset(NEW_ID).exists())
        store.rollbackCommit(prepared)

        assertFalse(formalAsset(NEW_ID).exists())
        assertTrue(draftAsset(SESSION_ID, NEW_ID).exists())
    }

    @Test
    fun successfulCommitDeletesOnlyUnreferencedFormalAssets() {
        val store = HomeSidePanelImageAssetStore(tempDir)
        writeFixture(formalAsset(REMOVED_ID), byteArrayOf(1))
        writeFixture(formalAsset(RETAINED_ID), byteArrayOf(2))
        writeFixture(draftAsset(SESSION_ID, ADDED_ID), byteArrayOf(3))
        val prepared = store.prepareCommit(
            sessionId = SESSION_ID,
            oldAssetIds = setOf(REMOVED_ID, RETAINED_ID),
            newAssetIds = setOf(RETAINED_ID, ADDED_ID),
        ).getOrThrow()

        store.finalizeCommit(prepared)

        assertFalse(formalAsset(REMOVED_ID).exists())
        assertTrue(formalAsset(RETAINED_ID).exists())
        assertTrue(formalAsset(ADDED_ID).exists())
        assertFalse(draftSession(SESSION_ID).exists())
    }

    @Test
    fun retainedReferencePreventsSharedAssetDeletion() {
        val store = HomeSidePanelImageAssetStore(tempDir)
        writeFixture(formalAsset(SHARED_ID), byteArrayOf(1))
        val prepared = store.prepareCommit(
            sessionId = SESSION_ID,
            oldAssetIds = setOf(SHARED_ID),
            newAssetIds = setOf(SHARED_ID),
        ).getOrThrow()

        store.finalizeCommit(prepared)

        assertTrue(formalAsset(SHARED_ID).exists())
    }

    @Test
    fun discardAndStartupCleanupNeverDeleteFormalAssets() {
        val store = HomeSidePanelImageAssetStore(tempDir)
        writeFixture(formalAsset(RETAINED_ID), byteArrayOf(1))
        writeFixture(draftAsset(SESSION_ID, NEW_ID), byteArrayOf(2))
        writeFixture(draftAsset(STALE_SESSION_ID, ADDED_ID), byteArrayOf(3))
        val stalePartial = tempDir / ".$NEW_ID.part"
        writeFixture(stalePartial, byteArrayOf(4))

        store.discardSession(SESSION_ID)
        store.cleanupStaleDrafts()

        assertTrue(formalAsset(RETAINED_ID).exists())
        assertFalse(draftSession(SESSION_ID).exists())
        assertFalse(draftSession(STALE_SESSION_ID).exists())
        assertFalse(stalePartial.exists())
    }

    private fun formalAsset(assetId: String): Path = tempDir / assetId

    private fun draftSession(sessionId: String): Path = tempDir / ".draft" / sessionId

    private fun draftAsset(sessionId: String, assetId: String): Path = draftSession(sessionId) / assetId

    private fun writeFixture(path: Path, bytes: ByteArray) {
        path.parent.createDirectories()
        path.writeBytes(bytes)
    }

    private companion object {
        const val SESSION_ID = "00000000-0000-0000-0000-000000000001"
        const val STALE_SESSION_ID = "00000000-0000-0000-0000-000000000002"
        const val OLD_ID = "10000000-0000-0000-0000-000000000001"
        const val NEW_ID = "10000000-0000-0000-0000-000000000002"
        const val REMOVED_ID = "10000000-0000-0000-0000-000000000003"
        const val RETAINED_ID = "10000000-0000-0000-0000-000000000004"
        const val ADDED_ID = "10000000-0000-0000-0000-000000000005"
        const val SHARED_ID = "10000000-0000-0000-0000-000000000006"
    }
}
