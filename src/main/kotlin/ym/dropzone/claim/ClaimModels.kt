package ym.dropzone.claim

import java.util.UUID

enum class ClaimDenyReason {
    COOLDOWN,
    MAX_CLAIMS,
    REPEAT_REWARD
}

data class ClaimResult(
    val allowed: Boolean,
    val denyReason: ClaimDenyReason? = null,
    val remainingSeconds: Long = 0L,
    val totalClaims: Int = 0
)

data class PlayerClaimRecord(
    val playerId: UUID,
    val activityId: String,
    val rewardId: String,
    val claimedAtMillis: Long,
    val totalClaims: Int
)
