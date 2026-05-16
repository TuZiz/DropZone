package ym.dropzone.task

import org.bukkit.Server
import ym.dropzone.config.ConfigManager
import ym.dropzone.entity.DropZoneEntityManager
import ym.dropzone.player.PlayerSnapshotService
import ym.dropzone.region.RandomLocationService
import ym.dropzone.scheduler.SchedulerAdapter

class PlayerSnapshotTask(
    private val server: Server,
    private val snapshots: PlayerSnapshotService
) : Runnable {
    override fun run() {
        snapshots.refresh(server.onlinePlayers)
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
    private val entityManager: DropZoneEntityManager
) : Runnable {
    override fun run() {
        val snapshot = configManager.snapshot ?: return
        if (!snapshot.main.spawn.enabled) return
        val missing = (snapshot.main.spawn.maxActive - entityManager.activeCount()).coerceAtMost(snapshot.main.spawn.attemptsPerCycle)
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
