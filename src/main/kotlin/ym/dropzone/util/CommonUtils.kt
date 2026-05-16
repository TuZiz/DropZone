package ym.dropzone.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Particle
import java.util.concurrent.ThreadLocalRandom

object WeightedRandom {
    fun <T> choose(values: Collection<T>, weight: (T) -> Double): T? {
        val positive = values.filter { weight(it) > 0.0 }
        val total = positive.sumOf(weight)
        if (positive.isEmpty() || total <= 0.0) return null
        var cursor = ThreadLocalRandom.current().nextDouble(total)
        for (value in positive) {
            cursor -= weight(value)
            if (cursor <= 0.0) return value
        }
        return positive.lastOrNull()
    }
}

object SafeEnumParser {
    inline fun <reified T : Enum<T>> parse(name: String?): T? {
        if (name.isNullOrBlank()) return null
        return enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

object MiniMessageUtil {
    private val mini = MiniMessage.miniMessage()

    fun deserialize(text: String): Component = mini.deserialize(text)
}

object PlainTextUtil {
    private val tagPattern = Regex("<[^>]+>")

    fun stripMiniMessage(text: String): String = text.replace(tagPattern, "")
}

object ParticleUtil {
    fun parse(name: String): Particle? {
        val normalized = name.uppercase()
        val aliases = when (normalized) {
            "HAPPY_VILLAGER" -> listOf("HAPPY_VILLAGER", "VILLAGER_HAPPY")
            "VILLAGER_HAPPY" -> listOf("VILLAGER_HAPPY", "HAPPY_VILLAGER")
            else -> listOf(normalized)
        }
        return aliases.firstNotNullOfOrNull { candidate ->
            runCatching { Particle.valueOf(candidate) }.getOrNull()
        }
    }
}
