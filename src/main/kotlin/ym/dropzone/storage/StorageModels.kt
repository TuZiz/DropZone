package ym.dropzone.storage

import ym.dropzone.config.ActivityRulesConfig
import java.util.UUID

enum class SpawnPointState {
    WAITING,
    CLAIMING,
    CLAIMED,
    EXPIRED,
    CLEARED
}

enum class OutboxStatus {
    PENDING,
    PROCESSING,
    DONE,
    FAILED
}

data class StoredSpawnPoint(
    val id: UUID,
    val activityId: String,
    val serverGroup: String,
    val worldName: String,
    val worldUuid: String?,
    val x: Double,
    val y: Double,
    val z: Double,
    val rarityId: String,
    val headId: String,
    val rewardId: String,
    val state: SpawnPointState,
    val createdAt: Long,
    val expiresAt: Long,
    val claimedBy: String? = null,
    val claimedAt: Long? = null,
    val claimedOnServer: String? = null,
    val createdByServer: String? = null,
    val version: Long = 0L
)

data class MysqlClaimRequest(
    val spawnId: UUID,
    val activityId: String,
    val serverGroup: String,
    val serverId: String,
    val playerUuid: UUID,
    val playerName: String,
    val rewardId: String,
    val rarityId: String,
    val commands: List<String>,
    val rules: ActivityRulesConfig,
    val maxAttempts: Int,
    val now: Long = System.currentTimeMillis()
)

enum class MysqlClaimDenyReason {
    ALREADY_CLAIMED,
    COOLDOWN,
    MAX_CLAIMS,
    REPEAT_REWARD,
    NOT_FOUND,
    FAILED
}

data class MysqlClaimResult(
    val allowed: Boolean,
    val denyReason: MysqlClaimDenyReason? = null,
    val remainingSeconds: Long = 0L,
    val outboxId: Long? = null,
    val error: String? = null
)

data class RewardOutboxEntry(
    val id: Long,
    val spawnId: String,
    val activityId: String,
    val serverGroup: String,
    val playerUuid: UUID,
    val playerName: String,
    val rewardId: String,
    val rarityId: String,
    val serverId: String,
    val commands: List<String>,
    val attemptCount: Int,
    val maxAttempts: Int
)

data class OutboxStats(
    val pending: Long,
    val processing: Long,
    val done: Long,
    val failed: Long
)
