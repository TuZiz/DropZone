package ym.dropzone.entity

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ym.dropzone.claim.ClaimDenyReason
import ym.dropzone.claim.ClaimTracker
import ym.dropzone.config.LangKeys
import ym.dropzone.config.RuntimeConfigSnapshot
import ym.dropzone.head.HeadFactory
import ym.dropzone.message.LangService
import ym.dropzone.message.PlaceholderService
import ym.dropzone.packet.PacketEntityAdapter
import ym.dropzone.player.PlayerPositionSnapshot
import ym.dropzone.player.PlayerSnapshotService
import ym.dropzone.reward.RewardExecutor
import ym.dropzone.reward.RewardSelector
import ym.dropzone.scheduler.SchedulerAdapter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin
import kotlin.math.sqrt

class DropZoneEntityManager(
    private val plugin: Plugin,
    private val configManager: ym.dropzone.config.ConfigManager,
    private val scheduler: SchedulerAdapter,
    private val packetAdapter: PacketEntityAdapter,
    private val playerSnapshots: PlayerSnapshotService,
    private val claimTracker: ClaimTracker,
    private val langService: LangService,
    private val placeholderService: PlaceholderService,
    private val rewardSelector: RewardSelector,
    private val rewardExecutor: RewardExecutor,
    private val headFactory: HeadFactory
) {
    private val entities = ConcurrentHashMap<UUID, DropZoneEntity>()
    private val nextRuntimeId = AtomicInteger(900_000)

    fun activeCount(): Int = entities.size
    fun activeEntities(): List<DropZoneEntity> = entities.values.toList()

    fun createAt(location: Location, snapshot: RuntimeConfigSnapshot): DropZoneEntity? {
        if (!plugin.isEnabled || entities.size >= snapshot.main.spawn.maxActive) return null
        val roll = rewardSelector.roll(snapshot) ?: return null
        val item = headFactory.create(roll.head)
        val entity = DropZoneEntity(
            id = UUID.randomUUID(),
            runtimeEntityId = nextRuntimeId.incrementAndGet(),
            roll = roll,
            itemStack = item,
            spawnLocation = location.clone(),
            expiresAtMillis = System.currentTimeMillis() + snapshot.main.spawn.despawnSeconds * 1000L,
            glowing = if (snapshot.main.fakeEntity.glowByRarity) roll.rarity.headGlow else snapshot.main.fakeEntity.defaultGlow
        )
        entities[entity.id] = entity
        return entity
    }

    fun tick() {
        val snapshot = configManager.snapshot ?: return
        val now = System.currentTimeMillis()
        for (entity in entities.values.toList()) {
            if (now >= entity.expiresAtMillis && entity.state.compareAndSet(DropZoneEntityState.WAITING, DropZoneEntityState.EXPIRED)) {
                scheduler.runAt(entity.currentLocation) { remove(entity, destroy = true) }
                continue
            }
            scheduler.runAt(entity.currentLocation) {
                if (entities.containsKey(entity.id)) updateEntity(entity, snapshot)
            }
        }
    }

    fun updateViewers() {
        val snapshot = configManager.snapshot ?: return
        val viewDistanceSquared = snapshot.main.fakeEntity.viewDistance * snapshot.main.fakeEntity.viewDistance
        val snapshotsByWorld = playerSnapshots.byWorld()
        for (entity in entities.values.toList()) {
            val worldUid = entity.worldUid ?: continue
            val current = entity.currentLocation
            val seen = mutableSetOf<UUID>()
            val worldSnapshots = snapshotsByWorld[worldUid].orEmpty()
            for (viewerSnapshot in worldSnapshots) {
                seen += viewerSnapshot.uuid
                val visible = viewerSnapshot.distanceSquared(current.x, current.y, current.z) <= viewDistanceSquared
                if (visible) {
                    showOrUpdate(viewerSnapshot.uuid, entity)
                } else {
                    hideViewer(viewerSnapshot.uuid, entity)
                }
            }
            entity.visibleTo.toList().forEach { viewerId ->
                if (viewerId !in seen) hideViewer(viewerId, entity)
            }
        }
    }

    fun clearAll(): Int {
        val removed = entities.size
        entities.values.toList().forEach { remove(it, destroy = true) }
        return removed
    }

    fun handleQuit(player: Player) {
        playerSnapshots.remove(player.uniqueId)
        entities.values.forEach { entity ->
            entity.visibleTo.remove(player.uniqueId)
            entity.ignoredUntil.remove(player.uniqueId)
            if (entity.lockedPlayer == player.uniqueId) {
                entity.lockedPlayer = null
                entity.state.compareAndSet(DropZoneEntityState.ATTRACTING, DropZoneEntityState.WAITING)
            }
        }
    }

    private fun updateEntity(entity: DropZoneEntity, snapshot: RuntimeConfigSnapshot) {
        val fake = snapshot.main.fakeEntity
        if (fake.rotate) {
            entity.yaw = ((entity.yaw + fake.rotationSpeed) % 360.0).toFloat()
        }
        val target = findTarget(entity, fake.attractDistance * fake.attractDistance)
        if (target == null) {
            entity.lockedPlayer = null
            entity.state.compareAndSet(DropZoneEntityState.ATTRACTING, DropZoneEntityState.WAITING)
            applyIdleMotion(entity, fake.bobbing, fake.bobbingHeight)
            return
        }
        entity.lockedPlayer = target.uuid
        entity.state.set(DropZoneEntityState.ATTRACTING)
        val current = entity.currentLocation.clone()
        val dx = target.x - current.x
        val dy = target.y + 1.0 - current.y
        val dz = target.z - current.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance <= fake.pickupDistance && entity.markClaiming()) {
            val handle = scheduler.runForPlayer(target.uuid) { player -> claim(entity, player, snapshot) }
            if (handle == null) {
                entity.releaseClaiming()
                entity.lockedPlayer = null
            }
            return
        }
        if (distance > 0.001) {
            val step = fake.flySpeed.coerceAtMost(distance)
            entity.currentLocation = current.add(dx / distance * step, dy / distance * step, dz / distance * step)
        }
    }

    private fun findTarget(entity: DropZoneEntity, attractDistanceSquared: Double): PlayerPositionSnapshot? {
        val now = System.currentTimeMillis()
        entity.ignoredUntil.entries.removeIf { it.value <= now }
        val worldUid = entity.worldUid ?: return null
        val current = entity.currentLocation
        val locked = entity.lockedPlayer?.let { playerSnapshots.get(it) }
        if (locked != null &&
            locked.worldUid == worldUid &&
            locked.uuid !in entity.ignoredUntil &&
            locked.distanceSquared(current.x, current.y, current.z) <= attractDistanceSquared * 2.25
        ) {
            return locked
        }
        return playerSnapshots.all()
            .asSequence()
            .filter { it.worldUid == worldUid && it.uuid !in entity.ignoredUntil }
            .map { it to it.distanceSquared(current.x, current.y, current.z) }
            .filter { it.second <= attractDistanceSquared }
            .minByOrNull { it.second }
            ?.first
    }

    private fun applyIdleMotion(entity: DropZoneEntity, bobbing: Boolean, height: Double) {
        if (!bobbing) return
        val age = (System.currentTimeMillis() / 250.0)
        val y = entity.spawnLocation.y + sin(age) * height
        entity.currentLocation = entity.currentLocation.clone().apply { this.y = y }
    }

    private fun claim(entity: DropZoneEntity, player: Player, snapshot: RuntimeConfigSnapshot) {
        if (!plugin.isEnabled || !player.isOnline || !entities.containsKey(entity.id)) {
            entity.releaseClaiming()
            entity.lockedPlayer = null
            return
        }
        val claimResult = claimTracker.tryClaim(player.uniqueId, snapshot.activity.id, entity.roll.reward.id, snapshot.activity.rules)
        if (!claimResult.allowed) {
            denyClaim(entity, player, snapshot, claimResult.denyReason, claimResult.remainingSeconds)
            return
        }
        entity.state.set(DropZoneEntityState.CLAIMED)
        remove(entity, destroy = true)
        rewardExecutor.execute(player, entity.roll, snapshot, entity.currentLocation.clone())
    }

    private fun denyClaim(entity: DropZoneEntity, player: Player, snapshot: RuntimeConfigSnapshot, reason: ClaimDenyReason?, seconds: Long) {
        val key = when (reason) {
            ClaimDenyReason.COOLDOWN -> LangKeys.CLAIM_DENIED_COOLDOWN
            ClaimDenyReason.MAX_CLAIMS -> LangKeys.CLAIM_DENIED_MAX_CLAIMS
            ClaimDenyReason.REPEAT_REWARD -> LangKeys.CLAIM_DENIED_REPEAT_REWARD
            null -> LangKeys.CLAIM_DENIED_MAX_CLAIMS
        }
        entity.ignoredUntil[player.uniqueId] = System.currentTimeMillis() + snapshot.main.claim.deniedIgnoreSeconds * 1000L
        entity.lockedPlayer = null
        entity.releaseClaiming()
        val values = placeholderService.build(snapshot.lang, player, entity.roll, entity.currentLocation, mapOf("seconds" to seconds.toString()))
        langService.send(player, snapshot.lang, key, values)
    }

    private fun showOrUpdate(playerId: UUID, entity: DropZoneEntity) {
        val firstView = entity.visibleTo.add(playerId)
        val handle = scheduler.runForPlayer(playerId) { player ->
            if (!player.isOnline || !entities.containsKey(entity.id)) {
                entity.visibleTo.remove(playerId)
                return@runForPlayer
            }
            if (firstView) {
                packetAdapter.spawnItemEntity(player, entity)
            } else {
                packetAdapter.updateEntity(player, entity)
            }
        }
        if (handle == null) entity.visibleTo.remove(playerId)
    }

    private fun hideViewer(playerId: UUID, entity: DropZoneEntity) {
        if (!entity.visibleTo.remove(playerId)) return
        destroyForViewer(playerId, entity.runtimeEntityId)
    }

    private fun remove(entity: DropZoneEntity, destroy: Boolean) {
        entities.remove(entity.id)
        if (destroy) {
            val viewers = entity.visibleTo.toList()
            entity.visibleTo.clear()
            viewers.forEach { destroyForViewer(it, entity.runtimeEntityId) }
        }
    }

    private fun destroyForViewer(playerId: UUID, entityId: Int) {
        scheduler.runForPlayer(playerId) { player ->
            if (player.isOnline) {
                packetAdapter.destroyEntity(player, entityId)
            }
        }
    }
}
