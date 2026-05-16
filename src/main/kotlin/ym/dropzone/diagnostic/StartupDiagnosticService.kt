package ym.dropzone.diagnostic

import org.bukkit.plugin.java.JavaPlugin
import ym.dropzone.config.OutboxConsumeMode
import ym.dropzone.config.RuntimeConfigSnapshot
import ym.dropzone.config.StorageMode
import ym.dropzone.entity.DropZoneEntityManager
import ym.dropzone.scheduler.SchedulerProvider
import ym.dropzone.storage.MysqlStorage
import ym.dropzone.storage.OutboxStats
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class StartupDiagnosticService(
    private val plugin: JavaPlugin,
    private val entityManager: DropZoneEntityManager,
    private val mysqlStorage: MysqlStorage?
) {
    private val emitted = AtomicBoolean(false)

    fun emit(snapshot: RuntimeConfigSnapshot) {
        if (!emitted.compareAndSet(false, true)) return
        val protocolLibDetected = plugin.server.pluginManager.getPlugin("ProtocolLib")?.isEnabled == true
        warnDangerousConfig(snapshot, protocolLibDetected)

        val mysqlFuture = if (snapshot.main.stateStorage.mode == StorageMode.MYSQL) {
            mysqlStorage?.isHealthy()?.exceptionally { error ->
                warnDiagnosticFailure(snapshot, "MySQL 健康检查", error)
                false
            } ?: CompletableFuture.completedFuture(false)
        } else {
            CompletableFuture.completedFuture<Boolean?>(null)
        }
        val outboxFuture = if (snapshot.main.stateStorage.mode == StorageMode.MYSQL && mysqlStorage != null) {
            mysqlStorage.outboxStats().exceptionally { error ->
                warnDiagnosticFailure(snapshot, "发奖队列统计", error)
                OutboxStats(0, 0, 0, 0)
            }
        } else {
            CompletableFuture.completedFuture(OutboxStats(0, 0, 0, 0))
        }

        mysqlFuture.thenCombine(outboxFuture) { mysqlHealthy, outbox ->
            DiagnosticData(mysqlHealthy, outbox, protocolLibDetected)
        }.whenComplete { data, error ->
            if (error != null || data == null) {
                warnDiagnosticFailure(snapshot, "启动诊断", error ?: IllegalStateException("没有诊断数据"))
                return@whenComplete
            }
            logSummary(snapshot, data)
        }
    }

    private fun logSummary(snapshot: RuntimeConfigSnapshot, data: DiagnosticData) {
        val main = snapshot.main
        val mysqlStatus = when (data.mysqlHealthy) {
            true -> "已连接"
            false -> "连接失败"
            null -> "LOCAL_JSON 模式未启用"
        }
        plugin.logger.info("[DropZone] ===== 启动诊断 =====")
        plugin.logger.info("[DropZone] 插件版本: ${plugin.description.version}")
        plugin.logger.info("[DropZone] 当前启用活动: ${snapshot.activity.id}")
        plugin.logger.info("[DropZone] 存储模式: ${main.stateStorage.mode}")
        plugin.logger.info("[DropZone] 服务器: ${main.server.id} / 分组 ${main.server.group}")
        plugin.logger.info("[DropZone] 跨服同步: ${onOff(main.crossServer.enabled)}")
        plugin.logger.info("[DropZone] 生成模式: ${main.spawn.crossServerMode}")
        plugin.logger.info("[DropZone] MySQL: $mysqlStatus")
        plugin.logger.info("[DropZone] 本服活跃奖励点: ${entityManager.activeCount()}")
        plugin.logger.info("[DropZone] 发奖队列: 待处理=${data.outbox.pending} 处理中=${data.outbox.processing} 已完成=${data.outbox.done} 失败=${data.outbox.failed}")
        plugin.logger.info("[DropZone] ProtocolLib: ${if (data.protocolLibDetected) "已检测" else "未检测到"}")
        plugin.logger.info(
            "[DropZone] 盔甲架参数: 高度偏移=${main.fakeEntity.armorStandYOffset} " +
                "小型=${main.fakeEntity.armorStandSmall} 标记=${main.fakeEntity.armorStandMarker}"
        )
        plugin.logger.info("[DropZone] 发奖队列配置: 启用=${main.rewardOutbox.enabled} 消费模式=${main.rewardOutbox.consumeMode}")
        plugin.logger.info("[DropZone] 调试开关: 发包=${main.fakeEntity.debugPackets} 可见盔甲架=${main.fakeEntity.debugVisibleArmorStand}")
        plugin.logger.info("[DropZone] PlaceholderAPI: ${if (plugin.server.pluginManager.getPlugin("PlaceholderAPI")?.isEnabled == true) "已检测" else "未检测到"}")
        plugin.logger.info("[DropZone] Folia: ${yesNo(SchedulerProvider.isFolia())}")
        plugin.logger.info("[DropZone] ==============================")
    }

    private fun warnDangerousConfig(snapshot: RuntimeConfigSnapshot, protocolLibDetected: Boolean) {
        val main = snapshot.main
        if (main.fakeEntity.debugPackets) warn(snapshot, "fake-entity.debug-packets=true，发包调试日志已开启")
        if (main.fakeEntity.debugVisibleArmorStand) warn(snapshot, "fake-entity.debug-visible-armorstand=true，隐藏盔甲架会变为可见")
        if (!main.rewardOutbox.enabled && main.stateStorage.mode == StorageMode.MYSQL) {
            warn(snapshot, "storage.mode=MYSQL 但 reward-outbox.enabled=false，发奖恢复队列已关闭")
        }
        if (main.rewardOutbox.consumeMode == OutboxConsumeMode.ANY_SERVER) warn(snapshot, "reward-outbox.consume-mode=ANY_SERVER，任意服务器都可消费发奖任务")
        if (main.spawn.maxActive > 500) warn(snapshot, "spawn.max-active=${main.spawn.maxActive} 大于 500")
        if (main.spawn.intervalSeconds < 10) warn(snapshot, "spawn.interval-seconds=${main.spawn.intervalSeconds} 小于 10")
        if (main.locationRules.loadChunkIfNeeded) warn(snapshot, "location-rules.load-chunk-if-needed=true，可能触发同步区块加载")
        if (main.fakeEntity.armorStandMarker) warn(snapshot, "fake-entity.armor-stand-marker=true，可能影响头颅显示稳定性")
        if (!protocolLibDetected) warn(snapshot, "ProtocolLib 未安装或未启用")
        if (main.fakeEntity.legacyPacketEventsConfigured) {
            warn(snapshot, "检测到旧配置 fake-entity.packet-backend=packetevents，已回退到 PROTOCOLLIB")
        }
    }

    private fun warn(snapshot: RuntimeConfigSnapshot, message: String) {
        plugin.logger.warning("[DropZone] 启动警告: $message (${context(snapshot)})")
    }

    private fun warnDiagnosticFailure(snapshot: RuntimeConfigSnapshot, phase: String, error: Throwable) {
        plugin.logger.warning(
            "[DropZone] 启动警告: $phase 失败: ${error.javaClass.simpleName}: ${error.message} " +
                "(${context(snapshot)}, outbox_id=n/a, spawn_id=n/a, player_uuid=n/a, reward_id=n/a)"
        )
    }

    private fun context(snapshot: RuntimeConfigSnapshot): String {
        val main = snapshot.main
        return "server.id=${main.server.id}, server.group=${main.server.group}, " +
            "storage.mode=${main.stateStorage.mode}, activity_id=${snapshot.activity.id}"
    }

    private fun onOff(value: Boolean): String = if (value) "开启" else "关闭"

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"

    private data class DiagnosticData(
        val mysqlHealthy: Boolean?,
        val outbox: OutboxStats,
        val protocolLibDetected: Boolean
    )
}
