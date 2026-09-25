package com.github.ssquadteam.kmap.world

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.AreaEntry
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AreaService(private val plugin: KMapPlugin) {
    private class Staged(val world: String, val area: AreaEntry)

    private val staged = ConcurrentHashMap<UUID, Staged>()

    private fun say(player: Player, key: String, vararg args: Any) {
        player.sendMessage(Component.text(plugin.lang.get(player, "command.$key", *args)))
    }

    fun names(world: String): List<String> = plugin.worldsConfig.entry(world)?.areas?.map { it.name } ?: emptyList()

    fun add(player: Player, name: String, width: Int, height: Int) {
        val world = player.world.name
        if (plugin.worldsConfig.entry(world) == null) return say(player, "area_unregistered", world)
        val loc = player.location
        val area = AreaEntry(name, loc.blockX, loc.blockZ, width.coerceIn(16, 8192), height.coerceIn(16, 8192))
        val total = totalWidth(world, name) + area.width
        if (total > MAX_TOTAL) return say(player, "area_too_big")
        staged[player.uniqueId] = Staged(world, area)
        say(player, "area_staged", name, area.width, area.height)
        if (total > WARN_TOTAL) say(player, "area_warn")
        player.sendMessage(
            Component.text(plugin.lang.get(player, "command.area_accept"), NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/kmap area confirm"))
                .append(Component.text("  "))
                .append(Component.text(plugin.lang.get(player, "command.area_decline"), NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/kmap area cancel"))),
        )
    }

    fun confirm(player: Player) {
        val s = staged.remove(player.uniqueId) ?: return say(player, "area_none")
        val kept = plugin.worldsConfig.entry(s.world)?.areas?.filter { it.name != s.area.name } ?: emptyList()
        save(player, s.world, kept + s.area)
        say(player, "area_saved", s.area.name)
    }

    fun cancel(player: Player) {
        if (staged.remove(player.uniqueId) == null) return say(player, "area_none")
        say(player, "area_cancelled")
    }

    fun remove(player: Player, name: String) {
        val world = player.world.name
        val areas = plugin.worldsConfig.entry(world)?.areas ?: emptyList()
        if (areas.none { it.name == name }) return say(player, "area_unknown", name)
        save(player, world, areas.filter { it.name != name })
        say(player, "area_removed", name)
    }

    fun list(player: Player) {
        val areas = plugin.worldsConfig.entry(player.world.name)?.areas ?: emptyList()
        if (areas.isEmpty()) return say(player, "area_empty")
        for (a in areas) say(player, "area_list", a.name, a.width, a.height, a.width * a.height)
        say(player, "area_total", areas.sumOf { it.width })
    }

    fun forget(player: Player) {
        staged.remove(player.uniqueId)
    }

    private fun totalWidth(world: String, except: String): Int =
        plugin.worldsConfig.entry(world)?.areas?.filter { it.name != except }?.sumOf { it.width } ?: 0

    private fun save(player: Player, world: String, areas: List<AreaEntry>) {
        plugin.worldsConfig.setAreas(world, areas)
        plugin.worldsConfig.load { plugin.logger.warning(it) }
        plugin.bakes.rebake(player.world, player)
    }

    companion object {
        private const val MAX_TOTAL = 16384
        private const val WARN_TOTAL = 8192
    }
}
