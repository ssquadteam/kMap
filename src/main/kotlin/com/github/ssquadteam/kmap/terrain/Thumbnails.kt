package com.github.ssquadteam.kmap.terrain

object Thumbnails {
    const val PRESENT = 0x8000

    fun of(s: ChunkSurface, brightness: Double): ShortArray {
        val out = ShortArray(64)
        val rgb = s.rgb.isNotEmpty()
        for (ty in 0 until 8) {
            for (tx in 0 until 8) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dz in 0..1) {
                    for (dx in 0..1) {
                        val x = tx * 2 + dx
                        val z = ty * 2 + dz
                        val c = color(s, x, z, rgb, brightness)
                        if (c < 0) continue
                        r += c shr 16 and 255
                        g += c shr 8 and 255
                        b += c and 255
                        n++
                    }
                }
                if (n == 0) continue
                out[ty * 8 + tx] = (PRESENT or (((r / n) shr 3) shl 10) or (((g / n) shr 3) shl 5) or ((b / n) shr 3)).toShort()
            }
        }
        return out
    }

    private fun color(s: ChunkSurface, x: Int, z: Int, rgb: Boolean, brightness: Double): Int {
        val i = z * 16 + x
        val h = s.heights[i].toInt()
        if (h == Short.MIN_VALUE.toInt()) return TileAssembler.CAVE_ROCK_RGB
        if (s.mapColor[i].toInt() == 0 && s.water[i].toInt() == 0) return -1
        val north = if (z > 0) s.heights[i - 16].toInt() else h
        if (!rgb) {
            val id = TileData.paletteByte(s, i, north, x, z).toInt() and 255
            return if (id == 0) -1 else MapPalette.rgb[id]
        }
        val west = if (x > 0) s.heights[i - 1].toInt() else h
        val nw = if (x > 0 && z > 0) s.heights[i - 17].toInt() else h
        return TileData.finalRgb(s, i, north, west, nw, nw, false, brightness)
    }
}
