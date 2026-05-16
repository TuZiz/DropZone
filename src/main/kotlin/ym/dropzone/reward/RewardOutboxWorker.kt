package ym.dropzone.reward

import org.bukkit.plugin.Plugin
import ym.dropzone.config.ConfigManager
import ym.dropzone.config.RewardCommandExecutorMode
import ym.dropzone.message.PlaceholderService
import ym.dropzone.scheduler.SchedulerAdapter
import ym.dropzone.storage.MysqlStorage

class RewardOutboxWorker(
    private val plugin: Plugin,
    private val configManager: ConfigManager,
    private val scheduler: SchedulerAdapter,
    private val storage: MysqlStorage,
    placeholderService: PlaceholderService
) : Runnable {
    private val dispatcher = RewardCommandDispatcher(plugin, scheduler, placeholderService)
    @Volatile private var running = false

    override fun run() {
        val snapshot = configManager.snapshot ?: return
        if (!snapshot.main.rewardOutbox.enabled || running) return
        running = true
        storage.restoreStaleProcessing(snapshot.main.rewardOutbox.processingTimeoutSeconds).thenCompose { restored ->
            if (restored > 0) {
                plugin.logger.warning(
                    snapshot.lang.format(
                        ym.dropzone.config.LangKeys.CONFIG_WARNING_OUTBOX_PROCESSING_RESTORED,
                        mapOf("count" to restored.toString())
                    )
                )
            }
            storage.claimOutboxBatch(snapshot.main.rewardOutbox.claimBatchSize, snapshot.main.rewardOutbox.consumeMode)
        }.whenComplete { entries, error ->
            if (error != null) {
                running = false
                plugin.logger.warning("DropZone outbox scan failed: server=${snapshot.main.server.id}, group=${snapshot.main.server.group}, activity=${snapshot.activity.id}, error=${error.message ?: error.javaClass.simpleName}")
                return@whenComplete
            }
            val batch = entries.orEmpty()
            if (batch.isEmpty()) {
                running = false
                return@whenComplete
            }
            var remaining = batch.size
            batch.forEach { entry ->
                dispatcher.dispatch(
                    mode = RewardCommandExecutorMode.GLOBAL_SAFE,
                    playerName = entry.playerName,
                    rewardId = entry.rewardId,
                    commands = entry.commands,
                    values = emptyMap()
                ) {
                    // 玩家提示在领取事务成功时已发送；outbox 只负责可恢复发奖。
                }.whenComplete { success, dispatchError ->
                    val finish = if (dispatchError == null && success == true) {
                        storage.markOutboxDone(entry.id)
                    } else {
                        storage.markOutboxFailedOrPending(entry.id, dispatchError?.message ?: "dispatchCommand returned false")
                    }
                    finish.whenComplete { _, finishError ->
                        if (finishError != null) {
                            plugin.logger.warning(
                                "DropZone outbox update failed: server=${snapshot.main.server.id}, group=${snapshot.main.server.group}, " +
                                    "activity=${entry.activityId}, spawnId=${entry.spawnId}, playerUuid=${entry.playerUuid}, " +
                                    "rewardId=${entry.rewardId}, outboxId=${entry.id}, error=${finishError.message ?: finishError.javaClass.simpleName}"
                            )
                        }
                        remaining -= 1
                        if (remaining <= 0) running = false
                    }
                }
            }
        }
    }
}
