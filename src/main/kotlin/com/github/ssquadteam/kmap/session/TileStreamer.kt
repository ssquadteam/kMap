package com.github.ssquadteam.kmap.session

import com.github.ssquadteam.kmap.nms.FakeIds
import com.github.ssquadteam.kmap.nms.Packets
import com.github.ssquadteam.kmap.pack.ShaderDefines
import com.github.ssquadteam.kmap.render.TileFrame
import com.github.ssquadteam.kmap.terrain.TerrainCache
import com.github.ssquadteam.kmap.terrain.TileAssembler
import com.github.ssquadteam.kmap.terrain.TileData
import com.github.ssquadteam.kmap.terrain.TileMeta
import com.github.ssquadteam.kmap.terrain.ZoomAnim
import com.github.ssquadteam.kmap.world.WorldMap
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import com.github.ssquadteam.kmap.screen.ScreenSession
import kotlin.math.floor
import kotlin.math.ln

class View(val mini: Int, val sens: Int, val panQX: Int, val panQZ: Int, val screen: Int, val anim: ZoomAnim, val centerX: Int, val centerZ: Int, val halfW: Int, val halfH: Int)

class TileStreamer(private val map: PlayerMap) {
    private data class TileKey(val lod: Int, val layer: Int, val tx: Int, val tz: Int)

    private class Slot(val key: TileKey, val mapId: Int, val originX: Int, val originZ: Int, val kind: Int) {
        var frame: TileFrame? = null
        var sentMeta: ByteArray? = null
        var sigs: LongArray? = null
        var mods = -1L
        var discMod = -1L
        var missing = false
        var renderedAt = Int.MIN_VALUE
    }

    private class Want(val key: TileKey, val originX: Int, val originZ: Int, val kind: Int, val flags: Int)

    private val cached = LinkedHashMap<TileKey, Slot>(128, 0.75f, true)
    private val active = HashMap<TileKey, Slot>()
    private val freeMapIds = ArrayDeque<Int>()
    private val assembler = TileAssembler()
    private val sigScratch = LongArray(12 * 12)
    private var wanted: List<Want> = emptyList()
    private var wantKey = LongArray(12)
    private var wantedAt = Int.MIN_VALUE
    private var cachedWorld: String? = null
    private val fallback = HashMap<TileKey, Slot>()
    var caveLayer = Int.MIN_VALUE
        private set
    private val liftUp = FakeIds.next()
    private val liftDown = FakeIds.next()
    private var lifted = false

    init {
        for (i in 0 until CLIENT_CACHE) freeMapIds.add(MAP_ID_BASE - i)
    }

    val activeCount: Int get() = active.size
    val cachedCount: Int get() = cached.size

    fun riderIds(into: MutableSet<Int>) {
        if (lifted) {
            into.add(liftUp)
            into.add(liftDown)
        }
    }

    fun liftPassengers(): List<Packet<in ClientGamePacketListener>> {
        if (!lifted) return emptyList()
        val frames = active.values.mapNotNull { it.frame }
        return listOf(
            Packets.passengers(liftUp, frames.map { it.id }.toIntArray()),
            Packets.passengers(liftDown, frames.map { it.id2 }.toIntArray()),
        )
    }

    fun resetMeta() {
        for (s in cached.values) s.sentMeta = null
    }

    fun enterWorld(world: String) {
        hideAll()
        if (cachedWorld != world) {
            for (s in cached.values) freeMapIds.add(s.mapId)
            cached.clear()
            cachedWorld = world
        }
        caveLayer = Int.MIN_VALUE
        wantedAt = Int.MIN_VALUE
    }

    fun hideAll() {
        val ids = ArrayList<Int>()
        if (lifted) {
            ids.add(liftUp)
            ids.add(liftDown)
            lifted = false
        }
        if (active.isEmpty() && ids.isEmpty()) return
        for (s in active.values) s.frame?.let { ids.add(it.id); ids.add(it.id2) }
        for (s in active.values) s.frame = null
        active.clear()
        fallback.clear()
        if (ids.isNotEmpty()) map.send(Packets.remove(*ids.toIntArray()))
        map.markRidersDirty()
        wantedAt = Int.MIN_VALUE
    }

    fun stream(wm: WorldMap, bx: Int, bz: Int, layer: Int, screen: ScreenSession?, view: View, ticks: Int) {
        if (layer != caveLayer) {
            caveLayer = layer
            wantedAt = Int.MIN_VALUE
        }
        val cfg = map.plugin.cfg
        val cache = wm.cache
        val kind = if (wm.rgb) TileData.RGB else TileData.PALETTE
        val wide = if (kind == TileData.RGB) 64 else 128
        val lod = if (screen == null) 0 else lodFor(screen.scale)
        val radius = tileRadiusBlocks()
        val next = LongArray(12)
        next[0] = TileAssembler.tileX(bx - radius, wide).toLong()
        next[1] = TileAssembler.tileX(bx + radius, wide).toLong()
        next[2] = TileAssembler.tileZ(bz - radius).toLong()
        next[3] = TileAssembler.tileZ(bz + radius).toLong()
        next[4] = lod.toLong()
        next[5] = layer.toLong()
        if (screen != null) {
            val span = if (lod == 0) wide else 128 shl lod
            val stride = if (lod == 0) TileData.STRIDE_Z else TileData.STRIDE_Z shl lod
            next[6] = Math.floorDiv(view.centerX - view.halfW, span).toLong()
            next[7] = Math.floorDiv(view.centerX + view.halfW, span).toLong()
            next[8] = Math.floorDiv(view.centerZ - view.halfH, stride).toLong()
            next[9] = Math.floorDiv(view.centerZ + view.halfH, stride).toLong()
            next[10] = 1
        }
        val out = ArrayList<Packet<in ClientGamePacketListener>>()
        if (!next.contentEquals(wantKey) || ticks - wantedAt >= 40) {
            wantKey = next
            wantedAt = ticks
            rebuildWanted(kind, wide, lod, layer, bx, bz, screen, view, next, out)
        }
        val base = cfg.tilesPerFlush * (if (screen != null) 2 else 1)
        val unsent = wanted.count { cached[it.key]?.sigs == null }
        var budget = base + (unsent / 3).coerceAtMost(base * 2)
        val discMod = map.discovered.modCount
        for (w in wanted) {
            val slot = activate(w, out) ?: continue
            if (slot.kind == TileData.OVERVIEW) {
                val mods = cache.overviewMod(w.key.lod, w.key.tx, w.key.tz)
                val stale = slot.sigs == null || mods != slot.mods || (discMod != slot.discMod && ticks - slot.renderedAt >= 40) || (slot.missing && ticks - slot.renderedAt >= 20)
                if (!stale) {
                    refreshMeta(slot, w.flags, view, out)
                    continue
                }
                if (budget <= 0) continue
                budget--
                val missing = BooleanArray(1)
                val data = assembler.overview(slot.originX, slot.originZ, w.key.lod, { x, z -> cache.thumb(x, z) }, { x, z -> map.discovered.contains(x, z) }, missing)
                sendFull(slot, data, meta(slot, w.flags, view), out)
                slot.sigs = EMPTY_SIGS
                slot.mods = mods
                slot.discMod = discMod
                slot.missing = missing[0]
                slot.renderedAt = ticks
                continue
            }
            val mods = cache.tileMod(wide, w.key.tx, w.key.tz)
            if (slot.sigs != null && mods == slot.mods && discMod == slot.discMod && !(slot.missing && (ticks + w.key.tx + w.key.tz) % 20 == 0)) {
                refreshMeta(slot, w.flags, view, out)
                continue
            }
            if (!updateDetail(slot, w, wm, wide, mods, discMod, view, budget > 0, out)) continue
            budget--
        }
        if (fallback.isNotEmpty()) {
            if (wanted.all { cached[it.key]?.sigs != null }) {
                dropFallback(out)
            } else {
                for (f in fallback.values) refreshMeta(f, TileData.FLAG_SCREEN or TileData.FLAG_FALLBACK, view, out)
            }
        }
        evictOverflow()
        map.sendAll(out)
    }

    fun syncMeta(view: View, out: MutableList<Packet<in ClientGamePacketListener>>) {
        for (w in wanted) {
            val slot = active[w.key] ?: continue
            if (slot.sentMeta != null) refreshMeta(slot, w.flags, view, out)
        }
        for (f in fallback.values) if (f.sentMeta != null) refreshMeta(f, TileData.FLAG_SCREEN or TileData.FLAG_FALLBACK, view, out)
    }

    private fun meta(slot: Slot, flags: Int, v: View) =
        TileMeta(slot.kind, slot.originX, slot.originZ, flags, v.mini, v.panQX, v.panQZ, v.screen, v.sens, slot.key.lod, v.anim)

    private fun dropFallback(out: MutableList<Packet<in ClientGamePacketListener>>) {
        val ids = ArrayList<Int>()
        for ((k, f) in fallback) {
            if (wanted.any { it.key == k }) continue
            active.remove(k)
            f.frame?.let { ids.add(it.id); ids.add(it.id2) }
            f.frame = null
        }
        fallback.clear()
        if (ids.isNotEmpty()) {
            out.add(Packets.remove(*ids.toIntArray()))
            map.markRidersDirty()
        }
    }

    private fun lodFor(scale: Double): Int {
        if (scale >= 0.75) return 0
        return floor(ln(1.0 / scale) / ln(2.0)).toInt().coerceIn(1, TerrainCache.MAX_LOD)
    }

    private fun rebuildWanted(kind: Int, wide: Int, lod: Int, layer: Int, bx: Int, bz: Int, screen: ScreenSession?, view: View, r: LongArray, out: MutableList<Packet<in ClientGamePacketListener>>) {
        val want = LinkedHashMap<TileKey, Want>()
        val detailFlags = TileData.FLAG_MINI or TileData.FLAG_BIG or (if (screen != null) TileData.FLAG_SCREEN else 0)
        for (tx in r[0].toInt()..r[1].toInt()) for (tz in r[2].toInt()..r[3].toInt()) {
            val k = TileKey(0, layer, tx, tz)
            want[k] = Want(k, tx * wide, tz * TileData.STRIDE_Z, kind, detailFlags)
        }
        if (screen != null) {
            for (tx in r[6].toInt()..r[7].toInt()) for (tz in r[8].toInt()..r[9].toInt()) {
                if (lod == 0) {
                    val k = TileKey(0, layer, tx, tz)
                    if (k !in want) want[k] = Want(k, tx * wide, tz * TileData.STRIDE_Z, kind, detailFlags)
                } else if (layer == Int.MIN_VALUE) {
                    val k = TileKey(lod, Int.MIN_VALUE, tx, tz)
                    want[k] = Want(k, tx * (128 shl lod), tz * (TileData.STRIDE_Z shl lod), TileData.OVERVIEW, TileData.FLAG_SCREEN)
                }
            }
        }
        val cx = if (screen != null) view.centerX else bx
        val cz = if (screen != null) view.centerZ else bz
        wanted = want.values.sortedBy { w ->
            val span = span(w.key, w.kind)
            val ox = (w.originX + span / 2 - cx).toLong()
            val oz = (w.originZ + span / 2 - cz).toLong()
            ox * ox + oz * oz
        }.take(MAX_ACTIVE)
        val keep = wanted.mapTo(HashSet()) { it.key }
        val hide = active.keys.filter { it !in keep }
        if (hide.isEmpty()) return
        val ids = ArrayList<Int>()
        val room = MAX_ACTIVE + FALLBACK_MAX - wanted.size
        for (k in hide) {
            val s = active[k] ?: continue
            if (screen != null && s.sigs != null && (layer == Int.MIN_VALUE || s.key.layer == layer) && fallback.size < room.coerceAtMost(FALLBACK_MAX) && overlaps(s, view)) {
                fallback[k] = s
                continue
            }
            active.remove(k)
            fallback.remove(k)
            s.frame?.let { ids.add(it.id); ids.add(it.id2) }
            s.frame = null
        }
        for (k in keep) fallback.remove(k)
        if (ids.isNotEmpty()) {
            out.add(Packets.remove(*ids.toIntArray()))
            map.markRidersDirty()
        }
    }

    private fun span(k: TileKey, kind: Int): Int = if (k.lod == 0) (if (kind == TileData.RGB) 64 else 128) else 128 shl k.lod

    private fun overlaps(s: Slot, v: View): Boolean {
        val w = span(s.key, s.kind)
        val h = if (s.key.lod == 0) 128 else 128 shl s.key.lod
        return s.originX < v.centerX + v.halfW && s.originX + w > v.centerX - v.halfW && s.originZ < v.centerZ + v.halfH && s.originZ + h > v.centerZ - v.halfH
    }

    private fun activate(w: Want, out: MutableList<Packet<in ClientGamePacketListener>>): Slot? {
        var slot = cached[w.key]
        if (slot == null) {
            val id = freeMapIds.removeFirstOrNull() ?: evictOne() ?: return null
            slot = Slot(w.key, id, w.originX, w.originZ, w.kind)
            cached[w.key] = slot
        }
        if (slot.frame == null) {
            val frame = TileFrame(slot.mapId)
            slot.frame = frame
            active[w.key] = slot
            if (slot.sigs != null) {
                spawnFrame(frame, out)
                slot.sentMeta = null
            }
        }
        return slot
    }

    private fun evictOne(): Int? {
        val it = cached.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value.frame != null) continue
            it.remove()
            return e.value.mapId
        }
        return null
    }

    private fun evictOverflow() {
        if (cached.size <= CLIENT_CACHE) return
        val it = cached.entries.iterator()
        while (cached.size > CLIENT_CACHE && it.hasNext()) {
            val e = it.next()
            if (e.value.frame != null) continue
            it.remove()
            freeMapIds.add(e.value.mapId)
        }
    }

    private fun updateDetail(slot: Slot, w: Want, wm: WorldMap, wide: Int, mods: Long, discMod: Long, view: View, canSend: Boolean, out: MutableList<Packet<in ClientGamePacketListener>>): Boolean {
        val cache = wm.cache
        val layer = w.key.layer
        val cx0 = (slot.originX shr 4) - 1
        val cx1 = ((slot.originX + wide - 1) shr 4) + 1
        val cz0 = (slot.originZ shr 4) - 1
        val cz1 = ((slot.originZ + 127) shr 4) + 1
        val nx = cx1 - cx0 + 1
        val nz = cz1 - cz0 + 1
        val prev = slot.sigs
        val sig = sigScratch
        var minX = Int.MAX_VALUE
        var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxZ = Int.MIN_VALUE
        var missing = false
        for (z in 0 until nz) {
            for (x in 0 until nx) {
                val wx = cx0 + x
                val wz = cz0 + z
                val i = z * nx + x
                val old = if (prev != null && prev.size == nx * nz) prev[i] else -1L
                val v = if (!map.discovered.contains(wx, wz)) {
                    0L
                } else {
                    val su = if (layer == Int.MIN_VALUE) cache.get(wx, wz) else cache.slice(wx, wz, layer)
                    if (su != null) {
                        su.version * 2 + 2
                    } else {
                        if (layer == Int.MIN_VALUE) cache.request(wx, wz)
                        missing = true
                        if (old >= 2) old else 1L
                    }
                }
                sig[i] = v
                if (v != old) {
                    if (wx < minX) minX = wx
                    if (wx > maxX) maxX = wx
                    if (wz < minZ) minZ = wz
                    if (wz > maxZ) maxZ = wz
                }
            }
        }
        val n = nx * nz
        slot.missing = missing
        if (minX == Int.MAX_VALUE) {
            slot.mods = mods
            slot.discMod = discMod
            refreshMeta(slot, w.flags, view, out)
            return false
        }
        if (!canSend) return false
        val source = if (layer == Int.MIN_VALUE) TileAssembler.Source { x, z -> cache.get(x, z) } else TileAssembler.Source { x, z -> cache.slice(x, z, layer) }
        val known = TileAssembler.Discovered { x, z -> map.discovered.contains(x, z) }
        val scale = if (slot.kind == TileData.RGB) 2 else 1
        var x0 = ((minX shl 4) - 1 - slot.originX).coerceAtLeast(0) * scale
        var x1 = (((maxX shl 4) + 17 - slot.originX) * scale + scale - 1).coerceAtMost(127)
        val y0 = ((minZ shl 4) - 1 - slot.originZ).coerceAtLeast(0)
        val y1 = ((maxZ shl 4) + 17 - slot.originZ).coerceAtMost(127)
        if (scale == 2) {
            x0 = x0 and 1.inv()
            x1 = x1 or 1
        }
        val width = x1 - x0 + 1
        val height = y1 - y0 + 1
        val sentMeta = slot.sentMeta
        if (prev == null || sentMeta == null || width <= 0 || height <= 0 || width * height > 128 * 128 * 3 / 5) {
            val data = if (slot.kind == TileData.RGB) assembler.rgb(slot.originX, slot.originZ, source, known, wm.brightness) else assembler.palette(slot.originX, slot.originZ, source, known)
            sendFull(slot, data, meta(slot, w.flags, view), out)
        } else {
            val data = if (slot.kind == TileData.RGB) assembler.rgb(slot.originX, slot.originZ, source, known, wm.brightness, x0, y0, width, height) else assembler.palette(slot.originX, slot.originZ, source, known, x0, y0, width, height)
            if (y0 == 0) for (c in x0 until minOf(x0 + width, TileData.META_WIDTH)) data[c - x0] = sentMeta[c]
            out.add(slot.frame!!.patch(x0, y0, width, height, data))
            refreshMeta(slot, w.flags, view, out)
        }
        slot.sigs = if (prev != null && prev.size == n) sig.copyInto(prev, 0, 0, n) else sig.copyOf(n)
        slot.mods = mods
        slot.discMod = discMod
        return true
    }

    private fun sendFull(slot: Slot, data: ByteArray, meta: TileMeta, out: MutableList<Packet<in ClientGamePacketListener>>) {
        val frame = slot.frame ?: return
        TileData.writeMeta(data, meta)
        slot.sentMeta = TileData.meta(meta)
        out.add(frame.full(data))
        if (slot.sigs == null) spawnFrame(frame, out)
    }

    private fun spawnFrame(frame: TileFrame, out: MutableList<Packet<in ClientGamePacketListener>>) {
        val loc = map.player.location
        if (!lifted) {
            out.addAll(TileFrame.liftPackets(liftUp, TileFrame.LIFT_UP, loc.x, loc.y + 1.8, loc.z))
            out.addAll(TileFrame.liftPackets(liftDown, TileFrame.LIFT_DOWN, loc.x, loc.y + 1.8, loc.z))
            lifted = true
        }
        out.addAll(frame.spawnPackets(loc.x, loc.y + 1.8, loc.z))
        map.markRidersDirty()
    }

    private fun refreshMeta(slot: Slot, flags: Int, view: View, out: MutableList<Packet<in ClientGamePacketListener>>) {
        val frame = slot.frame ?: return
        val m = TileData.meta(meta(slot, flags, view))
        if (!m.contentEquals(slot.sentMeta)) {
            slot.sentMeta = m
            out.add(frame.patch(0, 0, TileData.META_WIDTH, 1, m))
        }
    }

    private fun tileRadiusBlocks(): Int {
        val cfg = map.plugin.cfg
        val mini = (cfg.minimapBlocks / ShaderDefines.MINI_ZOOMS[0] * 0.75).toInt()
        val big = if (map.settings.module.big) (cfg.bigMapBlocks / ShaderDefines.MINI_ZOOMS[0] * 0.75).toInt().coerceAtMost(cfg.streamRadiusChunks * 16) else 0
        return maxOf(mini, big, 64)
    }

    companion object {
        const val MAP_ID_BASE = 2_000_000_000
        const val MAX_ACTIVE = 112
        const val CLIENT_CACHE = 640
        private const val FALLBACK_MAX = 48
        private val EMPTY_SIGS = LongArray(0)
    }
}
