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
                if (s == null) {
                    out[o] = MapPalette.GREY_KNOWN
                    continue
                }
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
        val (gk0, gk1) = MapPalette.rgb555Pair(KNOWN_RGB)
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
                if (s == null) {
                    out[idx] = gk0
                    out[idx + 1] = gk1
                    continue
                }
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
                val shore = s.water[i].toInt() > 0 && (side.dry(wx, wz - 1) || side.dry(wx - 1, wz) || side.dry(wx + 1, wz) || side.dry(wx, wz + 1))
                val (a, bb) = MapPalette.rgb555Pair(TileData.finalRgb(s, i, north, west, nw, nw2, shore, brightness))
                out[idx] = a
                out[idx + 1] = bb
            }
        }
        return out
    }

    companion object {
        const val KNOWN_RGB = 0x2A2D31
        const val CAVE_ROCK_RGB = 0x16181B
        val CAVE_ROCK_PALETTE: Byte = (29 * 4 + 0).toByte()

        fun tileX(wx: Int, wide: Int): Int = Math.floorDiv(wx, wide)

        fun tileZ(wz: Int): Int = Math.floorDiv(wz, TileData.STRIDE_Z)
    }
}
