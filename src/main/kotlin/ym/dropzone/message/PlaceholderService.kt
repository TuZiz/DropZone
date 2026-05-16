package ym.dropzone.message

import org.bukkit.Location
import org.bukkit.entity.Player
import ym.dropzone.config.LangConfig
import ym.dropzone.reward.RewardRollResult
import java.util.UUID

class PlaceholderService {
    fun build(
        lang: LangConfig,
        player: Player?,
        roll: RewardRollResult?,
        location: Location?,
        extra: Map<String, String> = emptyMap()
    ): Map<String, String> {
        // 占位符统一在这里维护，奖励命令和 lang 消息共用同一套变量。
        val values = linkedMapOf<String, String>()
        values["%prefix%"] = lang.prefix
        values["%player%"] = player?.name ?: ""
        values["%uuid%"] = player?.uniqueId?.toString() ?: UUID(0, 0).toString()
        values["%reward_id%"] = roll?.reward?.id ?: ""
        values["%reward%"] = roll?.reward?.displayName ?: ""
        values["%rarity_id%"] = roll?.rarity?.id ?: ""
        values["%rarity%"] = roll?.rarity?.displayName ?: ""
        values["%head_id%"] = roll?.head?.id ?: ""
        values["%head%"] = roll?.head?.displayName ?: ""
        values["%x%"] = location?.blockX?.toString() ?: ""
        values["%y%"] = location?.blockY?.toString() ?: ""
        values["%z%"] = location?.blockZ?.toString() ?: ""
        values["%world%"] = location?.world?.name ?: ""
        values.putAll(extra.mapKeys { "%${it.key}%" })
        return values
    }

    fun apply(text: String, placeholders: Map<String, String>): String {
        var result = text
        placeholders.forEach { (key, value) -> result = result.replace(key, value) }
        return result
    }
}
