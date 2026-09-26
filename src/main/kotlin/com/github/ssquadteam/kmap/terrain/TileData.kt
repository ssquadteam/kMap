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
    var lod: Int = 0,
    var anim: ZoomAnim = ZoomAnim.NONE,
)

class ZoomAnim(val startRatio: Double, val anchorX: Double, val anchorY: Double, val offsetX: Double, val offsetY: Double, val startTick: Int) {
    val active get() = startRatio != 1.0 || offsetX != 0.0 || offsetY != 0.0

    companion object {
        val NONE = ZoomAnim(1.0, 0.0, 0.0, 0.0, 0.0, 0)
    }
}

object TileData {
    const val PALETTE = 0
    const val RGB = 1
    const val OVERVIEW = 3
    const val FLAG_MINI = 1
    const val FLAG_SCREEN = 2
    const val FLAG_BIG = 4
    const val FLAG_FALLBACK = 8
    const val META_WIDTH = 36
    const val QUARTER = 4
    const val STRIDE_Z = 127

    private fun put(out: ByteArray, index: Int, digit: Int) {
        out[index] = MapPalette.digit(digit and 127)
    }

    private fun put2(out: ByteArray, index: Int, value: Int) {
        val v = value.coerceIn(0, 16383)
        put(out, index, v shr 7)
        put(out, index + 1, v)
    }

    private fun put4(out: ByteArray, index: Int, value: Int) {
        val v = value + (1 shl 27)
        put(out, index, v shr 21)
        put(out, index + 1, v shr 14)
        put(out, index + 2, v shr 7)
        put(out, index + 3, v)
    }

    fun meta(m: TileMeta): ByteArray {
        val out = ByteArray(META_WIDTH) { MapPalette.digit(0) }
        put(out, 0, 107)
        put(out, 1, 77)
        put(out, 2, m.kind)
        put4(out, 3, m.originX)
        put4(out, 7, m.originZ)
        put(out, 11, m.flags)
        put(out, 12, m.mini)
        put4(out, 13, m.panX)
        put4(out, 17, m.panZ)
        put(out, 21, m.screen)
        put(out, 22, m.sens)
        put(out, 23, m.lod)
        val a = m.anim
        put2(out, 24, Math.round((Math.log(a.startRatio) + 4.0) * 2048.0).toInt())
        put2(out, 26, Math.round(a.anchorX * 2.0).toInt() + 4096)
        put2(out, 28, Math.round(a.anchorY * 2.0).toInt() + 4096)
        put(out, 30, a.startTick)
        put2(out, 31, Math.round(a.offsetX * 2.0).toInt() + 8192)
        put2(out, 33, Math.round(a.offsetY * 2.0).toInt() + 8192)
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

    fun finalRgb(s: ChunkSurface, i: Int, north: Int, west: Int, northWest: Int, farNorthWest: Int, shore: Boolean, brightness: Double = 1.0): Int {
        val depth = s.water[i].toInt()
        if (depth > 0) return Shading.water(s.rgb[i], s.waterRgb[i], depth, shore, brightness)
        return Shading.land(s.rgb[i], s.heights[i].toInt(), north, west, northWest, farNorthWest, brightness)
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
