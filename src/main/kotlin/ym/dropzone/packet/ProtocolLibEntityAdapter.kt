package ym.dropzone.packet

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.wrappers.EnumWrappers
import com.comphenix.protocol.wrappers.Pair
import com.comphenix.protocol.wrappers.Vector3F
import com.comphenix.protocol.wrappers.WrappedDataValue
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import com.comphenix.protocol.wrappers.WrappedWatchableObject
import com.comphenix.protocol.wrappers.WrappedChatComponent
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ym.dropzone.entity.DropZoneEntity
import ym.dropzone.util.PlainTextUtil
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

class ProtocolLibEntityAdapter(
    private val plugin: JavaPlugin,
    private val debugPackets: () -> Boolean,
    private val debugVisibleArmorStand: () -> Boolean
) : PacketEntityAdapter {
    private val sentLocations = ConcurrentHashMap<Int, Location>()

    override fun spawnItemEntity(player: Player, entity: DropZoneEntity) {
        val loc = armorStandLocation(entity.currentLocation)
        sentLocations[entity.runtimeEntityId] = loc.clone()
        if (debugPackets()) {
            player.server.logger.info(
                "[DropZone] ProtocolLib spawnArmorStandHead: player=${player.name}, " +
                    "entityId=${entity.runtimeEntityId}, " +
                    "item=${entity.itemStack.type}, " +
                    "amount=${entity.itemStack.amount}, " +
                    "loc=${loc.world?.name} ${loc.x},${loc.y},${loc.z}"
            )
        }

        send(player, spawnPacket(entity, loc), "spawn", logSuccess = true)
        send(player, equipmentPacket(entity), "equipment", logSuccess = true)
        send(player, headStaticMetadataPacket(entity), "metadata-static", logSuccess = true)
        send(player, headPoseMetadataPacket(entity), "metadata-head-pose", logSuccess = true)
    }

    override fun updateEntity(player: Player, entity: DropZoneEntity) {
        val loc = armorStandLocation(entity.currentLocation)
        val packet = relativeMoveLookPacket(entity, loc) ?: return
        send(player, packet, "relative-move")
        sentLocations[entity.runtimeEntityId] = loc.clone()
    }

    override fun destroyEntity(player: Player, entityId: Int) {
        destroyEntities(player, listOf(entityId))
    }

    override fun destroyEntities(player: Player, entityIds: Collection<Int>) {
        if (entityIds.isEmpty()) return
        entityIds.forEach { sentLocations.remove(it) }
        send(player, destroyPacket(entityIds), "destroy", logSuccess = true)
    }

    private fun spawnPacket(entity: DropZoneEntity, loc: Location): PacketContainer {
        val packet = PacketContainer(PacketType.Play.Server.SPAWN_ENTITY)
        packet.modifier.writeDefaults()
        packet.integers.write(0, entity.runtimeEntityId)
        packet.integers.writeSafely(6, 0)
        packet.uuiDs.write(0, entity.id)
        packet.entityTypeModifier.write(0, EntityType.ARMOR_STAND)
        packet.doubles.write(0, loc.x)
        packet.doubles.write(1, loc.y)
        packet.doubles.write(2, loc.z)
        packet.bytes.writeSafely(0, angle(entity.yaw.toDouble()))
        packet.bytes.writeSafely(1, angle(0.0))
        packet.bytes.writeSafely(2, angle(0.0))
        return packet
    }

    private fun equipmentPacket(entity: DropZoneEntity): PacketContainer {
        val packet = PacketContainer(PacketType.Play.Server.ENTITY_EQUIPMENT)
        packet.modifier.writeDefaults()
        packet.integers.write(0, entity.runtimeEntityId)
        packet.slotStackPairLists.write(0, listOf(Pair(EnumWrappers.ItemSlot.HEAD, entity.itemStack)))
        return packet
    }

    private fun headStaticMetadataPacket(entity: DropZoneEntity): PacketContainer {
        val baseFlags = when {
            debugVisibleArmorStand() -> if (entity.glowing) ENTITY_FLAG_GLOWING else 0x00.toByte()
            entity.glowing -> (ENTITY_FLAG_INVISIBLE.toInt() or ENTITY_FLAG_GLOWING.toInt()).toByte()
            else -> ENTITY_FLAG_INVISIBLE
        }
        return metadataPacket(
            entity.runtimeEntityId,
            listOf(
                metadataByte(0, baseFlags),
                metadataOptionalChatComponent(2, Optional.of(WrappedChatComponent.fromLegacyText(hologramText(entity)))),
                metadataBoolean(3, true),
                metadataBoolean(5, true),
                metadataByte(15, ARMOR_STAND_FLAG_SMALL_MARKER)
            )
        )
    }

    private fun headPoseMetadataPacket(entity: DropZoneEntity): PacketContainer {
        return metadataPacket(
            entity.runtimeEntityId,
            listOf(
                metadataVector3F(16, Vector3F(0.0f, 0.0f, 0.0f))
            )
        )
    }

    private fun metadataPacket(entityId: Int, entries: List<MetadataEntry>): PacketContainer {
        val packet = PacketContainer(PacketType.Play.Server.ENTITY_METADATA)
        packet.modifier.writeDefaults()
        packet.integers.write(0, entityId)
        if (!useLegacyMetadataValues() && packet.dataValueCollectionModifier.size() > 0) {
            packet.dataValueCollectionModifier.write(
                0,
                entries.map { WrappedDataValue.fromWrappedValue(it.index, it.serializer, it.value) }
            )
        } else if (packet.watchableCollectionModifier.size() > 0) {
            packet.watchableCollectionModifier.write(
                0,
                entries.map {
                    WrappedWatchableObject(
                        WrappedDataWatcher.WrappedDataWatcherObject(it.index, it.serializer),
                        it.value
                    ).apply {
                        setDirtyState(true)
                    }
                }
            )
        }
        return packet
    }

    private fun relativeMoveLookPacket(entity: DropZoneEntity, nextLocation: Location): PacketContainer? {
        val previous = sentLocations[entity.runtimeEntityId] ?: return null
        val packet = PacketContainer(PacketType.Play.Server.REL_ENTITY_MOVE_LOOK)
        packet.modifier.writeDefaults()
        if (packet.integers.size() <= 0 || packet.shorts.size() < 3) return null
        packet.integers.write(0, entity.runtimeEntityId)
        packet.shorts.write(0, relativeDelta(nextLocation.x - previous.x))
        packet.shorts.write(1, relativeDelta(nextLocation.y - previous.y))
        packet.shorts.write(2, relativeDelta(nextLocation.z - previous.z))
        if (packet.bytes.size() >= 2) {
            packet.bytes.write(0, angle(entity.yaw.toDouble()))
            packet.bytes.write(1, angle(0.0))
        }
        if (packet.booleans.size() >= 1) {
            packet.booleans.write(0, false)
        }
        return packet
    }

    private fun destroyPacket(entityIds: Collection<Int>): PacketContainer {
        val packet = PacketContainer(PacketType.Play.Server.ENTITY_DESTROY)
        packet.modifier.writeDefaults()
        if (packet.intLists.size() > 0) {
            packet.intLists.write(0, entityIds.toList())
        } else {
            packet.integerArrays.writeSafely(0, entityIds.toIntArray())
        }
        return packet
    }

    private fun metadataByte(index: Int, value: Byte): MetadataEntry {
        return MetadataEntry(index, WrappedDataWatcher.Registry.get(Byte::class.javaObjectType), value)
    }

    private fun metadataBoolean(index: Int, value: Boolean): MetadataEntry {
        return MetadataEntry(index, WrappedDataWatcher.Registry.get(Boolean::class.javaObjectType), value)
    }

    private fun metadataVector3F(index: Int, value: Vector3F): MetadataEntry {
        return MetadataEntry(index, WrappedDataWatcher.Registry.getVectorSerializer(), value)
    }

    private fun metadataOptionalChatComponent(index: Int, value: Optional<WrappedChatComponent>): MetadataEntry {
        return MetadataEntry(index, WrappedDataWatcher.Registry.getChatComponentSerializer(true), value)
    }

    private fun armorStandLocation(location: Location): Location {
        return location.clone().add(0.0, -1.05, 0.0)
    }

    private fun angle(value: Double): Byte {
        return ((value % 360.0) * 256.0 / 360.0).toInt().toByte()
    }

    private fun relativeDelta(delta: Double): Short {
        return (delta * RELATIVE_MOVE_SCALE)
            .roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private fun hologramText(entity: DropZoneEntity): String {
        return PlainTextUtil.stripMiniMessage(entity.roll.reward.displayName).ifBlank { entity.roll.reward.id }
    }

    private fun useLegacyMetadataValues(): Boolean {
        return plugin.server.bukkitVersion.contains("1.18.2")
    }

    private fun send(player: Player, packet: PacketContainer, action: String, logSuccess: Boolean = false) {
        runCatching {
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet, false)
            if (logSuccess && debugPackets()) {
                plugin.logger.info(
                    "[DropZone] ProtocolLib packet $action sent: player=${player.name}, packet=${packet.type}"
                )
            }
        }.onFailure { error ->
            if (player.isOnline) {
                plugin.logger.warning(
                    "[DropZone] ProtocolLib send failed: player=${player.name}, " +
                        "packet=${packet.type}, " +
                        "error=${error.javaClass.simpleName}: ${error.message}"
                )
            }
        }
    }

    private data class MetadataEntry(
        val index: Int,
        val serializer: WrappedDataWatcher.Serializer,
        val value: Any
    )

    private companion object {
        const val ENTITY_FLAG_INVISIBLE: Byte = 0x20
        const val ENTITY_FLAG_GLOWING: Byte = 0x40
        const val ARMOR_STAND_FLAG_SMALL_MARKER: Byte = 0x11
        const val RELATIVE_MOVE_SCALE = 4096.0
    }
}
