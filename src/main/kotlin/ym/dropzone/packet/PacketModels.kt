package ym.dropzone.packet

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ym.dropzone.entity.DropZoneEntity
import java.util.UUID

data class FakeItemEntityPacketData(
    val entityId: Int,
    val uuid: UUID,
    val location: Location,
    val itemStack: ItemStack,
    val glowing: Boolean,
    val yaw: Float
)

interface PacketEntityAdapter {
    fun spawnItemEntity(player: Player, entity: DropZoneEntity)
    fun updateEntity(player: Player, entity: DropZoneEntity)
    fun destroyEntity(player: Player, entityId: Int)
    fun destroyEntities(player: Player, entityIds: Collection<Int>)
}
