package ym.dropzone.packet

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ym.dropzone.config.ConfigManager
import ym.dropzone.config.PacketBackend
import ym.dropzone.entity.DropZoneEntity

object PacketAdapterFactory {
    fun create(plugin: JavaPlugin, configManager: ConfigManager): PacketEntityAdapter {
        return SwitchingPacketEntityAdapter(plugin, configManager)
    }
}

private class SwitchingPacketEntityAdapter(
    private val plugin: JavaPlugin,
    private val configManager: ConfigManager
) : PacketEntityAdapter {
    private var protocolLibAdapter: PacketEntityAdapter? = null
    private var packetEventsAdapter: PacketEntityAdapter? = null
    private val warned = mutableSetOf<String>()

    override fun spawnItemEntity(player: Player, entity: DropZoneEntity) {
        delegate()?.spawnItemEntity(player, entity)
    }

    override fun updateEntity(player: Player, entity: DropZoneEntity) {
        delegate()?.updateEntity(player, entity)
    }

    override fun destroyEntity(player: Player, entityId: Int) {
        delegate()?.destroyEntity(player, entityId)
    }

    override fun destroyEntities(player: Player, entityIds: Collection<Int>) {
        delegate()?.destroyEntities(player, entityIds)
    }

    private fun delegate(): PacketEntityAdapter? {
        val desired = configManager.snapshot?.main?.fakeEntity?.packetBackend ?: PacketBackend.PROTOCOLLIB
        val hasProtocolLib = plugin.server.pluginManager.getPlugin("ProtocolLib")?.isEnabled == true
        val hasPacketEvents = plugin.server.pluginManager.getPlugin("packetevents")?.isEnabled == true ||
            plugin.server.pluginManager.getPlugin("PacketEvents")?.isEnabled == true

        return when (desired) {
            PacketBackend.PROTOCOLLIB -> {
                if (hasProtocolLib) {
                    protocolLib()
                } else if (hasPacketEvents) {
                    warnOnce("missing-protocollib", "[DropZone] ProtocolLib is not installed; falling back to PacketEvents fake entity backend.")
                    packetEvents()
                } else {
                    severeOnce()
                    null
                }
            }
            PacketBackend.PACKETEVENTS -> {
                if (hasPacketEvents) {
                    packetEvents()
                } else if (hasProtocolLib) {
                    warnOnce("missing-packetevents", "[DropZone] PacketEvents is not installed; falling back to ProtocolLib fake entity backend.")
                    protocolLib()
                } else {
                    severeOnce()
                    null
                }
            }
        }
    }

    private fun protocolLib(): PacketEntityAdapter {
        val existing = protocolLibAdapter
        if (existing != null) return existing
        return ProtocolLibEntityAdapter(plugin) {
            configManager.snapshot?.main?.fakeEntity?.debugPackets ?: true
        }.also {
            protocolLibAdapter = it
            plugin.logger.info("[DropZone] Fake entity packet backend initialized: PROTOCOLLIB")
        }
    }

    private fun packetEvents(): PacketEntityAdapter {
        val existing = packetEventsAdapter
        if (existing != null) return existing
        return PacketEventsEntityAdapter().also {
            packetEventsAdapter = it
            plugin.logger.info("[DropZone] Fake entity packet backend initialized: PACKETEVENTS")
        }
    }

    private fun warnOnce(key: String, message: String) {
        if (warned.add(key)) plugin.logger.warning(message)
    }

    private fun severeOnce() {
        if (warned.add("missing-all-packet-backends")) {
            plugin.logger.severe("[DropZone] Neither ProtocolLib nor PacketEvents is installed; fake entity packets are disabled.")
        }
    }
}
