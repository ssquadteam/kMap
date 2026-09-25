package com.github.ssquadteam.kmap.terrain

import com.github.ssquadteam.kmap.storage.TerrainStore
import net.minecraft.world.level.chunk.LevelChunk
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class TerrainCache(
    private val plugin: Plugin,
    val world: World,
    private val sampler: SurfaceSampler,
    val store: TerrainStore?,
    private val options: () -> SampleOptions,
) {
    private val surfaces = ConcurrentHashMap<Long, ChunkSurface>()
    private val slices = ConcurrentHashMap<Long, ConcurrentHashMap<Int, ChunkSurface>>()
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val misses = ConcurrentHashMap<Long, Long>()
    private val queue = ConcurrentLinkedQueue<Long>()
    private val sliceQueue = ConcurrentLinkedQueue<Pair<Long, Int>>()
    private val slicePending = ConcurrentHashMap.newKeySet<Pair<Long, Int>>()
    private val clock = AtomicLong()

    fun get(cx: Int, cz: Int): ChunkSurface? = surfaces[key(cx, cz)]?.also { it.lastAccess = now }

    fun slice(cx: Int, cz: Int, index: Int): ChunkSurface? = slices[key(cx, cz)]?.get(index)?.also { it.lastAccess = now }

    fun size(): Int = surfaces.size

    fun known(cx: Int, cz: Int): Boolean = surfaces.containsKey(key(cx, cz)) || store?.has(cx, cz) == true

    fun request(cx: Int, cz: Int) {
        val k = key(cx, cz)
        if (surfaces.containsKey(k)) return
        val miss = misses[k]
        if (miss != null && now - miss < MISS_TICKS) return
        if (pending.add(k)) queue.add(k)
    }

    fun requestSlice(cx: Int, cz: Int, index: Int) {
        val k = key(cx, cz)
        if (slices[k]?.containsKey(index) == true) return
        val p = k to index
        if (slicePending.add(p)) sliceQueue.add(p)
    }

    fun invalidate(cx: Int, cz: Int) {
        if (!known(cx, cz)) return
        val k = key(cx, cz)
        slices.remove(k)
        misses.remove(k)
        if (pending.add(k)) queue.add(k)
    }

    fun onChunkLoad(cx: Int, cz: Int) {
        if (surfaces[key(cx, cz)]?.fromDisk == true) invalidate(cx, cz)
    }

    fun sampleNow(chunk: LevelChunk) {
        put(sampler.sample(chunk, options()))
    }

    private fun put(surface: ChunkSurface) {
        surface.version = clock.incrementAndGet()
        surface.lastAccess = now
        val k = key(surface.cx, surface.cz)
        surfaces[k] = surface
        misses.remove(k)
        if (store != null && store.hashOf(surface.cx, surface.cz) != surface.hash) store.mark(surface)
    }

    private fun putFromDisk(surface: ChunkSurface) {
        surface.version = clock.incrementAndGet()
        surface.lastAccess = now
        surfaces.putIfAbsent(key(surface.cx, surface.cz), surface)
    }

    fun pump(budget: Int, sliceBudget: Int, sliceHeight: Int) {
        val level = (world as CraftWorld).handle
        var n = 0
        while (n < budget) {
            val k = queue.poll() ?: break
            n++
            val cx = (k shr 32).toInt()
            val cz = k.toInt()
            if (level.chunkSource.getChunkNow(cx, cz) != null) {
                Bukkit.getRegionScheduler().execute(plugin, world, cx, cz) {
                    try {
                        val chunk = level.getChunkIfLoaded(cx, cz)
                        if (chunk != null) put(sampler.sample(chunk, options())) else misses[k] = now
                    } finally {
                        pending.remove(k)
                    }
                }
            } else if (store != null && store.has(cx, cz)) {
                store.load(cx, cz) { s ->
                    if (s != null) putFromDisk(s) else misses[k] = now
                    pending.remove(k)
                }
            } else {
                misses[k] = now
                pending.remove(k)
            }
        }
        var s = 0
        while (s < sliceBudget) {
            val p = sliceQueue.poll() ?: break
            s++
            val cx = (p.first shr 32).toInt()
            val cz = p.first.toInt()
            val index = p.second
            Bukkit.getRegionScheduler().execute(plugin, world, cx, cz) {
                try {
                    val chunk = level.getChunkIfLoaded(cx, cz)
                    if (chunk != null) {
                        val base = options()
                        val top = (index + 1) * sliceHeight - 1
                        val opt = SampleOptions(null, base.skipDecoration, base.skipBlocks, base.biomeTint, top, top - sliceHeight * 4)
                        val surface = sampler.sample(chunk, opt)
                        surface.version = clock.incrementAndGet()
                        surface.lastAccess = now
                        slices.computeIfAbsent(p.first) { ConcurrentHashMap() }[index] = surface
                    }
                } finally {
                    slicePending.remove(p)
                }
            }
        }
    }

    fun evict(maxChunks: Int) {
        val t = now
        misses.values.removeIf { t - it > MISS_TICKS }
        slices.values.removeIf { m ->
            m.values.removeIf { t - it.lastAccess > SLICE_IDLE_TICKS }
            m.isEmpty()
        }
        if (surfaces.size <= maxChunks) return
        val keepCount = maxChunks * 3 / 4
        val order = surfaces.entries.map { it.key to it.value.lastAccess }.sortedBy { it.second }
        for (i in 0 until order.size - keepCount) surfaces.remove(order[i].first)
    }

    fun all(): Collection<ChunkSurface> = surfaces.values

    companion object {
        @Volatile
        var now = 0L
        private const val MISS_TICKS = 200L
        private const val SLICE_IDLE_TICKS = 2400L

        fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
    }
}
