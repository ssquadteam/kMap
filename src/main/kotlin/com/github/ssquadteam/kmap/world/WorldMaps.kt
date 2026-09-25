package com.github.ssquadteam.kmap.world

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.RenderMode
import com.github.ssquadteam.kmap.storage.TerrainStore
import com.github.ssquadteam.kmap.terrain.SampleOptions
import com.github.ssquadteam.kmap.terrain.TerrainCache
import org.bukkit.Bukkit
import org.bukkit.World
import java.util.concurrent.ConcurrentHashMap

class WorldMaps(private val plugin: KMapPlugin) : org.bukkit.event.Listener {
    private val maps = ConcurrentHashMap<String, WorldMap>()

    fun of(world: World): WorldMap = maps.computeIfAbsent(world.name) { create(world) }

    fun all(): Collection<WorldMap> = maps.values

    fun clear() = maps.clear()

    private fun create(world: World): WorldMap {
        val entry = plugin.worldsConfig.entry(world.name)
        val cfg = plugin.cfg
        var mode = entry?.mode ?: cfg.largeWorldMode
        if (mode.baked && entry != null && (entry.sizeX > cfg.largeWorldThreshold || entry.sizeZ > cfg.largeWorldThreshold) && cfg.largeWorldThreshold > 0) {
            plugin.logger.warning("World '${world.name}' is ${entry.sizeX}x${entry.sizeZ}, past the ${cfg.largeWorldThreshold} block cap for one baked image; streaming it as ${cfg.largeWorldMode}.")
            mode = cfg.largeWorldMode
        }
        val ceiling = entry?.ceiling ?: if (world.environment == World.Environment.NETHER) 123 else null
        val skip = entry?.skipBlocks ?: emptySet()
        val options = SampleOptions(ceiling, entry?.skipDecoration ?: true, skip, entry?.biomeTint ?: true)
        val persist = !mode.baked && (entry?.saveWorldColors ?: cfg.saveWorldColors)
        val store = if (persist) {
            val signature = listOf(TerrainStore.FORMAT, ceiling, options.skipDecoration, options.skipBlocks.map { it.toString() }.sorted(), options.biomeTint).hashCode()
            TerrainStore(java.io.File(plugin.dataFolder, "data/terrain/${world.name}"), plugin.files, signature).also { it.open() }
        } else {
            null
        }
        val cache = TerrainCache(plugin, world, plugin.sampler, store) { options }
        val rgb = entry?.rgbTextured ?: (cfg.rgbTextured || mode.baked)
        val brightness = 1.0 + (entry?.surfaceBrightness ?: 0) / 100.0
        return WorldMap(world, mode, cache, ceiling, rgb, brightness)
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

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    fun onChunkLoad(e: org.bukkit.event.world.ChunkLoadEvent) {
        if (e.isNewChunk) return
        maps[e.world.name]?.cache?.onChunkLoad(e.chunk.x, e.chunk.z)
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    fun onChunkUnload(e: org.bukkit.event.world.ChunkUnloadEvent) {
        val cache = maps[e.world.name]?.cache ?: return
        val cx = e.chunk.x
        val cz = e.chunk.z
        if (cache.store == null || !cache.known(cx, cz)) return
        val nms = (e.world as org.bukkit.craftbukkit.CraftWorld).handle.getChunkIfLoaded(cx, cz) ?: return
        if (nms.isUnsaved || plugin.blocks.consume(e.world, cx, cz)) cache.sampleNow(nms)
    }

    companion object {
        fun modeLabel(mode: RenderMode): String = mode.name
    }
}
