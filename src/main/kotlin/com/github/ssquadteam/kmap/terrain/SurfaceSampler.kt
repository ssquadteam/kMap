package com.github.ssquadteam.kmap.terrain

import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor

class SampleOptions(
    val ceiling: Int?,
    val skipDecoration: Boolean,
    val skipBlocks: Set<Block>,
    val biomeTint: Boolean,
    val sliceTop: Int? = null,
    val sliceBottom: Int? = null,
    val rgb: Boolean = true,
)

class SurfaceSampler(private val colors: BlockColors) {
    private val decorations: Set<Block> = setOf(
        Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.DEAD_BUSH, Blocks.SHORT_DRY_GRASS, Blocks.TALL_DRY_GRASS,
        Blocks.BUSH, Blocks.FIREFLY_BUSH, Blocks.LEAF_LITTER, Blocks.PINK_PETALS, Blocks.WILDFLOWERS, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS,
    )

    private fun skippable(state: BlockState, opt: SampleOptions): Boolean {
        val b = state.block
        if (b in opt.skipBlocks) return true
        if (!opt.skipDecoration) return false
        return b in decorations || state.`is`(BlockTags.SMALL_FLOWERS)
    }

    fun sample(chunk: LevelChunk, opt: SampleOptions): ChunkSurface {
        val cx = chunk.pos.x
        val cz = chunk.pos.z
        val out = ChunkSurface.empty(cx, cz, opt.rgb)
        val level = chunk.level
        val minY = chunk.minY
        val pos = BlockPos.MutableBlockPos()
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val i = lz * 16 + lx
                val wx = (cx shl 4) + lx
                val wz = (cz shl 4) + lz
                var y: Int
                if (opt.sliceTop != null) {
                    y = findSliceFloor(chunk, lx, lz, opt.sliceTop, opt.sliceBottom ?: minY, pos)
                    if (y == Int.MIN_VALUE) {
                        out.heights[i] = Short.MIN_VALUE
                        out.mapColor[i] = 0
                        continue
                    }
                } else if (opt.ceiling != null) {
                    y = findUnderCeiling(chunk, lx, lz, opt.ceiling, minY, pos)
                } else {
                    y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz)
                }
                if (y < minY) {
                    out.heights[i] = Short.MIN_VALUE
                    continue
                }
                var state = chunk.getBlockState(pos.set(wx, y, wz))
                var guard = 0
                while (y > minY && guard++ < 24 && (state.isAir || skippable(state, opt))) {
                    y--
                    state = chunk.getBlockState(pos.set(wx, y, wz))
                }
                var depth = 0
                var waterRgb = 0
                if (state.fluidState.`is`(FluidTags.WATER) && (state.block == Blocks.WATER || state.block == Blocks.BUBBLE_COLUMN || state.block == Blocks.KELP || state.block == Blocks.KELP_PLANT || state.block == Blocks.SEAGRASS || state.block == Blocks.TALL_SEAGRASS)) {
                    waterRgb = if (opt.biomeTint) chunk.getNoiseBiome(lx shr 2, y shr 2, lz shr 2).value().waterColor and 0xFFFFFF else DEFAULT_WATER
                    while (y > minY && depth < 32) {
                        val s = chunk.getBlockState(pos.set(wx, y, wz))
                        if (!s.fluidState.`is`(FluidTags.WATER) || !(s.block == Blocks.WATER || s.block == Blocks.BUBBLE_COLUMN || s.block == Blocks.KELP || s.block == Blocks.KELP_PLANT || s.block == Blocks.SEAGRASS || s.block == Blocks.TALL_SEAGRASS)) break
                        depth++
                        y--
                    }
                    state = chunk.getBlockState(pos.set(wx, y, wz))
                }
                out.heights[i] = (y + depth).toShort()
                out.water[i] = depth.coerceAtMost(127).toByte()
                val entry = colors.of(state.block)
                if (opt.rgb) {
                    out.waterRgb[i] = waterRgb
                    out.rgb[i] = color(chunk, state, entry, lx, y, lz, wx, wz, opt, true)
                }
                val mc = when {
                    depth > 0 -> if (opt.biomeTint) MapPalette.nearestBase(mapBoost(waterRgb)) else WATER_BASE
                    state.block == Blocks.LAVA -> MapColor.COLOR_ORANGE.id
                    entry != null && opt.biomeTint && entry.tint in TINTED -> MapPalette.nearestBase(mapBoost(color(chunk, state, entry, lx, y, lz, wx, wz, opt, false)))
                    else -> state.getMapColor(level, pos.set(wx, y, wz)).id
                }
                out.mapColor[i] = mc.toByte()
            }
        }
        return out
    }

    private fun findUnderCeiling(chunk: LevelChunk, lx: Int, lz: Int, ceiling: Int, minY: Int, pos: BlockPos.MutableBlockPos): Int {
        val wx = (chunk.pos.x shl 4) + lx
        val wz = (chunk.pos.z shl 4) + lz
        var y = ceiling
        while (y > minY && !chunk.getBlockState(pos.set(wx, y, wz)).isAir) y--
        while (y > minY && chunk.getBlockState(pos.set(wx, y, wz)).isAir) y--
        return y
    }

    private fun findSliceFloor(chunk: LevelChunk, lx: Int, lz: Int, top: Int, bottom: Int, pos: BlockPos.MutableBlockPos): Int {
        val wx = (chunk.pos.x shl 4) + lx
        val wz = (chunk.pos.z shl 4) + lz
        var y = top
        var sawAir = false
        while (y >= bottom) {
            val s = chunk.getBlockState(pos.set(wx, y, wz))
            val open = s.isAir || !s.fluidState.isEmpty && s.block == Blocks.WATER || !s.blocksMotion()
            if (open) {
                sawAir = true
            } else if (sawAir) {
                return y
            }
            y--
        }
        return Int.MIN_VALUE
    }

    private fun tone(entry: BlockColor, wx: Int, wz: Int): Int {
        val h = (wx * 73856093) xor (wz * 19349663)
        return when ((h ushr 13) and 7) {
            0, 1 -> mix(entry.rgb, entry.dark, 0.7)
            2 -> mix(entry.rgb, entry.light, 0.7)
            else -> entry.rgb
        }
    }

    private fun color(chunk: LevelChunk, state: BlockState, entry: BlockColor?, lx: Int, y: Int, lz: Int, wx: Int, wz: Int, opt: SampleOptions, textured: Boolean): Int {
        if (entry == null) {
            return state.getMapColor(chunk.level, BlockPos(wx, y, wz)).col
        }
        val base = if (textured) tone(entry, wx, wz) else entry.rgb
        if (entry.tint == '-') return base
        val biome = chunk.getNoiseBiome(lx shr 2, y shr 2, lz shr 2).value()
        val tint = when (entry.tint) {
            'G' -> if (opt.biomeTint) biome.getGrassColor(wx.toDouble(), wz.toDouble()) else 0x91BD59
            'F' -> if (opt.biomeTint) biome.foliageColor else 0x77AB2F
            'W' -> return biome.waterColor and 0xFFFFFF
            'S' -> 0x619961
            'B' -> 0x80A755
            'L' -> 0x208030
            else -> 0xFFFFFF
        }
        return multiply(base, tint)
    }

    companion object {
        private const val DEFAULT_WATER = 0x3F76E4
        private val WATER_BASE = MapColor.WATER.id
        private val TINTED = charArrayOf('G', 'F', 'S', 'B', 'L')

        fun mapBoost(c: Int): Int {
            val r = c shr 16 and 255
            val g = c shr 8 and 255
            val b = c and 255
            val l = (r + g + b) / 3.0
            fun ch(v: Int) = ((l + (v - l) * 1.15) * 1.3).toInt().coerceIn(0, 255)
            return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }

        fun mix(a: Int, b: Int, t: Double): Int {
            val r = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * t).toInt()
            val g = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * t).toInt()
            val bl = ((a and 255) + ((b and 255) - (a and 255)) * t).toInt()
            return (r shl 16) or (g shl 8) or bl
        }

        fun multiply(a: Int, b: Int): Int {
            val r = (a shr 16 and 255) * (b shr 16 and 255) / 255
            val g = (a shr 8 and 255) * (b shr 8 and 255) / 255
            val bl = (a and 255) * (b and 255) / 255
            return (r shl 16) or (g shl 8) or bl
        }
    }
}
