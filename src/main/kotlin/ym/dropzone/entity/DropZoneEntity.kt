package ym.dropzone.entity

import org.bukkit.Location
import org.bukkit.inventory.ItemStack
import ym.dropzone.reward.RewardRollResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class DropZoneEntityState {
    WAITING,
    ATTRACTING,
    CLAIMING,
    CLAIMED,
    EXPIRED
}

// 单个奖励点的运行时状态；所有奖励、头颅和位置数据都来自配置快照。
class DropZoneEntity(
    val id: UUID,
    val runtimeEntityId: Int,
    val roll: RewardRollResult,
    val itemStack: ItemStack,
    val spawnLocation: Location,
    val expiresAtMillis: Long,
    val glowing: Boolean
) {
    val visibleTo: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    val ignoredUntil: MutableMap<UUID, Long> = ConcurrentHashMap()
    val worldUid: UUID? = spawnLocation.world?.uid
    val state = AtomicReference(DropZoneEntityState.WAITING)
    val claimed = AtomicBoolean(false)
    @Volatile var currentLocation: Location = spawnLocation.clone()
    @Volatile var lockedPlayer: UUID? = null
    @Volatile var yaw: Float = 0f

    fun markClaiming(): Boolean {
        return claimed.compareAndSet(false, true).also { success ->
            if (success) state.set(DropZoneEntityState.CLAIMING)
        }
    }

    fun releaseClaiming() {
        claimed.set(false)
        state.set(DropZoneEntityState.WAITING)
    }
}
