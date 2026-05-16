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
        val missing = (snapshot.main.spawn.maxActive - entityManager.activeCount()).coerceAtMost(perCycle)
        repeat(missing.coerceAtLeast(0)) {
            locationService.findLocation(snapshot).thenAccept { location ->
                if (location != null) {
                    scheduler.runAt(location) {
                        entityManager.createAt(location, snapshot)
                    }
                }
            }
        }
    }
}
