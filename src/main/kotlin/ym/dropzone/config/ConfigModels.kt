package ym.dropzone.config

import ym.dropzone.head.HeadDefinition
import ym.dropzone.reward.RarityDefinition
import ym.dropzone.reward.RewardDefinition

data class ConfigValidationResult(
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList()
) {
    val success: Boolean get() = errors.isEmpty()

    companion object {
        fun ok(warnings: List<String> = emptyList()) = ConfigValidationResult(warnings, emptyList())
        fun failed(errors: List<String>, warnings: List<String> = emptyList()) = ConfigValidationResult(warnings, errors)
    }
}

data class HeadConfig(
    val heads: Map<String, HeadDefinition>
)

data class RewardConfig(
    val rarities: Map<String, RarityDefinition>,
    val rewards: Map<String, RewardDefinition>
)

data class ActivityConfig(
    val id: String,
    val displayName: String,
    val enabled: Boolean,
    val rules: ActivityRulesConfig
)

data class ActivityRulesConfig(
    val clearActiveOnStart: Boolean,
    val maxClaimsPerPlayer: Int,
    val claimCooldownSeconds: Long,
    val allowRepeatRewards: Boolean
)

data class LangConfig(
    val prefix: String,
    val messages: Map<String, String>
) {
    fun format(key: String, values: Map<String, String> = emptyMap()): String {
        var result = messages[key] ?: key
        values.forEach { (name, value) -> result = result.replace("%$name%", value) }
        return result.replace("%prefix%", prefix)
    }
}

object LangKeys {
    const val REWARD_CLAIMED = "reward-claimed"
    const val REWARD_BROADCAST = "reward-broadcast"
    const val CLAIM_TITLE = "claim-title"
    const val CLAIM_SUBTITLE = "claim-subtitle"
    const val CLAIM_ACTIONBAR = "claim-actionbar"
    const val CLAIM_DENIED_COOLDOWN = "claim-denied-cooldown"
    const val CLAIM_DENIED_MAX_CLAIMS = "claim-denied-max-claims"
    const val CLAIM_DENIED_REPEAT_REWARD = "claim-denied-repeat-reward"
    const val RELOAD_START = "reload-start"
    const val RELOAD_SUCCESS = "reload-success"
    const val RELOAD_FAILED = "reload-failed"
    const val NO_PERMISSION = "no-permission"
    const val ADMIN_SPAWN_SUCCESS = "admin-spawn-success"
    const val ADMIN_SPAWN_FAILED = "admin-spawn-failed"
    const val ADMIN_CLEAR_SUCCESS = "admin-clear-success"
    const val ADMIN_START_SUCCESS = "admin-start-success"
    const val ADMIN_START_FAILED = "admin-start-failed"
    const val LIST_HEADER = "list-header"
    const val LIST_LINE = "list-line"
    const val DEBUG_HEADER = "debug-header"
    const val DEBUG_LINE = "debug-line"
    const val DEBUG_ACTIVE = "debug-active"
    const val DEBUG_RARITIES = "debug-rarities"
    const val DEBUG_HEADS = "debug-heads"
    const val DEBUG_REWARDS = "debug-rewards"
    const val DEBUG_SPAWN_ENABLED = "debug-spawn-enabled"
    const val DEBUG_ACTIVITY = "debug-activity"
    const val CONSOLE_CONFIG_LOAD_FAILED = "console-config-load-failed"
    const val CONSOLE_RELOAD_FAILED = "console-reload-failed"
    const val CONSOLE_INVALID_WORLD = "console-invalid-world"
    const val CONSOLE_INVALID_SOUND = "console-invalid-sound"
    const val CONSOLE_INVALID_PARTICLE = "console-invalid-particle"
    const val CONSOLE_PAPI_REGISTER_FAILED = "console-papi-register-failed"
    const val CONFIG_WARNING_RARITY_NO_REWARDS = "config-warning-rarity-no-rewards"
    const val CONFIG_WARNING_RARITY_NO_HEADS = "config-warning-rarity-no-heads"
    const val CONFIG_WARNING_HEAD_TEXTURE_EMPTY = "config-warning-head-texture-empty"
    const val CONFIG_WARNING_HEAD_TEXTURE_PLACEHOLDER = "config-warning-head-texture-placeholder"
    const val CONFIG_ERROR_REGION_MODE = "config-error-region-mode"
    const val CONFIG_ERROR_REWARD_SELECTION_MODE = "config-error-reward-selection-mode"
    const val CONFIG_ERROR_REWARD_COMMAND_EXECUTOR_MODE = "config-error-reward-command-executor-mode"
    const val CONFIG_ERROR_Y_RANGE = "config-error-y-range"
    const val CONFIG_ERROR_RARITIES_MISSING = "config-error-rarities-missing"
    const val CONFIG_ERROR_RARITY_WEIGHT = "config-error-rarity-weight"
    const val CONFIG_ERROR_REWARDS_MISSING = "config-error-rewards-missing"
    const val CONFIG_ERROR_REWARD_RARITY_MISSING = "config-error-reward-rarity-missing"
    const val CONFIG_ERROR_REWARD_COMMANDS_EMPTY = "config-error-reward-commands-empty"
    const val CONFIG_ERROR_HEADS_MISSING = "config-error-heads-missing"
    const val CONFIG_ERROR_HEAD_RARITY_MISSING = "config-error-head-rarity-missing"
    const val CONFIG_ERROR_ACTIVITY_FOLDER = "config-error-activity-folder"
    const val CONFIG_ERROR_ACTIVITY_DISABLED = "config-error-activity-disabled"
}

data class RuntimeConfigSnapshot(
    val main: MainConfig,
    val activity: ActivityConfig,
    val heads: Map<String, HeadDefinition>,
    val rarities: Map<String, RarityDefinition>,
    val rewards: Map<String, RewardDefinition>,
    val lang: LangConfig,
    val headsByRarity: Map<String, List<HeadDefinition>>,
    val rewardsByRarity: Map<String, List<RewardDefinition>>,
    val warnings: List<String>
) {
    fun usableRarities(): List<RarityDefinition> = rarities.values.filter {
        it.selectionWeight > 0.0 &&
            !headsByRarity[it.id].isNullOrEmpty() &&
            !rewardsByRarity[it.id].isNullOrEmpty()
    }
}
