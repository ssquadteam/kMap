package com.github.ssquadteam.kmap.locations

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.render.Glyphs
import com.github.ssquadteam.kmap.screen.ScreenSession
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player

class PinService(private val plugin: KMapPlugin) {
    fun iconGlyphName(name: String, glyphs: Glyphs): String? {
        val n = name.removeSuffix(".png").lowercase().replace(Regex("[^a-z0-9_]"), "_")
        val stripped = n.removeSuffix("_outline").removeSuffix("_pin")
        return listOf("user_locations_$n", "user_icons_$n", "loc_$n", "user_locations_$stripped", "user_icons_$stripped", "loc_$stripped").firstOrNull { glyphs.find(it) != null }
    }

    fun pinGlyph(l: MapLocation, glyphs: Glyphs): String {
        val base = l.pinIcon ?: l.icon ?: "waypoint"
        val n = base.removeSuffix(".png").lowercase()
        glyphs.find("loc_${n}_outline")?.let { return it.name }
        val icon = iconGlyphName(n, glyphs) ?: return "loc_waypoint_outline"
        return glyphs.find(icon + "_pin")?.name ?: icon
    }

    fun activate(player: Player, l: MapLocation, screen: ScreenSession?) {
        val map = plugin.maps.of(player) ?: return
        if (l.actions.isEmpty()) {
            map.trackPin(if (map.trackedPin == l.index) null else l.index)
            screen?.invalidateMap()
            return
        }
        run(player, l, l.actions, 0, screen)
    }

    private fun run(player: Player, l: MapLocation, actions: List<PinAction>, from: Int, screen: ScreenSession?) {
        var i = from
        while (i < actions.size) {
            val a = actions[i]
            val v = a.value.replace("{player}", player.name)
            when (a.type) {
                "DELAY" -> {
                    val ticks = v.toLongOrNull()?.coerceAtLeast(1) ?: 20L
                    player.scheduler.runDelayed(plugin, { run(player, l, actions, i + 1, plugin.maps.of(player)?.screen) }, null, ticks)
                    return
                }
                "RUN_COMMAND" -> player.performCommand(v.removePrefix("/"))
                "CONSOLE_COMMAND" -> Bukkit.getGlobalRegionScheduler().execute(plugin) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), v.removePrefix("/")) }
                "TELEPORT" -> {
                    val w = l.world?.let { Bukkit.getWorld(it) } ?: player.world
                    plugin.maps.of(player)?.closeScreen()
                    player.teleportAsync(Location(w, l.x + 0.5, l.y ?: (w.getHighestBlockYAt(l.x.toInt(), l.z.toInt()) + 1.0), l.z + 0.5, player.location.yaw, player.location.pitch))
                }
                "CLOSE_MAP" -> plugin.maps.of(player)?.closeScreen()
                "TRACK" -> plugin.maps.of(player)?.let { it.trackPin(if (it.trackedPin == l.index) null else l.index) }
                "MESSAGE" -> player.sendMessage(Component.text(v))
                "ACTIONBAR" -> player.sendActionBar(Component.text(v))
                "TITLE" -> player.showTitle(Title.title(Component.text(v.substringBefore(';')), Component.text(v.substringAfter(';', ""))))
                "SOUND" -> player.playSound(Sound.sound(Key.key(v.ifBlank { "minecraft:ui.button.click" }), Sound.Source.MASTER, 1f, 1f))
            }
            i++
        }
        screen?.invalidateMap()
    }
}
