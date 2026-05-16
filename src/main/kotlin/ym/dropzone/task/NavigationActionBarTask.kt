package ym.dropzone.task

import org.bukkit.entity.Player
import ym.dropzone.config.ConfigManager
import ym.dropzone.config.LangConfig
import ym.dropzone.config.LangKeys
import ym.dropzone.entity.DropZoneEntityManager
import ym.dropzone.message.LangService
import ym.dropzone.message.PlaceholderService
import ym.dropzone.player.PlayerSnapshotService
import ym.dropzone.reward.RewardRollResult
import ym.dropzone.scheduler.SchedulerAdapter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class NavigationActionBarTask(
    private val configManager: ConfigManager,
    private val scheduler: SchedulerAdapter,
    private val entityManager: DropZoneEntityManager,
    private val playerSnapshots: PlayerSnapshotService,
    private val langService: LangService,
    private val placeholderService: PlaceholderService
) : Runnable {
    override fun run() {
        val snapshot = configManager.snapshot ?: return
        val navigation = snapshot.main.navigation
        if (!navigation.actionbarEnabled) return
        if (!snapshot.activity.enabled) return

        val entities = entityManager.activeEntities().mapNotNull { entity ->
            val worldUid = entity.worldUid ?: return@mapNotNull null
            val location = entity.currentLocation
            NavigationEntitySnapshot(
                worldUid = worldUid,
                x = location.x,
                y = location.y,
                z = location.z,
                blockX = location.blockX,
                blockY = location.blockY,
                blockZ = location.blockZ,
                roll = entity.roll
            )
        }
        if (entities.isEmpty()) return

        for (playerId in playerSnapshots.trackedIds()) {
            scheduler.runForPlayer(playerId) { player ->
                if (!player.isOnline) return@runForPlayer
                val playerLocation = player.location
                val playerWorld = player.world

                val nearest = entities
                    .asSequence()
                    .filter { entity -> entity.worldUid == playerWorld.uid }
                    .map { entity ->
                        entity to distanceSquared(playerLocation.x, playerLocation.y, playerLocation.z, entity.x, entity.y, entity.z)
                    }
                    .minByOrNull { it.second }

                if (nearest == null) {
                    sendEmpty(player, snapshot.lang)
                    return@runForPlayer
                }

                val distance = sqrt(nearest.second)
                if (navigation.maxDistance > 0.0 && distance > navigation.maxDistance) {
                    sendEmpty(player, snapshot.lang)
                    return@runForPlayer
                }

                val entity = nearest.first
                val values = placeholderService.build(
                    lang = snapshot.lang,
                    player = player,
                    roll = entity.roll,
                    location = null,
                    extra = mapOf(
                        "distance" to formatDistance(distance),
                        "world" to playerWorld.name,
                        "x" to entity.blockX.toString(),
                        "y" to entity.blockY.toString(),
                        "z" to entity.blockZ.toString(),
                        "direction" to directionText(
                            playerX = playerLocation.x,
                            playerZ = playerLocation.z,
                            playerYaw = playerLocation.yaw.toDouble(),
                            targetX = entity.x,
                            targetZ = entity.z,
                            lang = snapshot.lang
                        )
                    )
                )
                langService.actionBar(player, snapshot.lang, LangKeys.NAVIGATION_ACTIONBAR, values)
            }
        }
    }

    private fun sendEmpty(player: Player, lang: LangConfig) {
        langService.actionBar(
            player = player,
            lang = lang,
            key = LangKeys.NAVIGATION_ACTIONBAR_EMPTY,
            values = placeholderService.build(lang, player, null, null)
        )
    }

    private fun formatDistance(distance: Double): String {
        return if (distance >= 100.0) {
            distance.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", distance)
        }
    }

    private fun distanceSquared(fromX: Double, fromY: Double, fromZ: Double, toX: Double, toY: Double, toZ: Double): Double {
        val dx = fromX - toX
        val dy = fromY - toY
        val dz = fromZ - toZ
        return dx * dx + dy * dy + dz * dz
    }

    private fun directionText(
        playerX: Double,
        playerZ: Double,
        playerYaw: Double,
        targetX: Double,
        targetZ: Double,
        lang: LangConfig
    ): String {
        val dx = targetX - playerX
        val dz = targetZ - playerZ

        val yaw = Math.toRadians(playerYaw)
        val forwardX = -sin(yaw)
        val forwardZ = cos(yaw)
        val rightX = cos(yaw)
        val rightZ = sin(yaw)

        val forward = dx * forwardX + dz * forwardZ
        val right = dx * rightX + dz * rightZ

        val key = when {
            forward >= 0.0 && abs(right) < forward * 0.5 -> LangKeys.DIRECTION_FRONT
            forward < 0.0 && abs(right) < -forward * 0.5 -> LangKeys.DIRECTION_BACK
            right > 0.0 && abs(forward) < right * 0.5 -> LangKeys.DIRECTION_RIGHT
            right < 0.0 && abs(forward) < -right * 0.5 -> LangKeys.DIRECTION_LEFT
            forward >= 0.0 && right > 0.0 -> LangKeys.DIRECTION_FRONT_RIGHT
            forward >= 0.0 && right < 0.0 -> LangKeys.DIRECTION_FRONT_LEFT
            forward < 0.0 && right > 0.0 -> LangKeys.DIRECTION_BACK_RIGHT
            else -> LangKeys.DIRECTION_BACK_LEFT
        }
        return lang.messages[key] ?: key
    }

    private data class NavigationEntitySnapshot(
        val worldUid: UUID,
        val x: Double,
        val y: Double,
        val z: Double,
        val blockX: Int,
        val blockY: Int,
        val blockZ: Int,
        val roll: RewardRollResult
    )
}
