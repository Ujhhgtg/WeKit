package dev.ujhhgtg.wekit.pet.core

/**
 * Affinity score — pure, clock-injected. Ported 1:1 from dsh-pet affinity.ts.
 * Every completed turn earns a small reward, petting earns a tiny one
 * (cooldown-gated), feeding earns the most. Persistence lives in the service;
 * this module only computes transitions.
 */

/** One interaction the user can perform on the pet. */
enum class PetInteraction { PET, FEED }

/** Affinity state as persisted. */
data class AffinityState(
    val points: Long,
    val lastPetAt: Long,
    val lastFeedAt: Long,
    val pets: Long,
    val feeds: Long,
    val petRejects: Long,
    val feedRejects: Long,
    val turns: Long,
)

/** Affinity points cap (full 999,999,999 range). */
const val AFFINITY_MAX: Long = 999_999_999L

/** Affinity ranks by points; the pet visibly grows with its rank. */
data class AffinityRank(val min: Long, val name: String, val emoji: String)

val AFFINITY_RANKS: List<AffinityRank> = listOf(
    AffinityRank(0, "幼鲸", "*"),
    AffinityRank(25, "伙伴", "**"),
    AffinityRank(50, "挚友", "***"),
    AffinityRank(80, "深海羁绊", "****"),
    AffinityRank(200, "心有灵犀", "*****"),
    AffinityRank(500, "传说羁绊", "******"),
    AffinityRank(2_000, "神话羁绊", "*******"),
    AffinityRank(10_000, "永恒之契", "********"),
    AffinityRank(100_000, "鲸生共渡", "*********"),
)

/** Interaction tuning (all in points / ms). */
data class AffinityConfig(
    val turnReward: Long = 1,
    val petReward: Long = 1,
    val petCooldownMs: Long = 10_000,
    val feedReward: Long = 5,
    val feedCooldownMs: Long = 30_000,
)

fun emptyAffinity(): AffinityState = AffinityState(
    points = 0,
    lastPetAt = 0,
    lastFeedAt = 0,
    pets = 0,
    feeds = 0,
    petRejects = 0,
    feedRejects = 0,
    turns = 0,
)

/** Outcome of one interaction. */
data class InteractionOutcome(
    val affinity: AffinityState,
    val delta: Long,
    val reaction: String,
    val accepted: Boolean,
)

/** Rank for a point total. */
fun rankOf(points: Long): AffinityRank {
    var rank = AFFINITY_RANKS.first()
    for (candidate in AFFINITY_RANKS) {
        if (points >= candidate.min) rank = candidate
    }
    return rank
}

/** Read-only affinity snapshot suited for the UI. */
data class PetAffinityView(
    val points: Long,
    val rank: String,
    val rankEmoji: String,
    val pets: Long,
    val feeds: Long,
    val turns: Long,
    val petCooldown: Boolean,
    val feedCooldown: Boolean,
)

/** Derive the read-only view of one affinity state at a wall-clock instant. */
fun affinityViewOf(
    state: AffinityState,
    nowMs: Long,
    config: AffinityConfig = AffinityConfig(),
): PetAffinityView {
    val rank = rankOf(state.points)
    return PetAffinityView(
        points = state.points,
        rank = rank.name,
        rankEmoji = rank.emoji,
        pets = state.pets,
        feeds = state.feeds,
        turns = state.turns,
        petCooldown = nowMs - state.lastPetAt < config.petCooldownMs,
        feedCooldown = nowMs - state.lastFeedAt < config.feedCooldownMs,
    )
}

private fun clamp(points: Long): Long = AFFINITY_MAX.coerceAtMost(0L.coerceAtLeast(points))

/**
 * Apply one interaction to a copy of the state. Cooldowns only apply once the
 * pet has been interacted with at least once (last*At == 0 means "never", so
 * the first pet/feed always lands).
 */
fun applyInteraction(
    state: AffinityState,
    kind: PetInteraction,
    nowMs: Long,
    config: AffinityConfig = AffinityConfig(),
): InteractionOutcome {
    if (kind == PetInteraction.PET) {
        if (state.lastPetAt != 0L && nowMs - state.lastPetAt < config.petCooldownMs) {
            val next = state.copy(petRejects = state.petRejects + 1)
            return InteractionOutcome(
                affinity = next,
                delta = 0,
                reaction = countedRemark(RemarkKind.PET_COOLDOWN, state.petRejects),
                accepted = false,
            )
        }
        val next = state.copy(
            lastPetAt = nowMs,
            pets = state.pets + 1,
            points = clamp(state.points + config.petReward),
        )
        return InteractionOutcome(
            affinity = next,
            delta = config.petReward,
            reaction = countedRemark(RemarkKind.PET, state.pets),
            accepted = true,
        )
    }
    if (kind == PetInteraction.FEED) {
        if (state.lastFeedAt != 0L && nowMs - state.lastFeedAt < config.feedCooldownMs) {
            val next = state.copy(feedRejects = state.feedRejects + 1)
            return InteractionOutcome(
                affinity = next,
                delta = 0,
                reaction = countedRemark(RemarkKind.FEED_COOLDOWN, state.feedRejects),
                accepted = false,
            )
        }
        val next = state.copy(
            lastFeedAt = nowMs,
            feeds = state.feeds + 1,
            points = clamp(state.points + config.feedReward),
        )
        return InteractionOutcome(
            affinity = next,
            delta = config.feedReward,
            reaction = countedRemark(RemarkKind.FEED, state.feeds),
            accepted = true,
        )
    }
    return InteractionOutcome(affinity = state, delta = 0, reaction = "", accepted = false)
}

/** Reward one completed turn (called by the host on `done`). */
fun applyTurnReward(
    state: AffinityState,
    config: AffinityConfig = AffinityConfig(),
): AffinityState = state.copy(
    turns = state.turns + 1,
    points = clamp(state.points + config.turnReward),
)

/** Built-in reaction selected deterministically from a persisted counter. */
private fun countedRemark(kind: RemarkKind, count: Long): String {
    val pool = BUILTIN_REMARKS[kind] ?: return builtinRemark(kind)
    return pool[(0L.coerceAtLeast(count) % pool.size).toInt()]
}
