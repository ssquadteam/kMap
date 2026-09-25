package com.github.ssquadteam.kmap.terrain

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import java.io.InputStream
import java.util.IdentityHashMap

class BlockColor(val rgb: Int, val dark: Int, val light: Int, val tint: Char)

class BlockColors(input: InputStream) {
    private val byName = HashMap<String, BlockColor>()
    private val cache = IdentityHashMap<Block, BlockColor?>()

    init {
        input.bufferedReader().forEachLine { line ->
            val p = line.trim().split(' ')
            if (p.size != 11) return@forEachLine
            fun rgb(o: Int) = (p[o].toInt() shl 16) or (p[o + 1].toInt() shl 8) or p[o + 2].toInt()
            byName[p[0]] = BlockColor(rgb(1), rgb(4), rgb(7), p[10][0])
        }
    }

    @Synchronized
    fun of(block: Block): BlockColor? = cache.getOrPut(block) { byName[BuiltInRegistries.BLOCK.getKey(block).path] }
}
