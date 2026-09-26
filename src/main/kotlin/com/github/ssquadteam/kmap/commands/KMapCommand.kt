package com.github.ssquadteam.kmap.commands

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.Corner
import com.github.ssquadteam.kmap.config.MapModule
import com.github.ssquadteam.kmap.config.MinimapShape
import com.github.ssquadteam.kmap.pack.ShaderDefines
import com.github.ssquadteam.kmap.session.PlayerMap
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.WorldCreator
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.io.File

class KMapCommand(private val plugin: KMapPlugin) : BasicCommand {
    private class Sub(
        val name: String,
        val admin: Boolean,
        val complete: (List<String>) -> List<String> = { emptyList() },
        val run: (CommandSender, Player?, List<String>) -> Unit,
    )

    private val subs: List<Sub> = listOf(
        Sub("help", false) { sender, _, _ -> help(sender) },
        Sub("toggle", false) { sender, player, _ -> withMap(sender, player) { it.toggleFromCommand() } },
        Sub("module", false, { a -> if (a.size == 1) MapModule.entries.map { it.name.lowercase() } else emptyList() }) { sender, player, a ->
            val m = MapModule.parse(a.getOrNull(0)) ?: return@Sub usage(sender, "module")
            withMap(sender, player) { map ->
                map.player.scheduler.run(plugin, {
                    map.closeScreen()
                    map.setBand(0)
                    map.settings.module = m
                    map.onSettingsChanged()
                }, null)
                say(sender, "module", plugin.lang.get(map.player, "module." + m.name.lowercase()))
            }
        },
        Sub("shape", false, { a -> if (a.size == 1) MinimapShape.entries.map { it.name.lowercase() } else emptyList() }) { sender, player, a ->
            val s = MinimapShape.entries.firstOrNull { it.name.equals(a.getOrNull(0), true) } ?: return@Sub usage(sender, "shape")
            withMap(sender, player) { map -> settings(map) { map.settings.shape = s }; say(sender, "shape", s.name.lowercase()) }
        },
        Sub("corner", false, { a -> if (a.size == 1) Corner.entries.map { it.name.lowercase() } else emptyList() }) { sender, player, a ->
            val c = Corner.entries.firstOrNull { it.name.equals(a.getOrNull(0), true) } ?: return@Sub usage(sender, "corner")
            withMap(sender, player) { map -> settings(map) { map.settings.corner = c }; say(sender, "corner", c.name.lowercase().replace('_', ' ')) }
        },
        Sub("zoom", false, { a -> if (a.size == 1) listOf("in", "out", "reset") else emptyList() }) { sender, player, a ->
            withMap(sender, player) { map ->
                val next = when (a.getOrNull(0)?.lowercase()) {
                    "in" -> map.settings.zoom + 1
                    "out" -> map.settings.zoom - 1
                    "reset" -> DEFAULT_MINI_ZOOM
                    else -> return@withMap usage(sender, "zoom")
                }.coerceIn(0, ShaderDefines.MINI_ZOOMS.size - 1)
                settings(map) {
                    map.settings.zoom = next
                    map.settings.zoomTouched = true
                }
                say(sender, "zoom", ShaderDefines.MINI_ZOOMS[next])
            }
        },
        Sub("sens", false) { sender, player, a ->
            val v = a.getOrNull(0)?.toDoubleOrNull()?.takeIf { it in 1.0..3.0 } ?: return@Sub usage(sender, "sens")
            val rounded = Math.round(v * 10) / 10.0
            withMap(sender, player) { map -> settings(map) { map.settings.sensitivity = rounded }; say(sender, "sensitivity", rounded) }
        },
        Sub("reload", true) { sender, _, _ ->
            plugin.reload()
            say(sender, "reloaded")
        },
        Sub("locations-reload", true) { sender, _, _ ->
            if (plugin.locations.load()) {
                plugin.maps.all().forEach { m -> m.player.scheduler.run(plugin, { m.onWaypointsChanged(); m.screen?.invalidateMap() }, null) }
                say(sender, "locations_reloaded", plugin.locations.all.size)
            } else {
                say(sender, "locations_failed")
            }
        },
        Sub("refresh", true) { sender, player, a ->
            val p = player ?: return@Sub say(sender, "players_only")
            val r = (a.getOrNull(0)?.toIntOrNull() ?: 8).coerceIn(1, 64)
            val cache = plugin.worlds.of(p.world).cache
            val cx = p.location.blockX shr 4
            val cz = p.location.blockZ shr 4
            var n = 0
            for (dx in -r..r) for (dz in -r..r) {
                if (cache.known(cx + dx, cz + dz)) {
                    cache.invalidate(cx + dx, cz + dz)
                    n++
                }
            }
            say(sender, "refresh", n)
        },
        Sub("world", true, { a -> if (a.size == 1) worldFolders() else emptyList() }) { sender, player, a ->
            val name = a.getOrNull(0)
            if (name == null) {
                val loaded = Bukkit.getWorlds().joinToString(", ") { it.name }
                val disk = worldFolders().filter { Bukkit.getWorld(it) == null }.joinToString(", ")
                return@Sub say(sender, "worlds", loaded, disk.ifEmpty { "-" })
            }
            val p = player ?: return@Sub say(sender, "players_only")
            Bukkit.getGlobalRegionScheduler().execute(plugin) {
                val world = Bukkit.getWorld(name) ?: runCatching { WorldCreator(name).createWorld() }.getOrNull()
                if (world == null) say(sender, "unknown_world", name) else p.teleportAsync(world.spawnLocation)
            }
        },
        Sub("testperm", true, { a -> if (a.size == 2) listOf("on", "off", "clear") else emptyList() }) { sender, player, a ->
            val p = player ?: return@Sub say(sender, "players_only")
            val node = a.getOrNull(0) ?: return@Sub usage(sender, "testperm")
            val v = when (a.getOrNull(1)?.lowercase()) {
                "on" -> true
                "off" -> false
                "clear" -> null
                else -> return@Sub usage(sender, "testperm")
            }
            plugin.locations.setOverride(p, node, v)
            plugin.maps.of(p)?.let { m -> p.scheduler.run(plugin, { m.onWaypointsChanged(); m.screen?.invalidateMap() }, null) }
            say(sender, "testperm", node, v?.toString() ?: "clear")
        },
        Sub("pack", true) { sender, _, _ ->
            plugin.packs.rebuildLater()
            say(sender, "pack_rebuilding")
        },
        Sub("debug", true) { sender, player, _ ->
            sender.sendMessage(Component.text(player?.let { plugin.maps.of(it)?.debug() } ?: plugin.lang.get(null, "command.players_only")))
        },
    )

    private val byName = subs.associateBy { it.name }

    private fun say(sender: CommandSender, key: String, vararg args: Any) {
        sender.sendMessage(Component.text(plugin.lang.get(sender as? Player, "command.$key", *args)))
    }

    private fun usage(sender: CommandSender, sub: String) {
        val p = sender as? Player
        sender.sendMessage(
            Component.text(plugin.lang.get(p, "help.usage_prefix") + " ", NamedTextColor.GRAY)
                .append(Component.text("/kmap $sub " + plugin.lang.get(p, "help.args.$sub"), NamedTextColor.GOLD)),
        )
    }

    private fun withMap(sender: CommandSender, player: Player?, action: (PlayerMap) -> Unit) {
        val map = player?.let { plugin.maps.of(it) } ?: return say(sender, "players_only")
        action(map)
    }

    private fun settings(map: PlayerMap, change: () -> Unit) {
        map.player.scheduler.run(plugin, {
            change()
            map.onSettingsChanged()
        }, null)
    }

    private fun worldFolders(): List<String> =
        Bukkit.getWorldContainer().listFiles { f -> f.isDirectory && File(f, "level.dat").isFile }?.map { it.name } ?: emptyList()

    private fun help(sender: CommandSender) {
        val p = sender as? Player
        val admin = sender.hasPermission(ADMIN)
        sender.sendMessage(Component.text(plugin.lang.get(p, "help.header"), NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        for (s in subs) {
            if (s.admin && !admin) continue
            val args = plugin.lang.get(p, "help.args.${s.name}")
            val line = ("/kmap ${s.name} $args").trimEnd()
            val desc = plugin.lang.get(p, "help.desc.${s.name}")
            sender.sendMessage(
                Component.text(" $line", if (s.admin) NamedTextColor.RED else NamedTextColor.YELLOW)
                    .append(Component.text("  " + desc, NamedTextColor.GRAY))
                    .hoverEvent(HoverEvent.showText(Component.text(plugin.lang.get(p, "help.detail.${s.name}"), NamedTextColor.WHITE)))
                    .clickEvent(ClickEvent.suggestCommand("/kmap ${s.name} ")),
            )
        }
        sender.sendMessage(Component.text(plugin.lang.get(p, "help.footer"), NamedTextColor.DARK_GRAY))
    }

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val sender = source.sender
        val player = source.executor as? Player ?: sender as? Player
        val name = args.getOrNull(0)?.lowercase()
        if (name == null || name == "?") return help(sender)
        val sub = byName[name] ?: return help(sender)
        if (sub.admin && !sender.hasPermission(ADMIN)) return say(sender, "no_permission")
        sub.run(sender, player, args.drop(1))
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): Collection<String> {
        val admin = source.sender.hasPermission(ADMIN)
        val prefix = args.lastOrNull()?.lowercase() ?: ""
        if (args.size <= 1) return subs.filter { !it.admin || admin }.map { it.name }.filter { it.startsWith(prefix) }
        val sub = byName[args[0].lowercase()]?.takeIf { !it.admin || admin } ?: return emptyList()
        return sub.complete(args.drop(1)).filter { it.lowercase().startsWith(prefix) }
    }

    override fun permission(): String = USE

    companion object {
        const val USE = "kmap.minimap"
        const val ADMIN = "kmap.admin"
        private const val DEFAULT_MINI_ZOOM = 3
    }
}

class SensitivityCommand(private val plugin: KMapPlugin) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val p = source.executor as? Player ?: source.sender as? Player ?: return
        val v = args.getOrNull(0)?.toDoubleOrNull()?.takeIf { it in 1.0..3.0 }
        if (v == null) {
            p.sendMessage(Component.text(plugin.lang.get(p, "command.sensitivity_usage")))
            return
        }
        val rounded = Math.round(v * 10) / 10.0
        val map = plugin.maps.of(p) ?: return
        p.scheduler.run(plugin, {
            map.settings.sensitivity = rounded
            map.onSettingsChanged()
        }, null)
        p.sendMessage(Component.text(plugin.lang.get(p, "command.sensitivity", rounded)))
    }

    override fun permission(): String = KMapCommand.USE
}
