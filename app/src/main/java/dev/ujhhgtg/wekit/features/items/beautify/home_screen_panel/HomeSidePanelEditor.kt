package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

internal sealed interface HomeSidePanelRoute {
    data object Home : HomeSidePanelRoute
    data object PanelSettings : HomeSidePanelRoute
    data object EditHome : HomeSidePanelRoute
    sealed interface EditorDetail : HomeSidePanelRoute
    data class DateTimeSettings(val cardId: String) : EditorDetail
    data class WeatherSettings(val cardId: String) : EditorDetail
    data class WalletSettings(val cardId: String) : EditorDetail
    data class HitokotoSettings(val cardId: String) : EditorDetail
    data object AddCard : EditorDetail
    data class AddAction(val cardId: String) : EditorDetail
}

internal sealed interface HomeSidePanelEditCommit {
    data class Retained(val failure: Throwable) : HomeSidePanelEditCommit
    data class Committed(
        val layout: HomeSidePanelLayout,
        val promotionFailure: Throwable?,
    ) : HomeSidePanelEditCommit
}

internal inline fun commitHomeSidePanelEdit(
    editor: HomeSidePanelEditSession,
    persist: (HomeSidePanelLayout) -> Result<Unit>,
    promote: (HomeSidePanelLayout) -> Unit,
): HomeSidePanelEditCommit {
    val layout = runCatching(editor::committedLayout).getOrElse {
        return HomeSidePanelEditCommit.Retained(it)
    }
    runCatching { persist(layout).getOrThrow() }.exceptionOrNull()?.let {
        return HomeSidePanelEditCommit.Retained(it)
    }
    return HomeSidePanelEditCommit.Committed(
        layout = layout,
        promotionFailure = runCatching { promote(layout) }.exceptionOrNull(),
    )
}

internal class HomeSidePanelEditSession(
    private val original: HomeSidePanelLayout,
    private val idGenerator: HomeSidePanelIdGenerator,
) {
    var draft: HomeSidePanelLayout = original
        private set

    fun addCard(type: HomeSidePanelCardType, index: Int = draft.cards.size): String {
        requireInsertIndex(index, draft.cards.size, "card")
        val card = newCard(type)
        draft = draft.copy(cards = draft.cards.toMutableList().also { it.add(index, card) })
        return card.id
    }

    fun removeCard(cardId: String) {
        val index = cardIndex(cardId)
        draft = draft.copy(cards = draft.cards.toMutableList().also { it.removeAt(index) })
    }

    fun moveCard(fromIndex: Int, toIndex: Int) {
        val cards = draft.cards.toMutableList()
        requireElementIndex(fromIndex, cards.size, "card")
        requireElementIndex(toIndex, cards.size, "card")
        cards.add(toIndex, cards.removeAt(fromIndex))
        draft = draft.copy(cards = cards)
    }

    fun updateWeather(cardId: String, transform: (WeatherCardConfig) -> WeatherCardConfig) {
        val card = card(cardId)
        require(card is WeatherCardConfig) {
            "Card '$cardId' is ${card.type}; expected Weather card"
        }
        replaceCard(transform(card).copy(id = card.id))
    }

    fun updateDateTime(cardId: String, transform: (DateTimeCardConfig) -> DateTimeCardConfig) {
        val card = card(cardId)
        require(card is DateTimeCardConfig) {
            "Card '$cardId' is ${card.type}; expected Date & time card"
        }
        replaceCard(transform(card).copy(id = card.id))
    }

    fun updateWallet(cardId: String, transform: (WalletCardConfig) -> WalletCardConfig) {
        val card = card(cardId)
        require(card is WalletCardConfig) {
            "Card '$cardId' is ${card.type}; expected Wallet card"
        }
        replaceCard(transform(card).copy(id = card.id))
    }

    fun updateHitokoto(cardId: String, transform: (HitokotoCardConfig) -> HitokotoCardConfig) {
        val card = card(cardId)
        require(card is HitokotoCardConfig) {
            "Card '$cardId' is ${card.type}; expected Hitokoto card"
        }
        replaceCard(transform(card).copy(id = card.id))
    }

    fun updateImage(cardId: String, transform: (ImageCardConfig) -> ImageCardConfig) {
        val card = card(cardId)
        require(card is ImageCardConfig) {
            "Card '$cardId' is ${card.type}; expected Image card"
        }
        replaceCard(transform(card).copy(id = card.id))
    }

    fun addAction(
        cardId: String,
        kind: HomeSidePanelActionKind,
        index: Int = actionCount(cardId),
    ): String {
        val card = actionCard(cardId)
        requireInsertIndex(index, card.actions.size, "action")
        val action = HomeSidePanelActionConfig(idGenerator.nextId(), kind)
        replaceCard(card.withActions(card.actions.toMutableList().also { it.add(index, action) }))
        return action.id
    }

    fun removeAction(cardId: String, actionId: String) {
        val card = actionCard(cardId)
        val index = card.actions.indexOfFirst { it.id == actionId }
        require(index >= 0) { "Action '$actionId' does not exist in card '$cardId'" }
        replaceCard(card.withActions(card.actions.toMutableList().also { it.removeAt(index) }))
    }

    fun moveAction(cardId: String, fromIndex: Int, toIndex: Int) {
        val card = actionCard(cardId)
        val actions = card.actions.toMutableList()
        requireElementIndex(fromIndex, actions.size, "action")
        requireElementIndex(toIndex, actions.size, "action")
        actions.add(toIndex, actions.removeAt(fromIndex))
        replaceCard(card.withActions(actions))
    }

    fun committedLayout(): HomeSidePanelLayout = draft.also(::validateHomeSidePanelLayout)

    fun discardedLayout(): HomeSidePanelLayout = original

    private fun actionCount(cardId: String): Int = actionCard(cardId).actions.size

    private fun card(cardId: String): HomeSidePanelCardConfig = draft.cards.firstOrNull {
        it.id == cardId
    } ?: throw IllegalArgumentException("Card '$cardId' does not exist")

    private fun cardIndex(cardId: String): Int = draft.cards.indexOfFirst { it.id == cardId }.also {
        require(it >= 0) { "Card '$cardId' does not exist" }
    }

    private fun actionCard(cardId: String): ActionCardConfig {
        val card = card(cardId)
        require(card is HorizontalActionsCardConfig || card is VerticalActionsCardConfig) {
            "Card '$cardId' is ${card.type}; expected an action card"
        }
        return ActionCardConfig(card)
    }

    private fun replaceCard(updated: HomeSidePanelCardConfig) {
        val index = cardIndex(updated.id)
        draft = draft.copy(cards = draft.cards.toMutableList().also { it[index] = updated })
    }

    private fun newCard(type: HomeSidePanelCardType): HomeSidePanelCardConfig = when (type) {
        HomeSidePanelCardType.DATE_TIME -> DateTimeCardConfig(idGenerator.nextId())
        HomeSidePanelCardType.WEATHER -> WeatherCardConfig(idGenerator.nextId(), DEFAULT_WEATHER_CITY)
        HomeSidePanelCardType.WALLET -> WalletCardConfig(idGenerator.nextId())
        HomeSidePanelCardType.HITOKOTO -> HitokotoCardConfig(idGenerator.nextId())
        HomeSidePanelCardType.IMAGE -> ImageCardConfig(idGenerator.nextId())
        HomeSidePanelCardType.HORIZONTAL_ACTIONS -> HorizontalActionsCardConfig(idGenerator.nextId(), emptyList())
        HomeSidePanelCardType.VERTICAL_ACTIONS -> VerticalActionsCardConfig(idGenerator.nextId(), emptyList())
    }

    private class ActionCardConfig(card: HomeSidePanelCardConfig) {
        val actions: List<HomeSidePanelActionConfig> = when (card) {
            is HorizontalActionsCardConfig -> card.actions
            is VerticalActionsCardConfig -> card.actions
            else -> error("ActionCardConfig requires an action card")
        }

        private val id = card.id
        private val type = card.type

        fun withActions(actions: List<HomeSidePanelActionConfig>): HomeSidePanelCardConfig = when (type) {
            HomeSidePanelCardType.HORIZONTAL_ACTIONS -> HorizontalActionsCardConfig(id, actions)
            HomeSidePanelCardType.VERTICAL_ACTIONS -> VerticalActionsCardConfig(id, actions)
            else -> error("ActionCardConfig has non-action type $type")
        }
    }

    private fun requireElementIndex(index: Int, size: Int, subject: String) {
        if (index !in 0 until size) {
            throw IndexOutOfBoundsException("$subject index $index is outside 0..${size - 1}")
        }
    }

    private fun requireInsertIndex(index: Int, size: Int, subject: String) {
        if (index !in 0..size) {
            throw IndexOutOfBoundsException("$subject insertion index $index is outside 0..$size")
        }
    }
}

internal fun isWholeCardDeleteVisible(card: HomeSidePanelCardConfig): Boolean = when (card) {
    is HorizontalActionsCardConfig -> card.actions.isEmpty()
    is VerticalActionsCardConfig -> card.actions.isEmpty()
    else -> false
}
