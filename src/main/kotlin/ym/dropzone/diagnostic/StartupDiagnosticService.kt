package ym.dropzone.diagnostic

import org.bukkit.ChatColor
import org.bukkit.plugin.java.JavaPlugin
import ym.dropzone.config.OutboxConsumeMode
import ym.dropzone.config.RuntimeConfigSnapshot
import ym.dropzone.config.StorageMode
import ym.dropzone.scheduler.SchedulerProvider
import java.util.concurrent.atomic.AtomicBoolean

class StartupDiagnosticService(
    private val plugin: JavaPlugin
) {
    private val emitted = AtomicBoolean(false)

    fun emit(snapshot: RuntimeConfigSnapshot) {
        if (!emitted.compareAndSet(false, true)) return
        val protocolLibDetected = plugin.server.pluginManager.getPlugin("ProtocolLib")?.isEnabled == true
        warnDangerousConfig(snapshot, protocolLibDetected)
        logSummary(snapshot, protocolLibDetected)
    }

    private fun logSummary(snapshot: RuntimeConfigSnapshot, protocolLibDetected: Boolean) {
        val main = snapshot.main
        plugin.logger.info(color(ChatColor.GOLD, "===== 启动诊断 ====="))
        plugin.logger.info("插件版本: ${color(ChatColor.AQUA, plugin.description.version)}")
        plugin.logger.info("当前启用活动: ${color(ChatColor.GREEN, snapshot.activity.id)}")
        plugin.logger.info("存储模式: ${color(ChatColor.YELLOW, main.stateStorage.mode.name)}")
        plugin.logger.info("ProtocolLib: ${status(protocolLibDetected)}")
        plugin.logger.info("PlaceholderAPI: ${status(plugin.server.pluginManager.getPlugin("PlaceholderAPI")?.isEnabled == true)}")
        plugin.logger.info("Folia: ${status(SchedulerProvider.isFolia())}")
        plugin.logger.info(color(ChatColor.GOLD, "=============================="))
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
        plugin.logger.warning("启动警告: $message (${context(snapshot)})")
    }

    private fun context(snapshot: RuntimeConfigSnapshot): String {
        val main = snapshot.main
        return "server.id=${main.server.id}, server.group=${main.server.group}, " +
            "storage.mode=${main.stateStorage.mode}, activity_id=${snapshot.activity.id}"
    }

    private fun status(value: Boolean): String {
        return if (value) color(ChatColor.GREEN, "已检测") else color(ChatColor.RED, "未检测到")
    }

    private fun color(color: ChatColor, text: String): String = "$color$text${ChatColor.RESET}"
}
