package com.github.ssquadteam.kmap.hud

import com.github.ssquadteam.kmap.render.Canvas
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.Glyphs
import com.github.ssquadteam.kmap.render.Tint
import com.github.ssquadteam.kmap.world.BakedMap
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component

object BigPainter {
    fun frame(glyphs: Glyphs, size: Int, bakes: List<BakedMap>): Component {
        val c = Canvas(glyphs)
        c.glyph("fill", 0.0, 0, Codes.fill(Tint.VOID))
        for (b in bakes) c.sprite(Key.key("minecraft", "map_decorations"), Key.key("kmap", b.sprite), 0.0, Codes.terrainMini())
        val seg = 32
        val n = (size + 12 + seg - 1) / seg
        for (i in 0 until n) {
            val x = (-6 + i * seg).coerceAtMost(size + 6 - seg).toDouble()
            c.glyph("big_h", x, -6, Codes.glyph(-6, Tint.NONE, 3))
            c.glyph("big_h", x, size, Codes.glyph(size, Tint.NONE, 3))
        }
        for (i in 0 until n) {
            val y = (-6 + i * seg).coerceAtMost(size + 6 - seg)
            c.glyph("big_v", -6.0, y, Codes.glyph(y, Tint.NONE, 3))
            c.glyph("big_v", size.toDouble(), y, Codes.glyph(y, Tint.NONE, 3))
        }
        c.glyph("arrow", size / 2.0 - 4.5, size / 2 - 4, Codes.arrow(size / 2 - 4))
        return c.build()
    }
}
