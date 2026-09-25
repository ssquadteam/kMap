package com.github.ssquadteam.kmap.terrain

import java.io.InputStream

object MapPalette {
    val rgb = IntArray(256)
    private val valid = BooleanArray(256)
    const val KNOWN: Byte = 119
    const val UNKNOWN: Byte = 116
    const val GREY_KNOWN: Byte = 87

    fun load(input: InputStream) {
        input.bufferedReader().forEachLine { line ->
            val p = line.trim().split(' ')
            if (p.size == 4) {
                val id = p[0].toInt()
                rgb[id] = (p[1].toInt() shl 16) or (p[2].toInt() shl 8) or p[3].toInt()
                valid[id] = true
            }
        }
    }

    private val baseLut: ByteArray by lazy {
        val lut = ByteArray(32768)
        for (v in 0 until 32768) {
            val r = ((v shr 10) and 31) * 255 / 31
            val g = ((v shr 5) and 31) * 255 / 31
            val b = (v and 31) * 255 / 31
            var best = 1
            var bestD = Int.MAX_VALUE
            for (base in 1 until 64) {
                val id = base * 4 + 1
                if (!valid[id]) continue
                val c = rgb[id]
                val dr = (c shr 16 and 255) - r
                val dg = (c shr 8 and 255) - g
                val db = (c and 255) - b
                val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
                if (d < bestD) {
                    bestD = d
                    best = base
                }
            }
            lut[v] = best.toByte()
        }
        lut
    }

    fun nearestBase(color: Int): Int = baseLut[((color shr 19 and 31) shl 10) or ((color shr 11 and 31) shl 5) or (color shr 3 and 31)].toInt() and 255

    fun digit(d: Int): Byte = (4 + d).toByte()

    fun rgb555Pair(color: Int): Pair<Byte, Byte> {
        val r = (color shr 19) and 31
        val g = (color shr 11) and 31
        val b = (color shr 3) and 31
        val v = (r shl 10) or (g shl 5) or b
        return (4 + v / 240).toByte() to (4 + v % 240).toByte()
    }

    fun nearest(color: Int): Byte {
        val r = color shr 16 and 255
        val g = color shr 8 and 255
        val b = color and 255
        var best = 4
        var bestD = Int.MAX_VALUE
        for (i in 4 until 256) {
            if (!valid[i]) continue
            val c = rgb[i]
            val dr = (c shr 16 and 255) - r
            val dg = (c shr 8 and 255) - g
            val db = (c and 255) - b
            val d = dr * dr * 3 + dg * dg * 4 + db * db * 2
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best.toByte()
    }
}
