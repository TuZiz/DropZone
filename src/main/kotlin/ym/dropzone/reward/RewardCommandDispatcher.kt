package ym.dropzone.reward

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import ym.dropzone.config.RewardCommandExecutorMode
import ym.dropzone.message.PlaceholderService
import ym.dropzone.scheduler.SchedulerAdapter

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
    ) {
        val task = {
            commands.forEach { command ->
                val resolved = placeholderService.apply(command, values).removePrefix("/")
                runCatching {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)
                }.onFailure { error ->
                    plugin.logger.warning(
                        "DropZone reward command failed: player=$playerName reward=$rewardId command=$resolved error=${error.message ?: error.javaClass.simpleName}"
                    )
                }
            }
            afterDispatch()
        }
        when (mode) {
            RewardCommandExecutorMode.PLAYER_REGION -> task()
            RewardCommandExecutorMode.GLOBAL -> scheduler.runGlobal(task)
        }
    }
}
