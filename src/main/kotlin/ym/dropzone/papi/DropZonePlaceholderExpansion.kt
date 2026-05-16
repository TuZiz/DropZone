package ym.dropzone.papi

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ym.dropzone.config.ConfigManager
import ym.dropzone.config.RuntimeConfigSnapshot
import ym.dropzone.entity.DropZoneEntity
import ym.dropzone.entity.DropZoneEntityManager
import ym.dropzone.player.PlayerSnapshotService
import java.util.Locale
import kotlin.math.sqrt

class DropZonePlaceholderExpansion(
    private val plugin: JavaPlugin,
    private val configManager: ConfigManager,
    private val entityManager: DropZoneEntityManager,
    private val playerSnapshots: PlayerSnapshotService
) : PlaceholderExpansion() {
    override fun getIdentifier(): String = "dropzone"

    override fun getAuthor(): String = plugin.description.authors.joinToString(", ").ifBlank { "DropZone" }

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun onPlaceholderRequest(player: Player?, params: String): String {
        val snapshot = configManager.snapshot
        val key = params.lowercase(Locale.ROOT)
        if (snapshot == null) {
            return when (key) {
                "loaded" -> "0"
                "status" -> "loading"
                "active", "active_count" -> entityManager.activeCount().toString()
                else -> ""
            }
        }

        return when (key) {
            "loaded" -> "1"
            "status" -> if (snapshot.activity.enabled) "enabled" else "disabled"
            "activity" -> snapshot.activity.id
            "activity_name" -> plain(snapshot.activity.displayName)
            "activity_name_raw" -> snapshot.activity.displayName
            "activity_enabled" -> snapshot.activity.enabled.toString()
            "active", "active_count" -> entityManager.activeCount().toString()
            "max_active" -> snapshot.main.spawn.maxActive.toString()
            "remaining_slots" -> (snapshot.main.spawn.maxActive - entityManager.activeCount()).coerceAtLeast(0).toString()
            "spawn_enabled" -> snapshot.main.spawn.enabled.toString()
            "spawn_interval", "spawn_interval_seconds" -> snapshot.main.spawn.intervalSeconds.toString()
            "despawn_seconds" -> snapshot.main.spawn.despawnSeconds.toString()
            "world" -> snapshot.main.spawnRegion.world
            "selection_mode" -> snapshot.main.rewardSelectionMode.name
            "language" -> snapshot.main.language
            "rarities", "rarity_count" -> snapshot.rarities.size.toString()
            "usable_rarities", "usable_rarity_count" -> snapshot.usableRarities().size.toString()
            "heads", "head_count" -> snapshot.heads.size.toString()
            "rewards", "reward_count" -> snapshot.rewards.size.toString()
            "activities", "activity_count" -> configManager.activityNames.size.toString()
            "view_distance" -> number(snapshot.main.fakeEntity.viewDistance)
            "attract_distance" -> number(snapshot.main.fakeEntity.attractDistance)
            "pickup_distance" -> number(snapshot.main.fakeEntity.pickupDistance)
            else -> nearestPlaceholder(player, key, snapshot)
        }
    }

    private fun nearestPlaceholder(player: Player?, key: String, snapshot: RuntimeConfigSnapshot): String {
        if (player == null || !key.startsWith("nearest_")) return ""
        val nearest = nearestEntity(player) ?: return ""
        val entity = nearest.entity
        return when (key) {
            "nearest_id" -> entity.id.toString()
            "nearest_state" -> entity.state.get().name
            "nearest_distance" -> number(nearest.distance)
            "nearest_world" -> entity.currentLocation.world?.name.orEmpty()
            "nearest_x" -> number(entity.currentLocation.x)
            "nearest_y" -> number(entity.currentLocation.y)
            "nearest_z" -> number(entity.currentLocation.z)
            "nearest_reward" -> plain(entity.roll.reward.displayName)
            "nearest_reward_raw" -> entity.roll.reward.displayName
            "nearest_reward_id" -> entity.roll.reward.id
            "nearest_rarity" -> plain(entity.roll.rarity.displayName)
            "nearest_rarity_raw" -> entity.roll.rarity.displayName
            "nearest_rarity_id" -> entity.roll.rarity.id
            "nearest_head" -> plain(entity.roll.head.displayName)
            "nearest_head_raw" -> entity.roll.head.displayName
            "nearest_head_id" -> entity.roll.head.id
            "nearest_visible" -> (nearest.distance <= snapshot.main.fakeEntity.viewDistance).toString()
            "nearest_attractable" -> (nearest.distance <= snapshot.main.fakeEntity.attractDistance).toString()
            else -> ""
        }
    }

    private fun nearestEntity(player: Player): NearestEntity? {
        val playerSnapshot = playerSnapshots.get(player.uniqueId) ?: return null
        return entityManager.activeEntities()
            .asSequence()
            .filter { it.worldUid == playerSnapshot.worldUid }
            .map { entity ->
                val current = entity.currentLocation
                val distanceSquared = playerSnapshot.distanceSquared(current.x, current.y, current.z)
                NearestEntity(entity, sqrt(distanceSquared))
            }
            .minByOrNull { it.distance }
    }

    private fun plain(text: String): String {
        return MINI_TAG.replace(text, "")
    }

    private fun number(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private data class NearestEntity(
        val entity: DropZoneEntity,
        val distance: Double
    )

    private companion object {
        private val MINI_TAG = Regex("<[^>]+>")
    }
}
