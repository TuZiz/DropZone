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
import ym.dropzone.storage.MysqlClaimDenyReason
import ym.dropzone.storage.MysqlClaimRequest
import ym.dropzone.storage.MysqlStorage
import ym.dropzone.storage.SpawnPointState
import ym.dropzone.storage.StoredSpawnPoint
import ym.dropzone.util.ParticleUtil
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.CompletableFuture
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
    private val headFactory: HeadFactory,
    private val mysqlStorage: MysqlStorage? = null
) {
    private val entities = ConcurrentHashMap<UUID, DropZoneEntity>()
    private val nextRuntimeId = AtomicInteger(-1)

    fun activeCount(): Int = entities.size
    fun activeEntities(): List<DropZoneEntity> = entities.values.toList()

    fun revealTo(playerId: UUID, entity: DropZoneEntity) {
        if (entities.containsKey(entity.id)) {
            showOrUpdate(playerId, entity)
        }
    }

    fun revealToNearbyPlayersLive(entity: DropZoneEntity, snapshot: RuntimeConfigSnapshot) {
        val entityWorldUid = entity.worldUid ?: return
        val entityLoc = entity.currentLocation.clone()
        val viewDistanceSquared = snapshot.main.fakeEntity.viewDistance * snapshot.main.fakeEntity.viewDistance
        playerSnapshots.trackedIds().forEach { playerId ->
            scheduler.runForPlayer(playerId) { player ->
                if (!player.isOnline) return@runForPlayer
                if (player.world.uid != entityWorldUid) return@runForPlayer
                if (player.location.distanceSquared(entityLoc) <= viewDistanceSquared) {
                    revealTo(player.uniqueId, entity)
                }
            }
        }
    }

    fun playSpawnMarker(entity: DropZoneEntity, snapshot: RuntimeConfigSnapshot) {
        val particle = ParticleUtil.parse(snapshot.main.effects.idleParticle.name)
            ?: ParticleUtil.parse("VILLAGER_HAPPY")
            ?: return
        val loc = entity.currentLocation
        loc.world?.spawnParticle(
            particle,
            loc.clone().add(0.0, 0.35, 0.0),
            (snapshot.main.effects.idleParticle.count * 6).coerceAtLeast(12),
            0.35,
            0.45,
            0.35,
            0.02
        )
    }

    fun createAt(location: Location, snapshot: RuntimeConfigSnapshot): DropZoneEntity? {
        if (!plugin.isEnabled || entities.size >= snapshot.main.spawn.maxActive) return null
        val roll = rewardSelector.roll(snapshot) ?: return null
        return createRuntimeEntity(UUID.randomUUID(), location, snapshot, roll.rarity.id, roll.head.id, roll.reward.id)
    }

    fun createAtAsync(location: Location, snapshot: RuntimeConfigSnapshot): CompletableFuture<DropZoneEntity?> {
        val storage = mysqlStorage ?: return CompletableFuture.completedFuture(createAt(location, snapshot))
        if (!plugin.isEnabled || entities.size >= snapshot.main.spawn.maxActive) return CompletableFuture.completedFuture(null)
        val roll = rewardSelector.roll(snapshot) ?: return CompletableFuture.completedFuture(null)
        val id = UUID.randomUUID()
        val now = System.currentTimeMillis()
        val point = StoredSpawnPoint(
            id = id,
            activityId = snapshot.activity.id,
            serverGroup = snapshot.main.server.group,
            worldName = location.world?.name ?: snapshot.activity.spawnRegion.world,
            worldUuid = location.world?.uid?.toString(),
            x = location.x,
            y = location.y,
            z = location.z,
            rarityId = roll.rarity.id,
            headId = roll.head.id,
            rewardId = roll.reward.id,
            state = SpawnPointState.WAITING,
            createdAt = now,
            expiresAt = now + snapshot.main.spawn.despawnSeconds * 1000L,
            createdByServer = snapshot.main.server.id,
            version = 1L
        )
        return storage.createSpawnPoint(point).thenCompose { created ->
            val future = CompletableFuture<DropZoneEntity?>()
            if (!created) {
                future.complete(null)
                return@thenCompose future
            }
            scheduler.runAt(location) {
                val entity = createRuntimeEntity(id, location, snapshot, roll.rarity.id, roll.head.id, roll.reward.id, point.expiresAt)
                future.complete(entity)
            }
            future
        }
    }

    private fun createRuntimeEntity(
        id: UUID,
        location: Location,
        snapshot: RuntimeConfigSnapshot,
        rarityId: String,
        headId: String,
        rewardId: String,
        expiresAtMillis: Long = System.currentTimeMillis() + snapshot.main.spawn.despawnSeconds * 1000L
    ): DropZoneEntity? {
        if (!plugin.isEnabled || entities.size >= snapshot.main.spawn.maxActive) return null
        val rarity = snapshot.rarities[rarityId] ?: return null
        val head = snapshot.heads[headId] ?: return null
        val reward = snapshot.rewards[rewardId] ?: return null
        val roll = ym.dropzone.reward.RewardRollResult(rarity, reward, head)
        val item = headFactory.create(roll.head)
        val entity = DropZoneEntity(
            id = id,
            runtimeEntityId = nextRuntimeId.getAndDecrement(),
            roll = roll,
            itemStack = item,
            spawnLocation = location.clone(),
            expiresAtMillis = expiresAtMillis,
            glowing = if (snapshot.main.fakeEntity.glowByRarity) roll.rarity.headGlow else snapshot.main.fakeEntity.defaultGlow
        )
        entities[entity.id] = entity
        return entity
    }

    fun syncFromDatabase(snapshot: RuntimeConfigSnapshot): CompletableFuture<Int> {
        val storage = mysqlStorage ?: return CompletableFuture.completedFuture(0)
        return storage.loadWaitingSpawns(snapshot.activity.id, snapshot.main.server.group).thenCompose { points ->
            val wanted = points.map { it.id }.toSet()
            entities.values
                .filter { it.id !in wanted }
                .forEach { scheduler.runAt(it.currentLocation) { remove(it, destroy = true) } }
            val future = CompletableFuture<Int>()
            scheduler.runGlobal {
                var added = 0
                points.filter { !entities.containsKey(it.id) }.forEach { point ->
                    val world = Bukkit.getWorld(point.worldName) ?: return@forEach
                    val location = Location(world, point.x, point.y, point.z)
                    scheduler.runAt(location) {
                        if (!entities.containsKey(point.id) && createRuntimeEntity(point.id, location, snapshot, point.rarityId, point.headId, point.rewardId, point.expiresAt) != null) {
                            added += 1
                        }
                    }
                }
                future.complete(added)
            }
            future
        }
    }

    fun tick() {
        val snapshot = configManager.snapshot ?: return
        val now = System.currentTimeMillis()
        for (entity in entities.values.toList()) {
            if (now >= entity.expiresAtMillis && entity.state.compareAndSet(DropZoneEntityState.WAITING, DropZoneEntityState.EXPIRED)) {
                mysqlStorage?.markExpired(entity.id)?.whenComplete { _, error ->
                    if (error != null) {
                        plugin.logger.warning("DropZone MySQL expire failed: server=${configManager.snapshot?.main?.server?.id}, activity=${snapshot.activity.id}, spawnId=${entity.id}, rewardId=${entity.roll.reward.id}, error=${error.message ?: error.javaClass.simpleName}")
                    }
                }
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
                } else if (viewerSnapshot.uuid in entity.visibleTo) {
                    validateViewerLive(viewerSnapshot.uuid, entity, worldUid, current.clone(), viewDistanceSquared, snapshot)
                }
            }
            entity.visibleTo.toList().forEach { viewerId ->
                if (viewerId !in seen) {
                    validateViewerLive(viewerId, entity, worldUid, current.clone(), viewDistanceSquared, snapshot)
                }
            }
        }
    }

    fun clearAll(): Int {
        val removed = entities.size
        entities.values.toList().forEach { remove(it, destroy = true) }
        return removed
    }

    fun clearAllPersistent(snapshot: RuntimeConfigSnapshot): CompletableFuture<Int> {
        val storage = mysqlStorage ?: return CompletableFuture.completedFuture(clearAll())
        return storage.clearActive(snapshot.activity.id, snapshot.main.server.group).whenComplete { _, error ->
            if (error == null) scheduler.runGlobal { clearAll() }
        }
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
            playIdleParticle(entity, snapshot)
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
        playIdleParticle(entity, snapshot)
    }

    private fun playIdleParticle(entity: DropZoneEntity, snapshot: RuntimeConfigSnapshot) {
        val effect = snapshot.main.effects.idleParticle
        if (!effect.enabled || effect.count <= 0) return
        val now = System.currentTimeMillis()
        if (now < entity.nextIdleParticleAtMillis) return
        entity.nextIdleParticleAtMillis = now + effect.intervalTicks * 50L
        val particle = ParticleUtil.parse(effect.name)
        if (particle == null) {
            if (snapshot.main.debug) {
                plugin.logger.warning(snapshot.lang.format(LangKeys.CONSOLE_INVALID_PARTICLE, mapOf("name" to effect.name)))
            }
            return
        }
        val loc = entity.currentLocation
        loc.world?.spawnParticle(
            particle,
            loc.clone().add(0.0, 0.35, 0.0),
            effect.count,
            effect.offsetX,
            effect.offsetY,
            effect.offsetZ,
            effect.speed
        )
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
        val storage = mysqlStorage
        if (storage != null) {
            claimPersistent(storage, entity, player, snapshot)
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

    private fun claimPersistent(storage: MysqlStorage, entity: DropZoneEntity, player: Player, snapshot: RuntimeConfigSnapshot) {
        val values = placeholderService.build(snapshot.lang, player, entity.roll, entity.currentLocation)
        val request = MysqlClaimRequest(
            spawnId = entity.id,
            activityId = snapshot.activity.id,
            serverGroup = snapshot.main.server.group,
            serverId = snapshot.main.server.id,
            playerUuid = player.uniqueId,
            playerName = player.name,
            rewardId = entity.roll.reward.id,
            rarityId = entity.roll.rarity.id,
            commands = entity.roll.reward.commands.map { placeholderService.apply(it, values).removePrefix("/") },
            rules = snapshot.activity.rules,
            maxAttempts = snapshot.main.rewardOutbox.maxAttempts
        )
        val playerId = player.uniqueId
        storage.claim(request).whenComplete { result, error ->
            scheduler.runForPlayer(playerId) { online ->
                if (!online.isOnline) return@runForPlayer
                if (error != null || result == null || !result.allowed) {
                    val reason = when (result?.denyReason) {
                        MysqlClaimDenyReason.COOLDOWN -> ClaimDenyReason.COOLDOWN
                        MysqlClaimDenyReason.MAX_CLAIMS -> ClaimDenyReason.MAX_CLAIMS
                        MysqlClaimDenyReason.REPEAT_REWARD -> ClaimDenyReason.REPEAT_REWARD
                        else -> null
                    }
                    if (result?.denyReason == MysqlClaimDenyReason.ALREADY_CLAIMED || result?.denyReason == MysqlClaimDenyReason.NOT_FOUND) {
                        langService.send(online, snapshot.lang, LangKeys.CLAIM_ALREADY_CLAIMED, values)
                    } else if (reason != null) {
                        denyClaim(entity, online, snapshot, reason, result.remainingSeconds)
                    } else {
                        entity.releaseClaiming()
                        entity.lockedPlayer = null
                        langService.send(online, snapshot.lang, LangKeys.CLAIM_FAILED, values)
                    }
                    return@runForPlayer
                }
                entity.state.set(DropZoneEntityState.CLAIMED)
                remove(entity, destroy = true)
                langService.send(online, snapshot.lang, LangKeys.CLAIM_SUCCESS, values)
                langService.send(online, snapshot.lang, LangKeys.REWARD_OUTBOX_PENDING, values)
                rewardExecutor.playClaimEffects(online, entity.roll, snapshot, entity.currentLocation.clone(), values)
            }
        }
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

    private fun validateViewerLive(
        playerId: UUID,
        entity: DropZoneEntity,
        worldUid: UUID,
        current: Location,
        viewDistanceSquared: Double,
        snapshot: RuntimeConfigSnapshot
    ) {
        val handle = scheduler.runForPlayer(playerId) { player ->
            if (!entities.containsKey(entity.id)) {
                entity.visibleTo.remove(playerId)
                return@runForPlayer
            }
            if (!player.isOnline) {
                entity.visibleTo.remove(playerId)
                return@runForPlayer
            }
            if (player.world.uid != worldUid) {
                destroyVisibleViewer(player, entity, snapshot, "OUT_OF_RANGE_OR_WORLD")
                return@runForPlayer
            }
            val stillVisible = player.location.distanceSquared(current) <= viewDistanceSquared
            if (stillVisible) {
                showOrUpdate(player.uniqueId, entity)
            } else {
                destroyVisibleViewer(player, entity, snapshot, "OUT_OF_RANGE_OR_WORLD")
            }
        }
        if (handle == null) {
            entity.visibleTo.remove(playerId)
        }
    }

    private fun destroyVisibleViewer(player: Player, entity: DropZoneEntity, snapshot: RuntimeConfigSnapshot, reason: String) {
        if (!entity.visibleTo.remove(player.uniqueId)) return
        if (snapshot.main.fakeEntity.debugPackets || snapshot.main.debug) {
            plugin.logger.info(
                "[DropZone] hideViewer: player=${player.name}, entity=${entity.runtimeEntityId}, reason=$reason"
            )
        }
        packetAdapter.destroyEntity(player, entity.runtimeEntityId)
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
