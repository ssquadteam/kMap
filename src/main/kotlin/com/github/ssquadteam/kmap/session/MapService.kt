package com.github.ssquadteam.kmap.session

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.nms.PacketInterceptor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.player.PlayerRespawnEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MapService(private val plugin: KMapPlugin) : Listener {
    private val sessions = ConcurrentHashMap<UUID, PlayerMap>()

    fun of(player: Player): PlayerMap? = sessions[player.uniqueId]

    fun all(): Collection<PlayerMap> = sessions.values

    fun attach(player: Player) {
        if (sessions.containsKey(player.uniqueId)) return
        val session = PlayerMap(plugin, player, plugin.storage.loadSettings(player))
        sessions[player.uniqueId] = session
        PacketInterceptor.inject(player, session)
    }

    fun detach(player: Player) {
        val s = sessions.remove(player.uniqueId) ?: return
        s.stop()
        plugin.storage.saveSettings(player, s.settings)
        s.discoveryWorld()?.let { plugin.storage.saveDiscovery(player.uniqueId, it, s.discovered) }
        PacketInterceptor.eject(player)
    }

    fun open(player: Player) {
        val s = sessions[player.uniqueId] ?: return
        player.scheduler.run(plugin, { s.start() }, null)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(e: PlayerJoinEvent) {
        val p = e.player
        attach(p)
        if (plugin.cfg.packSetOnJoin && !plugin.packs.mergedIntoPlugin) {
            p.scheduler.runDelayed(plugin, { plugin.packs.send(p) }, null, plugin.cfg.packJoinDelayTicks.toLong().coerceAtLeast(1))
        }
        if (plugin.cfg.openMapOnJoin) {
            val fallback = if (plugin.cfg.packSetOnJoin && !plugin.packs.mergedIntoPlugin) 200L else plugin.cfg.joinOpenDelayTicks.toLong().coerceAtLeast(1)
            p.scheduler.runDelayed(plugin, { sessions[p.uniqueId]?.start() }, null, fallback)
        }
    }

    @EventHandler
    fun onPack(e: PlayerResourcePackStatusEvent) {
        if (e.id != plugin.packs.baseId && !plugin.packs.mergedIntoPlugin) return
        if (e.status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            plugin.packs.markLoaded(e.player, true)
            if (plugin.cfg.openMapOnJoin) {
                e.player.scheduler.runDelayed(plugin, { sessions[e.player.uniqueId]?.start() }, null, 10L)
            }
        } else if (e.status == PlayerResourcePackStatusEvent.Status.DECLINED || e.status == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            plugin.logger.warning("${e.player.name} did not load the kMap pack (${e.status}); the map draws as empty glyph boxes without it.")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(e: PlayerQuitEvent) {
        plugin.packs.markLoaded(e.player, false)
        plugin.areas.forget(e.player)
        detach(e.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorld(e: PlayerChangedWorldEvent) {
        val s = sessions[e.player.uniqueId] ?: return
        s.discoveryWorld()?.let { plugin.storage.saveDiscovery(e.player.uniqueId, it, s.discovered) }
        if (s.active) e.player.scheduler.run(plugin, { s.enterWorld() }, null)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(e: PlayerRespawnEvent) {
        val s = sessions[e.player.uniqueId] ?: return
        e.player.scheduler.runDelayed(plugin, {
            if (s.active) s.enterWorld() else if (plugin.cfg.openMapOnJoin) s.start()
        }, null, 2L)
    }
}
