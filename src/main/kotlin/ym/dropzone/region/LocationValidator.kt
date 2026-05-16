package ym.dropzone.region

import org.bukkit.Material
import org.bukkit.plugin.Plugin
import ym.dropzone.config.LocationRulesConfig
import org.bukkit.Location
import java.util.concurrent.atomic.AtomicBoolean

class LocationValidator(private val plugin: Plugin) {
    private val chunkLoadWarningSent = AtomicBoolean(false)

    fun isValid(location: Location, rules: LocationRulesConfig): Boolean {
        val world = location.world ?: return false
        val chunkX = location.blockX shr 4
        val chunkZ = location.blockZ shr 4
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            if (rules.loadChunkIfNeeded || rules.maxSyncChunkLoadsPerCycle > 0) {
                if (chunkLoadWarningSent.compareAndSet(false, true)) {
                    plugin.logger.warning("已跳过未加载区块校验。为保证服务器安全，同步区块加载已禁用。")
                }
            }
            return false
        }
        val block = location.block
        val below = block.getRelative(0, -1, 0)
        if (block.type.name in rules.avoidBlocks) return false
        if (below.type.name in rules.avoidBlocks) return false
        if (rules.requireAir && !block.type.isAir) return false
        if (rules.requireSolidGround && !below.type.isSolid) return false
        if (!rules.allowWater && (block.type == Material.WATER || below.type == Material.WATER)) return false
        if (!rules.allowLava && (block.type == Material.LAVA || below.type == Material.LAVA)) return false
        return true
    }
}
