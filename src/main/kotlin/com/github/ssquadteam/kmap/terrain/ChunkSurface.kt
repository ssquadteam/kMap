package com.github.ssquadteam.kmap.terrain

class ChunkSurface(
    val cx: Int,
    val cz: Int,
    val heights: ShortArray,
    val rgb: IntArray,
    val mapColor: ByteArray,
    val water: ByteArray,
    val waterRgb: IntArray,
) {
    @Volatile
    var version: Long = 0

    @Volatile
    var lastAccess: Long = 0

    var fromDisk = false

    val hash: Int by lazy {
        var h = heights.contentHashCode()
        h = h * 31 + rgb.contentHashCode()
        h = h * 31 + mapColor.contentHashCode()
        h = h * 31 + water.contentHashCode()
        h = h * 31 + waterRgb.contentHashCode()
        if (h == 0) 1 else h
    }

    companion object {
        private val NO_RGB = IntArray(0)

        fun empty(cx: Int, cz: Int, rgb: Boolean = true) =
            ChunkSurface(cx, cz, ShortArray(256), if (rgb) IntArray(256) else NO_RGB, ByteArray(256), ByteArray(256), if (rgb) IntArray(256) else NO_RGB)
    }
}
