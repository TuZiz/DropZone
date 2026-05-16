package ym.dropzone.reward

import ym.dropzone.head.HeadDefinition

data class RarityDefinition(
    val id: String,
    val displayName: String,
    val chance: Double,
    val weight: Double,
    val broadcast: Boolean,
    val headGlow: Boolean
) {
    val selectionWeight: Double get() = if (chance > 0.0) chance else weight
}

data class RewardDefinition(
    val id: String,
    val rarity: String,
    val displayName: String,
    val weight: Double,
    val commands: List<String>
)

data class RewardRollResult(
    val rarity: RarityDefinition,
    val reward: RewardDefinition,
    val head: HeadDefinition
)
