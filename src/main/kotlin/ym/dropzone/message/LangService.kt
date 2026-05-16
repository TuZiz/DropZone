package ym.dropzone.message

import net.kyori.adventure.platform.bukkit.BukkitAudiences
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ym.dropzone.config.LangConfig
import ym.dropzone.util.MiniMessageUtil
import ym.dropzone.util.PlainTextUtil
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
        val rendered = placeholders.apply(raw, values)
        val component = MiniMessageUtil.deserialize(rendered)
        runCatching {
            audiences.player(player).sendActionBar(component)
        }
        sendSpigotActionBar(player, rendered)
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

    private fun sendSpigotActionBar(player: Player, rendered: String) {
        runCatching {
            val chatMessageType = Class.forName("net.md_5.bungee.api.ChatMessageType")
            val actionBar = java.lang.Enum.valueOf(chatMessageType.asSubclass(Enum::class.java), "ACTION_BAR")
            val textComponent = Class.forName("net.md_5.bungee.api.chat.TextComponent")
            val baseComponent = Class.forName("net.md_5.bungee.api.chat.BaseComponent")
            val legacy = LegacyComponentSerializer.legacySection().serialize(MiniMessageUtil.deserialize(rendered))
            val components = textComponent.getMethod("fromLegacyText", String::class.java).invoke(null, legacy)
            val spigot = player.javaClass.getMethod("spigot").invoke(player)
            val sendMessage = spigot.javaClass.methods.firstOrNull { method ->
                method.name == "sendMessage" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[0].name == "net.md_5.bungee.api.ChatMessageType" &&
                    method.parameterTypes[1].isArray &&
                    method.parameterTypes[1].componentType == baseComponent
            } ?: return@runCatching
            sendMessage.invoke(spigot, actionBar, components)
        }.recoverCatching {
            player.sendMessage(PlainTextUtil.stripMiniMessage(rendered))
        }
    }
}
