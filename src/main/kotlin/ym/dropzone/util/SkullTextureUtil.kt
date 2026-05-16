package ym.dropzone.util

import org.bukkit.Material
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object SkullTextureUtil {
    private val fallbackLogged = ConcurrentHashMap.newKeySet<String>()
    private val textureHashPattern = Pattern.compile("^[a-fA-F0-9]{32,128}$")

    fun createHead(texture: String): ItemStack {
        val item = ItemStack(Material.PLAYER_HEAD, 1)
        val meta = item.itemMeta as? SkullMeta ?: return item
        val normalized = normalizeTexture(texture)
        val applied = normalized != null && (applyBukkitProfile(meta, normalized) || applyAuthlibProfile(meta, normalized))
        if (!applied && normalized != null && fallbackLogged.add(normalized.take(48))) {
            Bukkit.getLogger().fine("[DropZone] Skull texture write failed; using plain PLAYER_HEAD fallback.")
        }
        item.itemMeta = meta
        return item
    }

    private fun applyBukkitProfile(meta: SkullMeta, texture: String): Boolean {
        return runCatching {
            val uuid = UUID.nameUUIDFromBytes(texture.toByteArray(Charsets.UTF_8))
            val createProfile = Bukkit::class.java.methods.firstOrNull {
                (it.name == "createPlayerProfile" || it.name == "createProfile") &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == UUID::class.java &&
                    it.parameterTypes[1] == String::class.java
            } ?: Bukkit::class.java.methods.firstOrNull {
                (it.name == "createPlayerProfile" || it.name == "createProfile") &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == UUID::class.java
            } ?: return false
            val playerProfile = if (createProfile.parameterTypes.size == 2) {
                createProfile.invoke(null, uuid, "DropZone")
            } else {
                createProfile.invoke(null, uuid)
            } ?: return false
            val textures = playerProfile.javaClass.getMethod("getTextures").invoke(playerProfile)
            val url = decodeTextureUrl(texture) ?: return@runCatching false
            val setSkin = textures.javaClass.methods.firstOrNull { it.name == "setSkin" && it.parameterTypes.size == 1 } ?: return@runCatching false
            setSkin.invoke(textures, java.net.URI(url).toURL())
            val setter = meta.javaClass.methods.firstOrNull {
                (it.name == "setOwnerProfile" || it.name == "setPlayerProfile") && it.parameterTypes.size == 1
            } ?: return@runCatching false
            setter.invoke(meta, playerProfile)
            true
        }.onFailure { error ->
            Bukkit.getLogger().fine("[DropZone] Bukkit skull profile write failed: ${error.javaClass.simpleName}: ${error.message}")
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
            val property = createTextureProperty(propertyClass, texture)
            properties.javaClass.getMethod("put", Any::class.java, Any::class.java).invoke(properties, "textures", property)
            val field = findProfileField(meta.javaClass) ?: return@runCatching false
            field.isAccessible = true
            field.set(meta, profile)
            true
        }.onFailure { error ->
            Bukkit.getLogger().fine("[DropZone] Authlib skull profile write failed: ${error.javaClass.simpleName}: ${error.message}")
        }.getOrDefault(false)
    }

    private fun normalizeTexture(texture: String): String? {
        val trimmed = texture.trim()
        if (trimmed.isBlank() || trimmed == "CHANGE_ME_BASE64_TEXTURE") return null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return encodeTextureUrl(trimmed)
        }
        if (textureHashPattern.matcher(trimmed).matches()) {
            return encodeTextureUrl("http://textures.minecraft.net/texture/$trimmed")
        }
        return trimmed
    }

    private fun encodeTextureUrl(url: String): String {
        val json = """{"textures":{"SKIN":{"url":"$url"}}}"""
        return java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    private fun decodeTextureUrl(texture: String): String? {
        return runCatching {
            val json = String(java.util.Base64.getDecoder().decode(texture), Charsets.UTF_8)
            Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        }.getOrNull()
    }

    private fun createTextureProperty(propertyClass: Class<*>, texture: String): Any {
        val twoArg = propertyClass.constructors.firstOrNull { it.parameterTypes.size == 2 }
        if (twoArg != null) return twoArg.newInstance("textures", texture)
        val threeArg = propertyClass.constructors.first { it.parameterTypes.size == 3 }
        return threeArg.newInstance("textures", texture, null)
    }

    private fun findProfileField(type: Class<*>): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            val field = current.declaredFields.firstOrNull { it.name == "profile" }
            if (field != null) return field
            current = current.superclass
        }
        return null
    }
}
