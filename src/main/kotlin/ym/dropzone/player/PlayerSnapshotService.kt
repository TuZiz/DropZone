package ym.dropzone.player

import org.bukkit.entity.Player
import ym.dropzone.scheduler.SchedulerAdapter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class PlayerPositionSnapshot(
    val uuid: UUID,
    val name: String,
    val worldUid: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val capturedAtMillis: Long
) {
    fun distanceSquared(x: Double, y: Double, z: Double): Double {
        val dx = this.x - x
        val dy = this.y - y
        val dz = this.z - z
        return dx * dx + dy * dy + dz * dz
    }
}

class PlayerSnapshotService(
    private val scheduler: SchedulerAdapter
) {
    private val snapshots = ConcurrentHashMap<UUID, PlayerPositionSnapshot>()

    fun refresh(players: Collection<Player>) {
        players.forEach { player ->
            scheduler.runForPlayer(player) {
                if (!player.isOnline) {
                    remove(player.uniqueId)
                    return@runForPlayer
                }
                val location = player.location
                snapshots[player.uniqueId] = PlayerPositionSnapshot(
                    uuid = player.uniqueId,
                    name = player.name,
                    worldUid = player.world.uid,
                    x = location.x,
                    y = location.y,
                    z = location.z,
                    capturedAtMillis = System.currentTimeMillis()
                )
            }
        }
    }

    fun remove(uuid: UUID) {
        snapshots.remove(uuid)
    }

    fun all(): List<PlayerPositionSnapshot> = snapshots.values.toList()

    fun byWorld(): Map<UUID, List<PlayerPositionSnapshot>> = snapshots.values.groupBy { it.worldUid }

    fun get(uuid: UUID): PlayerPositionSnapshot? = snapshots[uuid]

    fun clear() {
        snapshots.clear()
    }
}
