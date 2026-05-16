package ym.dropzone.reward

import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ym.dropzone.config.LangKeys
import ym.dropzone.config.RuntimeConfigSnapshot
import ym.dropzone.message.LangService
import ym.dropzone.message.PlaceholderService
import ym.dropzone.scheduler.SchedulerAdapter

class RewardExecutor(
    private val plugin: Plugin,
    private val scheduler: SchedulerAdapter,
    private val langService: LangService,
    private val placeholderService: PlaceholderService
) {
    fun execute(player: Player, result: RewardRollResult, snapshot: RuntimeConfigSnapshot, location: org.bukkit.Location) {
        // 奖励命令和消息变量在领取时一次性展开，不再读取任何配置文件。
        val values = placeholderService.build(snapshot.lang, player, result, location)
        scheduler.runForPlayer(player) {
            // 发奖命令只在玩家安全调度上下文内执行，命令文本来自内存快照。
            result.reward.commands.forEach { command ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), placeholderService.apply(command, values).removePrefix("/"))
            }
            langService.send(player, snapshot.lang, LangKeys.REWARD_CLAIMED, values)
            playEffects(player, snapshot, values)
            if (result.rarity.broadcast) {
                langService.broadcast(Bukkit.getOnlinePlayers(), snapshot.lang, LangKeys.REWARD_BROADCAST, values)
            }
        }
    }

    private fun playEffects(player: Player, snapshot: RuntimeConfigSnapshot, values: Map<String, String>) {
        val claim = snapshot.main.effects.claim
        if (claim.sound.enabled) {
            val sound = runCatching { Sound.valueOf(claim.sound.name.uppercase()) }.getOrNull()
            if (sound != null) {
                player.playSound(player.location, sound, claim.sound.volume, claim.sound.pitch)
            } else {
                plugin.logger.warning(snapshot.lang.format(LangKeys.CONSOLE_INVALID_SOUND, mapOf("name" to claim.sound.name)))
            }
        }
        if (claim.particle.enabled) {
            val particle = parseParticle(claim.particle.name)
            if (particle != null) {
                player.world.spawnParticle(
                    particle,
                    player.location.add(0.0, 1.0, 0.0),
                    claim.particle.count,
                    claim.particle.offsetX,
                    claim.particle.offsetY,
                    claim.particle.offsetZ,
                    claim.particle.speed
                )
            } else {
                plugin.logger.warning(snapshot.lang.format(LangKeys.CONSOLE_INVALID_PARTICLE, mapOf("name" to claim.particle.name)))
            }
        }
        if (claim.title.enabled) {
            langService.title(player, snapshot.lang, LangKeys.CLAIM_TITLE, LangKeys.CLAIM_SUBTITLE, claim.title.fadeIn, claim.title.stay, claim.title.fadeOut, values)
        }
        if (claim.actionbar.enabled) {
            langService.actionBar(player, snapshot.lang, LangKeys.CLAIM_ACTIONBAR, values)
        }
    }

    private fun parseParticle(name: String): Particle? {
        val normalized = name.uppercase()
        val aliases = when (normalized) {
            "HAPPY_VILLAGER" -> listOf("HAPPY_VILLAGER", "VILLAGER_HAPPY")
            "VILLAGER_HAPPY" -> listOf("VILLAGER_HAPPY", "HAPPY_VILLAGER")
            else -> listOf(normalized)
        }
        return aliases.firstNotNullOfOrNull { candidate ->
            runCatching { Particle.valueOf(candidate) }.getOrNull()
        }
    }
}
