package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class DragItemBounds(
    val start: Float,
    val end: Float,
)

internal fun insertionIndex(
    position: Float,
    bounds: List<DragItemBounds>,
): Int {
    val before = bounds.indexOfFirst { position < (it.start + it.end) / 2f }
    return if (before >= 0) before else bounds.size
}

internal enum class HomeSidePanelPointerLifecycleDecision {
    Continue,
    Finish,
    Cancel,
}

internal fun homeSidePanelPointerLifecycleDecision(
    previousPressed: Boolean,
    pressed: Boolean,
    consumedAtInitialPass: Boolean,
): HomeSidePanelPointerLifecycleDecision = when {
    !previousPressed || pressed -> HomeSidePanelPointerLifecycleDecision.Continue
    consumedAtInitialPass -> HomeSidePanelPointerLifecycleDecision.Cancel
    else -> HomeSidePanelPointerLifecycleDecision.Finish
}

internal fun normalizedMoveDestination(
    sourceIndex: Int,
    insertionIndex: Int,
): Int = if (insertionIndex > sourceIndex) insertionIndex - 1 else insertionIndex

internal data class RootDragPosition(
    val x: Float,
    val y: Float,
)

internal data class RootDragBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(position: RootDragPosition): Boolean =
        position.x in left..right && position.y in top..bottom
}

internal enum class HomeSidePanelDragAxis {
    Horizontal,
    Vertical,
}

internal sealed interface HomeSidePanelDragPayload {
    data class ExistingCard(val cardId: String) : HomeSidePanelDragPayload
    data class NewCard(val type: HomeSidePanelCardType) : HomeSidePanelDragPayload
    data class ExistingAction(
        val cardId: String,
        val actionId: String,
    ) : HomeSidePanelDragPayload

    data class NewAction(
        val cardId: String,
        val kind: HomeSidePanelActionKind,
    ) : HomeSidePanelDragPayload
}

internal sealed interface HomeSidePanelExistingDragSource {
    data object CardBackground : HomeSidePanelExistingDragSource
    data object VirtualAdd : HomeSidePanelExistingDragSource
    data class Action(val actionId: String) : HomeSidePanelExistingDragSource
}

internal fun homeSidePanelExistingDragPayload(
    cardId: String,
    source: HomeSidePanelExistingDragSource,
): HomeSidePanelDragPayload = when (source) {
    HomeSidePanelExistingDragSource.CardBackground,
    HomeSidePanelExistingDragSource.VirtualAdd,
    -> HomeSidePanelDragPayload.ExistingCard(cardId)

    is HomeSidePanelExistingDragSource.Action ->
        HomeSidePanelDragPayload.ExistingAction(cardId, source.actionId)
}

internal sealed interface HomeSidePanelDragTarget {
    data class Card(val insertionIndex: Int) : HomeSidePanelDragTarget
    data class Action(
        val cardId: String,
        val insertionIndex: Int,
    ) : HomeSidePanelDragTarget
}

internal sealed interface HomeSidePanelDragCommit {
    data class MoveCard(
        val cardId: String,
        val insertionIndex: Int,
    ) : HomeSidePanelDragCommit

    data class InsertCard(
        val type: HomeSidePanelCardType,
        val insertionIndex: Int,
    ) : HomeSidePanelDragCommit

    data class MoveAction(
        val cardId: String,
        val actionId: String,
        val insertionIndex: Int,
    ) : HomeSidePanelDragCommit

    data class InsertAction(
        val cardId: String,
        val kind: HomeSidePanelActionKind,
        val insertionIndex: Int,
    ) : HomeSidePanelDragCommit
}

internal fun HomeSidePanelEditSession.applyHomeSidePanelDragCommit(
    commit: HomeSidePanelDragCommit,
): String? = when (commit) {
    is HomeSidePanelDragCommit.MoveCard -> {
        val source = draft.cards.indexOfFirst { it.id == commit.cardId }
        require(source >= 0) { "Card '${commit.cardId}' does not exist in the draft layout" }
        moveCard(source, normalizedMoveDestination(source, commit.insertionIndex))
        null
    }

    is HomeSidePanelDragCommit.InsertCard -> addCard(commit.type, commit.insertionIndex)
    is HomeSidePanelDragCommit.MoveAction -> {
        val card = draft.cards.firstOrNull { it.id == commit.cardId }
            ?: throw IllegalArgumentException("Card '${commit.cardId}' does not exist in the draft layout")
        val actions = when (card) {
            is HorizontalActionsCardConfig -> card.actions
            is VerticalActionsCardConfig -> card.actions
            else -> throw IllegalArgumentException(
                "Card '${commit.cardId}' is ${card.type}; expected an action card",
            )
        }
        val source = actions.indexOfFirst { it.id == commit.actionId }
        require(source >= 0) {
            "Action '${commit.actionId}' does not exist in card '${commit.cardId}'"
        }
        moveAction(
            cardId = commit.cardId,
            fromIndex = source,
            toIndex = normalizedMoveDestination(source, commit.insertionIndex),
        )
        null
    }

    is HomeSidePanelDragCommit.InsertAction ->
        addAction(commit.cardId, commit.kind, commit.insertionIndex)
}

internal data class HomeSidePanelDragSnapshot(
    val payload: HomeSidePanelDragPayload,
    val pointerId: Long,
    val rootPosition: RootDragPosition,
    val anchor: RootDragPosition,
    val sourceBounds: RootDragBounds,
    val target: HomeSidePanelDragTarget?,
    val targetBounds: RootDragBounds?,
    val startToken: Long,
    val targetChangeToken: Long,
)

internal class HomeSidePanelDragState {
    private data class RegisteredBounds(
        val index: Int,
        val bounds: RootDragBounds,
    )

    private data class ActionContainer(
        val axis: HomeSidePanelDragAxis,
        val bounds: RootDragBounds,
    )

    private data class HorizontalRow(
        val items: MutableList<RegisteredBounds>,
        var top: Float,
        var bottom: Float,
    ) {
        val centerY: Float get() = (top + bottom) / 2f

        fun overlaps(bounds: RootDragBounds): Boolean =
            bounds.top < bottom && bounds.bottom > top

        fun add(item: RegisteredBounds) {
            items += item
            top = minOf(top, item.bounds.top)
            bottom = maxOf(bottom, item.bounds.bottom)
        }
    }

    private val cardBounds = mutableMapOf<String, RegisteredBounds>()
    private val actionContainers = mutableMapOf<String, ActionContainer>()
    private val actionBounds = mutableMapOf<String, MutableMap<String, RegisteredBounds>>()
    private val sourceClaims = mutableMapOf<Long, HomeSidePanelDragPayload>()
    private var nextStartToken = 0L

    var snapshot: HomeSidePanelDragSnapshot? by mutableStateOf(null)
        private set

    var viewportBounds: RootDragBounds? by mutableStateOf(null)
        private set

    fun registerViewport(bounds: RootDragBounds) {
        if (viewportBounds == bounds) return
        viewportBounds = bounds
        updateTargetForGeometryChange()
    }

    fun unregisterViewport() {
        viewportBounds = null
        updateTargetForGeometryChange()
    }

    fun registerCardBounds(
        cardId: String,
        index: Int,
        bounds: RootDragBounds,
    ) {
        val registered = RegisteredBounds(index, bounds)
        if (cardBounds[cardId] == registered) return
        cardBounds[cardId] = registered
        updateTargetForGeometryChange()
    }

    fun unregisterCardBounds(cardId: String) {
        if (cardBounds.remove(cardId) != null) updateTargetForGeometryChange()
    }

    fun registerActionContainer(
        cardId: String,
        axis: HomeSidePanelDragAxis,
        bounds: RootDragBounds,
    ) {
        val registered = ActionContainer(axis, bounds)
        if (actionContainers[cardId] == registered) return
        actionContainers[cardId] = registered
        updateTargetForGeometryChange()
    }

    fun unregisterActionContainer(cardId: String) {
        if (actionContainers.remove(cardId) != null) updateTargetForGeometryChange()
    }

    fun registerActionBounds(
        cardId: String,
        actionId: String,
        index: Int,
        bounds: RootDragBounds,
    ) {
        val registered = RegisteredBounds(index, bounds)
        val cardActions = actionBounds.getOrPut(cardId) { mutableMapOf() }
        if (cardActions[actionId] == registered) return
        cardActions[actionId] = registered
        updateTargetForGeometryChange()
    }

    fun unregisterActionBounds(cardId: String, actionId: String) {
        val cardActions = actionBounds[cardId] ?: return
        if (cardActions.remove(actionId) == null) return
        if (cardActions.isEmpty()) actionBounds.remove(cardId)
        updateTargetForGeometryChange()
    }

    fun claimSource(pointerId: Long, payload: HomeSidePanelDragPayload) {
        val claimed = sourceClaims[pointerId]
        if (claimed == null || payloadPriority(payload) > payloadPriority(claimed)) {
            sourceClaims[pointerId] = payload
        }
    }

    fun releaseSourceClaim(pointerId: Long) {
        sourceClaims.remove(pointerId)
    }

    fun begin(
        payload: HomeSidePanelDragPayload,
        pointerId: Long,
        rootPosition: RootDragPosition = RootDragPosition(0f, 0f),
        anchor: RootDragPosition = RootDragPosition(0f, 0f),
        sourceBounds: RootDragBounds = RootDragBounds(0f, 0f, 0f, 0f),
    ): Boolean {
        val claimed = sourceClaims[pointerId]
        if (claimed != null && claimed != payload) return false
        val active = snapshot
        if (active != null) {
            if (active.pointerId != pointerId) return false
            if (active.payload == payload) return false
            if (payloadPriority(payload) <= payloadPriority(active.payload)) return false
        }

        val registeredSource = sourceBoundsFor(payload) ?: sourceBounds
        val adjustedAnchor = if (registeredSource != sourceBounds) {
            RootDragPosition(
                x = rootPosition.x - registeredSource.left,
                y = rootPosition.y - registeredSource.top,
            )
        } else {
            anchor
        }
        val target = targetFor(payload, rootPosition)
        snapshot = HomeSidePanelDragSnapshot(
            payload = payload,
            pointerId = pointerId,
            rootPosition = rootPosition,
            anchor = adjustedAnchor,
            sourceBounds = registeredSource,
            target = target,
            targetBounds = targetBoundsFor(payload, target, registeredSource),
            startToken = active?.startToken ?: ++nextStartToken,
            targetChangeToken = active?.targetChangeToken ?: 0L,
        )
        return active == null
    }

    fun updateRootPosition(x: Float, y: Float) {
        val active = snapshot ?: return
        val position = RootDragPosition(x, y)
        updateSnapshotTarget(active.copy(rootPosition = position), targetFor(active.payload, position))
    }

    fun cancel() {
        sourceClaims.clear()
        snapshot = null
    }

    fun finish(): HomeSidePanelDragCommit? {
        val active = snapshot ?: return null
        sourceClaims.remove(active.pointerId)
        snapshot = null
        return when (val payload = active.payload) {
            is HomeSidePanelDragPayload.ExistingCard -> {
                val target = active.target as? HomeSidePanelDragTarget.Card ?: return null
                HomeSidePanelDragCommit.MoveCard(payload.cardId, target.insertionIndex)
            }

            is HomeSidePanelDragPayload.NewCard -> {
                val target = active.target as? HomeSidePanelDragTarget.Card ?: return null
                HomeSidePanelDragCommit.InsertCard(payload.type, target.insertionIndex)
            }

            is HomeSidePanelDragPayload.ExistingAction -> {
                val target = active.target as? HomeSidePanelDragTarget.Action ?: return null
                if (target.cardId != payload.cardId) return null
                HomeSidePanelDragCommit.MoveAction(
                    cardId = payload.cardId,
                    actionId = payload.actionId,
                    insertionIndex = target.insertionIndex,
                )
            }

            is HomeSidePanelDragPayload.NewAction -> {
                val target = active.target as? HomeSidePanelDragTarget.Action ?: return null
                if (target.cardId != payload.cardId) return null
                HomeSidePanelDragCommit.InsertAction(
                    cardId = payload.cardId,
                    kind = payload.kind,
                    insertionIndex = target.insertionIndex,
                )
            }
        }
    }

    private fun updateTargetForGeometryChange() {
        val active = snapshot ?: return
        updateSnapshotTarget(active, targetFor(active.payload, active.rootPosition))
    }

    private fun updateSnapshotTarget(
        active: HomeSidePanelDragSnapshot,
        target: HomeSidePanelDragTarget?,
    ) {
        snapshot = active.copy(
            target = target,
            targetBounds = targetBoundsFor(active.payload, target, active.sourceBounds),
            targetChangeToken = if (target != null && target != active.target) {
                active.targetChangeToken + 1
            } else {
                active.targetChangeToken
            },
        )
    }

    private fun targetFor(
        payload: HomeSidePanelDragPayload,
        position: RootDragPosition,
    ): HomeSidePanelDragTarget? = when (payload) {
        is HomeSidePanelDragPayload.ExistingCard,
        is HomeSidePanelDragPayload.NewCard,
        -> cardInsertionTarget(position)

        is HomeSidePanelDragPayload.ExistingAction ->
            actionInsertionTarget(payload.cardId, position)

        is HomeSidePanelDragPayload.NewAction ->
            actionInsertionTarget(payload.cardId, position)
    }

    private fun targetBoundsFor(
        payload: HomeSidePanelDragPayload,
        target: HomeSidePanelDragTarget?,
        sourceBounds: RootDragBounds,
    ): RootDragBounds? = when {
        payload is HomeSidePanelDragPayload.ExistingCard ||
            payload is HomeSidePanelDragPayload.ExistingAction -> null

        target is HomeSidePanelDragTarget.Card -> {
            val ordered = cardBounds.values.sortedBy(RegisteredBounds::index)
            ordered.getOrNull(target.insertionIndex)?.bounds ?: ordered.lastOrNull()?.bounds
        }

        target is HomeSidePanelDragTarget.Action -> {
            val ordered = actionBounds[target.cardId].orEmpty().values.sortedBy(RegisteredBounds::index)
            ordered.getOrNull(target.insertionIndex)?.bounds ?: ordered.lastOrNull()?.bounds ?: run {
                val container = actionContainers[target.cardId] ?: return null
                val width = when (container.axis) {
                    HomeSidePanelDragAxis.Horizontal -> container.bounds.width / 3f
                    HomeSidePanelDragAxis.Vertical -> container.bounds.width
                }
                RootDragBounds(0f, 0f, width, sourceBounds.height)
            }
        }

        else -> null
    }

    private fun cardInsertionTarget(position: RootDragPosition): HomeSidePanelDragTarget.Card? {
        val viewport = viewportBounds ?: return null
        if (!viewport.contains(position)) return null
        val ordered = cardBounds.values.sortedBy(RegisteredBounds::index)
        if (ordered.isEmpty()) {
            return HomeSidePanelDragTarget.Card(0)
        }
        return HomeSidePanelDragTarget.Card(
            modelInsertionIndex(position.y, ordered, HomeSidePanelDragAxis.Vertical),
        )
    }

    private fun actionInsertionTarget(
        cardId: String,
        position: RootDragPosition,
    ): HomeSidePanelDragTarget.Action? {
        val container = actionContainers[cardId] ?: return null
        if (!container.bounds.contains(position)) return null
        val ordered = actionBounds[cardId].orEmpty().values.sortedBy(RegisteredBounds::index)
        val insertion = if (ordered.isEmpty()) {
            0
        } else {
            when (container.axis) {
                HomeSidePanelDragAxis.Horizontal ->
                    horizontalModelInsertionIndex(position, ordered)

                HomeSidePanelDragAxis.Vertical ->
                    modelInsertionIndex(position.y, ordered, HomeSidePanelDragAxis.Vertical)
            }
        }
        return HomeSidePanelDragTarget.Action(cardId, insertion)
    }

    private fun modelInsertionIndex(
        position: Float,
        ordered: List<RegisteredBounds>,
        axis: HomeSidePanelDragAxis,
    ): Int {
        val localIndex = insertionIndex(
            position = position,
            bounds = ordered.map { registered ->
                val bounds = registered.bounds
                val start = if (axis == HomeSidePanelDragAxis.Horizontal) bounds.left else bounds.top
                val end = if (axis == HomeSidePanelDragAxis.Horizontal) bounds.right else bounds.bottom
                DragItemBounds(start, end)
            },
        )
        return ordered.getOrNull(localIndex)?.index ?: (ordered.last().index + 1)
    }

    private fun horizontalModelInsertionIndex(
        position: RootDragPosition,
        ordered: List<RegisteredBounds>,
    ): Int {
        val rows = mutableListOf<HorizontalRow>()
        ordered.forEach { item ->
            val row = rows.lastOrNull()
            if (row != null && row.overlaps(item.bounds)) {
                row.add(item)
            } else {
                rows += HorizontalRow(
                    items = mutableListOf(item),
                    top = item.bounds.top,
                    bottom = item.bounds.bottom,
                )
            }
        }
        val row = rows
            .filter { position.y in it.top..it.bottom }
            .minByOrNull { kotlin.math.abs(position.y - it.centerY) }
            ?: rows.minBy { kotlin.math.abs(position.y - it.centerY) }
        return modelInsertionIndex(position.x, row.items, HomeSidePanelDragAxis.Horizontal)
    }

    private fun sourceBoundsFor(payload: HomeSidePanelDragPayload): RootDragBounds? = when (payload) {
        is HomeSidePanelDragPayload.ExistingCard -> cardBounds[payload.cardId]?.bounds
        is HomeSidePanelDragPayload.ExistingAction -> actionBounds[payload.cardId]?.get(payload.actionId)?.bounds
        is HomeSidePanelDragPayload.NewCard,
        is HomeSidePanelDragPayload.NewAction,
        -> null
    }

    private fun payloadPriority(payload: HomeSidePanelDragPayload): Int = when (payload) {
        is HomeSidePanelDragPayload.ExistingAction -> 2
        is HomeSidePanelDragPayload.ExistingCard -> 1
        is HomeSidePanelDragPayload.NewCard,
        is HomeSidePanelDragPayload.NewAction,
        -> 3
    }
}
