package ym.dropzone.config

data class MainConfig(
    val debug: Boolean,
    val language: String,
    val activityFiles: ActivityFilesConfig,
    val stateStorage: StateStorageConfig,
    val spawnRegion: SpawnRegionConfig,
    val locationRules: LocationRulesConfig,
    val rewardSelectionMode: RewardSelectionMode,
    val spawn: SpawnConfig,
    val fakeEntity: FakeEntityConfig,
    val effects: EffectsConfig,
    val reload: ReloadConfig
)

data class ActivityFilesConfig(
    val rootFolder: String,
    val defaultActivity: String,
    val configFile: String,
    val headsFile: String,
    val rewardsFile: String
)

data class StateStorageConfig(
    val mode: String,
    val folder: String,
    val activeActivityFile: String
)

enum class RewardSelectionMode {
    RARITY_THEN_REWARD,
    RANDOM_REWARD_THEN_MATCH_HEAD
}

data class SpawnRegionConfig(
    val world: String,
    val mode: SpawnRegionMode,
    val centerX: Int,
    val centerZ: Int,
    val maxRadius: Int,
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
    val minY: Int,
    val maxY: Int
)

enum class SpawnRegionMode {
    MAX_RADIUS,
    BOX
}

data class LocationRulesConfig(
    val requireAir: Boolean,
    val requireSolidGround: Boolean,
    val allowWater: Boolean,
    val allowLava: Boolean,
    val avoidBlocks: Set<String>,
    val maxLocationAttempts: Int,
    val allowUnloadedChunks: Boolean,
    val loadChunkIfNeeded: Boolean
)

data class SpawnConfig(
    val enabled: Boolean,
    val intervalSeconds: Long,
    val maxActive: Int,
    val despawnSeconds: Long,
    val spawnOnStartup: Boolean,
    val startupAmount: Int,
    val attemptsPerCycle: Int
)

data class FakeEntityConfig(
    val viewDistance: Double,
    val attractDistance: Double,
    val pickupDistance: Double,
    val flySpeed: Double,
    val updateIntervalTicks: Long,
    val bobbing: Boolean,
    val bobbingHeight: Double,
    val rotate: Boolean,
    val rotationSpeed: Double,
    val glowByRarity: Boolean,
    val defaultGlow: Boolean
)

data class EffectsConfig(
    val claim: ClaimEffectsConfig
)

data class ClaimEffectsConfig(
    val sound: SoundEffectConfig,
    val particle: ParticleEffectConfig,
    val title: TitleEffectConfig,
    val actionbar: ActionBarEffectConfig
)

data class SoundEffectConfig(
    val enabled: Boolean,
    val name: String,
    val volume: Float,
    val pitch: Float
)

data class ParticleEffectConfig(
    val enabled: Boolean,
    val name: String,
    val count: Int,
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val speed: Double
)

data class TitleEffectConfig(
    val enabled: Boolean,
    val fadeIn: Int,
    val stay: Int,
    val fadeOut: Int
)

data class ActionBarEffectConfig(
    val enabled: Boolean
)

data class ReloadConfig(
    val clearActiveEntities: Boolean
)
