package ym.dropzone.command

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import ym.dropzone.config.ConfigManager

class DropZoneTabCompleter(private val configManager: ConfigManager) : TabCompleter {
    private val subcommands = listOf("reload", "start", "spawn", "clear", "list", "debug", "sync", "dbstatus", "outbox")

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 2 && args[0].equals("start", ignoreCase = true) && has(sender, "start")) {
            val prefix = args[1].lowercase()
            return configManager.activityNames.filter { it.lowercase().startsWith(prefix) }
        }
        if (args.size == 2 && args[0].equals("outbox", ignoreCase = true) && has(sender, "outbox")) {
            val prefix = args[1].lowercase()
            return listOf("failed", "retry").filter { it.startsWith(prefix) }
        }
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase()
        return subcommands.filter { it.startsWith(prefix) && has(sender, it) }
    }

    private fun has(sender: CommandSender, subcommand: String): Boolean {
        return sender.hasPermission("dropzone.admin") || sender.hasPermission("dropzone.$subcommand")
    }
}
