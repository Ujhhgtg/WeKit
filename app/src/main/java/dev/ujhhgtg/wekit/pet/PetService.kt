package dev.ujhhgtg.wekit.pet

import androidx.compose.runtime.mutableStateOf
import dev.ujhhgtg.wekit.agent.engine.AgentEvent
import dev.ujhhgtg.wekit.pet.core.ActivityPhase
import dev.ujhhgtg.wekit.pet.core.AffinityState
import dev.ujhhgtg.wekit.pet.core.ConsumeTreatResult
import dev.ujhhgtg.wekit.pet.core.InteractionOutcome
import dev.ujhhgtg.wekit.pet.core.PetAffinityView
import dev.ujhhgtg.wekit.pet.core.PetAnimation
import dev.ujhhgtg.wekit.pet.core.PetEntry
import dev.ujhhgtg.wekit.pet.core.PetInteraction
import dev.ujhhgtg.wekit.pet.core.PetProjectionRuntime
import dev.ujhhgtg.wekit.pet.core.PetRegistry
import dev.ujhhgtg.wekit.pet.core.PetStateMachine
import dev.ujhhgtg.wekit.pet.core.RemarkKind
import dev.ujhhgtg.wekit.pet.core.RemarkPicker
import dev.ujhhgtg.wekit.pet.core.TreatLedger
import dev.ujhhgtg.wekit.pet.core.affinityViewOf
import dev.ujhhgtg.wekit.pet.core.applyInteraction
import dev.ujhhgtg.wekit.pet.core.applyTurnReward
import dev.ujhhgtg.wekit.pet.core.consumeTreat
import dev.ujhhgtg.wekit.pet.core.emptyAffinity
import dev.ujhhgtg.wekit.pet.core.emptyTreatLedger
import dev.ujhhgtg.wekit.pet.core.parsePetManifest
import dev.ujhhgtg.wekit.pet.core.settleTreatGrants
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The pet brain: a process-level singleton that projects WeAgent session events onto the pet's
 * visual states, drives the affinity + treat economy, and exposes Compose snapshot state for the
 * overlay UI. Mirrors [dev.ujhhgtg.wekit.features.api.agent.WeAgentService]'s thin-UI philosophy:
 * all heavy logic lives here; the overlay only renders the state below and calls [pet]/[feed].
 */
object PetService {

    private const val TAG = "PetService"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // --- UI state (Compose snapshot) ---

    /** The active pet entry (definition + asset dir). Null until [init]. */
    val petEntry = mutableStateOf<PetEntry?>(null)

    /** Current animation track (derived; kept for status/other consumers). */
    val animation = mutableStateOf(PetAnimation.IDLE)

    /** Current effective activity phase (drives sequence rendering in the UI). */
    val phase = mutableStateOf(ActivityPhase.IDLE)

    /** Wall-clock ms the current [phase] started (UI computes frames from it). */
    val animationStartedAt = mutableStateOf(0L)

    /** Optional status bubble copy (follows the session state machine); null hides it. */
    val bubble = mutableStateOf<String?>(null)

    /** Transient interaction reaction bubble (pet/feed); takes precedence over [bubble]. */
    val reactionBubble = mutableStateOf<String?>(null)

    /** Read-only affinity view (points, rank, counts, cooldowns). */
    val affinityView = mutableStateOf<PetAffinityView?>(null)

    /** Current stocked treat (小鱼干) count. */
    val treatCount = mutableStateOf(0L)

    /** Current pet display name (custom name wins over the manifest name). */
    val petDisplayName = mutableStateOf(DEFAULT_PET_NAME)

    /** Current pet sprite height in dp (user-adjustable). */
    val displaySize = mutableStateOf(DEFAULT_DISPLAY_SIZE)

    /** Whether the pet info/treat panel is expanded. */
    val panelOpen = mutableStateOf(false)

    // --- internal state ---

    private var initialized = false
    private var registry: PetRegistry? = null
    private var persist: PetPersistData = emptyPersist()
    private var affinity: AffinityState = emptyAffinity()
    private var treats: TreatLedger = emptyTreatLedger()
    private lateinit var remarkPicker: RemarkPicker

    /** Per-session state machines + projection runtimes, keyed by session id. */
    private val machines = ConcurrentHashMap<String, PetStateMachine>()
    private val runtimes = ConcurrentHashMap<String, PetProjectionRuntime>()

    /** The session that most recently produced an activity event (drives the visible pet). */
    private var lastActiveSessionId: String? = null

    private var currentAnimation: PetAnimation = PetAnimation.IDLE
    private var currentPhase: ActivityPhase = ActivityPhase.IDLE

    // -----------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------

    fun init() {
        if (initialized) return
        initialized = true
        persist = PetPersist.load()
        affinity = persist.affinity.toCore()
        treats = persist.treats.toCore()
        loadRegistry()
        remarkPicker = RemarkPicker(activeEntry()?.definition?.remarks)
        // State updates and overlay mounting must run on the main thread; init itself may be
        // invoked from an IO coroutine (WeAgentService.init).
        scope.launch {
            refreshUiState()
            if (persist.display.visible) PetOverlayController.show()
        }
        WeLogger.d(TAG, "pet initialized: entries=${registry?.entries?.size}, warnings=${registry?.warnings}")
    }

    private fun loadRegistry() {
        val warnings = mutableListOf<String>()
        val entries = mutableListOf<PetEntry>()
        try {
            val assets = HostInfo.application.assets
            val dirs = assets.list("pets") ?: emptyArray()
            for (dir in dirs) {
                val manifestPath = "pets/$dir/pet.json"
                val json = runCatching {
                    assets.open(manifestPath).bufferedReader().use { it.readText() }
                }.getOrNull() ?: continue
                val def = parsePetManifest(json, warnings) ?: continue
                entries.add(PetEntry(def, "pets/$dir"))
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to load pet registry", e)
            warnings.add("load registry: ${e.message}")
        }
        registry = PetRegistry(entries, warnings)
    }

    private fun activeEntry(): PetEntry? = registry?.defaultEntry()

    // -----------------------------------------------------------------------------------------
    // Event projection
    // -----------------------------------------------------------------------------------------

    /**
     * Feed one agent event (from [dev.ujhhgtg.wekit.features.api.agent.WeAgentService.handleEvent])
     * into the per-session projection. Completed turns accrue affinity + treats.
     */
    fun onAgentEvent(sessionId: String, event: AgentEvent) {
        val runtime = runtimes.getOrPut(sessionId) { PetProjectionRuntime() }
        val machine = machines.getOrPut(sessionId) { PetStateMachine() }
        val transition = runtime.project(event) ?: return
        machine.onActivityStatus(transition.input)
        machine.onSessionActive()
        lastActiveSessionId = sessionId

        if (transition.completedTurn) {
            onTurnCompleted()
        }

        // A `done`/`failed` phase carries a one-shot window before settling to idle. Schedule a
        // refresh once the window elapses so the pet visibly calms back down without further events.
        if (transition.input.phase == ActivityPhase.DONE || transition.input.phase == ActivityPhase.FAILED) {
            scope.launch {
                delay(2400)
                refreshUiState()
            }
        }

        refreshUiState()
    }

    /** A completed turn: accrue affinity and settle treat grants (work + time). */
    private fun onTurnCompleted() {
        affinity = applyTurnReward(affinity)
        val settlement = settleTreatGrants(treats, affinity.turns, System.currentTimeMillis())
        treats = settlement.ledger
        savePersist()
    }

    // -----------------------------------------------------------------------------------------
    // Interactions
    // -----------------------------------------------------------------------------------------

    /** Pet the pet (cooldown-gated affinity). */
    fun pet(): InteractionOutcome {
        val outcome = applyInteraction(affinity, PetInteraction.PET, System.currentTimeMillis())
        affinity = outcome.affinity
        savePersist()
        refreshUiState()
        showReactionBubble(outcome.reaction)
        return outcome
    }

    /** Feed the pet (consumes one treat, then cooldown-gated affinity). */
    fun feed(): FeedOutcome {
        val now = System.currentTimeMillis()
        // Settle outstanding grants first so a freshly-earned treat can be spent.
        val settlement = settleTreatGrants(treats, affinity.turns, now)
        treats = settlement.ledger

        return when (val consume = consumeTreat(treats)) {
            is ConsumeTreatResult.No -> {
                refreshUiState()
                showReactionBubble(remarkPicker.pick(RemarkKind.NO_TREATS))
                FeedOutcome(accepted = false, treatCount = treats.treats)
            }

            is ConsumeTreatResult.Ok -> {
                treats = consume.ledger
                val outcome = applyInteraction(affinity, PetInteraction.FEED, now)
                affinity = outcome.affinity
                savePersist()
                refreshUiState()
                showReactionBubble(outcome.reaction)
                FeedOutcome(accepted = true, treatCount = treats.treats)
            }
        }
    }

    data class FeedOutcome(val accepted: Boolean, val treatCount: Long)

    /** Open/close the pet info panel. */
    fun setPanelOpen(open: Boolean) {
        panelOpen.value = open
    }

    /**
     * Rename the active pet. Trims surrounding whitespace, caps at [PET_NAME_MAX_LENGTH] chars,
     * and rejects a blank result. Persists under the active pet's id so it survives restarts.
     */
    fun rename(name: String): Boolean {
        val trimmed = name.trim().take(PET_NAME_MAX_LENGTH)
        if (trimmed.isBlank()) return false
        val petId = activeEntry()?.definition?.id ?: persist.petId
        persist = persist.copy(names = persist.names + (petId to trimmed))
        savePersist()
        refreshUiState()
        showReactionBubble("我是 $trimmed 啦～")
        return true
    }

    /**
     * Resize the pet (sprite height in dp), clamped to [DISPLAY_SIZE_MIN]..[DISPLAY_SIZE_MAX].
     * Persists and, when the overlay is mounted, triggers a window re-layout.
     */
    fun setSize(size: Int) {
        val clamped = size.coerceIn(DISPLAY_SIZE_MIN, DISPLAY_SIZE_MAX)
        persist = persist.copy(display = persist.display.copy(size = clamped))
        displaySize.value = clamped
        savePersist()
        PetOverlayController.onSizeChanged()
    }

    /** Open the focusable rename dialog overlay window. */
    fun openRename() = PetOverlayController.openRenameWindow()

    /**
     * Show/hide the pet overlay and persist the visibility choice so the pet's
     * startup behaviour (see [init]) matches the user's last toggle.
     */
    fun setVisible(visible: Boolean) {
        persist = persist.copy(display = persist.display.copy(visible = visible))
        savePersist()
        if (visible) PetOverlayController.show() else PetOverlayController.hide()
    }

    // -----------------------------------------------------------------------------------------
    // State refresh + persistence
    // -----------------------------------------------------------------------------------------

    private fun showReactionBubble(text: String) {
        reactionBubble.value = text
        scope.launch {
            delay(3200)
            reactionBubble.value = null
        }
    }

    private fun refreshUiState() {
        val sid = lastActiveSessionId
        val machine = sid?.let { machines[sid] }
        val snap = machine?.render()
        val targetPhase = snap?.phase ?: ActivityPhase.IDLE
        val targetAnimation = snap?.animation ?: PetAnimation.IDLE
        if (targetPhase != currentPhase) {
            currentPhase = targetPhase
            animationStartedAt.value = System.currentTimeMillis()
        }
        currentAnimation = targetAnimation
        animation.value = currentAnimation
        phase.value = currentPhase
        bubble.value = snap?.bubble
        affinityView.value = affinityViewOf(affinity, System.currentTimeMillis())
        treatCount.value = treats.treats
        petEntry.value = activeEntry()
        petDisplayName.value = displayNameOf(activeEntry())
        displaySize.value = persist.display.size.coerceIn(DISPLAY_SIZE_MIN, DISPLAY_SIZE_MAX)
    }

    /** Resolve the visible name: user rename wins, then the manifest display name, then the default. */
    private fun displayNameOf(entry: PetEntry?): String {
        val id = entry?.definition?.id ?: persist.petId
        return persist.names[id]?.takeIf { it.isNotBlank() }
            ?: entry?.definition?.displayName?.takeIf { it.isNotBlank() }
            ?: DEFAULT_PET_NAME
    }

    private fun savePersist() {
        persist = persist.copy(
            affinity = affinity.toPersist(),
            treats = treats.toPersist(),
        )
        PetPersist.save(persist)
    }
}
