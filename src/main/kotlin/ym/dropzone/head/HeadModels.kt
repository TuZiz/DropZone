package ym.dropzone.head

import org.bukkit.inventory.ItemStack
import ym.dropzone.util.SkullTextureUtil
import ym.dropzone.util.WeightedRandom

data class HeadDefinition(
    val id: String,
    val displayName: String,
    val texture: String,
    val lore: List<String>,
    val rarity: String,
    val weight: Double
)

class HeadFactory {
    fun create(definition: HeadDefinition): ItemStack = SkullTextureUtil.createHead(definition.texture)
}

class HeadSelector {
    fun select(heads: Collection<HeadDefinition>): HeadDefinition? =
        WeightedRandom.choose(heads) { it.weight }
}
