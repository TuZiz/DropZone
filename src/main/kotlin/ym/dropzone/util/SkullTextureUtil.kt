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
            Bukkit.getLogger().warning("[DropZone] Skull texture write failed; using plain PLAYER_HEAD fallback.")
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
            } ?: return bukkitProfileFailed("createPlayerProfile/createProfile method not found")
            createProfile.isAccessible = true
            val playerProfile = if (createProfile.parameterTypes.size == 2) {
                createProfile.invoke(null, uuid, "DropZone")
            } else {
                createProfile.invoke(null, uuid)
            } ?: return bukkitProfileFailed("created profile is null")
            val getTextures = playerProfile.javaClass.getMethod("getTextures")
            getTextures.isAccessible = true
            val textures = getTextures.invoke(playerProfile)
                ?: return@runCatching bukkitProfileFailed("getTextures returned null")
            val url = decodeTextureUrl(texture) ?: return@runCatching bukkitProfileFailed("texture url missing")
            val setSkin = textures.javaClass.methods.firstOrNull { it.name == "setSkin" && it.parameterTypes.size == 1 }
                ?: return@runCatching bukkitProfileFailed("PlayerTextures.setSkin(URL) method not found")
            setSkin.isAccessible = true
            setSkin.invoke(textures, java.net.URI(url).toURL())
            playerProfile.javaClass.methods.firstOrNull {
                it.name == "setTextures" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(textures.javaClass)
            }?.let {
                it.isAccessible = true
                it.invoke(playerProfile, textures)
            }
            val setter = (SkullMeta::class.java.methods.toList() + meta.javaClass.methods.toList()).firstOrNull {
                (it.name == "setOwnerProfile" || it.name == "setPlayerProfile") &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(playerProfile.javaClass)
            } ?: return@runCatching bukkitProfileFailed("SkullMeta owner profile setter not found")
            setter.isAccessible = true
            setter.invoke(meta, playerProfile)
            true
        }.onFailure { error ->
            Bukkit.getLogger().warning("[DropZone] Bukkit skull profile write failed: ${error.javaClass.simpleName}: ${error.message}")
        }.getOrDefault(false)
    }

    private fun applyAuthlibProfile(meta: SkullMeta, texture: String): Boolean {
        return runCatching {
            val gameProfileClass = Class.forName("com.mojang.authlib.GameProfile")
            val propertyClass = Class.forName("com.mojang.authlib.properties.Property")
            val profileConstructor = gameProfileClass.getConstructor(UUID::class.java, String::class.java)
            profileConstructor.isAccessible = true
            val profile = profileConstructor.newInstance(UUID.nameUUIDFromBytes(texture.toByteArray(Charsets.UTF_8)), "DropZone")
            val propertiesMethod = gameProfileClass.methods.firstOrNull {
                (it.name == "getProperties" || it.name == "properties") && it.parameterTypes.isEmpty()
            } ?: return@runCatching authlibProfileFailed("GameProfile properties method not found")
            propertiesMethod.isAccessible = true
            val properties = propertiesMethod.invoke(profile)
            val property = createTextureProperty(propertyClass, texture)
            val put = properties.javaClass.getMethod("put", Any::class.java, Any::class.java)
            put.isAccessible = true
            put.invoke(properties, "textures", property)
            val field = findProfileField(meta.javaClass) ?: return@runCatching authlibProfileFailed("profile field not found")
            field.isAccessible = true
            field.set(meta, profile)
            true
        }.onFailure { error ->
            Bukkit.getLogger().warning("[DropZone] Authlib skull profile write failed: ${error.javaClass.simpleName}: ${error.message}")
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
        val json = runCatching {
            val json = String(java.util.Base64.getDecoder().decode(texture), Charsets.UTF_8)
            json
        }.onFailure { error ->
            Bukkit.getLogger().warning("[DropZone] Skull texture base64 decode failed: ${error.javaClass.simpleName}: ${error.message}")
        }.getOrNull() ?: return null
        val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        if (url == null) {
            Bukkit.getLogger().warning("[DropZone] Skull texture base64 decode failed: url field not found")
        }
        return url
    }

    private fun createTextureProperty(propertyClass: Class<*>, texture: String): Any {
        val twoArg = propertyClass.constructors.firstOrNull { it.parameterTypes.size == 2 }
        if (twoArg != null) {
            twoArg.isAccessible = true
            return twoArg.newInstance("textures", texture)
        }
        val threeArg = propertyClass.constructors.first { it.parameterTypes.size == 3 }
        threeArg.isAccessible = true
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

    private fun bukkitProfileFailed(reason: String): Boolean {
        Bukkit.getLogger().warning("[DropZone] Bukkit skull profile write failed: $reason")
        return false
    }

    private fun authlibProfileFailed(reason: String): Boolean {
        Bukkit.getLogger().warning("[DropZone] Authlib skull profile write failed: $reason")
        return false
    }
}
