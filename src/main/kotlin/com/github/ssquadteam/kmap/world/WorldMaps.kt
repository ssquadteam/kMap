package com.github.ssquadteam.kmap.world

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.storage.TerrainStore
import com.github.ssquadteam.kmap.terrain.SampleOptions
import com.github.ssquadteam.kmap.terrain.TerrainCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent

class WorldMaps(private val plugin: KMapPlugin) : Listener {
    private val maps = ConcurrentHashMap<String, WorldMap>()

    fun of(world: World): WorldMap = maps.computeIfAbsent(world.name) { create(world) }

    fun all(): Collection<WorldMap> = maps.values

    fun clear() = maps.clear()

    private fun create(world: World): WorldMap {
        val entry = plugin.worldsConfig.entry(world.name)
        val cfg = plugin.cfg
        val ceiling = entry?.ceiling ?: if (world.environment == World.Environment.NETHER) 123 else null
        val skip = entry?.skipBlocks ?: emptySet()
        val rgb = entry?.rgbTextured ?: cfg.rgbTextured
        val options = SampleOptions(ceiling, entry?.skipDecoration ?: true, skip, entry?.biomeTint ?: true, rgb = rgb)
        val persist = entry?.saveWorldColors ?: cfg.saveWorldColors
        val store = if (persist) {
            val signature = listOf(TerrainStore.FORMAT, rgb, ceiling, options.skipDecoration, options.skipBlocks.map { it.toString() }.sorted(), options.biomeTint).hashCode()
            TerrainStore(File(plugin.dataFolder, "data/terrain/${world.name}"), plugin.files, signature).also { it.open() }
        } else {
            null
        }
        val brightness = 1.0 + (entry?.surfaceBrightness ?: 0) / 100.0
        val cache = TerrainCache(plugin, world, plugin.sampler, store, brightness) { options }
        return WorldMap(world, cache, ceiling, rgb, brightness)
    }

    fun pump() {
        TerrainCache.now++
        val cfg = plugin.cfg
        for (m in maps.values) {
            if (Bukkit.getWorld(m.world.uid) == null) continue
            m.cache.pump(cfg.caveSnapshotsPerTick * 2, cfg.caveSnapshotsPerTick, cfg.caveLayerHeight)
        }
    }

    fun invalidate(world: World, cx: Int, cz: Int) {
        maps[world.name]?.cache?.invalidate(cx, cz)
    }

    fun evict() {
        for (m in maps.values) m.cache.evict(plugin.cfg.terrainMemoryChunks)
    }

    fun flush() {
        for (m in maps.values) m.cache.store?.flush()
    }

    fun flushNow() {
        for (m in maps.values) m.cache.store?.flushNow()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(e: ChunkLoadEvent) {
        maps[e.world.name]?.cache?.onChunkLoad(e.chunk.x, e.chunk.z)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(e: ChunkUnloadEvent) {
        val cache = maps[e.world.name]?.cache ?: return
        val cx = e.chunk.x
        val cz = e.chunk.z
        if (cache.store == null) return
        val nms = (e.world as CraftWorld).handle.getChunkIfLoaded(cx, cz) ?: return
        if (!cache.known(cx, cz) || nms.isUnsaved || plugin.blocks.consume(e.world, cx, cz)) cache.sampleNow(nms)
    }
}
