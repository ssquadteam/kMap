package com.github.ssquadteam.kmap.screen

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.session.PlayerMap
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.entity.Player

class ScreenService(private val plugin: KMapPlugin) : Listener {
    fun toggle(map: PlayerMap) {
        if (map.screen != null) map.closeScreen() else map.openScreen()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onOpen(e: InventoryOpenEvent) {
        val p = e.player as? Player ?: return
        if (plugin.textInput.isOpen(p)) return
        plugin.maps.of(p)?.closeScreen()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(e: InventoryCloseEvent) {
        val p = e.player as? Player ?: return
        plugin.textInput.closed(p)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDrop(e: PlayerDropItemEvent) {
        if (plugin.maps.of(e.player)?.screen != null) e.isCancelled = true
    }
}
