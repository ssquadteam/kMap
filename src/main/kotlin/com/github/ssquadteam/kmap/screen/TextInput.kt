package com.github.ssquadteam.kmap.screen

import com.github.ssquadteam.kmap.KMapPlugin
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.view.AnvilView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TextInput(private val plugin: KMapPlugin) {
    private val open = ConcurrentHashMap.newKeySet<UUID>()

    fun isOpen(player: Player) = player.uniqueId in open

    fun open(player: Player, initial: String) {
        player.scheduler.run(plugin, {
            val view: AnvilView = MenuType.ANVIL.builder().title(Component.empty()).build(player)
            val item = ItemStack(Material.PAPER)
            item.editMeta { it.displayName(Component.text(initial.ifEmpty { " " })) }
            view.topInventory.setItem(0, item)
            open.add(player.uniqueId)
            player.openInventory(view)
        }, null)
    }

    fun closed(player: Player) {
        if (!open.remove(player.uniqueId)) return
        val inv = player.openInventory
        if (inv is AnvilView) inv.topInventory.clear()
        player.scheduler.run(plugin, { plugin.maps.of(player)?.screen?.onTextClosed() }, null)
    }
}
