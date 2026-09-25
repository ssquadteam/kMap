package com.github.ssquadteam.kmap.terrain

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
    private val options: () -> SampleOptions,
) {
    private val surfaces = ConcurrentHashMap<Long, ChunkSurface>()
    private val slices = ConcurrentHashMap<Long, ConcurrentHashMap<Int, ChunkSurface>>()
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val queue = ConcurrentLinkedQueue<Long>()
    private val sliceQueue = ConcurrentLinkedQueue<Pair<Long, Int>>()
    private val slicePending = ConcurrentHashMap.newKeySet<Pair<Long, Int>>()
    private val clock = AtomicLong()
    val listeners = ConcurrentLinkedQueue<(Int, Int) -> Unit>()

    fun get(cx: Int, cz: Int): ChunkSurface? = surfaces[key(cx, cz)]

    fun slice(cx: Int, cz: Int, index: Int): ChunkSurface? = slices[key(cx, cz)]?.get(index)

    fun size(): Int = surfaces.size

    fun request(cx: Int, cz: Int) {
        val k = key(cx, cz)
        if (surfaces.containsKey(k)) return
        if (pending.add(k)) queue.add(k)
    }

    fun requestSlice(cx: Int, cz: Int, index: Int) {
        val k = key(cx, cz)
        if (slices[k]?.containsKey(index) == true) return
        val p = k to index
        if (slicePending.add(p)) sliceQueue.add(p)
    }

    fun invalidate(cx: Int, cz: Int) {
        val k = key(cx, cz)
        slices.remove(k)
        if (pending.add(k)) queue.add(k)
    }

    fun put(surface: ChunkSurface) {
        surface.version = clock.incrementAndGet()
        surfaces[key(surface.cx, surface.cz)] = surface
        for (l in listeners) l(surface.cx, surface.cz)
    }

    fun pump(budget: Int, sliceBudget: Int, sliceHeight: Int) {
        var n = 0
        while (n < budget) {
            val k = queue.poll() ?: break
            n++
            val cx = (k shr 32).toInt()
            val cz = k.toInt()
            Bukkit.getRegionScheduler().execute(plugin, world, cx, cz) {
                try {
                    val chunk = (world as CraftWorld).handle.getChunkIfLoaded(cx, cz)
                    if (chunk != null) put(sampler.sample(chunk, options()))
                } finally {
                    pending.remove(k)
                }
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
                    val chunk = (world as CraftWorld).handle.getChunkIfLoaded(cx, cz)
                    if (chunk != null) {
                        val base = options()
                        val top = (index + 1) * sliceHeight - 1
                        val opt = SampleOptions(null, base.skipDecoration, base.skipBlocks, base.biomeTint, top, top - sliceHeight * 4)
                        val surface = sampler.sample(chunk, opt)
                        surface.version = clock.incrementAndGet()
                        slices.computeIfAbsent(p.first) { ConcurrentHashMap() }[index] = surface
                        for (l in listeners) l(cx, cz)
                    }
                } finally {
                    slicePending.remove(p)
                }
            }
        }
    }

    fun evictOutside(keep: (Int, Int) -> Boolean) {
        surfaces.keys.removeIf { !keep((it shr 32).toInt(), it.toInt()) }
        slices.keys.removeIf { !keep((it shr 32).toInt(), it.toInt()) }
    }

    fun all(): Collection<ChunkSurface> = surfaces.values

    companion object {
        fun key(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
    }
}
