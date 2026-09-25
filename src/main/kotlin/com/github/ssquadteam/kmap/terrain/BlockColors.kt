package com.github.ssquadteam.kmap.terrain

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.Block
import java.io.InputStream
import java.util.IdentityHashMap

class BlockColor(val rgb: Int, val tint: Char)

class BlockColors(input: InputStream) {
    private val byName = HashMap<String, BlockColor>()
    private val cache = IdentityHashMap<Block, BlockColor?>()

    init {
        input.bufferedReader().forEachLine { line ->
            val p = line.trim().split(' ')
            if (p.size == 5) byName[p[0]] = BlockColor((p[1].toInt() shl 16) or (p[2].toInt() shl 8) or p[3].toInt(), p[4][0])
        }
    }

    @Synchronized
    fun of(block: Block): BlockColor? = cache.getOrPut(block) { byName[BuiltInRegistries.BLOCK.getKey(block).path] }
}
