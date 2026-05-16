package ym.dropzone.claim

import ym.dropzone.config.ActivityRulesConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

class ClaimTracker {
    private val states = ConcurrentHashMap<String, PlayerClaimState>()

    @Synchronized
    fun tryClaim(playerId: UUID, activityId: String, rewardId: String, rules: ActivityRulesConfig): ClaimResult {
        val key = key(activityId, playerId)
        val now = System.currentTimeMillis()
        val state = states.getOrPut(key) { PlayerClaimState() }

        if (rules.maxClaimsPerPlayer > 0 && state.totalClaims >= rules.maxClaimsPerPlayer) {
            return ClaimResult(false, ClaimDenyReason.MAX_CLAIMS, totalClaims = state.totalClaims)
        }

        if (rules.claimCooldownSeconds > 0 && state.lastClaimAtMillis > 0) {
            val elapsed = now - state.lastClaimAtMillis
            val cooldownMillis = rules.claimCooldownSeconds * 1000L
            if (elapsed < cooldownMillis) {
                val remaining = ceil((cooldownMillis - elapsed) / 1000.0).toLong().coerceAtLeast(1L)
                return ClaimResult(false, ClaimDenyReason.COOLDOWN, remainingSeconds = remaining, totalClaims = state.totalClaims)
            }
        }

        if (!rules.allowRepeatRewards && rewardId in state.rewardIds) {
            return ClaimResult(false, ClaimDenyReason.REPEAT_REWARD, totalClaims = state.totalClaims)
        }

        state.totalClaims += 1
        state.lastClaimAtMillis = now
        state.rewardIds += rewardId
        return ClaimResult(true, totalClaims = state.totalClaims)
    }

    @Synchronized
    fun clearActivity(activityId: String) {
        val prefix = "$activityId:"
        states.keys.removeIf { it.startsWith(prefix) }
    }

    @Synchronized
    fun clearAll() {
        states.clear()
    }

    private fun key(activityId: String, playerId: UUID): String = "$activityId:$playerId"

    private class PlayerClaimState {
        var totalClaims: Int = 0
        var lastClaimAtMillis: Long = 0L
        val rewardIds: MutableSet<String> = linkedSetOf()
    }
}
