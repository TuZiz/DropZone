package ym.dropzone.message

import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ym.dropzone.config.LangConfig
import ym.dropzone.util.MiniMessageUtil
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

// 所有对玩家和命令发送者可见的文本都从 LangConfig 读取并用 MiniMessage 渲染。
class LangService(private val plugin: Plugin, private val placeholders: PlaceholderService) {
    private val audiences = BukkitAudiences.create(plugin)
    private val nextActionBarWarningAt = AtomicLong(0L)

    fun send(sender: CommandSender, lang: LangConfig, key: String, values: Map<String, String> = emptyMap()) {
        val raw = lang.messages[key] ?: return
        audiences.sender(sender).sendMessage(MiniMessageUtil.deserialize(placeholders.apply(raw, values)))
    }

    fun broadcast(players: Collection<Player>, lang: LangConfig, key: String, values: Map<String, String>) {
        val raw = lang.messages[key] ?: return
        val component = MiniMessageUtil.deserialize(placeholders.apply(raw, values))
        players.forEach { audiences.player(it).sendMessage(component) }
    }

    fun actionBar(player: Player, lang: LangConfig, key: String, values: Map<String, String> = emptyMap()) {
        val raw = lang.messages[key] ?: return
        val rendered = placeholders.apply(raw, values)
        val component = MiniMessageUtil.deserialize(rendered)
        val legacy = LegacyComponentSerializer.legacySection().serialize(component)
        runCatching {
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                *TextComponent.fromLegacyText(legacy)
            )
        }.onFailure { error ->
            warnActionBarFailure(key, error)
        }
    }

    fun title(player: Player, lang: LangConfig, titleKey: String, subtitleKey: String, fadeIn: Int, stay: Int, fadeOut: Int, values: Map<String, String>) {
        val times = Title.Times.times(
            Duration.ofMillis(fadeIn * 50L),
            Duration.ofMillis(stay * 50L),
            Duration.ofMillis(fadeOut * 50L)
        )
        audiences.player(player).showTitle(
            Title.title(
                MiniMessageUtil.deserialize(placeholders.apply(lang.messages[titleKey] ?: return, values)),
                MiniMessageUtil.deserialize(placeholders.apply(lang.messages[subtitleKey] ?: "", values)),
                times
            )
        )
    }

    fun close() {
        audiences.close()
    }

    private fun warnActionBarFailure(key: String, error: Throwable) {
        val now = System.currentTimeMillis()
        val next = nextActionBarWarningAt.get()
        if (now < next || !nextActionBarWarningAt.compareAndSet(next, now + ACTION_BAR_WARNING_INTERVAL_MILLIS)) return
        plugin.logger.warning(
            "DropZone ActionBar send failed for key=$key: ${error.message ?: error.javaClass.simpleName}"
        )
    }

    companion object {
        private const val ACTION_BAR_WARNING_INTERVAL_MILLIS = 30_000L
    }
}
