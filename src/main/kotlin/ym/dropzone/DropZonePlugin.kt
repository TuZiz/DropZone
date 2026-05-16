package ym.dropzone

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import ym.dropzone.claim.ClaimTracker
import ym.dropzone.command.DropZoneCommand
import ym.dropzone.command.DropZoneTabCompleter
import ym.dropzone.config.ConfigManager
import ym.dropzone.config.LangKeys
import ym.dropzone.entity.DropZoneEntityManager
import ym.dropzone.head.HeadFactory
import ym.dropzone.head.HeadSelector
import ym.dropzone.message.LangService
import ym.dropzone.message.PlaceholderService
import ym.dropzone.packet.PacketAdapterFactory
import ym.dropzone.player.PlayerSnapshotService
import ym.dropzone.region.LocationValidator
import ym.dropzone.region.RandomLocationService
import ym.dropzone.reward.RewardExecutor
import ym.dropzone.reward.RewardOutboxWorker
import ym.dropzone.reward.RewardSelector
import ym.dropzone.scheduler.ScheduledTaskHandle
import ym.dropzone.scheduler.SchedulerAdapter
import ym.dropzone.scheduler.SchedulerProvider
import ym.dropzone.task.EntityTickTask
import ym.dropzone.task.NavigationActionBarTask
import ym.dropzone.task.PlayerSnapshotTask
import ym.dropzone.task.SpawnCycleTask
import ym.dropzone.task.ViewerUpdateTask
import ym.dropzone.config.StorageMode
import ym.dropzone.storage.MysqlStorage

class DropZonePlugin : JavaPlugin(), Listener {
    private lateinit var scheduler: SchedulerAdapter
    private lateinit var configManager: ConfigManager
    private lateinit var entityManager: DropZoneEntityManager
    private lateinit var playerSnapshots: PlayerSnapshotService
    private lateinit var claimTracker: ClaimTracker
    private lateinit var locationService: RandomLocationService
    private lateinit var langService: LangService
    private lateinit var placeholderService: PlaceholderService
    private var mysqlStorage: MysqlStorage? = null
    private var papiExpansion: Any? = null
    private val runningTasks = mutableListOf<ScheduledTaskHandle>()

    override fun onEnable() {
        scheduler = SchedulerProvider.create(this)
        configManager = ConfigManager(this, scheduler)
        configManager.saveDefaultResources()
        placeholderService = PlaceholderService()
        langService = LangService(this, placeholderService)
        playerSnapshots = PlayerSnapshotService(scheduler)
        claimTracker = ClaimTracker()
        locationService = RandomLocationService(this, scheduler, LocationValidator(this))

        configManager.loadMainConfigAsync().whenComplete { main, bootstrapError ->
            if (bootstrapError != null || main == null) {
                failStartup("configuration bootstrap failed: ${bootstrapError?.message ?: "unknown"}")
                return@whenComplete
            }
            if (main.stateStorage.mode == StorageMode.MYSQL) {
                val storage = MysqlStorage(main.stateStorage.mysql, main.server, main.activityFiles.defaultActivity)
                mysqlStorage = storage
                storage.initialize().whenComplete { _, storageError ->
                    if (storageError != null) {
                        failStartup("MySQL initialization failed: ${storageError.message ?: storageError.javaClass.simpleName}")
                        return@whenComplete
                    }
                    configManager.setActivityStateStore(storage)
                    scheduler.runGlobal { finishEnable() }
                }
            } else {
                scheduler.runGlobal { finishEnable() }
            }
        }
    }

    private fun finishEnable() {
        val rewardSelector = RewardSelector(HeadSelector())
        val rewardExecutor = RewardExecutor(this, scheduler, langService, placeholderService)
        entityManager = DropZoneEntityManager(
            this,
            configManager,
            scheduler,
            PacketAdapterFactory.create(this, configManager),
            playerSnapshots,
            claimTracker,
            langService,
            placeholderService,
            rewardSelector,
            rewardExecutor,
            HeadFactory(),
            mysqlStorage
        )

        server.pluginManager.registerEvents(this, this)
        server.onlinePlayers.forEach { playerSnapshots.track(it.uniqueId) }
        getCommand("dropzone")?.setExecutor(
            DropZoneCommand(configManager, scheduler, entityManager, locationService, langService, placeholderService, claimTracker, mysqlStorage) { restartRuntimeTasks() }
        )
        getCommand("dropzone")?.tabCompleter = DropZoneTabCompleter(configManager)
        registerPlaceholderApiExpansion()

        configManager.reloadAsync().whenComplete { snapshot, error ->
            if (error != null) {
                logger.warning(configManager.lang?.format(LangKeys.CONSOLE_CONFIG_LOAD_FAILED, mapOf("error" to (error.message ?: error.javaClass.simpleName))) ?: LangKeys.CONSOLE_CONFIG_LOAD_FAILED)
                return@whenComplete
            }
            snapshot.warnings.forEach { logger.warning(it) }
            logger.info(
                "DropZone loaded: activity=${snapshot.activity.id}, spawn=${snapshot.main.spawn.enabled}, " +
                    "maxActive=${snapshot.main.spawn.maxActive}, rewardCommand=${snapshot.main.rewardCommandExecutorMode}, " +
                    "storage=${snapshot.main.stateStorage.mode}, server=${snapshot.main.server.id}, group=${snapshot.main.server.group}"
            )
            if (SchedulerProvider.isFolia()) {
                logger.warning("DropZone Folia mode: third-party reward commands may require GLOBAL_SAFE-compatible command handlers.")
            }
            scheduler.runGlobal { startRuntimeTasks() }
        }
    }

    private fun failStartup(message: String) {
        logger.severe("DropZone startup failed: $message")
        runCatching {
            if (::scheduler.isInitialized) {
                scheduler.runGlobal { server.pluginManager.disablePlugin(this) }
            } else {
                server.pluginManager.disablePlugin(this)
            }
        }
    }

    override fun onDisable() {
        if (::entityManager.isInitialized) entityManager.clearAll()
        if (::playerSnapshots.isInitialized) playerSnapshots.clear()
        papiExpansion?.javaClass?.getMethod("unregister")?.invoke(papiExpansion)
        papiExpansion = null
        runningTasks.forEach { it.cancel() }
        runningTasks.clear()
        mysqlStorage?.close()
        mysqlStorage = null
        if (::scheduler.isInitialized) scheduler.cancelAll()
        if (::langService.isInitialized) langService.close()
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (::playerSnapshots.isInitialized) playerSnapshots.track(event.player.uniqueId)
        configManager.snapshot?.let { snapshot ->
            if (::entityManager.isInitialized && mysqlStorage != null) entityManager.syncFromDatabase(snapshot)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        if (::playerSnapshots.isInitialized) playerSnapshots.untrack(event.player.uniqueId)
        if (::entityManager.isInitialized) entityManager.handleQuit(event.player)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        if (::playerSnapshots.isInitialized) playerSnapshots.remove(event.player.uniqueId)
        if (::entityManager.isInitialized) entityManager.handleQuit(event.player)
        if (::playerSnapshots.isInitialized) playerSnapshots.track(event.player.uniqueId)
        configManager.snapshot?.let { snapshot ->
            if (::entityManager.isInitialized && mysqlStorage != null) entityManager.syncFromDatabase(snapshot)
        }
    }

    private fun restartRuntimeTasks() {
        runningTasks.forEach { it.cancel() }
        runningTasks.clear()
        startRuntimeTasks()
    }

    private fun startRuntimeTasks() {
        val snapshot = configManager.snapshot ?: return
        val interval = snapshot.main.fakeEntity.updateIntervalTicks
        runningTasks += scheduler.runGlobalTimer(1L, interval, PlayerSnapshotTask(playerSnapshots)::run)
        runningTasks += scheduler.runGlobalTimer(1L, interval, EntityTickTask(entityManager)::run)
        runningTasks += scheduler.runGlobalTimer(1L, interval, ViewerUpdateTask(entityManager)::run)
        if (snapshot.main.navigation.actionbarEnabled) {
            val navigationInterval = snapshot.main.navigation.intervalTicks
            runningTasks += scheduler.runGlobalTimer(
                navigationInterval,
                navigationInterval,
                NavigationActionBarTask(configManager, scheduler, entityManager, playerSnapshots, langService, placeholderService)::run
            )
        }
        if (snapshot.main.spawn.enabled) {
            val period = snapshot.main.spawn.intervalSeconds * 20L
            val spawnTask = { SpawnCycleTask(configManager, scheduler, locationService, entityManager, mysqlStorage = mysqlStorage).run() }
            runningTasks += scheduler.runAsyncTimer(period, period, spawnTask)
            if (snapshot.main.spawn.spawnOnStartup) {
                scheduler.runAsync {
                    SpawnCycleTask(configManager, scheduler, locationService, entityManager, snapshot.main.spawn.startupAmount, mysqlStorage).run()
                }
            }
        }
        if (mysqlStorage != null && snapshot.main.crossServer.enabled) {
            val period = snapshot.main.crossServer.syncIntervalSeconds * 20L
            runningTasks += scheduler.runAsyncTimer(period, period) {
                configManager.reloadAsync().thenAccept { entityManager.syncFromDatabase(it) }
            }
        }
        mysqlStorage?.let { storage ->
            if (snapshot.main.rewardOutbox.enabled) {
                val period = snapshot.main.rewardOutbox.pollIntervalSeconds * 20L
                runningTasks += scheduler.runAsyncTimer(period, period, RewardOutboxWorker(this, configManager, scheduler, storage, placeholderService)::run)
            }
        }
    }

    private fun registerPlaceholderApiExpansion() {
        if (server.pluginManager.getPlugin("PlaceholderAPI") == null) return
        runCatching {
            val expansionClass = Class.forName("ym.dropzone.papi.DropZonePlaceholderExpansion")
            papiExpansion = expansionClass
                .getConstructor(JavaPlugin::class.java, ConfigManager::class.java, DropZoneEntityManager::class.java, PlayerSnapshotService::class.java)
                .newInstance(this, configManager, entityManager, playerSnapshots)
                .also { expansionClass.getMethod("register").invoke(it) }
        }.onFailure { error ->
            logger.warning(
                configManager.lang?.format(
                    LangKeys.CONSOLE_PAPI_REGISTER_FAILED,
                    mapOf("error" to (error.message ?: error.javaClass.simpleName))
                ) ?: LangKeys.CONSOLE_PAPI_REGISTER_FAILED
            )
        }
    }
}
