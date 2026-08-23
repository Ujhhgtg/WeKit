package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class HomeSidePanelSaveTransactionTest {

    @Test
    fun storageFailureRetainsDraftAndSkipsPromotion() {
        val editor = editorWithWalletDraft()
        val storageFailure = IllegalStateException("storage failed")
        var promotionCalls = 0

        val result = commitHomeSidePanelEdit(
            editor = editor,
            persist = { Result.failure(storageFailure) },
            promote = { promotionCalls++ },
        )

        val retained = assertInstanceOf(HomeSidePanelEditCommit.Retained::class.java, result)
        assertSame(storageFailure, retained.failure)
        assertEquals(0, promotionCalls)
        assertEquals(true, (editor.draft.cards.single() as WalletCardConfig).hideBalanceByDefault)
    }

    @Test
    fun promotionFailureAfterPersistenceIsStillACommittedFormalSave() {
        val editor = editorWithWalletDraft()
        val promotionFailure = IllegalStateException("promotion failed")
        var persisted: HomeSidePanelLayout? = null

        val result = commitHomeSidePanelEdit(
            editor = editor,
            persist = { layout ->
                persisted = layout
                Result.success(Unit)
            },
            promote = { throw promotionFailure },
        )

        val committed = assertInstanceOf(HomeSidePanelEditCommit.Committed::class.java, result)
        assertEquals(editor.draft, persisted)
        assertEquals(editor.draft, committed.layout)
        assertSame(promotionFailure, committed.promotionFailure)
    }

    @Test
    fun validationFailureSkipsStorageAndPromotion() {
        val editor = HomeSidePanelEditSession(
            HomeSidePanelLayout(cards = listOf(HitokotoCardConfig("hitokoto"))),
            HomeSidePanelIdGenerator { "unused" },
        )
        editor.updateHitokoto("hitokoto") {
            it.copy(settings = it.settings.copy(categories = emptySet()))
        }
        var storageCalls = 0
        var promotionCalls = 0

        val result = commitHomeSidePanelEdit(
            editor = editor,
            persist = {
                storageCalls++
                Result.success(Unit)
            },
            promote = { promotionCalls++ },
        )

        assertInstanceOf(HomeSidePanelEditCommit.Retained::class.java, result)
        assertEquals(0, storageCalls)
        assertEquals(0, promotionCalls)
    }

    private fun editorWithWalletDraft(): HomeSidePanelEditSession = HomeSidePanelEditSession(
        HomeSidePanelLayout(cards = listOf(WalletCardConfig("wallet"))),
        HomeSidePanelIdGenerator { "unused" },
    ).also { editor ->
        editor.updateWallet("wallet") { it.copy(hideBalanceByDefault = true) }
    }
}
