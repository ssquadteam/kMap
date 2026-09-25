package com.github.ssquadteam.kmap.terrain



class TileAssembler {
    fun interface Source {
        fun surface(cx: Int, cz: Int): ChunkSurface?
    }

    fun interface Discovered {
        fun known(cx: Int, cz: Int): Boolean
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

        fun height(wx: Int, wz: Int, fallback: Int): Int {
            val s = at(wx shr 4, wz shr 4) ?: return fallback
            val h = s.heights[(wz and 15) * 16 + (wx and 15)].toInt()
            return if (h == Short.MIN_VALUE.toInt()) fallback else h
        }
    }

    fun palette(originX: Int, originZ: Int, source: Source, discovered: Discovered, versionOut: LongArray? = null): ByteArray {
        val out = ByteArray(128 * 128)
        val main = Lookup(source)
        val side = Lookup(source)
        var maxVersion = 0L
        for (r in 0 until 128) {
            val wz = originZ + r
            for (c in 0 until 128) {
                val wx = originX + c
                val cx = wx shr 4
                val cz = wz shr 4
                if (!discovered.known(cx, cz)) continue
                val s = main.at(cx, cz)
                if (s == null) {
                    out[r * 128 + c] = MapPalette.GREY_KNOWN
                    continue
                }
                if (s.version > maxVersion) maxVersion = s.version
                val i = (wz and 15) * 16 + (wx and 15)
                val h = s.heights[i].toInt()
                if (h == Short.MIN_VALUE.toInt()) {
                    out[r * 128 + c] = CAVE_ROCK_PALETTE
                    continue
                }
                val north = side.height(wx, wz - 1, h)
                out[r * 128 + c] = TileData.paletteByte(s, i, north, wx, wz)
            }
        }
        versionOut?.set(0, maxVersion)
        return out
    }

    fun rgb(originX: Int, originZ: Int, source: Source, discovered: Discovered, brightness: Double, versionOut: LongArray? = null): ByteArray {
        val out = ByteArray(128 * 128)
        val main = Lookup(source)
        val side = Lookup(source)
        var maxVersion = 0L
        val (gk0, gk1) = MapPalette.rgb555Pair(KNOWN_RGB)
        val (rk0, rk1) = MapPalette.rgb555Pair(CAVE_ROCK_RGB)
        for (r in 0 until 128) {
            val wz = originZ + r
            for (b in 0 until 64) {
                val wx = originX + b
                val cx = wx shr 4
                val cz = wz shr 4
                if (!discovered.known(cx, cz)) continue
                val idx = r * 128 + b * 2
                val s = main.at(cx, cz)
                if (s == null) {
                    out[idx] = gk0
                    out[idx + 1] = gk1
                    continue
                }
                if (s.version > maxVersion) maxVersion = s.version
                val i = (wz and 15) * 16 + (wx and 15)
                val h = s.heights[i].toInt()
                if (h == Short.MIN_VALUE.toInt()) {
                    out[idx] = rk0
                    out[idx + 1] = rk1
                    continue
                }
                val north = side.height(wx, wz - 1, h)
                val west = side.height(wx - 1, wz, h)
                val (a, bb) = MapPalette.rgb555Pair(TileData.finalRgb(s, i, north, west, brightness))
                out[idx] = a
                out[idx + 1] = bb
            }
        }
        versionOut?.set(0, maxVersion)
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
