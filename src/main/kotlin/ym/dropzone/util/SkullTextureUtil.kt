package ym.dropzone.util

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID

object SkullTextureUtil {
    fun createHead(texture: String): ItemStack {
        val item = ItemStack(Material.PLAYER_HEAD, 1)
        val meta = item.itemMeta as? SkullMeta ?: return item
        applyBukkitProfile(meta, texture) || applyAuthlibProfile(meta, texture)
        item.itemMeta = meta
        return item
    }

    private fun applyBukkitProfile(meta: SkullMeta, texture: String): Boolean {
        val setter = meta.javaClass.methods.firstOrNull {
            it.name == "setPlayerProfile" && it.parameterTypes.size == 1
        } ?: return false
        return runCatching {
            val bukkit = Class.forName("org.bukkit.Bukkit")
            val createProfile = bukkit.getMethod("createProfile", UUID::class.java, String::class.java)
            val playerProfile = createProfile.invoke(null, UUID.nameUUIDFromBytes(texture.toByteArray(Charsets.UTF_8)), "DropZone")
            val textures = playerProfile.javaClass.getMethod("getTextures").invoke(playerProfile)
            val url = decodeTextureUrl(texture) ?: return@runCatching false
            val setSkin = textures.javaClass.methods.firstOrNull { it.name == "setSkin" && it.parameterTypes.size == 1 } ?: return@runCatching false
            setSkin.invoke(textures, java.net.URL(url))
            setter.invoke(meta, playerProfile)
            true
        }.getOrDefault(false)
    }

    private fun applyAuthlibProfile(meta: SkullMeta, texture: String): Boolean {
        return runCatching {
            val gameProfileClass = Class.forName("com.mojang.authlib.GameProfile")
            val propertyClass = Class.forName("com.mojang.authlib.properties.Property")
            val profile = gameProfileClass
                .getConstructor(UUID::class.java, String::class.java)
                .newInstance(UUID.nameUUIDFromBytes(texture.toByteArray(Charsets.UTF_8)), "DropZone")
            val properties = gameProfileClass.getMethod("getProperties").invoke(profile)
            val property = propertyClass.getConstructor(String::class.java, String::class.java).newInstance("textures", texture)
            properties.javaClass.getMethod("put", Any::class.java, Any::class.java).invoke(properties, "textures", property)
            val field = meta.javaClass.getDeclaredField("profile")
            field.isAccessible = true
            field.set(meta, profile)
            true
        }.getOrDefault(false)
    }

    private fun decodeTextureUrl(texture: String): String? {
        return runCatching {
            val json = String(java.util.Base64.getDecoder().decode(texture), Charsets.UTF_8)
            Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        }.getOrNull()
    }
}
