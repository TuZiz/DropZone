package ym.dropzone.task

import ym.dropzone.config.ConfigManager
import ym.dropzone.config.SpawnCrossServerMode
import ym.dropzone.entity.DropZoneEntityManager
import ym.dropzone.player.PlayerSnapshotService
import ym.dropzone.region.RandomLocationService
import ym.dropzone.scheduler.SchedulerAdapter
import ym.dropzone.storage.MysqlStorage

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
    private val requestedAmount: Int? = null,
    private val mysqlStorage: MysqlStorage? = null
) : Runnable {
    override fun run() {
        val snapshot = configManager.snapshot ?: return
        if (!snapshot.main.spawn.enabled) return
        val storage = mysqlStorage
        if (storage != null && snapshot.main.spawn.crossServerMode == SpawnCrossServerMode.DATABASE_LOCK) {
            storage.acquireLock("dropzone:spawn:${snapshot.main.server.group}", 30_000L).thenAccept { locked ->
                if (locked) runSpawnCycle(snapshot, storage) else Unit
            }
            return
        }
        runSpawnCycle(snapshot, storage)
    }

    private fun runSpawnCycle(snapshot: ym.dropzone.config.RuntimeConfigSnapshot, storage: MysqlStorage?) {
        val perCycle = requestedAmount ?: snapshot.main.spawn.attemptsPerCycle
        val activeFuture = storage?.activeCount(snapshot.activity.id, snapshot.main.server.group)
            ?: java.util.concurrent.CompletableFuture.completedFuture(entityManager.activeCount())
        activeFuture.thenAccept { activeCount ->
            val missing = (snapshot.main.spawn.maxActive - activeCount).coerceAtMost(perCycle)
            repeat(missing.coerceAtLeast(0)) {
                locationService.findLocation(snapshot).thenAccept { location ->
                    if (location == null) return@thenAccept
                    scheduler.runAt(location) {
                        entityManager.createAtAsync(location, snapshot).thenAccept { entity ->
                            if (entity == null) return@thenAccept
                            entityManager.revealToNearbyPlayersLive(entity, snapshot)
                            entityManager.playSpawnMarker(entity, snapshot)
                        }
                    }
                }
            }
        }
    }
}
