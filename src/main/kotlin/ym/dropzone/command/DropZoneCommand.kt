package ym.dropzone.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ym.dropzone.claim.ClaimTracker
import ym.dropzone.config.ConfigManager
import ym.dropzone.config.LangConfig
import ym.dropzone.config.LangKeys
import ym.dropzone.config.ManualSpawnMode
import ym.dropzone.config.RuntimeConfigSnapshot
import ym.dropzone.entity.DropZoneEntityManager
import ym.dropzone.message.LangService
import ym.dropzone.message.PlaceholderService
import ym.dropzone.region.RandomLocationService
import ym.dropzone.scheduler.SchedulerAdapter
import ym.dropzone.task.SpawnCycleTask

class DropZoneCommand(
    private val configManager: ConfigManager,
    private val scheduler: SchedulerAdapter,
    private val entityManager: DropZoneEntityManager,
    private val locationService: RandomLocationService,
    private val langService: LangService,
    private val placeholderService: PlaceholderService,
    private val claimTracker: ClaimTracker,
    private val restartTasks: () -> Unit
) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase() ?: "debug"
        when (sub) {
            "reload" -> reload(sender)
            "start" -> start(sender, args.getOrNull(1))
            "spawn" -> spawn(sender, args.getOrNull(1))
            "clear" -> clear(sender)
            "list" -> list(sender)
            "debug" -> debug(sender)
            else -> debug(sender)
        }
        return true
    }

    private fun start(sender: CommandSender, activityName: String?) {
        if (!has(sender, "dropzone.start")) return
        if (activityName.isNullOrBlank()) return sendKey(sender, LangKeys.ADMIN_START_FAILED)
        configManager.startActivityAsync(activityName).whenComplete { snapshot, error ->
            scheduler.runGlobal {
                if (error != null || snapshot == null) {
                    sendKey(sender, LangKeys.ADMIN_START_FAILED)
                    log(sender, configManager.lang, LangKeys.CONSOLE_RELOAD_FAILED, "error" to (error?.message ?: activityName))
                    return@runGlobal
                }
                if (snapshot.activity.rules.clearActiveOnStart) {
                    entityManager.clearAll()
                    claimTracker.clearActivity(snapshot.activity.id)
                }
                restartTasks()
                triggerSpawnCycle(snapshot)
                val values = placeholderService.build(
                    snapshot.lang,
                    null,
                    null,
                    null,
                    mapOf("activity" to snapshot.activity.id, "activity_display" to snapshot.activity.displayName)
                )
                langService.send(sender, snapshot.lang, LangKeys.ADMIN_START_SUCCESS, values)
            }
        }
    }

    private fun reload(sender: CommandSender) {
        if (!has(sender, "dropzone.reload")) return
        val oldLang = configManager.snapshot?.lang
        if (oldLang != null) langService.send(sender, oldLang, LangKeys.RELOAD_START, placeholderService.build(oldLang, null, null, null))
        configManager.reloadAsync().whenComplete { snapshot, error ->
            scheduler.runGlobal {
                if (error != null || snapshot == null) {
                    oldLang?.let { langService.send(sender, it, LangKeys.RELOAD_FAILED, placeholderService.build(it, null, null, null)) }
                    error?.let { log(sender, oldLang, LangKeys.CONSOLE_RELOAD_FAILED, "error" to (it.message ?: it.javaClass.simpleName)) }
                    return@runGlobal
                }
                if (snapshot.main.reload.clearActiveEntities) entityManager.clearAll()
                restartTasks()
                snapshot.warnings.forEach { sender.server.logger.warning(it) }
                langService.send(sender, snapshot.lang, LangKeys.RELOAD_SUCCESS, placeholderService.build(snapshot.lang, null, null, null))
            }
        }
    }

    private fun spawn(sender: CommandSender, amountArg: String?) {
        if (!has(sender, "dropzone.spawn")) return
        val snapshot = configManager.snapshot ?: return sendKey(sender, LangKeys.ADMIN_SPAWN_FAILED)
        val amount = amountArg?.toIntOrNull()
            ?.coerceIn(1, snapshot.main.spawn.manual.maxAmount)
            ?: snapshot.main.spawn.manual.amount.coerceAtMost(snapshot.main.spawn.manual.maxAmount)
        repeat(amount) {
            spawnOne(sender, snapshot)
        }
    }

    private fun spawnOne(sender: CommandSender, snapshot: RuntimeConfigSnapshot) {
        val locationFuture = when (snapshot.main.spawn.manual.mode) {
            ManualSpawnMode.PLAYER_NEAR -> {
                if (sender is Player) {
                    locationService.findNearPlayer(sender, snapshot).thenCompose { near ->
                        if (near != null) java.util.concurrent.CompletableFuture.completedFuture(near) else locationService.findLocation(snapshot)
                    }
                } else {
                    locationService.findLocation(snapshot)
                }
            }
            ManualSpawnMode.REGION_RANDOM -> locationService.findLocation(snapshot)
        }
        locationFuture.thenAccept { location ->
            if (location == null) {
                scheduler.runGlobal { sendKey(sender, LangKeys.ADMIN_SPAWN_FAILED) }
                return@thenAccept
            }
            scheduler.runAt(location) {
                val entity = entityManager.createAt(location, snapshot)
                if (entity != null && sender is Player) {
                    entityManager.revealTo(sender.uniqueId, entity)
                    entityManager.playSpawnMarker(entity, snapshot)
                }
                sendSpawnResultSafely(sender, if (entity != null) LangKeys.ADMIN_SPAWN_SUCCESS else LangKeys.ADMIN_SPAWN_FAILED, location)
            }
        }
    }

    private fun triggerSpawnCycle(snapshot: RuntimeConfigSnapshot) {
        if (!snapshot.main.spawn.enabled) return
        val amount = if (snapshot.main.spawn.spawnOnStartup) {
            snapshot.main.spawn.startupAmount
        } else {
            snapshot.main.spawn.attemptsPerCycle
        }
        scheduler.runAsync {
            SpawnCycleTask(
                configManager,
                scheduler,
                locationService,
                entityManager,
                amount.coerceAtLeast(1)
            ).run()
        }
    }

    private fun clear(sender: CommandSender) {
        if (!has(sender, "dropzone.clear")) return
        entityManager.clearAll()
        sendKey(sender, LangKeys.ADMIN_CLEAR_SUCCESS)
    }

    private fun list(sender: CommandSender) {
        if (!has(sender, "dropzone.list")) return
        val snapshot = configManager.snapshot ?: return
        val values = placeholderService.build(snapshot.lang, null, null, null, mapOf("count" to entityManager.activeCount().toString()))
        langService.send(sender, snapshot.lang, LangKeys.LIST_HEADER, values)
        entityManager.activeEntities().forEach { entity ->
            val lineValues = placeholderService.build(snapshot.lang, null, entity.roll, entity.currentLocation)
            langService.send(sender, snapshot.lang, LangKeys.LIST_LINE, lineValues)
        }
    }

    private fun debug(sender: CommandSender) {
        if (!has(sender, "dropzone.debug")) return
        val snapshot = configManager.snapshot ?: return
        langService.send(sender, snapshot.lang, LangKeys.DEBUG_HEADER, placeholderService.build(snapshot.lang, null, null, null))
        // Debug 的显示标签也从 lang 读取，避免代码里出现面向用户的固定文案。
        mapOf(
            LangKeys.DEBUG_ACTIVE to entityManager.activeCount().toString(),
            LangKeys.DEBUG_RARITIES to snapshot.rarities.size.toString(),
            LangKeys.DEBUG_HEADS to snapshot.heads.size.toString(),
            LangKeys.DEBUG_REWARDS to snapshot.rewards.size.toString(),
            LangKeys.DEBUG_SPAWN_ENABLED to snapshot.main.spawn.enabled.toString(),
            LangKeys.DEBUG_ACTIVITY to snapshot.activity.id
        ).forEach { (labelKey, value) ->
            langService.send(
                sender,
                snapshot.lang,
                LangKeys.DEBUG_LINE,
                placeholderService.build(snapshot.lang, null, null, null, mapOf("key" to snapshot.lang.format(labelKey), "value" to value))
            )
        }
    }

    private fun sendKey(sender: CommandSender, key: String) {
        val lang = configManager.snapshot?.lang ?: return
        langService.send(sender, lang, key, placeholderService.build(lang, null, null, null))
    }

    private fun sendKeySafely(sender: CommandSender, key: String) {
        if (sender is Player) {
            scheduler.runForPlayer(sender) { sendKey(sender, key) }
        } else {
            scheduler.runGlobal { sendKey(sender, key) }
        }
    }

    private fun sendSpawnResultSafely(sender: CommandSender, key: String, location: org.bukkit.Location) {
        val send = {
            val lang = configManager.snapshot?.lang
            if (lang != null) {
                langService.send(sender, lang, key, placeholderService.build(lang, null, null, location))
            }
        }
        if (sender is Player) {
            scheduler.runForPlayer(sender) { send() }
        } else {
            scheduler.runGlobal { send() }
        }
    }

    private fun log(sender: CommandSender, lang: LangConfig?, key: String, vararg values: Pair<String, String>) {
        sender.server.logger.warning(lang?.format(key, values.toMap()) ?: key)
    }

    private fun has(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission) || sender.hasPermission("dropzone.admin")) return true
        val lang = configManager.snapshot?.lang
        if (lang != null) {
            langService.send(sender, lang, LangKeys.NO_PERMISSION, placeholderService.build(lang, null, null, null))
        }
        return false
    }
}
