package com.github.ssquadteam.kmap.pack

import com.github.ssquadteam.kmap.KMapPlugin
import com.nexomc.nexo.api.events.resourcepack.NexoPrePackGenerateEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import java.io.File

class NexoPackMerger(private val plugin: KMapPlugin) : PackMerger {
    override val pluginName = "Nexo"

    override fun install(zip: File, sha1: String): Boolean = NexoHook.install(plugin, zip)

    override fun reload() {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "nexo reload pack")
    }
}

class NexoHook private constructor(private val zip: File) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onGenerate(event: NexoPrePackGenerateEvent) {
        event.addResourcePack(zip)
    }

    companion object {
        private var active: NexoHook? = null

        fun install(plugin: KMapPlugin, zip: File): Boolean {
            return try {
                active?.let { HandlerList.unregisterAll(it) }
                val hook = NexoHook(zip)
                plugin.server.pluginManager.registerEvents(hook, plugin)
                active = hook
                val legacy = File(plugin.dataFolder.parentFile, "Nexo/pack/external_packs/kmap.zip")
                if (legacy.exists()) legacy.delete()
                true
            } catch (t: Throwable) {
                plugin.logger.warning("Nexo merge failed, serving the kMap pack directly: ${t.message}")
                false
            }
        }
    }
}
