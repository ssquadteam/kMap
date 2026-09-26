package com.github.ssquadteam.kmap.pack

import com.github.ssquadteam.kmap.config.KMapConfig
import com.github.ssquadteam.kmap.render.Tint
import com.github.ssquadteam.kmap.screen.ScreenSession
import com.github.ssquadteam.kmap.terrain.TileData
import java.util.Locale

object ShaderDefines {
    const val CANVAS_W = 640
    const val CANVAS_H = 360
    const val MINI_MARGIN = 6
    const val MINI_PLATE = 12
    const val ARROW_PX = 9
    const val CURSOR_W = 10
    const val CURSOR_H = 14
    const val WICON_PX = 13
    const val WCELL_W = 7
    const val WCELL_H = 11
    const val SHADER_ONLY_RGB = 0xFFD54D
    val MINI_ZOOMS = doubleArrayOf(0.5, 0.63, 0.79, 1.0, 1.26, 1.59, 2.0)
    val SCREEN_ZOOMS = doubleArrayOf(0.125, 0.1875, 0.25, 0.375, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0)
    val CLIPS = arrayOf(doubleArrayOf(0.0, 0.0, 640.0, 360.0), doubleArrayOf(0.0, 0.0, 640.0, 360.0), doubleArrayOf(0.0, 0.0, 640.0, 360.0))

    private fun f(v: Double) = String.format(Locale.ROOT, "%.5f", v)

    fun vertex(cfg: KMapConfig, clips: List<DoubleArray>): String {
        val sb = StringBuilder()
        sb.append("#define KM_CANVAS_W ${f(CANVAS_W.toDouble())}\n")
        sb.append("#define KM_CANVAS_H ${f(CANVAS_H.toDouble())}\n")
        sb.append("#define KM_MINI_SIZE ${f(cfg.minimapSize.toDouble())}\n")
        sb.append("#define KM_MINI_MARGIN ${f(MINI_MARGIN.toDouble())}\n")
        sb.append("#define KM_MINI_PLATE ${f(MINI_PLATE.toDouble())}\n")
        sb.append("#define KM_BIG_SIZE ${f(cfg.bigMapSize.toDouble())}\n")
        sb.append("#define KM_MINI_BLOCKS ${f(cfg.minimapBlocks.toDouble())}\n")
        sb.append("#define KM_BIG_BLOCKS ${f(cfg.bigMapBlocks.toDouble())}\n")
        sb.append("#define KM_CURSOR_K ${f(cfg.cursorDegreesScale)}\n")
        sb.append("#define KM_CURSOR_PX vec2(${f(CURSOR_W.toDouble())}, ${f(CURSOR_H.toDouble())})\n")
        sb.append("#define KM_CURSOR_HOT_X 0.0\n#define KM_CURSOR_HOT_Y 0.0\n")
        sb.append("#define KM_ARROW_PX ${f(ARROW_PX.toDouble())}\n")
        sb.append("#define KM_WICON_PX ${f(WICON_PX.toDouble())}\n")
        sb.append("#define KM_WCELL_W ${f(WCELL_W.toDouble())}\n")
        sb.append("#define KM_WCELL_H ${f(WCELL_H.toDouble())}\n")
        sb.append("#define KM_REVEAL_RADIUS 36.0\n")
        sb.append("#define KM_SHADER_ONLY ivec3(${SHADER_ONLY_RGB shr 16}, ${(SHADER_ONLY_RGB shr 8) and 255}, ${SHADER_ONLY_RGB and 255})\n")
        sb.append("#define KM_ANIM_TICKS ${f(ScreenSession.ANIM_TICKS.toDouble())}\n")
        sb.append("#define KM_EDGE ${f(ScreenSession.EDGE)}\n")
        sb.append("const float KM_ZOOMS[7] = float[](${MINI_ZOOMS.joinToString(",") { f(it) }});\n")
        sb.append("const float KM_SCREEN_ZOOMS[12] = float[](${SCREEN_ZOOMS.joinToString(",") { f(it) }});\n")
        val c = (0 until 3).map { clips.getOrNull(it) ?: CLIPS[it] }
        sb.append("const vec4 KM_CLIPS[3] = vec4[](${c.joinToString(",") { "vec4(${f(it[0])},${f(it[1])},${f(it[2])},${f(it[3])})" }});\n")
        sb.append(Tint.glslArray()).append('\n')
        return sb.toString()
    }

    fun fragment(): String = "#define KM_META_W ${TileData.META_WIDTH}\n#define KM_CANVAS_W ${f(CANVAS_W.toDouble())}\n#define KM_CANVAS_H ${f(CANVAS_H.toDouble())}\n"
}
