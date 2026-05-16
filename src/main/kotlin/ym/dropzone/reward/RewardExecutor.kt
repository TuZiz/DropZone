package ym.dropzone.reward

import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ym.dropzone.config.LangKeys
import ym.dropzone.config.RuntimeConfigSnapshot
import ym.dropzone.message.LangService
import ym.dropzone.message.PlaceholderService
import ym.dropzone.scheduler.SchedulerAdapter
import ym.dropzone.util.ParticleUtil

class RewardExecutor(
    private val plugin: Plugin,
    private val scheduler: SchedulerAdapter,
    private val langService: LangService,
    private val placeholderService: PlaceholderService
) {
    private val commandDispatcher = RewardCommandDispatcher(plugin, scheduler, placeholderService)

    fun execute(player: Player, result: RewardRollResult, snapshot: RuntimeConfigSnapshot, location: org.bukkit.Location) {
        scheduler.runForPlayer(player) {
            if (!player.isOnline) return@runForPlayer
            val values = placeholderService.build(snapshot.lang, player, result, location)
            val playerId = player.uniqueId
            val playerName = player.name
            commandDispatcher.dispatch(
                mode = snapshot.main.rewardCommandExecutorMode,
                playerName = playerName,
                rewardId = result.reward.id,
                commands = result.reward.commands,
                values = values
            ) {
                scheduler.runForPlayer(playerId) { online ->
                    if (!online.isOnline) return@runForPlayer
                    langService.send(online, snapshot.lang, LangKeys.REWARD_CLAIMED, values)
                    playEffects(online, snapshot, values)
                    if (result.rarity.broadcast) {
                        broadcast(snapshot, values)
                    }
                }
            }
        }
    }

    private fun broadcast(snapshot: RuntimeConfigSnapshot, values: Map<String, String>) {
        scheduler.runGlobal {
            Bukkit.getOnlinePlayers().forEach { target ->
                scheduler.runForPlayer(target) {
                    if (target.isOnline) {
                        langService.send(target, snapshot.lang, LangKeys.REWARD_BROADCAST, values)
                    }
                }
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
            val particle = ParticleUtil.parse(claim.particle.name)
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

}
