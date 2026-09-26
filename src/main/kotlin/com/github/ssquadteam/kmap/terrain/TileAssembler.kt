package com.github.ssquadteam.kmap.terrain



class TileAssembler {
    fun interface Source {
        fun surface(cx: Int, cz: Int): ChunkSurface?
    }

    fun interface Discovered {
        fun known(cx: Int, cz: Int): Boolean
    }

    private class Known(val discovered: Discovered) {
        private var lastCx = Int.MIN_VALUE
        private var lastCz = Int.MIN_VALUE
        private var last = false

        fun at(cx: Int, cz: Int): Boolean {
            if (cx != lastCx || cz != lastCz) {
                lastCx = cx
                lastCz = cz
                last = discovered.known(cx, cz)
            }
            return last
        }
    }

    private class Lookup(val source: Source) {
        private var lastKey = Long.MIN_VALUE
        private var last: ChunkSurface? = null

        fun at(cx: Int, cz: Int): ChunkSurface? {
            val k = TerrainCache.key(cx, cz)
            if (k != lastKey) {
                lastKey = k
                last = source.surface(cx, cz)
            }
            return last
        }

        fun dry(wx: Int, wz: Int): Boolean {
            val s = at(wx shr 4, wz shr 4) ?: return false
            return s.water[(wz and 15) * 16 + (wx and 15)].toInt() == 0
        }

        fun height(wx: Int, wz: Int, fallback: Int): Int {
            val s = at(wx shr 4, wz shr 4) ?: return fallback
            val h = s.heights[(wz and 15) * 16 + (wx and 15)].toInt()
            return if (h == Short.MIN_VALUE.toInt()) fallback else h
        }
    }

    fun palette(originX: Int, originZ: Int, source: Source, discovered: Discovered, x0: Int = 0, y0: Int = 0, w: Int = 128, h: Int = 128): ByteArray {
        val out = ByteArray(w * h)
        val main = Lookup(source)
        val known = Known(discovered)
        val side = Lookup(source)
        for (r in 0 until h) {
            val wz = originZ + y0 + r
            for (c in 0 until w) {
                val wx = originX + x0 + c
                val cx = wx shr 4
                val cz = wz shr 4
                if (!known.at(cx, cz)) continue
                val o = r * w + c
                val s = main.at(cx, cz)
                if (s == null) continue
                val i = (wz and 15) * 16 + (wx and 15)
                val hh = s.heights[i].toInt()
                if (hh == Short.MIN_VALUE.toInt()) {
                    out[o] = CAVE_ROCK_PALETTE
                    continue
                }
                val north = side.height(wx, wz - 1, hh)
                out[o] = TileData.paletteByte(s, i, north, wx, wz)
            }
        }
        return out
    }

    fun rgb(originX: Int, originZ: Int, source: Source, discovered: Discovered, brightness: Double, x0: Int = 0, y0: Int = 0, w: Int = 128, h: Int = 128): ByteArray {
        val out = ByteArray(w * h)
        val main = Lookup(source)
        val known = Known(discovered)
        val side = Lookup(source)
        val (rk0, rk1) = MapPalette.rgb555Pair(CAVE_ROCK_RGB)
        for (r in 0 until h) {
            val wz = originZ + y0 + r
            for (b in 0 until w / 2) {
                val wx = originX + x0 / 2 + b
                val cx = wx shr 4
                val cz = wz shr 4
                if (!known.at(cx, cz)) continue
                val idx = r * w + b * 2
                val s = main.at(cx, cz)
                if (s == null) continue
                val i = (wz and 15) * 16 + (wx and 15)
                val hh = s.heights[i].toInt()
                if (hh == Short.MIN_VALUE.toInt()) {
                    out[idx] = rk0
                    out[idx + 1] = rk1
                    continue
                }
                val north = side.height(wx, wz - 1, hh)
                val west = side.height(wx - 1, wz, hh)
                val nw = side.height(wx - 1, wz - 1, hh)
                val nw2 = side.height(wx - 2, wz - 2, hh)
                val wet = s.water[i].toInt() > 0
                val shore = wet && (side.dry(wx, wz - 1) || side.dry(wx - 1, wz) || side.dry(wx + 1, wz) || side.dry(wx, wz + 1))
                val edges = if (wet) 0 else (if (hh - side.height(wx, wz + 1, hh) >= EDGE_DROP) 1 else 0) or (if (hh - side.height(wx + 1, wz, hh) >= EDGE_DROP) 2 else 0)
                val (a, bb) = MapPalette.rgb555Pair(TileData.finalRgb(s, i, north, west, nw, nw2, shore, brightness), edges)
                out[idx] = a
                out[idx + 1] = bb
            }
        }
        return out
    }

    fun overview(originX: Int, originZ: Int, lod: Int, thumbs: (Int, Int) -> ShortArray?, discovered: Discovered, missing: BooleanArray): ByteArray {
        val out = ByteArray(128 * 128)
        val known = Known(discovered)
        val step = 1 shl lod
        val sub = (step shr 1).coerceAtLeast(1)
        var lastCx = Int.MIN_VALUE
        var lastCz = Int.MIN_VALUE
        var thumb: ShortArray? = null
        for (r in 0 until 128) {
            val wz = originZ + r * step
            val cz = wz shr 4
            val pz = (wz and 15) shr 1
            for (c in 0 until 128) {
                val wx = originX + c * step
                val cx = wx shr 4
                if (!known.at(cx, cz)) continue
                if (cx != lastCx || cz != lastCz) {
                    lastCx = cx
                    lastCz = cz
                    thumb = thumbs(cx, cz)
                    if (thumb == null) missing[0] = true
                }
                val t = thumb ?: continue
                val px = (wx and 15) shr 1
                var rr = 0
                var gg = 0
                var bb = 0
                var n = 0
                for (dz in 0 until sub) {
                    val row = (pz + dz) * 8
                    for (dx in 0 until sub) {
                        val v = t[row + px + dx].toInt()
                        if (v and Thumbnails.PRESENT == 0) continue
                        rr += (v shr 10) and 31
                        gg += (v shr 5) and 31
                        bb += v and 31
                        n++
                    }
                }
                if (n == 0) continue
                out[r * 128 + c] = MapPalette.nearestFull555(((rr / n) shl 10) or ((gg / n) shl 5) or (bb / n))
            }
        }
        return out
    }

    companion object {
        const val CAVE_ROCK_RGB = 0x16181B
        private const val EDGE_DROP = 3
        val CAVE_ROCK_PALETTE: Byte = (29 * 4 + 0).toByte()

        fun tileX(wx: Int, wide: Int): Int = Math.floorDiv(wx, wide)

        fun tileZ(wz: Int): Int = Math.floorDiv(wz, TileData.STRIDE_Z)
    }
}
