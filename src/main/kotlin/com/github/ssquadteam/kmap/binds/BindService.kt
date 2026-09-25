package com.github.ssquadteam.kmap.binds

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.BindConfig
import com.github.ssquadteam.kmap.config.Gesture
import com.github.ssquadteam.kmap.config.HandFilter
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class BindService(private val plugin: KMapPlugin) : Listener {
    private fun binds(g: Gesture): List<BindConfig> = if (plugin.cfg.bindsEnabled) plugin.cfg.binds.filter { it.gesture == g } else emptyList()

    private fun matches(b: BindConfig, p: Player, item: ItemStack?, offhandEmpty: Boolean): Boolean {
        if (b.sneaking != null && b.sneaking != p.isSneaking) return false
        when (b.hand) {
            HandFilter.ANY -> {}
            HandFilter.EMPTY -> if (item != null && !item.type.isAir || !offhandEmpty) return false
            HandFilter.MAIN_EMPTY -> if (item != null && !item.type.isAir) return false
        }
        if (b.material != null && (item == null || !item.type.name.equals(b.material, true))) return false
        val meta = item?.itemMeta
        if (b.customModelData != null && (meta == null || !meta.hasCustomModelData() || meta.customModelData != b.customModelData)) return false
        if (b.nbtKey != null) {
            val key = NamespacedKey.fromString(b.nbtKey) ?: return false
            val pdc = meta?.persistentDataContainer ?: return false
            if (!pdc.has(key)) return false
            if (b.nbtValue != null && pdc.get(key, PersistentDataType.STRING) != b.nbtValue) return false
        }
        return true
    }

    fun toggle(p: Player) {
        val map = plugin.maps.of(p) ?: return
        p.scheduler.run(plugin, {
            val open = p.openInventory
            if (open.topInventory.type != InventoryType.CRAFTING) p.closeInventory()
            if (!map.active) map.start()
            map.toggle()
        }, null)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwap(e: PlayerSwapHandItemsEvent) {
        val p = e.player
        if (binds(Gesture.SWAP_HANDS).any { matches(it, p, p.inventory.itemInMainHand, p.inventory.itemInOffHand.type.isAir) }) {
            e.isCancelled = true
            plugin.maps.of(p)?.toggle()
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onInteract(e: PlayerInteractEvent) {
        if (e.hand != EquipmentSlot.HAND) return
        val p = e.player
        if (plugin.maps.of(p)?.screen != null) return
        val gesture = when (e.action) {
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> Gesture.RIGHT_CLICK
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> Gesture.LEFT_CLICK
            else -> return
        }
        if (binds(gesture).any { matches(it, p, e.item, p.inventory.itemInOffHand.type.isAir) }) {
            e.isCancelled = true
            plugin.maps.of(p)?.toggle()
        }
    }

    fun onAdvancementsOpened(p: Player): Boolean {
        if (binds(Gesture.ADVANCEMENTS).none { matches(it, p, p.inventory.itemInMainHand, p.inventory.itemInOffHand.type.isAir) }) return false
        p.scheduler.run(plugin, {
            p.closeInventory()
            plugin.maps.of(p)?.toggle()
        }, null)
        return true
    }

    fun commandBinds(): List<String> = plugin.cfg.binds.filter { it.gesture == Gesture.COMMAND && it.command != null }.map { it.command!!.trim().removePrefix("/") }

    fun commandExecutor(): BasicCommand = object : BasicCommand {
        override fun execute(source: CommandSourceStack, args: Array<out String>) {
            val p = source.executor as? Player ?: source.sender as? Player ?: return
            plugin.maps.of(p)?.let { if (plugin.cfg.bindsEnabled) it.toggleFromCommand() }
        }

        override fun permission(): String = "kmap.minimap"
    }

    fun describe(p: Player): String {
        val b = plugin.cfg.binds.firstOrNull() ?: return "/kmap toggle"
        return when (b.gesture) {
            Gesture.SWAP_HANDS -> "F"
            Gesture.RIGHT_CLICK -> plugin.lang.get(p, "faq.k_right")
            Gesture.LEFT_CLICK -> plugin.lang.get(p, "faq.k_left")
            Gesture.ADVANCEMENTS -> "L"
            Gesture.COMMAND -> "/" + (b.command ?: "kmap toggle").removePrefix("/")
            Gesture.DROP -> "Q"
        }
    }
}
