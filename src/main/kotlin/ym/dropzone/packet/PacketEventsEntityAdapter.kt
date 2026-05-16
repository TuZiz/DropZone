package ym.dropzone.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.Location
import org.bukkit.entity.Player
import ym.dropzone.entity.DropZoneEntity
import java.util.Optional

// PacketEvents 版本适配集中在这里，业务层只调用 PacketEntityAdapter。
class PacketEventsEntityAdapter(
    private val debugPackets: () -> Boolean
) : PacketEntityAdapter {
    override fun spawnItemEntity(player: Player, entity: DropZoneEntity) {
        val loc = armorStandLocation(entity.currentLocation)
        if (debugPackets()) {
            player.server.logger.info(
                "[DropZone] PacketEvents spawnArmorStandHead: player=${player.name}, " +
                    "entityId=${entity.runtimeEntityId}, item=${entity.itemStack.type}, loc=${loc.world?.name} ${loc.x},${loc.y},${loc.z}"
            )
        }
        // 只向指定玩家发送假盔甲架头颅，服务端不创建真实实体或掉落物。
        val spawn = WrapperPlayServerSpawnEntity(
            entity.runtimeEntityId,
            Optional.of(entity.id),
            EntityTypes.ARMOR_STAND,
            Vector3d(loc.x, loc.y, loc.z),
            loc.pitch,
            loc.yaw,
            entity.yaw,
            0,
            Optional.of(Vector3d.zero())
        )
        send(player, spawn)
        send(player, armorStandMetadata(entity))
        send(player, equipmentPacket(entity))
    }

    override fun updateEntity(player: Player, entity: DropZoneEntity) {
        val loc = armorStandLocation(entity.currentLocation)
        send(player, WrapperPlayServerEntityTeleport(entity.runtimeEntityId, Vector3d(loc.x, loc.y, loc.z), entity.yaw, loc.pitch, false))
        send(player, armorStandMetadata(entity))
    }

    override fun destroyEntity(player: Player, entityId: Int) {
        send(player, WrapperPlayServerDestroyEntities(entityId))
    }

    override fun destroyEntities(player: Player, entityIds: Collection<Int>) {
        if (entityIds.isEmpty()) return
        send(player, WrapperPlayServerDestroyEntities(*entityIds.toIntArray()))
    }

    private fun armorStandLocation(location: Location): Location {
        return location.clone().add(0.0, -1.35, 0.0)
    }

    private fun armorStandMetadata(entity: DropZoneEntity): WrapperPlayServerEntityMetadata {
        val baseFlags: Byte = if (entity.glowing) {
            (0x20 or 0x40).toByte()
        } else {
            0x20.toByte()
        }
        return WrapperPlayServerEntityMetadata(
            entity.runtimeEntityId,
            listOf(
                EntityData(0, EntityDataTypes.BYTE, baseFlags),
                EntityData(5, EntityDataTypes.BOOLEAN, true),
                EntityData(15, EntityDataTypes.BYTE, 0x11.toByte())
            )
        )
    }

    private fun equipmentPacket(entity: DropZoneEntity): WrapperPlayServerEntityEquipment {
        val packetItem = SpigotConversionUtil.fromBukkitItemStack(entity.itemStack)
        return WrapperPlayServerEntityEquipment(
            entity.runtimeEntityId,
            listOf(
                Equipment(EquipmentSlot.HELMET, packetItem)
            )
        )
    }

    private fun send(player: Player, packet: Any) {
        runCatching {
            PacketEvents.getAPI().playerManager.sendPacket(player, packet)
        }.onFailure { error ->
            player.server.logger.warning(
                "[DropZone] PacketEvents send failed: player=${player.name}, " +
                    "packet=${packet.javaClass.simpleName}, " +
                    "error=${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }
}
