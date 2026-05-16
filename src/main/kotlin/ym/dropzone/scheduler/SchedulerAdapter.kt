package ym.dropzone.scheduler

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

// 统一调度入口：业务层不关心当前是 Bukkit 主线程模型还是 Folia region 模型。
interface SchedulerAdapter {
    fun runAsync(task: () -> Unit): ScheduledTaskHandle
    fun runAsyncLater(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle
    fun runAsyncTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle
    fun runGlobal(task: () -> Unit): ScheduledTaskHandle
    fun runGlobalLater(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle
    fun runGlobalTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle
    fun runAt(location: Location, task: () -> Unit): ScheduledTaskHandle
    fun runForPlayer(player: Player, task: () -> Unit): ScheduledTaskHandle
    fun runForPlayer(playerId: UUID, task: (Player) -> Unit): ScheduledTaskHandle?
    fun <T> callAt(location: Location, task: () -> T): CompletableFuture<T>
    fun cancelAll()
}

interface ScheduledTaskHandle {
    fun cancel()
}

object SchedulerProvider {
    fun create(plugin: Plugin): SchedulerAdapter {
        return if (isFolia()) FoliaSchedulerAdapter(plugin) else BukkitSchedulerAdapter(plugin)
    }

    fun isFolia(): Boolean {
        return runCatching {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
            true
        }.getOrDefault(false)
    }
}
