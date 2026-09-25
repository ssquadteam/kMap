package com.github.ssquadteam.kmap.commands

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.Corner
import com.github.ssquadteam.kmap.config.MapModule
import com.github.ssquadteam.kmap.config.MinimapShape
import com.github.ssquadteam.kmap.pack.ShaderDefines
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.WorldCreator
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.io.File

class KMapCommand(private val plugin: KMapPlugin) : BasicCommand {
    private fun msg(sender: CommandSender, key: String, vararg args: Any) {
        sender.sendMessage(Component.text(plugin.lang.get(sender as? Player, "command.$key", *args)))
    }

    private fun admin(sender: CommandSender): Boolean {
        if (sender.hasPermission(ADMIN)) return true
        msg(sender, "no_permission")
        return false
    }

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val sender = source.sender
        val player = source.executor as? Player ?: sender as? Player
        val sub = args.getOrNull(0)?.lowercase()
        when (sub) {
            "toggle" -> {
                val map = player?.let { plugin.maps.of(it) } ?: return msg(sender, "players_only")
                map.toggleFromCommand()
            }
            "module" -> {
                val map = player?.let { plugin.maps.of(it) } ?: return msg(sender, "players_only")
                val m = MapModule.parse(args.getOrNull(1)) ?: return msg(sender, "usage")
                player.scheduler.run(plugin, {
                    map.closeScreen()
                    map.setBand(0)
                    map.settings.module = m
                    map.onSettingsChanged()
                }, null)
                msg(sender, "module", m.name)
            }
            "shape" -> {
                val map = player?.let { plugin.maps.of(it) } ?: return msg(sender, "players_only")
                val s = runCatching { MinimapShape.valueOf(args[1].uppercase()) }.getOrNull() ?: return msg(sender, "usage")
                player.scheduler.run(plugin, { map.settings.shape = s; map.onSettingsChanged() }, null)
                msg(sender, "shape", s.name)
            }
            "corner" -> {
                val map = player?.let { plugin.maps.of(it) } ?: return msg(sender, "players_only")
                val c = runCatching { Corner.valueOf(args[1].uppercase()) }.getOrNull() ?: return msg(sender, "usage")
                player.scheduler.run(plugin, { map.settings.corner = c; map.onSettingsChanged() }, null)
                msg(sender, "corner", c.name)
            }
            "zoom" -> {
                val map = player?.let { plugin.maps.of(it) } ?: return msg(sender, "players_only")
                val hd = map.worldMap()?.mode?.hd == true
                val def = if (hd) 2 else 3
                val next = when (args.getOrNull(1)?.uppercase()) {
                    "IN" -> map.settings.zoom + 1
                    "OUT" -> map.settings.zoom - 1
                    "RESET" -> def
                    else -> return msg(sender, "usage")
                }.coerceIn(0, 6)
                player.scheduler.run(plugin, { map.settings.zoom = next; map.settings.zoomTouched = true; map.onSettingsChanged() }, null)
                msg(sender, "zoom", ShaderDefines.MINI_ZOOMS[next])
            }
            "reload" -> {
                if (!admin(sender)) return
                plugin.reload()
                msg(sender, "reloaded")
            }
            "locations-reload" -> {
                if (!admin(sender)) return
                if (plugin.locations.load()) {
                    plugin.maps.all().forEach { it.onWaypointsChanged(); it.screen?.invalidateMap() }
                    msg(sender, "locations_reloaded", plugin.locations.all.size)
                } else {
                    msg(sender, "locations_failed")
                }
            }
            "testperm" -> {
                if (!admin(sender)) return
                val p = player ?: return msg(sender, "players_only")
                val node = args.getOrNull(1) ?: return msg(sender, "usage")
                val v = when (args.getOrNull(2)?.lowercase()) {
                    "on" -> true
                    "off" -> false
                    "clear" -> null
                    else -> return msg(sender, "usage")
                }
                plugin.locations.setOverride(p, node, v)
                plugin.maps.of(p)?.let { m -> p.scheduler.run(plugin, { m.onWaypointsChanged(); m.screen?.invalidateMap() }, null) }
                msg(sender, "testperm", node, v?.toString() ?: "clear")
            }
            "world-register" -> {
                if (!admin(sender)) return
                val name = args.getOrNull(1) ?: return msg(sender, "usage")
                val w = args.getOrNull(2)?.toIntOrNull()?.coerceIn(64, 8192) ?: 1000
                val h = args.getOrNull(3)?.toIntOrNull()?.coerceIn(64, 8192) ?: w
                val world = Bukkit.getWorld(name)
                val cx = args.getOrNull(4)?.toIntOrNull() ?: world?.spawnLocation?.blockX ?: 0
                val cz = args.getOrNull(5)?.toIntOrNull() ?: world?.spawnLocation?.blockZ ?: 0
                val rot = args.getOrNull(6)?.toIntOrNull() ?: 0
                val tint = args.getOrNull(7)?.toBooleanStrictOrNull()
                plugin.worldsConfig.register(name, w, h, cx, cz, rot, tint)
                if (world != null) msg(sender, "registered", name, w, h, cx, cz) else msg(sender, "registered_loaded", name)
            }
            "refresh" -> {
                if (!admin(sender)) return
                val p = player ?: return msg(sender, "players_only")
                val r = (args.getOrNull(1)?.toIntOrNull() ?: 8).coerceIn(1, 64)
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
                msg(sender, "refresh", n)
            }
            "rebake" -> {
                if (!admin(sender)) return
                val name = args.getOrNull(1) ?: player?.world?.name ?: return msg(sender, "usage")
                val world = Bukkit.getWorld(name) ?: return msg(sender, "unknown_world", name)
                msg(sender, "rebake", name)
                plugin.bakes.rebake(world, sender)
            }
            "world" -> {
                if (!admin(sender)) return
                val name = args.getOrNull(1)
                if (name == null) {
                    val loaded = Bukkit.getWorlds().joinToString(", ") { it.name }
                    val disk = Bukkit.getWorldContainer().listFiles { f -> f.isDirectory && File(f, "level.dat").isFile && Bukkit.getWorld(f.name) == null }?.joinToString(", ") { it.name } ?: ""
                    return msg(sender, "worlds", loaded, disk.ifEmpty { "-" })
                }
                val p = player ?: return msg(sender, "players_only")
                Bukkit.getGlobalRegionScheduler().execute(plugin) {
                    val world = Bukkit.getWorld(name) ?: runCatching { WorldCreator(name).createWorld() }.getOrNull()
                    if (world == null) {
                        msg(sender, "unknown_world", name)
                    } else {
                        p.teleportAsync(world.spawnLocation)
                    }
                }
            }
            "area" -> {
                if (!admin(sender)) return
                val p = player ?: return msg(sender, "players_only")
                plugin.areas.handle(p, args.drop(1))
            }
            "admin" -> {
                if (!admin(sender)) return
                if (!plugin.cfg.guis.admin) return msg(sender, "no_permission")
                val map = player?.let { plugin.maps.of(it) } ?: return msg(sender, "players_only")
                player.scheduler.run(plugin, {
                    if (map.screen == null) map.openScreen()
                    map.screen?.setPanel(com.github.ssquadteam.kmap.screen.AdminPanel())
                }, null)
            }
            "reloadpack" -> {
                if (!admin(sender)) return
                plugin.packs.rebuild()
                for (p in Bukkit.getOnlinePlayers()) plugin.packs.send(p)
            }
            "debug" -> sender.sendMessage(player?.let { plugin.maps.of(it)?.debug() } ?: "no session")
            else -> msg(sender, "usage")
        }
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): Collection<String> {
        val admin = source.sender.hasPermission(ADMIN)
        val subs = mutableListOf("toggle", "module", "shape", "corner", "zoom")
        if (admin) subs += listOf("reload", "world-register", "refresh", "rebake", "area", "locations-reload", "testperm", "world", "admin")
        if (args.size <= 1) return subs.filter { it.startsWith(args.getOrNull(0)?.lowercase() ?: "") }
        val prefix = args.last().lowercase()
        val options = when (args[0].lowercase()) {
            "module" -> MapModule.entries.map { it.name }
            "shape" -> MinimapShape.entries.map { it.name }
            "corner" -> Corner.entries.map { it.name }
            "zoom" -> listOf("IN", "OUT", "RESET")
            "rebake", "world-register", "world" -> if (args.size == 2) Bukkit.getWorlds().map { it.name } else emptyList()
            "area" -> if (args.size == 2) listOf("add", "confirm", "cancel", "remove", "list") else emptyList()
            "testperm" -> if (args.size == 3) listOf("on", "off", "clear") else emptyList()
            else -> emptyList()
        }
        return options.filter { it.lowercase().startsWith(prefix) }
    }

    override fun permission(): String = "kmap.minimap"

    companion object {
        const val ADMIN = "kmap.admin"
    }
}

class SensitivityCommand(private val plugin: KMapPlugin) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val p = source.executor as? Player ?: source.sender as? Player ?: return
        val v = args.getOrNull(0)?.toDoubleOrNull()
        if (v == null || v < 1.0 || v > 3.0) {
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

    override fun permission(): String = "kmap.minimap"
}
