package ym.dropzone.config

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ym.dropzone.head.HeadDefinition
import ym.dropzone.reward.RarityDefinition
import ym.dropzone.reward.RewardDefinition
import ym.dropzone.scheduler.SchedulerAdapter
import ym.dropzone.storage.LocalJsonActivityStateStore
import ym.dropzone.util.SafeEnumParser
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

class ConfigManager(
    private val plugin: JavaPlugin,
    private val scheduler: SchedulerAdapter
) {
    private val snapshotRef = AtomicReference<RuntimeConfigSnapshot?>()
    private val langRef = AtomicReference<LangConfig?>()
    private val activityNamesRef = AtomicReference<List<String>>(emptyList())
    val snapshot: RuntimeConfigSnapshot? get() = snapshotRef.get()
    val lang: LangConfig? get() = snapshotRef.get()?.lang ?: langRef.get()
    val activityNames: List<String> get() = activityNamesRef.get()

    fun saveDefaultResources() {
        listOf(
            "config.yml",
            "action/default/config.yml",
            "action/default/heads.yml",
            "action/default/rewards.yml",
            "lang/zh_cn.yml"
        ).forEach { path ->
            val target = File(plugin.dataFolder, path)
            if (!target.exists()) plugin.saveResource(path, false)
        }
    }

    fun reloadAsync(): CompletableFuture<RuntimeConfigSnapshot> {
        val future = CompletableFuture<RuntimeConfigSnapshot>()
        // 所有 YAML 读取和解析都放在异步调度中，运行时逻辑只读内存快照。
        scheduler.runAsync {
            runCatching {
                val loaded = loadSnapshot()
                snapshotRef.set(loaded)
                loaded
            }.onSuccess(future::complete).onFailure(future::completeExceptionally)
        }
        return future
    }

    fun startActivityAsync(activityName: String): CompletableFuture<RuntimeConfigSnapshot> {
        val future = CompletableFuture<RuntimeConfigSnapshot>()
        scheduler.runAsync {
            runCatching {
                val safeName = sanitizeActivityName(activityName)
                val mainYaml = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, ROOT_CONFIG_FILE))
                val files = parseActivityFiles(mainYaml)
                val storage = parseStateStorage(mainYaml)
                val store = LocalJsonActivityStateStore(plugin, storage.folder, storage.activeActivityFile, files.defaultActivity)
                store.write(safeName)
                val loaded = loadSnapshot()
                snapshotRef.set(loaded)
                loaded
            }.onSuccess(future::complete).onFailure(future::completeExceptionally)
        }
        return future
    }

    private fun loadSnapshot(): RuntimeConfigSnapshot {
        // reload 会一次性构建新快照；构建失败时旧快照继续服务运行逻辑。
        val mainYaml = YamlConfiguration.loadConfiguration(File(plugin.dataFolder, ROOT_CONFIG_FILE))
        val files = parseActivityFiles(mainYaml)
        val storage = parseStateStorage(mainYaml)
        val activityStateStore = LocalJsonActivityStateStore(plugin, storage.folder, storage.activeActivityFile, files.defaultActivity)
        val language = mainYaml.getString("settings.language", "zh_CN") ?: "zh_CN"
        val langPath = "lang/${language.lowercase()}.yml"
        val langFile = File(plugin.dataFolder, langPath).takeIf { it.exists() } ?: File(plugin.dataFolder, "lang/zh_cn.yml")
        val langYaml = YamlConfiguration.loadConfiguration(langFile)
        val lang = parseLang(langYaml)
        langRef.set(lang)

        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val activeActivity = sanitizeActivityName(activityStateStore.read().activeActivity)
        val actionRoot = File(plugin.dataFolder, files.rootFolder)
        val activityNames = actionRoot
            .listFiles { file -> file.isDirectory && ACTIVITY_NAME.matches(file.name) }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        activityNamesRef.set(activityNames)
        val activityFolder = File(actionRoot, activeActivity)
        if (!activityFolder.isDirectory) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_ACTIVITY_FOLDER, "activity" to activeActivity)
        }
        val activityYaml = YamlConfiguration.loadConfiguration(File(activityFolder, files.configFile))
        val main = parseMain(mainYaml, lang, errors)
        val activity = parseActivity(activeActivity, activityYaml, lang, errors)
        if (!activity.enabled) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_ACTIVITY_DISABLED, "activity" to activeActivity)
        }
        val headsYaml = YamlConfiguration.loadConfiguration(File(activityFolder, files.headsFile))
        val rewardsYaml = YamlConfiguration.loadConfiguration(File(activityFolder, files.rewardsFile))
        val rarities = parseRarities(rewardsYaml, lang, errors)
        val rewards = parseRewards(rewardsYaml, rarities.keys, lang, errors)
        val heads = parseHeads(headsYaml, rarities.keys, lang, warnings, errors)

        rarities.keys.forEach { rarity ->
            if (rewards.values.none { it.rarity == rarity }) {
                warnings += issue(lang, LangKeys.CONFIG_WARNING_RARITY_NO_REWARDS, "rarity" to rarity)
            }
            if (heads.values.none { it.rarity == rarity }) {
                warnings += issue(lang, LangKeys.CONFIG_WARNING_RARITY_NO_HEADS, "rarity" to rarity)
            }
        }

        if (errors.isNotEmpty()) {
            throw IllegalStateException(errors.joinToString("; "))
        }
        return RuntimeConfigSnapshot(
            main = main,
            activity = activity,
            heads = heads,
            rarities = rarities,
            rewards = rewards,
            lang = lang,
            headsByRarity = heads.values.groupBy { it.rarity },
            rewardsByRarity = rewards.values.groupBy { it.rarity },
            warnings = warnings
        )
    }

    private fun parseActivityFiles(yaml: YamlConfiguration): ActivityFilesConfig {
        return ActivityFilesConfig(
            rootFolder = safeRelativePath(yaml.getString("action.root-folder", "action") ?: "action"),
            defaultActivity = sanitizeActivityName(yaml.getString("action.default-activity", "default") ?: "default"),
            configFile = safeFileName(yaml.getString("action.config-file", "config.yml") ?: "config.yml"),
            headsFile = safeFileName(yaml.getString("action.heads-file", "heads.yml") ?: "heads.yml"),
            rewardsFile = safeFileName(yaml.getString("action.rewards-file", "rewards.yml") ?: "rewards.yml")
        )
    }

    private fun parseStateStorage(yaml: YamlConfiguration): StateStorageConfig {
        return StateStorageConfig(
            mode = yaml.getString("state-storage.mode", "LOCAL_JSON") ?: "LOCAL_JSON",
            folder = safeRelativePath(yaml.getString("state-storage.folder", "data") ?: "data"),
            activeActivityFile = safeFileName(yaml.getString("state-storage.active-activity-file", "activity-state.json") ?: "activity-state.json")
        )
    }

    private fun parseActivity(
        id: String,
        yaml: YamlConfiguration,
        lang: LangConfig,
        errors: MutableList<String>
    ): ActivityConfig {
        return ActivityConfig(
            id = id,
            displayName = yaml.getString("activity.display-name", id) ?: id,
            enabled = yaml.getBoolean("activity.enabled", true),
            rules = ActivityRulesConfig(
                clearActiveOnStart = yaml.getBoolean("rules.clear-active-on-start", true),
                maxClaimsPerPlayer = yaml.getInt("rules.max-claims-per-player", 0).coerceAtLeast(0),
                claimCooldownSeconds = yaml.getLong("rules.claim-cooldown-seconds", 0).coerceAtLeast(0),
                allowRepeatRewards = yaml.getBoolean("rules.allow-repeat-rewards", true)
            ),
            spawnRegion = parseRequiredSpawnRegion(yaml, "action.$id.spawn-region", lang, errors)
        )
    }

    private fun parseRequiredSpawnRegion(
        yaml: YamlConfiguration,
        pathPrefix: String,
        lang: LangConfig,
        errors: MutableList<String>
    ): SpawnRegionConfig {
        val defaults = SpawnRegionConfig("world", SpawnRegionMode.MAX_RADIUS, 0, 0, 5000, -3000, 3000, -3000, 3000, 80, 160)
        if (!yaml.isConfigurationSection("spawn-region")) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_ACTIVITY_SPAWN_REGION_MISSING, "path" to pathPrefix)
            return defaults
        }
        return parseSpawnRegion(yaml, "spawn-region", pathPrefix, defaults, lang, errors)
    }

    private fun parseMain(yaml: YamlConfiguration, lang: LangConfig, errors: MutableList<String>): MainConfig {
        // 主配置只解析玩法参数，不解析玩家可见文案。
        val selectionText = yaml.getString("reward-selection.mode", "RARITY_THEN_REWARD")
        val selectionMode = SafeEnumParser.parse<RewardSelectionMode>(selectionText)
        if (selectionMode == null) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_REWARD_SELECTION_MODE, "path" to "config.reward-selection.mode", "value" to selectionText.orEmpty())
        }
        val commandExecutorText = yaml.getString("reward-command.executor", "PLAYER_REGION")
        val commandExecutorMode = SafeEnumParser.parse<RewardCommandExecutorMode>(commandExecutorText)
        if (commandExecutorMode == null) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_REWARD_COMMAND_EXECUTOR_MODE, "path" to "config.reward-command.executor", "value" to commandExecutorText.orEmpty())
        }
        val manualModeText = yaml.getString("spawn.manual.mode", yaml.getString("spawn.manual-mode", "PLAYER_NEAR"))
        val manualMode = SafeEnumParser.parse<ManualSpawnMode>(manualModeText)
        if (manualMode == null) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_MANUAL_SPAWN_MODE, "path" to "config.spawn.manual.mode", "value" to manualModeText.orEmpty())
        }
        val packetBackendText = yaml.getString("fake-entity.packet-backend", "PROTOCOLLIB")
        val packetBackend = SafeEnumParser.parse<PacketBackend>(packetBackendText) ?: PacketBackend.PROTOCOLLIB
        return MainConfig(
            debug = yaml.getBoolean("settings.debug", false),
            language = yaml.getString("settings.language", "zh_CN") ?: "zh_CN",
            activityFiles = parseActivityFiles(yaml),
            stateStorage = parseStateStorage(yaml),
            claim = ClaimConfig(
                deniedIgnoreSeconds = yaml.getLong("claim.denied-ignore-seconds", 3L).coerceAtLeast(1L)
            ),
            locationRules = LocationRulesConfig(
                requireAir = yaml.getBoolean("location-rules.require-air", true),
                requireSolidGround = yaml.getBoolean("location-rules.require-solid-ground", true),
                allowWater = yaml.getBoolean("location-rules.allow-water", false),
                allowLava = yaml.getBoolean("location-rules.allow-lava", false),
                avoidBlocks = yaml.getStringList("location-rules.avoid-blocks").map { it.uppercase() }.toSet(),
                maxLocationAttempts = yaml.getInt("location-rules.max-location-attempts", 50).coerceAtLeast(1),
                allowUnloadedChunks = yaml.getBoolean("location-rules.allow-unloaded-chunks", false),
                loadChunkIfNeeded = yaml.getBoolean("location-rules.load-chunk-if-needed", false),
                maxSyncChunkLoadsPerCycle = yaml.getInt("location-rules.max-sync-chunk-loads-per-cycle", 0).coerceAtLeast(0)
            ),
            rewardSelectionMode = selectionMode ?: RewardSelectionMode.RARITY_THEN_REWARD,
            rewardCommandExecutorMode = commandExecutorMode ?: RewardCommandExecutorMode.PLAYER_REGION,
            spawn = SpawnConfig(
                enabled = yaml.getBoolean("spawn.enabled", true),
                intervalSeconds = yaml.getLong("spawn.interval-seconds", 300).coerceAtLeast(1),
                maxActive = yaml.getInt("spawn.max-active", 10).coerceAtLeast(0),
                despawnSeconds = yaml.getLong("spawn.despawn-seconds", 600).coerceAtLeast(1),
                spawnOnStartup = yaml.getBoolean("spawn.spawn-on-startup", true),
                startupAmount = yaml.getInt("spawn.startup-amount", 3).coerceAtLeast(0),
                attemptsPerCycle = yaml.getInt("spawn.attempts-per-cycle", 3).coerceAtLeast(1),
                manual = ManualSpawnConfig(
                    mode = manualMode ?: ManualSpawnMode.PLAYER_NEAR,
                    amount = yaml.getInt("spawn.manual.amount", 3).coerceAtLeast(1),
                    maxAmount = yaml.getInt("spawn.manual.max-amount", 20).coerceAtLeast(1),
                    nearRadius = yaml.getInt("spawn.manual.near-radius", yaml.getInt("spawn.manual-near-radius", 24)).coerceAtLeast(1),
                    respectRegion = yaml.getBoolean("spawn.manual.respect-region", yaml.getBoolean("spawn.manual-respect-region", false))
                )
            ),
            fakeEntity = FakeEntityConfig(
                packetBackend = packetBackend,
                debugPackets = yaml.getBoolean("fake-entity.debug-packets", true),
                debugVisibleArmorStand = yaml.getBoolean("fake-entity.debug-visible-armorstand", false),
                viewDistance = yaml.getDouble("fake-entity.view-distance", 48.0),
                attractDistance = yaml.getDouble("fake-entity.attract-distance", 6.0),
                pickupDistance = yaml.getDouble("fake-entity.pickup-distance", 1.2),
                flySpeed = yaml.getDouble("fake-entity.fly-speed", 0.35),
                updateIntervalTicks = yaml.getLong("fake-entity.update-interval-ticks", 2).coerceAtLeast(1),
                bobbing = yaml.getBoolean("fake-entity.bobbing", true),
                bobbingHeight = yaml.getDouble("fake-entity.bobbing-height", 0.25),
                rotate = yaml.getBoolean("fake-entity.rotate", true),
                rotationSpeed = yaml.getDouble("fake-entity.rotation-speed", 6.0),
                glowByRarity = yaml.getBoolean("fake-entity.glow-by-rarity", true),
                defaultGlow = yaml.getBoolean("fake-entity.default-glow", false)
            ),
            navigation = NavigationConfig(
                actionbarEnabled = yaml.getBoolean("navigation.actionbar.enabled", true),
                intervalTicks = yaml.getLong("navigation.actionbar.interval-ticks", 20L).coerceAtLeast(1L),
                maxDistance = yaml.getDouble("navigation.actionbar.max-distance", 0.0).coerceAtLeast(0.0)
            ),
            effects = EffectsConfig(
                claim = ClaimEffectsConfig(
                    sound = SoundEffectConfig(
                        yaml.getBoolean("effects.claim.sound.enabled", true),
                        yaml.getString("effects.claim.sound.name", "ENTITY_PLAYER_LEVELUP") ?: "ENTITY_PLAYER_LEVELUP",
                        yaml.getDouble("effects.claim.sound.volume", 1.0).toFloat(),
                        yaml.getDouble("effects.claim.sound.pitch", 1.2).toFloat()
                    ),
                    particle = ParticleEffectConfig(
                        yaml.getBoolean("effects.claim.particle.enabled", true),
                        yaml.getString("effects.claim.particle.name", "VILLAGER_HAPPY") ?: "VILLAGER_HAPPY",
                        yaml.getInt("effects.claim.particle.count", 20),
                        yaml.getDouble("effects.claim.particle.offset-x", 0.4),
                        yaml.getDouble("effects.claim.particle.offset-y", 0.6),
                        yaml.getDouble("effects.claim.particle.offset-z", 0.4),
                        yaml.getDouble("effects.claim.particle.speed", 0.02)
                    ),
                    title = TitleEffectConfig(
                        yaml.getBoolean("effects.claim.title.enabled", true),
                        yaml.getInt("effects.claim.title.fade-in", 10),
                        yaml.getInt("effects.claim.title.stay", 40),
                        yaml.getInt("effects.claim.title.fade-out", 10)
                    ),
                    actionbar = ActionBarEffectConfig(
                        yaml.getBoolean("effects.claim.actionbar.enabled", true)
                    )
                ),
                idleParticle = IdleParticleEffectConfig(
                    enabled = yaml.getBoolean("effects.idle-particle.enabled", true),
                    name = yaml.getString("effects.idle-particle.name", "END_ROD") ?: "END_ROD",
                    intervalTicks = yaml.getLong("effects.idle-particle.interval-ticks", 10L).coerceAtLeast(1L),
                    count = yaml.getInt("effects.idle-particle.count", 2).coerceAtLeast(0),
                    offsetX = yaml.getDouble("effects.idle-particle.offset-x", 0.18),
                    offsetY = yaml.getDouble("effects.idle-particle.offset-y", 0.18),
                    offsetZ = yaml.getDouble("effects.idle-particle.offset-z", 0.18),
                    speed = yaml.getDouble("effects.idle-particle.speed", 0.01)
                )
            ),
            reload = ReloadConfig(
                clearActiveEntities = yaml.getBoolean("reload.clear-active-entities", true)
            )
        )
    }

    private fun parseSpawnRegion(
        yaml: YamlConfiguration,
        sectionPath: String,
        displayPath: String,
        fallback: SpawnRegionConfig,
        lang: LangConfig,
        errors: MutableList<String>
    ): SpawnRegionConfig {
        val modeText = yaml.getString("$sectionPath.mode", fallback.mode.name)
        val regionMode = SafeEnumParser.parse<SpawnRegionMode>(modeText)
        if (regionMode == null) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_REGION_MODE, "path" to "$displayPath.mode", "value" to modeText.orEmpty())
        }
        val minY = yaml.getInt("$sectionPath.min-y", fallback.minY)
        val maxY = yaml.getInt("$sectionPath.max-y", fallback.maxY)
        if (minY > maxY) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_Y_RANGE, "min" to minY.toString(), "max" to maxY.toString())
        }
        return SpawnRegionConfig(
            world = yaml.getString("$sectionPath.world", fallback.world) ?: fallback.world,
            mode = regionMode ?: fallback.mode,
            centerX = yaml.getInt("$sectionPath.center-x", fallback.centerX),
            centerZ = yaml.getInt("$sectionPath.center-z", fallback.centerZ),
            maxRadius = yaml.getInt("$sectionPath.max-radius", fallback.maxRadius),
            minX = yaml.getInt("$sectionPath.min-x", fallback.minX),
            maxX = yaml.getInt("$sectionPath.max-x", fallback.maxX),
            minZ = yaml.getInt("$sectionPath.min-z", fallback.minZ),
            maxZ = yaml.getInt("$sectionPath.max-z", fallback.maxZ),
            minY = minY,
            maxY = maxY
        )
    }

    private fun parseRarities(yaml: YamlConfiguration, lang: LangConfig, errors: MutableList<String>): Map<String, RarityDefinition> {
        val section = yaml.getConfigurationSection("rarities")
        if (section == null) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_RARITIES_MISSING, "path" to "rewards.yml/rarities")
            return emptyMap()
        }
        return section.getKeys(false).associateWith { id ->
            RarityDefinition(
                id = id,
                displayName = section.getString("$id.display-name", id) ?: id,
                chance = section.getDouble("$id.chance", -1.0),
                weight = section.getDouble("$id.weight", 0.0),
                broadcast = section.getBoolean("$id.broadcast", false),
                headGlow = section.getBoolean("$id.head-glow", false)
            )
        }.also { map ->
            map.values.filter { it.selectionWeight <= 0.0 }.forEach {
                errors += issue(lang, LangKeys.CONFIG_ERROR_RARITY_WEIGHT, "path" to "rarities.${it.id}.chance|weight")
            }
        }
    }

    private fun parseRewards(yaml: YamlConfiguration, rarityIds: Set<String>, lang: LangConfig, errors: MutableList<String>): Map<String, RewardDefinition> {
        val section = yaml.getConfigurationSection("rewards") ?: return emptyMap<String, RewardDefinition>().also {
            errors += issue(lang, LangKeys.CONFIG_ERROR_REWARDS_MISSING, "path" to "rewards.yml/rewards")
        }
        return section.getKeys(false).mapNotNull { id ->
            val rarity = section.getString("$id.rarity", "") ?: ""
            val commands = section.getStringList("$id.commands").map { it.removePrefix("/") }
            when {
                rarity !in rarityIds -> {
                    errors += issue(lang, LangKeys.CONFIG_ERROR_REWARD_RARITY_MISSING, "path" to "rewards.$id.rarity", "value" to rarity)
                    null
                }
                commands.isEmpty() -> {
                    errors += issue(lang, LangKeys.CONFIG_ERROR_REWARD_COMMANDS_EMPTY, "path" to "rewards.$id.commands")
                    null
                }
                else -> id to RewardDefinition(
                    id = id,
                    rarity = rarity,
                    displayName = section.getString("$id.display-name", id) ?: id,
                    weight = section.getDouble("$id.weight", 0.0),
                    commands = commands
                )
            }
        }.toMap()
    }

    private fun parseHeads(yaml: YamlConfiguration, rarityIds: Set<String>, lang: LangConfig, warnings: MutableList<String>, errors: MutableList<String>): Map<String, HeadDefinition> {
        val section = yaml.getConfigurationSection("heads") ?: return emptyMap<String, HeadDefinition>().also {
            errors += issue(lang, LangKeys.CONFIG_ERROR_HEADS_MISSING, "path" to "heads.yml/heads")
        }
        return section.getKeys(false).mapNotNull { id ->
            val texture = section.getString("$id.texture", "") ?: ""
            val rarity = section.getString("$id.rarity", "") ?: ""
            when {
                rarity !in rarityIds -> {
                    errors += issue(lang, LangKeys.CONFIG_ERROR_HEAD_RARITY_MISSING, "path" to "heads.$id.rarity", "value" to rarity)
                    null
                }
                texture.isBlank() -> {
                    warnings += issue(lang, LangKeys.CONFIG_WARNING_HEAD_TEXTURE_EMPTY, "path" to "heads.$id.texture")
                    null
                }
                texture == "CHANGE_ME_BASE64_TEXTURE" -> {
                    warnings += issue(lang, LangKeys.CONFIG_WARNING_HEAD_TEXTURE_PLACEHOLDER, "path" to "heads.$id.texture")
                    null
                }
                else -> id to HeadDefinition(
                    id = id,
                    displayName = section.getString("$id.display-name", id) ?: id,
                    texture = texture,
                    lore = section.getStringList("$id.lore"),
                    rarity = rarity,
                    weight = section.getDouble("$id.weight", 0.0)
                )
            }
        }.toMap()
    }

    private fun parseLang(yaml: YamlConfiguration): LangConfig {
        val section = yaml.getConfigurationSection("messages")
        val loaded = section?.getKeys(false)?.associateWith { section.getString(it, "") ?: "" }.orEmpty()
        return LangConfig(
            prefix = yaml.getString("prefix", "") ?: "",
            messages = DEFAULT_LANG_MESSAGES + loaded
        )
    }

    private fun issue(lang: LangConfig, key: String, vararg values: Pair<String, String>): String {
        return lang.format(key, values.toMap())
    }

    private fun sanitizeActivityName(name: String): String {
        val trimmed = name.trim()
        if (!ACTIVITY_NAME.matches(trimmed)) throw IllegalArgumentException(trimmed)
        return trimmed
    }

    private fun safeFileName(value: String): String {
        val trimmed = value.trim()
        require(FILE_NAME.matches(trimmed)) { trimmed }
        return trimmed
    }

    private fun safeRelativePath(value: String): String {
        val trimmed = value.trim().replace('\\', '/').trim('/')
        require(trimmed.isNotBlank() && !trimmed.contains("..") && RELATIVE_PATH.matches(trimmed)) { trimmed }
        return trimmed
    }

    companion object {
        private const val ROOT_CONFIG_FILE = "config.yml"
        private val ACTIVITY_NAME = Regex("[A-Za-z0-9_-]+")
        private val FILE_NAME = Regex("[A-Za-z0-9_.-]+")
        private val RELATIVE_PATH = Regex("[A-Za-z0-9_./-]+")
        private val DEFAULT_LANG_MESSAGES = mapOf(
            LangKeys.NAVIGATION_ACTIONBAR to "<#FFD700>最近奖励点 <#FFFFFF>%distance%m <#AAAAAA>| <#55FFFF>%direction% <#AAAAAA>| <#FFFFFF>%world% %x%, %y%, %z% <#AAAAAA>| <head>",
            LangKeys.NAVIGATION_ACTIONBAR_EMPTY to "<#AAAAAA>等待奖励点生成中...",
            LangKeys.DIRECTION_FRONT to "<#55FF55>前方",
            LangKeys.DIRECTION_FRONT_LEFT to "<#55FF55>左前",
            LangKeys.DIRECTION_LEFT to "<#55FF55>左侧",
            LangKeys.DIRECTION_BACK_LEFT to "<#55FF55>左后",
            LangKeys.DIRECTION_BACK to "<#55FF55>后方",
            LangKeys.DIRECTION_BACK_RIGHT to "<#55FF55>右后",
            LangKeys.DIRECTION_RIGHT to "<#55FF55>右侧",
            LangKeys.DIRECTION_FRONT_RIGHT to "<#55FF55>右前"
        )
    }
}
