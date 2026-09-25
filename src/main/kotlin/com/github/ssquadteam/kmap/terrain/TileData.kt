package com.github.ssquadteam.kmap.terrain

class TileMeta(
    var kind: Int,
    var originX: Int,
    var originZ: Int,
    var flags: Int,
    var mini: Int,
    var panX: Int,
    var panZ: Int,
    var screen: Int,
    var sens: Int,
)

object TileData {
    const val PALETTE = 0
    const val RGB = 1
    const val FOG = 2
    const val FLAG_MINI = 1
    const val FLAG_SCREEN = 2
    const val FLAG_BIG = 4
    const val META_WIDTH = 24
    const val STRIDE_Z = 127

    private fun put(out: ByteArray, index: Int, digit: Int) {
        out[index] = MapPalette.digit(digit and 127)
    }

    private fun put3(out: ByteArray, index: Int, value: Int) {
        val v = value + 1048576
        put(out, index, v shr 14)
        put(out, index + 1, v shr 7)
        put(out, index + 2, v)
    }

    fun meta(m: TileMeta): ByteArray {
        val out = ByteArray(META_WIDTH) { MapPalette.digit(0) }
        put(out, 0, 107)
        put(out, 1, 77)
        put(out, 2, m.kind)
        put3(out, 3, m.originX)
        put3(out, 6, m.originZ)
        put(out, 9, m.flags)
        put(out, 10, m.mini)
        put3(out, 11, m.panX)
        put3(out, 14, m.panZ)
        put(out, 17, m.screen)
        put(out, 18, m.sens)
        return out
    }

    fun writeMeta(tile: ByteArray, m: TileMeta) {
        System.arraycopy(meta(m), 0, tile, 0, META_WIDTH)
    }

    fun shadeRgb(rgb: Int, factor: Double): Int {
        val r = ((rgb shr 16 and 255) * factor).toInt().coerceIn(0, 255)
        val g = ((rgb shr 8 and 255) * factor).toInt().coerceIn(0, 255)
        val b = ((rgb and 255) * factor).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    fun mix(a: Int, b: Int, t: Double): Int {
        val r = ((a shr 16 and 255) * (1 - t) + (b shr 16 and 255) * t).toInt()
        val g = ((a shr 8 and 255) * (1 - t) + (b shr 8 and 255) * t).toInt()
        val bl = ((a and 255) * (1 - t) + (b and 255) * t).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    fun finalRgb(s: ChunkSurface, i: Int, north: Int, west: Int, brightness: Double = 1.0): Int {
        val h = s.heights[i].toInt()
        val depth = s.water[i].toInt()
        var f = 1.0 + ((h - north) * 0.09 + (h - west) * 0.05).coerceIn(-0.28, 0.22)
        f *= brightness
        val base = shadeRgb(s.rgb[i], f)
        if (depth > 0) {
            val t = (0.42 + depth * 0.055).coerceAtMost(0.88)
            return mix(base, shadeRgb(s.waterRgb[i], 1.0 - (depth * 0.012).coerceAtMost(0.25)), t)
        }
        return base
    }

    fun paletteByte(s: ChunkSurface, i: Int, north: Int, wx: Int, wz: Int): Byte {
        val mc = s.mapColor[i].toInt() and 255
        if (mc == 0) return 0
        val depth = s.water[i].toInt()
        val shade = if (depth > 0) {
            val d = depth * 0.1 + ((wx + wz) and 1) * 0.2
            if (d < 0.5) 2 else if (d > 0.9) 0 else 1
        } else {
            val h = s.heights[i].toInt()
            if (h > north) 2 else if (h < north) 0 else 1
        }
        return (mc * 4 + shade).toByte()
    }
}
