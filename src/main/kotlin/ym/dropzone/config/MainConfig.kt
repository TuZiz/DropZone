package ym.dropzone.config

data class MainConfig(
    val debug: Boolean,
    val language: String,
    val activityFiles: ActivityFilesConfig,
    val stateStorage: StateStorageConfig,
    val server: ServerConfig,
    val crossServer: CrossServerConfig,
    val claim: ClaimConfig,
    val locationRules: LocationRulesConfig,
    val rewardSelectionMode: RewardSelectionMode,
    val rewardCommandExecutorMode: RewardCommandExecutorMode,
    val spawn: SpawnConfig,
    val fakeEntity: FakeEntityConfig,
    val navigation: NavigationConfig,
    val effects: EffectsConfig,
    val rewardOutbox: RewardOutboxConfig,
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
    val mode: StorageMode,
    val folder: String,
    val activeActivityFile: String,
    val mysql: MysqlConfig
)

enum class StorageMode {
    LOCAL_JSON,
    MYSQL
}

data class MysqlConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val params: String,
    val poolSize: Int,
    val connectionTimeoutMs: Long,
    val maxLifetimeMs: Long
)

data class ServerConfig(
    val id: String,
    val group: String
)

data class CrossServerConfig(
    val enabled: Boolean,
    val syncIntervalSeconds: Long,
    val spawnOwnerMode: SpawnOwnerMode
)

enum class SpawnOwnerMode {
    ANY_SERVER
}

data class ClaimConfig(
    val deniedIgnoreSeconds: Long
)

enum class RewardSelectionMode {
    RARITY_THEN_REWARD,
    RANDOM_REWARD_THEN_MATCH_HEAD
}

enum class RewardCommandExecutorMode {
    PLAYER_REGION,
    GLOBAL,
    GLOBAL_SAFE
}

enum class SpawnCrossServerMode {
    LOCAL_ONLY,
    DATABASE_LOCK
}

enum class ManualSpawnMode {
    PLAYER_NEAR,
    REGION_RANDOM
}

enum class PacketBackend {
    PROTOCOLLIB,
    PACKETEVENTS
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
    val loadChunkIfNeeded: Boolean,
    val maxSyncChunkLoadsPerCycle: Int
)

data class SpawnConfig(
    val enabled: Boolean,
    val crossServerMode: SpawnCrossServerMode,
    val intervalSeconds: Long,
    val maxActive: Int,
    val despawnSeconds: Long,
    val spawnOnStartup: Boolean,
    val startupAmount: Int,
    val attemptsPerCycle: Int,
    val manual: ManualSpawnConfig
)

data class ManualSpawnConfig(
    val mode: ManualSpawnMode,
    val amount: Int,
    val maxAmount: Int,
    val nearRadius: Int,
    val respectRegion: Boolean
)

data class FakeEntityConfig(
    val packetBackend: PacketBackend,
    val debugPackets: Boolean,
    val debugVisibleArmorStand: Boolean,
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

data class NavigationConfig(
    val actionbarEnabled: Boolean,
    val intervalTicks: Long,
    val maxDistance: Double
)

data class EffectsConfig(
    val claim: ClaimEffectsConfig,
    val idleParticle: IdleParticleEffectConfig
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

data class IdleParticleEffectConfig(
    val enabled: Boolean,
    val name: String,
    val intervalTicks: Long,
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

data class RewardOutboxConfig(
    val enabled: Boolean,
    val pollIntervalSeconds: Long,
    val maxAttempts: Int,
    val claimBatchSize: Int,
    val processingTimeoutSeconds: Long,
    val consumeMode: OutboxConsumeMode
)

enum class OutboxConsumeMode {
    CURRENT_SERVER,
    SAME_GROUP,
    ANY_SERVER
}
