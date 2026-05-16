package ym.dropzone.region

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ym.dropzone.config.LangKeys
import ym.dropzone.config.RuntimeConfigSnapshot
import ym.dropzone.scheduler.SchedulerAdapter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadLocalRandom

class RandomLocationService(
    private val plugin: Plugin,
    private val scheduler: SchedulerAdapter,
    private val validator: LocationValidator
) {
    fun findLocation(snapshot: RuntimeConfigSnapshot): CompletableFuture<Location?> {
        // 坐标候选基于内存快照，区块和方块检查切回安全调度器。
        val future = CompletableFuture<Location?>()
        scheduler.runGlobal {
            val spawnRegion = snapshot.activity.spawnRegion
            val world = Bukkit.getWorld(spawnRegion.world)
            if (world == null) {
                plugin.logger.warning(snapshot.lang.format(LangKeys.CONSOLE_INVALID_WORLD, mapOf("world" to spawnRegion.world)))
                future.complete(null)
                return@runGlobal
            }
            val region = SpawnRegion(spawnRegion)
            attempt(snapshot, region, 0, future)
        }
        return future
    }

    fun findNearPlayer(player: Player, snapshot: RuntimeConfigSnapshot): CompletableFuture<Location?> {
        val future = CompletableFuture<Location?>()
        scheduler.runForPlayer(player) {
            if (!player.isOnline) {
                future.complete(null)
                return@runForPlayer
            }
            val spawnRegion = snapshot.activity.spawnRegion
            if (player.world.name != spawnRegion.world) {
                future.complete(null)
                return@runForPlayer
            }
            val region = SpawnRegion(spawnRegion)
            attemptNearPlayer(player.location.clone(), snapshot, region, 0, future)
        }
        return future
    }

    private fun attempt(snapshot: RuntimeConfigSnapshot, region: SpawnRegion, index: Int, future: CompletableFuture<Location?>) {
        val world = Bukkit.getWorld(snapshot.activity.spawnRegion.world)
        if (world == null || index >= snapshot.main.locationRules.maxLocationAttempts) {
            future.complete(null)
            return
        }
        val candidate = randomLoadedColumn(world, region) ?: region.randomColumn(world)
        scheduler.runAt(candidate) {
            val surface = if (isChunkLoaded(candidate)) findSurface(candidate, region) else null
            if (surface != null && validator.isValid(surface, snapshot.main.locationRules)) {
                future.complete(surface)
            } else {
                scheduler.runGlobal { attempt(snapshot, region, index + 1, future) }
            }
        }
    }

    private fun attemptNearPlayer(origin: Location, snapshot: RuntimeConfigSnapshot, region: SpawnRegion, index: Int, future: CompletableFuture<Location?>) {
        val world = origin.world
        if (world == null || index >= snapshot.main.locationRules.maxLocationAttempts) {
            future.complete(null)
            return
        }
        val radius = snapshot.main.spawn.manual.nearRadius
        val block = if (index == 0) {
            val direction = origin.direction.setY(0).normalize()
            val distance = (snapshot.main.fakeEntity.attractDistance + 2.0).toInt().coerceIn(2, radius)
            origin.clone().add(direction.multiply(distance.toDouble()))
        } else {
            val random = ThreadLocalRandom.current()
            origin.clone().add(
                random.nextInt(-radius, radius + 1).toDouble(),
                0.0,
                random.nextInt(-radius, radius + 1).toDouble()
            )
        }
        val x = block.blockX
        val z = block.blockZ
        if (snapshot.main.spawn.manual.respectRegion && !region.containsBlock(x, z)) {
            attemptNearPlayer(origin, snapshot, region, index + 1, future)
            return
        }
        val candidate = Location(world, x + 0.5, region.maxY().toDouble(), z + 0.5)
        scheduler.runAt(candidate) {
            val surface = if (isChunkLoaded(candidate)) findSurface(candidate, region) else null
            if (surface != null && validator.isValid(surface, snapshot.main.locationRules)) {
                future.complete(surface)
            } else {
                attemptNearPlayer(origin, snapshot, region, index + 1, future)
            }
        }
    }

    private fun isChunkLoaded(location: Location): Boolean {
        val world = location.world ?: return false
        return world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)
    }

    private fun randomLoadedColumn(world: World, region: SpawnRegion): Location? {
        val loadedChunks = world.loadedChunks.filter { chunk ->
            val minX = chunk.x shl 4
            val minZ = chunk.z shl 4
            val maxX = minX + 15
            val maxZ = minZ + 15

            region.containsBlock(minX, minZ) ||
                region.containsBlock(maxX, minZ) ||
                region.containsBlock(minX, maxZ) ||
                region.containsBlock(maxX, maxZ)
        }
        if (loadedChunks.isEmpty()) return null

        val random = ThreadLocalRandom.current()
        repeat(64) {
            val chunk = loadedChunks[random.nextInt(loadedChunks.size)]
            val x = (chunk.x shl 4) + random.nextInt(16)
            val z = (chunk.z shl 4) + random.nextInt(16)
            if (region.containsBlock(x, z)) {
                return Location(world, x + 0.5, region.maxY().toDouble(), z + 0.5)
            }
        }
        return null
    }

    private fun findSurface(column: Location, region: SpawnRegion): Location? {
        val world = column.world ?: return null
        val x = column.blockX
        val z = column.blockZ
        for (y in region.maxY() downTo region.minY()) {
            val block = world.getBlockAt(x, y, z)
            val below = world.getBlockAt(x, y - 1, z)
            if (block.type.isAir && below.type.isSolid) {
                return Location(world, x + 0.5, y + 0.35, z + 0.5)
            }
        }
        return null
    }
}
