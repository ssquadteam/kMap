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

    companion object {
        fun empty(cx: Int, cz: Int) = ChunkSurface(cx, cz, ShortArray(256), IntArray(256), ByteArray(256), ByteArray(256), IntArray(256))
    }
}
