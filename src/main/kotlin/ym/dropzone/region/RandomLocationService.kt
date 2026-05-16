package ym.dropzone.region

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.plugin.Plugin
import ym.dropzone.config.LangKeys
import ym.dropzone.config.RuntimeConfigSnapshot
import ym.dropzone.scheduler.SchedulerAdapter
import java.util.concurrent.CompletableFuture

class RandomLocationService(
    private val plugin: Plugin,
    private val scheduler: SchedulerAdapter,
    private val validator: LocationValidator
) {
    fun findLocation(snapshot: RuntimeConfigSnapshot): CompletableFuture<Location?> {
        // 坐标候选基于内存快照，区块和方块检查切回安全调度器。
        val future = CompletableFuture<Location?>()
        scheduler.runGlobal {
            val world = Bukkit.getWorld(snapshot.main.spawnRegion.world)
            if (world == null) {
                plugin.logger.warning(snapshot.lang.format(LangKeys.CONSOLE_INVALID_WORLD, mapOf("world" to snapshot.main.spawnRegion.world)))
                future.complete(null)
                return@runGlobal
            }
            val region = SpawnRegion(snapshot.main.spawnRegion)
            attempt(snapshot, region, 0, future)
        }
        return future
    }

    private fun attempt(snapshot: RuntimeConfigSnapshot, region: SpawnRegion, index: Int, future: CompletableFuture<Location?>) {
        val world = Bukkit.getWorld(snapshot.main.spawnRegion.world)
        if (world == null || index >= snapshot.main.locationRules.maxLocationAttempts) {
            future.complete(null)
            return
        }
        val candidate = region.randomLocation(world)
        scheduler.runAt(candidate) {
            if (validator.isValid(candidate, snapshot.main.locationRules)) {
                future.complete(candidate)
            } else {
                attempt(snapshot, region, index + 1, future)
            }
        }
    }
}
