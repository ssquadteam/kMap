package com.github.ssquadteam.kmap.render

enum class Tint(val rgb: Int) {
    NONE(0xFFFFFF),
    CREAM(0xF6E4C4),
    INK(0x4A2A1A),
    DARK(0x22120A),
    YELLOW(0xF0C83C),
    CRIMSON(0xD84838),
    LIME(0x96C446),
    SKY(0x78C8DC),
    PINK(0xDC386E),
    ORANGE(0xE6823A),
    ICE(0xAAC8E6),
    BLACK(0x28282C),
    GOLD(0xE8B84A),
    RED(0xC8423A),
    WHITE(0xFFFFFE),
    INK_SOFT(0x7A5034),
    MUTED(0xA07E60),
    GREEN(0x5FA84A),
    BLUE(0x4A7AD8),
    PURPLE(0x9A5AD0),
    TEAL(0x3AA89A),
    BROWN(0x8A5A34),
    GREY(0x9A9A9A),
    LIGHT_GREY(0xD0D0D0),
    SELECTION(0x8CE070),
    VOID(0x141418),
    FOG(0x1E2024),
    HINT(0x9C7A58),
    WARN(0xE05A4A),
    OFF(0x7A6A5A),
    BG_DARK(0x0C0C0E),
    CLAIM(0x64C864),
    ;

    companion object {
        private val values = entries

        fun nearest(rgb: Int): Tint {
            if (rgb == 0xFFFFFF) return NONE
            var best = CREAM
            var bestD = Int.MAX_VALUE
            val r = rgb shr 16 and 255
            val g = rgb shr 8 and 255
            val b = rgb and 255
            for (t in values) {
                if (t == NONE) continue
                val dr = (t.rgb shr 16 and 255) - r
                val dg = (t.rgb shr 8 and 255) - g
                val db = (t.rgb and 255) - b
                val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (d < bestD) {
                    bestD = d
                    best = t
                }
            }
            return best
        }

        fun glslArray(): String {
            val sb = StringBuilder("const vec3 KM_TINTS[64] = vec3[](")
            for (i in 0 until 64) {
                val rgb = values.getOrNull(i)?.rgb ?: 0xFFFFFF
                if (i > 0) sb.append(',')
                sb.append("vec3(%.4f,%.4f,%.4f)".format(java.util.Locale.ROOT, (rgb shr 16 and 255) / 255f, (rgb shr 8 and 255) / 255f, (rgb and 255) / 255f))
            }
            return sb.append(");").toString()
        }
    }
}
