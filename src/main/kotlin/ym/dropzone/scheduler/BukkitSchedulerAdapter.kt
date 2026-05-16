package ym.dropzone.scheduler

import org.bukkit.Location
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture

class BukkitSchedulerAdapter(private val plugin: Plugin) : SchedulerAdapter {
    private val tasks = Collections.synchronizedSet(mutableSetOf<BukkitTask>())

    override fun runAsync(task: () -> Unit): ScheduledTaskHandle =
        track(plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable(task)))

    override fun runAsyncLater(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle =
        track(plugin.server.scheduler.runTaskLaterAsynchronously(plugin, Runnable(task), delayTicks))

    override fun runAsyncTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle =
        track(plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable(task), delayTicks, periodTicks))

    override fun runGlobal(task: () -> Unit): ScheduledTaskHandle =
        track(plugin.server.scheduler.runTask(plugin, Runnable(task)))

    override fun runGlobalLater(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle =
        track(plugin.server.scheduler.runTaskLater(plugin, Runnable(task), delayTicks))

    override fun runGlobalTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle =
        track(plugin.server.scheduler.runTaskTimer(plugin, Runnable(task), delayTicks, periodTicks))

    override fun runAt(location: Location, task: () -> Unit): ScheduledTaskHandle = runGlobal(task)

    override fun runForPlayer(player: Player, task: () -> Unit): ScheduledTaskHandle = runGlobal(task)

    override fun runForPlayer(playerId: UUID, task: (Player) -> Unit): ScheduledTaskHandle? {
        val player = Bukkit.getPlayer(playerId) ?: return null
        return runForPlayer(player) {
            if (player.isOnline) task(player)
        }
    }

    override fun <T> callAt(location: Location, task: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        runAt(location) {
            runCatching(task).onSuccess(future::complete).onFailure(future::completeExceptionally)
        }
        return future
    }

    override fun cancelAll() {
        tasks.toList().forEach { it.cancel() }
        tasks.clear()
    }

    private fun track(task: BukkitTask): ScheduledTaskHandle {
        tasks.add(task)
        return object : ScheduledTaskHandle {
            override fun cancel() {
                task.cancel()
                tasks.remove(task)
            }
        }
    }
}
