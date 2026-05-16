package ym.dropzone.packet

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.wrappers.EnumWrappers
import com.comphenix.protocol.wrappers.Pair
import com.comphenix.protocol.wrappers.WrappedDataValue
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ym.dropzone.entity.DropZoneEntity

class ProtocolLibEntityAdapter(
    private val plugin: JavaPlugin,
    private val debugPackets: () -> Boolean
) : PacketEntityAdapter {
    private val protocolManager get() = ProtocolLibrary.getProtocolManager()

    override fun spawnItemEntity(player: Player, entity: DropZoneEntity) {
        val loc = armorStandLocation(entity.currentLocation)
        if (debugPackets()) {
            player.server.logger.info(
                "[DropZone] ProtocolLib spawnArmorStandHead: player=${player.name}, " +
                    "entityId=${entity.runtimeEntityId}, " +
                    "item=${entity.itemStack.type}, " +
                    "amount=${entity.itemStack.amount}, " +
                    "loc=${loc.world?.name} ${loc.x},${loc.y},${loc.z}"
            )
        }

        send(player, spawnPacket(entity, loc), "spawn")
        send(player, equipmentPacket(entity), "equipment")
        send(player, metadataPacket(entity), "metadata")
    }

    override fun updateEntity(player: Player, entity: DropZoneEntity) {
        val loc = armorStandLocation(entity.currentLocation)
        send(player, teleportPacket(entity, loc), "teleport")
        send(player, metadataPacket(entity), "metadata")
    }

    override fun destroyEntity(player: Player, entityId: Int) {
        destroyEntities(player, listOf(entityId))
    }

    override fun destroyEntities(player: Player, entityIds: Collection<Int>) {
        if (entityIds.isEmpty()) return
        send(player, destroyPacket(entityIds), "destroy")
    }

    private fun spawnPacket(entity: DropZoneEntity, loc: Location): PacketContainer {
        return protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY).apply {
            getModifier().writeDefaults()
            getIntegers().writeSafely(0, entity.runtimeEntityId)
            getUUIDs().writeSafely(0, entity.id)
            getEntityTypeModifier().writeSafely(0, EntityType.ARMOR_STAND)
            getDoubles().writeSafely(0, loc.x)
            getDoubles().writeSafely(1, loc.y)
            getDoubles().writeSafely(2, loc.z)
            getBytes().writeSafely(0, angle(loc.pitch))
            getBytes().writeSafely(1, angle(loc.yaw))
            getBytes().writeSafely(2, angle(entity.yaw))
            getIntegers().writeSafely(1, 0)
        }
    }

    private fun equipmentPacket(entity: DropZoneEntity): PacketContainer {
        return protocolManager.createPacket(PacketType.Play.Server.ENTITY_EQUIPMENT).apply {
            getModifier().writeDefaults()
            getIntegers().writeSafely(0, entity.runtimeEntityId)
            val equipment = listOf(Pair(EnumWrappers.ItemSlot.HEAD, entity.itemStack))
            if (getSlotStackPairLists().size() > 0) {
                getSlotStackPairLists().write(0, equipment)
            } else {
                getItemSlots().writeSafely(0, EnumWrappers.ItemSlot.HEAD)
                getItemModifier().writeSafely(0, entity.itemStack)
            }
        }
    }

    private fun metadataPacket(entity: DropZoneEntity): PacketContainer {
        val baseFlags: Byte = if (entity.glowing) {
            (0x20 or 0x40).toByte()
        } else {
            0x20.toByte()
        }
        val dataValues = listOf(
            WrappedDataValue(0, WrappedDataWatcher.Registry.get(Byte::class.javaObjectType), baseFlags),
            WrappedDataValue(5, WrappedDataWatcher.Registry.get(Boolean::class.javaObjectType), true),
            WrappedDataValue(15, WrappedDataWatcher.Registry.get(Byte::class.javaObjectType), 0x11.toByte())
        )
        return protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA).apply {
            getModifier().writeDefaults()
            getIntegers().writeSafely(0, entity.runtimeEntityId)
            if (getDataValueCollectionModifier().size() > 0) {
                getDataValueCollectionModifier().write(0, dataValues)
            } else {
                val watcher = WrappedDataWatcher()
                watcher.setObject(0, WrappedDataWatcher.Registry.get(Byte::class.javaObjectType), baseFlags, true)
                watcher.setObject(5, WrappedDataWatcher.Registry.get(Boolean::class.javaObjectType), true, true)
                watcher.setObject(15, WrappedDataWatcher.Registry.get(Byte::class.javaObjectType), 0x11.toByte(), true)
                getWatchableCollectionModifier().writeSafely(0, watcher.watchableObjects)
            }
        }
    }

    private fun teleportPacket(entity: DropZoneEntity, loc: Location): PacketContainer {
        return protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT).apply {
            getModifier().writeDefaults()
            getIntegers().writeSafely(0, entity.runtimeEntityId)
            getDoubles().writeSafely(0, loc.x)
            getDoubles().writeSafely(1, loc.y)
            getDoubles().writeSafely(2, loc.z)
            getBytes().writeSafely(0, angle(entity.yaw))
            getBytes().writeSafely(1, angle(loc.pitch))
            getBooleans().writeSafely(0, false)
        }
    }

    private fun destroyPacket(entityIds: Collection<Int>): PacketContainer {
        return protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY).apply {
            getModifier().writeDefaults()
            if (getIntLists().size() > 0) {
                getIntLists().write(0, entityIds.toList())
            } else {
                getIntegerArrays().writeSafely(0, entityIds.toIntArray())
            }
        }
    }

    private fun armorStandLocation(location: Location): Location {
        return location.clone().add(0.0, -1.35, 0.0)
    }

    private fun angle(value: Float): Byte {
        return (value * 256.0f / 360.0f).toInt().toByte()
    }

    private fun send(player: Player, packet: PacketContainer, action: String) {
        runCatching {
            protocolManager.sendServerPacket(player, packet, false)
            if (debugPackets()) {
                plugin.logger.info(
                    "[DropZone] ProtocolLib packet $action sent: player=${player.name}, packet=${packet.type}"
                )
            }
        }.onFailure { error ->
            plugin.logger.warning(
                "[DropZone] ProtocolLib send failed: player=${player.name}, " +
                    "packet=${packet.type}, " +
                    "error=${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }
}
