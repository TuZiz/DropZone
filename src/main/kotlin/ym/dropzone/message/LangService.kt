package ym.dropzone.message

import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.title.Title
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ym.dropzone.config.LangConfig
import ym.dropzone.util.MiniMessageUtil
import java.time.Duration

// 所有对玩家和命令发送者可见的文本都从 LangConfig 读取并用 MiniMessage 渲染。
class LangService(plugin: Plugin, private val placeholders: PlaceholderService) {
    private val audiences = BukkitAudiences.create(plugin)

    fun send(sender: CommandSender, lang: LangConfig, key: String, values: Map<String, String> = emptyMap()) {
        val raw = lang.messages[key] ?: return
        audiences.sender(sender).sendMessage(MiniMessageUtil.deserialize(placeholders.apply(raw, values)))
    }

    fun broadcast(players: Collection<Player>, lang: LangConfig, key: String, values: Map<String, String>) {
        val raw = lang.messages[key] ?: return
        val component = MiniMessageUtil.deserialize(placeholders.apply(raw, values))
        players.forEach { audiences.player(it).sendMessage(component) }
    }

    fun actionBar(player: Player, lang: LangConfig, key: String, values: Map<String, String>) {
        val raw = lang.messages[key] ?: return
        audiences.player(player).sendActionBar(MiniMessageUtil.deserialize(placeholders.apply(raw, values)))
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
}
