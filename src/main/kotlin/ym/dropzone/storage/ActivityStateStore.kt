package ym.dropzone.storage

import org.bukkit.plugin.Plugin
import java.io.File
import java.time.Instant

data class ActivityState(
    val activeActivity: String,
    val updatedAt: String
)

interface ActivityStateStore {
    fun read(): ActivityState
    fun write(activityName: String): ActivityState
}

class LocalJsonActivityStateStore(
    private val plugin: Plugin,
    private val folder: String,
    private val fileName: String,
    private val defaultActivity: String
) : ActivityStateStore {
    private val file: File = File(File(plugin.dataFolder, folder), fileName)

    // 默认本地 JSON 存储只负责当前活动状态，后续可在这里替换为 Redis/MySQL 等跨服实现。
    override fun read(): ActivityState {
        if (!file.exists()) return write(defaultActivity)
        val text = file.readText(Charsets.UTF_8)
        val activity = Regex("\"activeActivity\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?: defaultActivity
        val updatedAt = Regex("\"updatedAt\"\\s*:\\s*\"([^\"]*)\"")
            .find(text)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        return ActivityState(activity, updatedAt)
    }

    override fun write(activityName: String): ActivityState {
        val state = ActivityState(activityName, Instant.now().toString())
        file.parentFile.mkdirs()
        file.writeText(
            "{\n  \"activeActivity\": \"${escape(state.activeActivity)}\",\n  \"updatedAt\": \"${escape(state.updatedAt)}\"\n}\n",
            Charsets.UTF_8
        )
        return state
    }

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
