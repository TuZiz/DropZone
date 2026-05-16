package ym.dropzone.util

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

object SkullTextureUtil {
    fun createHead(texture: String): ItemStack {
        val item = ItemStack(Material.PLAYER_HEAD, 1)
        val meta = item.itemMeta as? SkullMeta ?: return item

        val applied = applyTexture(meta, texture)
        if (!applied) {
            Bukkit.getLogger().warning("[DropZone] Skull texture write failed; using plain PLAYER_HEAD fallback.")
        }

        item.itemMeta = meta
        return item
    }

    private fun applyTexture(meta: SkullMeta, texture: String?): Boolean {
        val skinUrl = resolveTextureUrl(texture) ?: return false
        return runCatching {
            val profile = createBukkitProfile()
                ?: return@runCatching profileFailed("Bukkit.createPlayerProfile(UUID, String?) method not found")
            val textures = profile.javaClass.getMethod("getTextures").invoke(profile)
                ?: return@runCatching profileFailed("PlayerProfile.getTextures returned null")
            val setSkin = textures.javaClass.methods.firstOrNull {
                it.name == "setSkin" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(URL::class.java)
            } ?: return@runCatching profileFailed("PlayerTextures.setSkin(URL) method not found")
            setSkin.isAccessible = true
            setSkin.invoke(textures, skinUrl)

            val setter = (SkullMeta::class.java.methods.toList() + meta.javaClass.methods.toList()).firstOrNull {
                it.name == "setOwnerProfile" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(profile.javaClass)
            } ?: return@runCatching profileFailed("SkullMeta.setOwnerProfile(PlayerProfile) method not found")
            setter.isAccessible = true
            setter.invoke(meta, profile)
            true
        }.onFailure { error ->
            Bukkit.getLogger().warning(
                "[DropZone] Bukkit profile skull texture write failed: ${error.javaClass.simpleName}: ${error.message}"
            )
        }.getOrDefault(false)
    }

    private fun createBukkitProfile(): Any? {
        val uuid = UUID.randomUUID()
        val createProfile = Bukkit::class.java.methods.firstOrNull {
            it.name == "createPlayerProfile" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == UUID::class.java &&
                it.parameterTypes[1] == String::class.java
        } ?: return null
        createProfile.isAccessible = true
        return createProfile.invoke(null, uuid, null)
    }

    private fun resolveTextureUrl(texture: String?): URL? {
        val normalized = texture?.trim().orEmpty()
        if (normalized.isEmpty() || normalized == "CHANGE_ME_BASE64_TEXTURE") return null

        extractTextureUrl(normalized)?.let { return runCatching { URL(toHttpsTextureUrl(it)) }.getOrNull() }
        if (normalized.matches(Regex("^[A-Za-z0-9]{20,}$"))) {
            return runCatching { URL("https://textures.minecraft.net/texture/$normalized") }.getOrNull()
        }

        val decoded = runCatching {
            String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8)
        }.onFailure { error ->
            Bukkit.getLogger().warning("[DropZone] Skull texture base64 decode failed: ${error.javaClass.simpleName}: ${error.message}")
        }.getOrNull() ?: return null

        val decodedUrl = extractTextureUrl(decoded) ?: return textureUrlFailed("decoded texture url missing")
        return runCatching { URL(toHttpsTextureUrl(decodedUrl)) }.getOrNull()
    }

    private fun extractTextureUrl(raw: String): String? {
        return Regex("""https?://textures\.minecraft\.net/texture/[A-Za-z0-9]+""")
            .find(raw)
            ?.value
    }

    private fun toHttpsTextureUrl(url: String): String {
        return url.replace("http://textures.minecraft.net/", "https://textures.minecraft.net/")
    }

    private fun profileFailed(reason: String): Boolean {
        Bukkit.getLogger().warning("[DropZone] Bukkit profile skull texture write failed: $reason")
        return false
    }

    private fun textureUrlFailed(reason: String): URL? {
        Bukkit.getLogger().warning("[DropZone] Skull texture url resolve failed: $reason")
        return null
    }
}
