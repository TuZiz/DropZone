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
        val activity = parseActivity(activeActivity, activityYaml)
        if (!activity.enabled) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_ACTIVITY_DISABLED, "activity" to activeActivity)
        }
        val headsYaml = YamlConfiguration.loadConfiguration(File(activityFolder, files.headsFile))
        val rewardsYaml = YamlConfiguration.loadConfiguration(File(activityFolder, files.rewardsFile))
        val main = parseMain(mainYaml, lang, errors)
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

    private fun parseActivity(id: String, yaml: YamlConfiguration): ActivityConfig {
        return ActivityConfig(
            id = id,
            displayName = yaml.getString("activity.display-name", id) ?: id,
            enabled = yaml.getBoolean("activity.enabled", true),
            rules = ActivityRulesConfig(
                clearActiveOnStart = yaml.getBoolean("rules.clear-active-on-start", true),
                maxClaimsPerPlayer = yaml.getInt("rules.max-claims-per-player", 0).coerceAtLeast(0),
                claimCooldownSeconds = yaml.getLong("rules.claim-cooldown-seconds", 0).coerceAtLeast(0),
                allowRepeatRewards = yaml.getBoolean("rules.allow-repeat-rewards", true)
            )
        )
    }

    private fun parseMain(yaml: YamlConfiguration, lang: LangConfig, errors: MutableList<String>): MainConfig {
        // 主配置只解析玩法参数，不解析玩家可见文案。
        val modeText = yaml.getString("spawn-region.mode", "MAX_RADIUS")
        val regionMode = SafeEnumParser.parse<SpawnRegionMode>(modeText)
        if (regionMode == null) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_REGION_MODE, "path" to "config.spawn-region.mode", "value" to modeText.orEmpty())
        }
        val selectionText = yaml.getString("reward-selection.mode", "RARITY_THEN_REWARD")
        val selectionMode = SafeEnumParser.parse<RewardSelectionMode>(selectionText)
        if (selectionMode == null) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_REWARD_SELECTION_MODE, "path" to "config.reward-selection.mode", "value" to selectionText.orEmpty())
        }
        val minY = yaml.getInt("spawn-region.min-y", 80)
        val maxY = yaml.getInt("spawn-region.max-y", 160)
        if (minY > maxY) {
            errors += issue(lang, LangKeys.CONFIG_ERROR_Y_RANGE, "min" to minY.toString(), "max" to maxY.toString())
        }

        return MainConfig(
            debug = yaml.getBoolean("settings.debug", false),
            language = yaml.getString("settings.language", "zh_CN") ?: "zh_CN",
            activityFiles = parseActivityFiles(yaml),
            stateStorage = parseStateStorage(yaml),
            spawnRegion = SpawnRegionConfig(
                world = yaml.getString("spawn-region.world", "world") ?: "world",
                mode = regionMode ?: SpawnRegionMode.MAX_RADIUS,
                centerX = yaml.getInt("spawn-region.center-x", 0),
                centerZ = yaml.getInt("spawn-region.center-z", 0),
                maxRadius = yaml.getInt("spawn-region.max-radius", 5000),
                minX = yaml.getInt("spawn-region.min-x", -3000),
                maxX = yaml.getInt("spawn-region.max-x", 3000),
                minZ = yaml.getInt("spawn-region.min-z", -3000),
                maxZ = yaml.getInt("spawn-region.max-z", 3000),
                minY = minY,
                maxY = maxY
            ),
            locationRules = LocationRulesConfig(
                requireAir = yaml.getBoolean("location-rules.require-air", true),
                requireSolidGround = yaml.getBoolean("location-rules.require-solid-ground", true),
                allowWater = yaml.getBoolean("location-rules.allow-water", false),
                allowLava = yaml.getBoolean("location-rules.allow-lava", false),
                avoidBlocks = yaml.getStringList("location-rules.avoid-blocks").map { it.uppercase() }.toSet(),
                maxLocationAttempts = yaml.getInt("location-rules.max-location-attempts", 50).coerceAtLeast(1),
                allowUnloadedChunks = yaml.getBoolean("location-rules.allow-unloaded-chunks", false),
                loadChunkIfNeeded = yaml.getBoolean("location-rules.load-chunk-if-needed", false)
            ),
            rewardSelectionMode = selectionMode ?: RewardSelectionMode.RARITY_THEN_REWARD,
            spawn = SpawnConfig(
                enabled = yaml.getBoolean("spawn.enabled", true),
                intervalSeconds = yaml.getLong("spawn.interval-seconds", 300).coerceAtLeast(1),
                maxActive = yaml.getInt("spawn.max-active", 10).coerceAtLeast(0),
                despawnSeconds = yaml.getLong("spawn.despawn-seconds", 600).coerceAtLeast(1),
                spawnOnStartup = yaml.getBoolean("spawn.spawn-on-startup", true),
                startupAmount = yaml.getInt("spawn.startup-amount", 3).coerceAtLeast(0),
                attemptsPerCycle = yaml.getInt("spawn.attempts-per-cycle", 3).coerceAtLeast(1)
            ),
            fakeEntity = FakeEntityConfig(
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
                )
            ),
            reload = ReloadConfig(
                clearActiveEntities = yaml.getBoolean("reload.clear-active-entities", true)
            )
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
        val messages = section?.getKeys(false)?.associateWith { section.getString(it, "") ?: "" }.orEmpty()
        return LangConfig(
            prefix = yaml.getString("prefix", "") ?: "",
            messages = messages
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
    }
}
