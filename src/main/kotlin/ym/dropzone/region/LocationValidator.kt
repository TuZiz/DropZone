package ym.dropzone.region

import org.bukkit.Material
import org.bukkit.plugin.Plugin
import ym.dropzone.config.LocationRulesConfig
import org.bukkit.Location

class LocationValidator(private val plugin: Plugin) {
    fun isValid(location: Location, rules: LocationRulesConfig): Boolean {
        val world = location.world ?: return false
        val chunkX = location.blockX shr 4
        val chunkZ = location.blockZ shr 4
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            if (!rules.allowUnloadedChunks) {
                if (rules.loadChunkIfNeeded) world.loadChunk(chunkX, chunkZ, true) else return false
            }
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
