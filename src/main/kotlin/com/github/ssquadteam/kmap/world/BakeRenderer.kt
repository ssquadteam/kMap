package com.github.ssquadteam.kmap.world

import com.github.ssquadteam.kmap.config.BakeSource
import com.github.ssquadteam.kmap.terrain.Shading
import com.github.ssquadteam.kmap.terrain.TileData
import java.awt.image.BufferedImage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class BakeGrid(val width: Int, val height: Int) {
    val heights = IntArray(width * height) { Int.MIN_VALUE }
    val rgb = IntArray(width * height)
    val water = ByteArray(width * height)
    val waterRgb = IntArray(width * height)

    fun has(i: Int) = heights[i] != Int.MIN_VALUE
}

object BakeRenderer {
    private const val VOID = 0x141418

    fun render(grid: BakeGrid, source: BakeSource, brightness: Double): IntArray {
        return if (source == BakeSource.HD_SURFACE) hd(grid, brightness) else plain(grid, brightness)
    }

    private fun h(grid: BakeGrid, x: Int, z: Int, fallback: Int): Int {
        if (x < 0 || z < 0 || x >= grid.width || z >= grid.height) return fallback
        val v = grid.heights[z * grid.width + x]
        return if (v == Int.MIN_VALUE) fallback else v
    }

    private fun plain(grid: BakeGrid, brightness: Double): IntArray {
        val out = IntArray(grid.width * grid.height)
        for (z in 0 until grid.height) {
            for (x in 0 until grid.width) {
                val i = z * grid.width + x
                if (!grid.has(i)) {
                    out[i] = VOID
                    continue
                }
                val hh = grid.heights[i]
                val north = h(grid, x, z - 1, hh)
                val west = h(grid, x - 1, z, hh)
                var f = 1.0 + ((hh - north) * 0.09 + (hh - west) * 0.05).coerceIn(-0.28, 0.22)
                f = min(f, 1.0) * 0.84 / 0.84
                f *= if (brightness != 1.0) brightness else 1.0
                val base = TileData.shadeRgb(grid.rgb[i], f * 0.92)
                val d = grid.water[i].toInt()
                out[i] = if (d > 0) TileData.mix(base, TileData.shadeRgb(grid.waterRgb[i], 1.0 - (d * 0.012).coerceAtMost(0.3)), (0.45 + d * 0.06).coerceAtMost(0.9)) else base
            }
        }
        return out
    }

    private fun hd(grid: BakeGrid, brightness: Double): IntArray {
        val w = grid.width
        val hgt = grid.height
        val blurred = IntArray(w * hgt)
        for (z in 0 until hgt) {
            for (x in 0 until w) {
                val i = z * w + x
                if (!grid.has(i)) continue
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                val me = grid.heights[i]
                for (dz in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val zz = z + dz
                    if (xx < 0 || zz < 0 || xx >= w || zz >= hgt) continue
                    val j = zz * w + xx
                    if (!grid.has(j) || kotlin.math.abs(grid.heights[j] - me) > 2 || (grid.water[j] > 0) != (grid.water[i] > 0)) continue
                    val c = grid.rgb[j]
                    val weight = if (dx == 0 && dz == 0) 4 else if (dx == 0 || dz == 0) 2 else 1
                    r += (c shr 16 and 255) * weight
                    g += (c shr 8 and 255) * weight
                    b += (c and 255) * weight
                    n += weight
                }
                blurred[i] = if (n == 0) grid.rgb[i] else ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        val out = IntArray(w * hgt)
        for (z in 0 until hgt) {
            for (x in 0 until w) {
                val i = z * w + x
                if (!grid.has(i)) {
                    out[i] = VOID
                    continue
                }
                val hh = grid.heights[i]
                val depth = grid.water[i].toInt()
                val base = TileData.mix(grid.rgb[i], blurred[i], 0.35)
                if (depth > 0) {
                    val shore = dry(grid, x, z - 1) || dry(grid, x - 1, z) || dry(grid, x + 1, z) || dry(grid, x, z + 1)
                    out[i] = Shading.water(base, grid.waterRgb[i], depth, shore, brightness)
                    continue
                }
                out[i] = Shading.land(base, hh, h(grid, x, z - 1, hh), h(grid, x - 1, z, hh), h(grid, x - 1, z - 1, hh), h(grid, x - 2, z - 2, hh), brightness)
            }
        }
        return out
    }

    private fun dry(grid: BakeGrid, x: Int, z: Int): Boolean {
        if (x < 0 || z < 0 || x >= grid.width || z >= grid.height) return false
        val i = z * grid.width + x
        return grid.has(i) && grid.water[i].toInt() == 0
    }

    fun sprite(pixels: IntArray, w: Int, h: Int, originX: Int, originZ: Int, blocksPerPx: Double): BufferedImage {
        val img = BufferedImage(w + 2, h + 2, BufferedImage.TYPE_INT_ARGB)
        for (z in 0 until h) for (x in 0 until w) img.setRGB(x + 1, z + 1, (0xFF shl 24) or pixels[z * w + x])
        fun put(x: Int, y: Int, v: Int) = img.setRGB(x, y, (0xFF shl 24) or (v and 0xFFFFFF))
        for (x in 0 until w + 2) {
            put(x, 0, 0x141418)
            put(x, h + 1, 0x141418)
        }
        for (y in 0 until h + 2) {
            put(0, y, 0x141418)
            put(w + 1, y, 0x141418)
        }
        val corners = listOf(Triple(0, 0, 0), Triple(0, h + 1, 1), Triple(w + 1, h + 1, 2), Triple(w + 1, 0, 3))
        for ((cx, cy, c) in corners) {
            put(cx, cy, (107 shl 16) or (77 shl 8) or (16 + c))
            put(if (cx == 0) 1 else w, cy, w)
            put(cx, if (cy == 0) 1 else h, h)
        }
        put(3, 0, originX + 8388608)
        put(4, 0, originZ + 8388608)
        put(5, 0, (blocksPerPx * 256).toInt())
        return img
    }
}
