package ym.dropzone.entity

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ym.dropzone.config.ConfigManager
import ym.dropzone.config.RuntimeConfigSnapshot
import ym.dropzone.head.HeadFactory
import ym.dropzone.packet.PacketEntityAdapter
import ym.dropzone.reward.RewardExecutor
import ym.dropzone.reward.RewardRollResult
import ym.dropzone.reward.RewardSelector
import ym.dropzone.scheduler.SchedulerAdapter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class DropZoneEntityManager(
    private val plugin: Plugin,
    private val configManager: ConfigManager,
    private val scheduler: SchedulerAdapter,
    private val packetAdapter: PacketEntityAdapter,
    private val rewardSelector: RewardSelector,
    private val rewardExecutor: RewardExecutor,
    private val headFactory: HeadFactory
) {
    private val entities = ConcurrentHashMap<UUID, DropZoneEntity>()
    private val nextRuntimeId = AtomicInteger(900_000)

    fun activeCount(): Int = entities.size
    fun activeEntities(): List<DropZoneEntity> = entities.values.toList()

    fun createAt(location: Location, snapshot: RuntimeConfigSnapshot): DropZoneEntity? {
        // 生成阶段先完成奖励和头颅抽取，再创建客户端假实体数据。
        if (entities.size >= snapshot.main.spawn.maxActive) return null
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
            // Folia 下把实体状态推进放回实体所在 region，避免跨区直接访问 Bukkit 对象。
            scheduler.runAt(entity.currentLocation) {
                if (entities.containsKey(entity.id)) updateEntity(entity, snapshot)
            }
        }
    }

    fun updateViewers() {
        val snapshot = configManager.snapshot ?: return
        val viewDistanceSquared = snapshot.main.fakeEntity.viewDistance * snapshot.main.fakeEntity.viewDistance
        for (entity in entities.values.toList()) {
            val currentWorld = entity.currentLocation.world ?: continue
            for (player in Bukkit.getOnlinePlayers().toList()) {
                scheduler.runForPlayer(player) {
                    if (!entities.containsKey(entity.id)) return@runForPlayer
                if (!player.isOnline || player.world.uid != currentWorld.uid) {
                    hide(player, entity)
                    return@runForPlayer
                }
                val visible = player.location.distanceSquared(entity.currentLocation) <= viewDistanceSquared
                if (visible && entity.visibleTo.add(player.uniqueId)) {
                    packetAdapter.spawnItemEntity(player, entity)
                } else if (visible) {
                    packetAdapter.updateEntity(player, entity)
                } else if (!visible) {
                    hide(player, entity)
                }
                }
            }
        }
    }

    fun clearAll(): Int {
        val removed = entities.size
        entities.values.toList().forEach { remove(it, destroy = true) }
        return removed
    }

    fun handleQuit(player: Player) {
        entities.values.forEach { entity ->
            entity.visibleTo.remove(player.uniqueId)
            if (entity.lockedPlayer == player.uniqueId) {
                entity.lockedPlayer = null
                entity.state.compareAndSet(DropZoneEntityState.ATTRACTING, DropZoneEntityState.WAITING)
            }
        }
    }

    private fun updateEntity(entity: DropZoneEntity, snapshot: RuntimeConfigSnapshot) {
        // 每次 tick 推进旋转、漂浮和吸附；领取状态用 AtomicBoolean 防重复发奖。
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
        entity.lockedPlayer = target.uniqueId
        entity.state.set(DropZoneEntityState.ATTRACTING)
        val targetLocation = target.location.add(0.0, 1.0, 0.0)
        val current = entity.currentLocation.clone()
        val dx = targetLocation.x - current.x
        val dy = targetLocation.y - current.y
        val dz = targetLocation.z - current.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (distance <= fake.pickupDistance && entity.markClaiming()) {
            claim(entity, target, snapshot)
            return
        }
        if (distance > 0.001) {
            val step = fake.flySpeed.coerceAtMost(distance)
            entity.currentLocation = current.add(dx / distance * step, dy / distance * step, dz / distance * step)
        }
    }

    private fun findTarget(entity: DropZoneEntity, attractDistanceSquared: Double): Player? {
        val locked = entity.lockedPlayer?.let { Bukkit.getPlayer(it) }
        if (locked != null && locked.isOnline && locked.world.uid == entity.currentLocation.world?.uid &&
            locked.location.distanceSquared(entity.currentLocation) <= attractDistanceSquared * 2.25
        ) return locked
        return Bukkit.getOnlinePlayers()
            .asSequence()
            .filter { it.isOnline && it.world.uid == entity.currentLocation.world?.uid }
            .map { it to it.location.distanceSquared(entity.currentLocation) }
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
        // 先从 active map 移除并销毁假实体，再执行奖励，避免同一奖励点重复领取。
        entity.state.set(DropZoneEntityState.CLAIMED)
        remove(entity, destroy = true)
        rewardExecutor.execute(player, entity.roll, snapshot, entity.currentLocation.clone())
    }

    private fun hide(player: Player, entity: DropZoneEntity) {
        if (entity.visibleTo.remove(player.uniqueId)) {
            packetAdapter.destroyEntity(player, entity.runtimeEntityId)
        }
    }

    private fun remove(entity: DropZoneEntity, destroy: Boolean) {
        entities.remove(entity.id)
        if (destroy) {
            val viewers = entity.visibleTo.toList()
            entity.visibleTo.clear()
            viewers.mapNotNull { Bukkit.getPlayer(it) }.forEach {
                packetAdapter.destroyEntity(it, entity.runtimeEntityId)
            }
        }
    }
}
