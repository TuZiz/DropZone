package ym.dropzone

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import ym.dropzone.command.DropZoneCommand
import ym.dropzone.command.DropZoneTabCompleter
import ym.dropzone.config.ConfigManager
import ym.dropzone.config.LangKeys
import ym.dropzone.entity.DropZoneEntityManager
import ym.dropzone.head.HeadFactory
import ym.dropzone.head.HeadSelector
import ym.dropzone.message.LangService
import ym.dropzone.message.PlaceholderService
import ym.dropzone.packet.PacketEventsEntityAdapter
import ym.dropzone.region.LocationValidator
import ym.dropzone.region.RandomLocationService
import ym.dropzone.reward.RewardExecutor
import ym.dropzone.reward.RewardSelector
import ym.dropzone.scheduler.ScheduledTaskHandle
import ym.dropzone.scheduler.SchedulerAdapter
import ym.dropzone.scheduler.SchedulerProvider
import ym.dropzone.task.EntityTickTask
import ym.dropzone.task.SpawnCycleTask
import ym.dropzone.task.ViewerUpdateTask

// 插件入口只负责装配服务、注册命令和管理生命周期，具体玩法逻辑放在各领域包。
class DropZonePlugin : JavaPlugin(), Listener {
    private lateinit var scheduler: SchedulerAdapter
    private lateinit var configManager: ConfigManager
    private lateinit var entityManager: DropZoneEntityManager
    private lateinit var locationService: RandomLocationService
    private lateinit var langService: LangService
    private lateinit var placeholderService: PlaceholderService
    private var papiExpansion: Any? = null
    private val runningTasks = mutableListOf<ScheduledTaskHandle>()

    override fun onEnable() {
        // 调度器先初始化，后续配置读取、世界访问和玩家操作都通过它进入安全上下文。
        scheduler = SchedulerProvider.create(this)
        configManager = ConfigManager(this, scheduler)
        configManager.saveDefaultResources()
        placeholderService = PlaceholderService()
        langService = LangService(this, placeholderService)
        locationService = RandomLocationService(this, scheduler, LocationValidator(this))
        val rewardSelector = RewardSelector(HeadSelector())
        val rewardExecutor = RewardExecutor(this, scheduler, langService, placeholderService)
        entityManager = DropZoneEntityManager(
            this,
            configManager,
            scheduler,
            PacketEventsEntityAdapter(),
            rewardSelector,
            rewardExecutor,
            HeadFactory()
        )
        server.pluginManager.registerEvents(this, this)
        getCommand("dropzone")?.setExecutor(
            DropZoneCommand(configManager, scheduler, entityManager, locationService, langService, placeholderService) { restartRuntimeTasks() }
        )
        getCommand("dropzone")?.tabCompleter = DropZoneTabCompleter(configManager)
        registerPlaceholderApiExpansion()
        configManager.reloadAsync().whenComplete { snapshot, error ->
            if (error != null) {
                logger.warning(configManager.lang?.format(LangKeys.CONSOLE_CONFIG_LOAD_FAILED, mapOf("error" to (error.message ?: error.javaClass.simpleName))) ?: LangKeys.CONSOLE_CONFIG_LOAD_FAILED)
                return@whenComplete
            }
            snapshot.warnings.forEach { logger.warning(it) }
            scheduler.runGlobal { startRuntimeTasks() }
        }
    }

    override fun onDisable() {
        if (::entityManager.isInitialized) entityManager.clearAll()
        papiExpansion?.javaClass?.getMethod("unregister")?.invoke(papiExpansion)
        papiExpansion = null
        runningTasks.forEach { it.cancel() }
        runningTasks.clear()
        if (::scheduler.isInitialized) scheduler.cancelAll()
        if (::langService.isInitialized) langService.close()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        if (::entityManager.isInitialized) entityManager.handleQuit(event.player)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        if (::entityManager.isInitialized) entityManager.handleQuit(event.player)
    }

    private fun restartRuntimeTasks() {
        runningTasks.forEach { it.cancel() }
        runningTasks.clear()
        startRuntimeTasks()
    }

    private fun startRuntimeTasks() {
        val snapshot = configManager.snapshot ?: return
        // 运行任务只读取内存快照，不在 tick 中访问 YAML 文件。
        runningTasks += scheduler.runGlobalTimer(1L, snapshot.main.fakeEntity.updateIntervalTicks, EntityTickTask(entityManager)::run)
        runningTasks += scheduler.runGlobalTimer(1L, snapshot.main.fakeEntity.updateIntervalTicks, ViewerUpdateTask(entityManager)::run)
        if (snapshot.main.spawn.enabled) {
            val period = snapshot.main.spawn.intervalSeconds * 20L
            runningTasks += scheduler.runAsyncTimer(period, period, SpawnCycleTask(configManager, locationService, entityManager)::run)
            if (snapshot.main.spawn.spawnOnStartup) {
                repeat(snapshot.main.spawn.startupAmount) {
                    scheduler.runAsync(SpawnCycleTask(configManager, locationService, entityManager)::run)
                }
            }
        }
    }

    private fun registerPlaceholderApiExpansion() {
        if (server.pluginManager.getPlugin("PlaceholderAPI") == null) return
        runCatching {
            val expansionClass = Class.forName("ym.dropzone.papi.DropZonePlaceholderExpansion")
            papiExpansion = expansionClass
                .getConstructor(JavaPlugin::class.java, ConfigManager::class.java, DropZoneEntityManager::class.java)
                .newInstance(this, configManager, entityManager)
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
