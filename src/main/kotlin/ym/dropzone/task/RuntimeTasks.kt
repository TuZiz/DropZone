package ym.dropzone.task

import ym.dropzone.config.ConfigManager
import ym.dropzone.config.LangKeys
import ym.dropzone.entity.DropZoneEntityManager
import ym.dropzone.message.LangService
import ym.dropzone.message.PlaceholderService
import ym.dropzone.player.PlayerSnapshotService
import ym.dropzone.region.RandomLocationService
import ym.dropzone.scheduler.SchedulerAdapter
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

class NavigationActionBarTask(
    private val configManager: ConfigManager,
    private val scheduler: SchedulerAdapter,
    private val entityManager: DropZoneEntityManager,
    private val snapshots: PlayerSnapshotService,
    private val langService: LangService,
    private val placeholderService: PlaceholderService
) : Runnable {
    override fun run() {
        val snapshot = configManager.snapshot ?: return
        val navigation = snapshot.main.navigation
        if (!navigation.actionbarEnabled) return
        snapshots.refreshTracked()
        val maxDistanceSquared = navigation.maxDistance * navigation.maxDistance
        val entities = entityManager.activeEntities()
        val players = snapshots.all()
        val fallbackPlayerIds = snapshots.trackedIds()
        if (entities.isEmpty()) {
            fallbackPlayerIds.forEach { playerId ->
                scheduler.runForPlayer(playerId) { online ->
                    langService.actionBar(online, snapshot.lang, LangKeys.NAVIGATION_ACTIONBAR_EMPTY, placeholderService.build(snapshot.lang, online, null, null))
                }
            }
            return
        }
        val delivered = HashSet<java.util.UUID>()
        players.forEach { player ->
            delivered += player.uuid
            val nearest = entities
                .asSequence()
                .filter { it.worldUid == player.worldUid }
                .map { entity -> entity to player.distanceSquared(entity.currentLocation.x, entity.currentLocation.y, entity.currentLocation.z) }
                .filter { navigation.maxDistance <= 0.0 || it.second <= maxDistanceSquared }
                .minByOrNull { it.second }
            if (nearest == null) {
                scheduler.runForPlayer(player.uuid) { online ->
                    langService.actionBar(online, snapshot.lang, LangKeys.NAVIGATION_ACTIONBAR_EMPTY, placeholderService.build(snapshot.lang, online, null, null))
                }
                return@forEach
            }
            val entity = nearest.first
            val location = entity.currentLocation
            val distance = sqrt(nearest.second).roundToInt().toString()
            scheduler.runForPlayer(player.uuid) { online ->
                val values = placeholderService.build(
                    snapshot.lang,
                    online,
                    entity.roll,
                    location,
                    mapOf(
                        "distance" to distance,
                        "direction" to directionText(snapshot.lang.messages, online.location.yaw.toDouble(), location.x - player.x, location.z - player.z)
                    )
                )
                langService.actionBar(online, snapshot.lang, LangKeys.NAVIGATION_ACTIONBAR, values)
            }
        }
        fallbackPlayerIds
            .asSequence()
            .filterNot { it in delivered }
            .forEach { playerId ->
                scheduler.runForPlayer(playerId) { online ->
                    langService.actionBar(online, snapshot.lang, LangKeys.NAVIGATION_ACTIONBAR_EMPTY, placeholderService.build(snapshot.lang, online, null, null))
                }
            }
    }

    private fun directionText(messages: Map<String, String>, yaw: Double, dx: Double, dz: Double): String {
        val targetYaw = Math.toDegrees(atan2(-dx, dz))
        val relative = normalizeDegrees(targetYaw - yaw)
        val key = when {
            relative >= -22.5 && relative < 22.5 -> LangKeys.DIRECTION_FRONT
            relative >= 22.5 && relative < 67.5 -> LangKeys.DIRECTION_FRONT_LEFT
            relative >= 67.5 && relative < 112.5 -> LangKeys.DIRECTION_LEFT
            relative >= 112.5 && relative < 157.5 -> LangKeys.DIRECTION_BACK_LEFT
            relative >= 157.5 || relative < -157.5 -> LangKeys.DIRECTION_BACK
            relative >= -157.5 && relative < -112.5 -> LangKeys.DIRECTION_BACK_RIGHT
            relative >= -112.5 && relative < -67.5 -> LangKeys.DIRECTION_RIGHT
            else -> LangKeys.DIRECTION_FRONT_RIGHT
        }
        return messages[key] ?: key
    }

    private fun normalizeDegrees(value: Double): Double {
        var result = value % 360.0
        if (result >= 180.0) result -= 360.0
        if (result < -180.0) result += 360.0
        return result
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
