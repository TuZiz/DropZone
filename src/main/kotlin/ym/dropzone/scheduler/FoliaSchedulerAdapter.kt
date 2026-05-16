package ym.dropzone.scheduler

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class FoliaSchedulerAdapter(private val plugin: Plugin) : SchedulerAdapter {
    private val tasks = Collections.synchronizedList(mutableListOf<Any>())
    private val server = plugin.server

    override fun runAsync(task: () -> Unit): ScheduledTaskHandle =
        invokeAsync("runNow", arrayOf(Plugin::class.java, java.util.function.Consumer::class.java), arrayOf(plugin, consumer(task)))

    override fun runAsyncLater(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle =
        invokeAsync(
            "runDelayed",
            arrayOf(Plugin::class.java, java.util.function.Consumer::class.java, java.lang.Long.TYPE, TimeUnit::class.java),
            arrayOf(plugin, consumer(task), ticksToMillis(delayTicks), TimeUnit.MILLISECONDS)
        )

    override fun runAsyncTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle =
        invokeAsync(
            "runAtFixedRate",
            arrayOf(Plugin::class.java, java.util.function.Consumer::class.java, java.lang.Long.TYPE, java.lang.Long.TYPE, TimeUnit::class.java),
            arrayOf(plugin, consumer(task), ticksToMillis(delayTicks), ticksToMillis(periodTicks), TimeUnit.MILLISECONDS)
        )

    override fun runGlobal(task: () -> Unit): ScheduledTaskHandle =
        invokeScheduler(server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server), "run", arrayOf(plugin, consumer(task)))

    override fun runGlobalLater(delayTicks: Long, task: () -> Unit): ScheduledTaskHandle =
        invokeScheduler(server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server), "runDelayed", arrayOf(plugin, consumer(task), delayTicks))

    override fun runGlobalTimer(delayTicks: Long, periodTicks: Long, task: () -> Unit): ScheduledTaskHandle =
        invokeScheduler(server.javaClass.getMethod("getGlobalRegionScheduler").invoke(server), "runAtFixedRate", arrayOf(plugin, consumer(task), delayTicks, periodTicks))

    override fun runAt(location: Location, task: () -> Unit): ScheduledTaskHandle {
        val region = server.javaClass.getMethod("getRegionScheduler").invoke(server)
        return invokeScheduler(region, "run", arrayOf(plugin, location, consumer(task)))
    }

    override fun runForPlayer(player: Player, task: () -> Unit): ScheduledTaskHandle {
        val scheduler = player.javaClass.getMethod("getScheduler").invoke(player)
        return invokeScheduler(
            scheduler,
            "run",
            arrayOf(plugin, consumer(task), null)
        )
    }

    override fun <T> callAt(location: Location, task: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        runAt(location) {
            runCatching(task).onSuccess(future::complete).onFailure(future::completeExceptionally)
        }
        return future
    }

    override fun cancelAll() {
        tasks.toList().forEach { cancelTask(it) }
        tasks.clear()
    }

    private fun invokeAsync(method: String, parameterTypes: Array<Class<*>>, args: Array<Any?>): ScheduledTaskHandle {
        val scheduler = server.javaClass.getMethod("getAsyncScheduler").invoke(server)
        return invokeScheduler(scheduler, method, args, parameterTypes)
    }

    private fun invokeScheduler(target: Any, method: String, args: Array<Any?>, parameterTypes: Array<Class<*>>? = null): ScheduledTaskHandle {
        val found = if (parameterTypes != null) {
            target.javaClass.getMethod(method, *parameterTypes)
        } else {
            target.javaClass.methods.first {
                it.name == method && it.parameterCount == args.size
            }
        }
        val task = found.invoke(target, *args)
        if (task != null) tasks.add(task)
        return object : ScheduledTaskHandle {
            override fun cancel() {
                if (task != null) {
                    cancelTask(task)
                    tasks.remove(task)
                }
            }
        }
    }

    private fun consumer(task: () -> Unit): java.util.function.Consumer<Any> = java.util.function.Consumer { task() }

    private fun cancelTask(task: Any) {
        runCatching { task.javaClass.getMethod("cancel").invoke(task) }
    }

    private fun ticksToMillis(ticks: Long): Long = (ticks.coerceAtLeast(1L)) * 50L
}
