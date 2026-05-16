package ym.dropzone.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
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
