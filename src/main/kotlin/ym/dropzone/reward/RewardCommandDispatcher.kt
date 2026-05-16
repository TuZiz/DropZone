package ym.dropzone.reward

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import ym.dropzone.config.RewardCommandExecutorMode
import ym.dropzone.message.PlaceholderService
import ym.dropzone.scheduler.SchedulerAdapter
import java.util.concurrent.CompletableFuture

class RewardCommandDispatcher(
    private val plugin: Plugin,
    private val scheduler: SchedulerAdapter,
    private val placeholderService: PlaceholderService
) {
    fun dispatch(
        mode: RewardCommandExecutorMode,
        playerName: String,
        rewardId: String,
        commands: List<String>,
        values: Map<String, String>,
        afterDispatch: () -> Unit
    ): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()
        val task = {
            var success = true
            commands.forEach { command ->
                val resolved = placeholderService.apply(command, values).removePrefix("/")
                runCatching {
                    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)) success = false
                }.onFailure { error ->
                    success = false
                    plugin.logger.warning(
                        "奖励命令执行失败: player=$playerName reward_id=$rewardId command=$resolved error=${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
            afterDispatch()
            future.complete(success)
            Unit
        }
        when (mode) {
            RewardCommandExecutorMode.PLAYER_REGION -> task()
            RewardCommandExecutorMode.GLOBAL -> scheduler.runGlobal(task)
            RewardCommandExecutorMode.GLOBAL_SAFE -> scheduler.runGlobal(task)
        }
        return future
    }
}
