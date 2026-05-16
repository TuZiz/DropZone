package ym.dropzone.task

import ym.dropzone.config.ConfigManager
import ym.dropzone.entity.DropZoneEntityManager
import ym.dropzone.player.PlayerSnapshotService
import ym.dropzone.region.RandomLocationService
import ym.dropzone.scheduler.SchedulerAdapter

class PlayerSnapshotTask(
    private val snapshots: PlayerSnapshotService
) : Runnable {
    override fun run() {
        snapshots.refreshTracked()
    }
}

class EntityTickTask(private val manager: DropZoneEntityManager) : Runnable {
    override fun run() {
        manager.tick()
    }
}

class ViewerUpdateTask(private val manager: DropZoneEntityManager) : Runnable {
    override fun run() {
        manager.updateViewers()
    }
}

class SpawnCycleTask(
    private val configManager: ConfigManager,
    private val scheduler: SchedulerAdapter,
    private val locationService: RandomLocationService,
    private val entityManager: DropZoneEntityManager,
    private val requestedAmount: Int? = null
) : Runnable {
    override fun run() {
        val snapshot = configManager.snapshot ?: return
        if (!snapshot.main.spawn.enabled) return
        val perCycle = requestedAmount ?: snapshot.main.spawn.attemptsPerCycle
        val activeCount = entityManager.activeCount()
        val missing = (snapshot.main.spawn.maxActive - activeCount).coerceAtMost(perCycle)
        if (snapshot.main.debug) {
            println(
                "[DropZone] SpawnCycle start: activity=${snapshot.activity.id}, " +
                    "requested=$requestedAmount, perCycle=$perCycle, missing=$missing, " +
                    "active=$activeCount, maxActive=${snapshot.main.spawn.maxActive}"
            )
        }
        repeat(missing.coerceAtLeast(0)) {
            locationService.findLocation(snapshot).thenAccept { location ->
                if (location == null) {
                    if (snapshot.main.debug) {
                        println(
                            "[DropZone] SpawnCycle failed: no valid location, " +
                                "world=${snapshot.activity.spawnRegion.world}, " +
                                "attempts=${snapshot.main.locationRules.maxLocationAttempts}"
                        )
                    }
                    return@thenAccept
                }
                scheduler.runAt(location) {
                    val entity = entityManager.createAt(location, snapshot)
                    if (entity == null) {
                        if (snapshot.main.debug) {
                            println(
                                "[DropZone] SpawnCycle failed: createAt returned null at " +
                                    "${location.world?.name} ${location.blockX},${location.blockY},${location.blockZ}, " +
                                    "active=${entityManager.activeCount()}"
                            )
                        }
                        return@runAt
                    }
                    entityManager.revealToNearbyPlayersLive(entity, snapshot)
                    entityManager.playSpawnMarker(entity, snapshot)
                    if (snapshot.main.debug) {
                        println(
                            "[DropZone] SpawnCycle success: " +
                                "${location.world?.name} ${location.blockX},${location.blockY},${location.blockZ}, " +
                                "entity=${entity.runtimeEntityId}, reward=${entity.roll.reward.id}, rarity=${entity.roll.rarity.id}"
                        )
                    }
                }
            }
        }
    }
}
