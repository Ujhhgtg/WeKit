package dev.ujhhgtg.wekit.pet.core

/**
 * Treat (小鱼干) economy — pure, clock-injected. Ported 1:1 from dsh-pet treats.ts.
 * Two sources: work output (every N completed turns) and time output (every T
 * minutes). Settlement is lazy (no timer, no drift) — elapsed periods are
 * computed from the persisted last-grant marks.
 */

/** Treat economy tuning. */
data class TreatConfig(
    val turnsPerTreat: Long = 30,
    val timeTreatMs: Long = 300 * 60_000L,
    val maxTreats: Long = 20,
)

/** Treat ledger as persisted. */
data class TreatLedger(
    val treats: Long,
    val lastTreatGrantAt: Long,
    val turnsAtLastTreatGrant: Long,
)

fun emptyTreatLedger(): TreatLedger = TreatLedger(treats = 0, lastTreatGrantAt = 0, turnsAtLastTreatGrant = 0)

/** Outcome of one settlement pass. */
data class TreatSettlement(
    val ledger: TreatLedger,
    val gained: Long,
)

private fun cap(treats: Long, max: Long): Long = max.coerceAtMost(0L.coerceAtLeast(treats))

/**
 * Settle treat grants from both sources against one ledger snapshot. Work
 * output counts whole periods since the last work settlement; time output
 * counts whole periods since the time anchor. Both clamped by the stock cap.
 * When the anchor is already set and nothing is due, the input ledger is
 * returned unchanged so callers can skip persistence.
 */
fun settleTreatGrants(
    ledger: TreatLedger,
    turns: Long,
    nowMs: Long,
    config: TreatConfig = TreatConfig(),
): TreatSettlement {
    val turnDelta = 0L.coerceAtLeast(turns - ledger.turnsAtLastTreatGrant)
    val workGrants = turnDelta / config.turnsPerTreat
    val timeAnchor = if (ledger.lastTreatGrantAt == 0L) nowMs else ledger.lastTreatGrantAt
    val timeGrants = 0L.coerceAtLeast(nowMs - timeAnchor) / config.timeTreatMs
    val gained = workGrants + timeGrants
    if (gained <= 0) {
        if (ledger.lastTreatGrantAt == 0L) {
            // Zero-gain first settlement: persist the clock start anyway.
            return TreatSettlement(ledger.copy(lastTreatGrantAt = nowMs), 0)
        }
        return TreatSettlement(ledger, 0)
    }
    return TreatSettlement(
        ledger = TreatLedger(
            treats = cap(ledger.treats + gained, config.maxTreats),
            lastTreatGrantAt = if (timeGrants > 0) timeAnchor + timeGrants * config.timeTreatMs else timeAnchor,
            turnsAtLastTreatGrant = if (workGrants > 0) turns - (turnDelta % config.turnsPerTreat) else ledger.turnsAtLastTreatGrant,
        ),
        gained = gained,
    )
}

/** Consume one treat for a feed. A feed with no stocked treats is refused. */
sealed class ConsumeTreatResult {
    data class Ok(val ledger: TreatLedger) : ConsumeTreatResult()
    data object No : ConsumeTreatResult()
}

fun consumeTreat(ledger: TreatLedger): ConsumeTreatResult {
    if (ledger.treats <= 0) return ConsumeTreatResult.No
    return ConsumeTreatResult.Ok(ledger.copy(treats = ledger.treats - 1))
}
