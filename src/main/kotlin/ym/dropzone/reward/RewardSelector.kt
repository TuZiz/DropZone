package ym.dropzone.reward

import ym.dropzone.config.RewardSelectionMode
import ym.dropzone.config.RuntimeConfigSnapshot
import ym.dropzone.head.HeadSelector
import ym.dropzone.util.WeightedRandom

class RewardSelector(private val headSelector: HeadSelector) {
    fun roll(snapshot: RuntimeConfigSnapshot): RewardRollResult? {
        // 两种抽取模式都保证奖励和头颅来自同一个稀有度。
        return when (snapshot.main.rewardSelectionMode) {
            RewardSelectionMode.RARITY_THEN_REWARD -> rollRarityThenReward(snapshot)
            RewardSelectionMode.RANDOM_REWARD_THEN_MATCH_HEAD -> rollRewardThenHead(snapshot)
        }
    }

    private fun rollRarityThenReward(snapshot: RuntimeConfigSnapshot): RewardRollResult? {
        val rarity = WeightedRandom.choose(snapshot.usableRarities()) { it.selectionWeight } ?: return null
        val reward = WeightedRandom.choose(snapshot.rewardsByRarity[rarity.id].orEmpty()) { it.weight } ?: return null
        val head = headSelector.select(snapshot.headsByRarity[rarity.id].orEmpty()) ?: return null
        return RewardRollResult(rarity, reward, head)
    }

    private fun rollRewardThenHead(snapshot: RuntimeConfigSnapshot): RewardRollResult? {
        val usableRewards = snapshot.rewards.values.filter { reward ->
            snapshot.rarities.containsKey(reward.rarity) && !snapshot.headsByRarity[reward.rarity].isNullOrEmpty()
        }
        val reward = WeightedRandom.choose(usableRewards) { it.weight } ?: return null
        val rarity = snapshot.rarities[reward.rarity] ?: return null
        val head = headSelector.select(snapshot.headsByRarity[reward.rarity].orEmpty()) ?: return null
        return RewardRollResult(rarity, reward, head)
    }
}
