package ym.dropzone.region

import org.bukkit.Location
import org.bukkit.World
import ym.dropzone.config.SpawnRegionConfig
import ym.dropzone.config.SpawnRegionMode
import java.util.concurrent.ThreadLocalRandom

class SpawnRegion(private val config: SpawnRegionConfig) {
    fun randomLocation(world: World): Location {
        val random = ThreadLocalRandom.current()
        val x = random.nextInt(minX(), maxX() + 1) + 0.5
        val y = random.nextInt(config.minY, config.maxY + 1).toDouble()
        val z = random.nextInt(minZ(), maxZ() + 1) + 0.5
        return Location(world, x, y, z)
    }

    fun randomColumn(world: World): Location {
        val random = ThreadLocalRandom.current()
        val x = random.nextInt(minX(), maxX() + 1) + 0.5
        val z = random.nextInt(minZ(), maxZ() + 1) + 0.5
        return Location(world, x, config.maxY.toDouble(), z)
    }

    fun containsBlock(x: Int, z: Int): Boolean {
        return x in minX()..maxX() && z in minZ()..maxZ()
    }

    fun minY(): Int = config.minY

    fun maxY(): Int = config.maxY

    private fun minX(): Int {
        return if (config.mode == SpawnRegionMode.MAX_RADIUS) {
            config.centerX - config.maxRadius
        } else {
            config.minX
        }
    }

    private fun maxX(): Int {
        return if (config.mode == SpawnRegionMode.MAX_RADIUS) {
            config.centerX + config.maxRadius
        } else {
            config.maxX
        }
    }

    private fun minZ(): Int {
        return if (config.mode == SpawnRegionMode.MAX_RADIUS) {
            config.centerZ - config.maxRadius
        } else {
            config.minZ
        }
    }

    private fun maxZ(): Int {
        return if (config.mode == SpawnRegionMode.MAX_RADIUS) {
            config.centerZ + config.maxRadius
        } else {
            config.maxZ
        }
    }

    @Deprecated("Use randomColumn plus surface scanning for better spawn success.")
    private fun oldRandomLocation(world: World): Location {
        val random = ThreadLocalRandom.current()
        val xRange = if (config.mode == SpawnRegionMode.MAX_RADIUS) {
            (config.centerX - config.maxRadius)..(config.centerX + config.maxRadius)
        } else {
            config.minX..config.maxX
        }
        val zRange = if (config.mode == SpawnRegionMode.MAX_RADIUS) {
            (config.centerZ - config.maxRadius)..(config.centerZ + config.maxRadius)
        } else {
            config.minZ..config.maxZ
        }
        val x = random.nextInt(xRange.first, xRange.last + 1) + 0.5
        val y = random.nextInt(config.minY, config.maxY + 1).toDouble()
        val z = random.nextInt(zRange.first, zRange.last + 1) + 0.5
        return Location(world, x, y, z)
    }
}
