package ym.dropzone.head

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import ym.dropzone.util.SkullTextureUtil
import ym.dropzone.util.WeightedRandom
import ym.dropzone.util.MiniMessageUtil
import ym.dropzone.util.PlainTextUtil

data class HeadDefinition(
    val id: String,
    val displayName: String,
    val texture: String,
    val lore: List<String>,
    val rarity: String,
    val weight: Double
)

class HeadFactory {
    fun create(definition: HeadDefinition): ItemStack {
        val item = SkullTextureUtil.createHead(definition.texture)
        val meta = item.itemMeta ?: return item
        applyDisplay(meta, definition)
        item.itemMeta = meta
        return item
    }

    private fun applyDisplay(meta: ItemMeta, definition: HeadDefinition) {
        val display = MiniMessageUtil.deserialize(definition.displayName)
        val componentDisplayMethod = meta.javaClass.methods.firstOrNull {
            it.name == "displayName" && it.parameterTypes.size == 1
        }
        if (componentDisplayMethod != null) {
            runCatching { componentDisplayMethod.invoke(meta, display) }
                .onFailure { meta.setDisplayName(PlainTextUtil.stripMiniMessage(definition.displayName)) }
        } else {
            meta.setDisplayName(PlainTextUtil.stripMiniMessage(definition.displayName))
        }
        if (definition.lore.isNotEmpty()) {
            val loreComponents = definition.lore.map { MiniMessageUtil.deserialize(it) }
            val componentLoreMethod = meta.javaClass.methods.firstOrNull {
                it.name == "lore" && it.parameterTypes.size == 1
            }
            if (componentLoreMethod != null) {
                runCatching { componentLoreMethod.invoke(meta, loreComponents) }
                    .onFailure { meta.lore = definition.lore.map { PlainTextUtil.stripMiniMessage(it) } }
            } else {
                meta.lore = definition.lore.map { PlainTextUtil.stripMiniMessage(it) }
            }
        }
    }
}

class HeadSelector {
    fun select(heads: Collection<HeadDefinition>): HeadDefinition? =
        WeightedRandom.choose(heads) { it.weight }
}
