package ym.dropzone.task

import ym.dropzone.config.ConfigManager
import ym.dropzone.entity.DropZoneEntityManager
import ym.dropzone.region.RandomLocationService

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
                    entityManager.createAt(location, snapshot)
                }
            }
        }
    }
}
