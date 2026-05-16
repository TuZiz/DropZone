package ym.dropzone.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.ClientVersion
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.entity.Player
import ym.dropzone.entity.DropZoneEntity
import java.util.Optional

// PacketEvents 版本适配集中在这里，业务层只调用 PacketEntityAdapter。
class PacketEventsEntityAdapter : PacketEntityAdapter {
    override fun spawnItemEntity(player: Player, entity: DropZoneEntity) {
        val loc = entity.currentLocation
        // 只向指定玩家发送假物品实体，服务端不创建真实掉落物。
        val spawn = WrapperPlayServerSpawnEntity(
            entity.runtimeEntityId,
            Optional.of(entity.id),
            EntityTypes.ITEM,
            Vector3d(loc.x, loc.y, loc.z),
            loc.pitch,
            loc.yaw,
            entity.yaw,
            0,
            Optional.of(Vector3d.zero())
        )
        send(player, spawn)
        send(player, WrapperPlayServerEntityVelocity(entity.runtimeEntityId, Vector3d.zero()))
        send(player, metadata(player, entity))
    }

    override fun updateEntity(player: Player, entity: DropZoneEntity) {
        val loc = entity.currentLocation
        send(player, WrapperPlayServerEntityTeleport(entity.runtimeEntityId, Vector3d(loc.x, loc.y, loc.z), entity.yaw, loc.pitch, false))
        send(player, metadata(player, entity))
    }

    override fun destroyEntity(player: Player, entityId: Int) {
        send(player, WrapperPlayServerDestroyEntities(entityId))
    }

    override fun destroyEntities(player: Player, entityIds: Collection<Int>) {
        if (entityIds.isEmpty()) return
        send(player, WrapperPlayServerDestroyEntities(*entityIds.toIntArray()))
    }

    private fun metadata(player: Player, entity: DropZoneEntity): WrapperPlayServerEntityMetadata {
        val flags: Byte = if (entity.glowing) 0x40 else 0x00
        val packetItem = SpigotConversionUtil.fromBukkitItemStack(entity.itemStack)
        return WrapperPlayServerEntityMetadata(
            entity.runtimeEntityId,
            listOf(
                EntityData(0, EntityDataTypes.BYTE, flags),
                EntityData(5, EntityDataTypes.BOOLEAN, true),
                EntityData(itemStackMetadataIndex(player), EntityDataTypes.ITEMSTACK, packetItem)
            )
        )
    }

    private fun itemStackMetadataIndex(player: Player): Int {
        val version = PacketEvents.getAPI().playerManager.getClientVersion(player)
        // 1.17 起实体 metadata 多了冻结时间，ItemEntity 的物品槽位后移。
        return if (version.isOlderThan(ClientVersion.V_1_17)) 7 else 8
    }

    private fun send(player: Player, packet: Any) {
        PacketEvents.getAPI().playerManager.sendPacket(player, packet)
    }
}
