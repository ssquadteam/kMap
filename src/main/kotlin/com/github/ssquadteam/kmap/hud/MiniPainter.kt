package com.github.ssquadteam.kmap.hud

import com.github.ssquadteam.kmap.config.MinimapShape
import com.github.ssquadteam.kmap.render.Canvas
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.Fx
import com.github.ssquadteam.kmap.render.Glyphs
import com.github.ssquadteam.kmap.render.Tint
import com.github.ssquadteam.kmap.world.BakedMap
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component

object MiniPainter {
    fun frame(glyphs: Glyphs, shape: MinimapShape, size: Int, bakes: List<BakedMap>): Component {
        val c = Canvas(glyphs)
        c.glyph("mini_bg_square", 0.0, 0, Codes.glyph(0, Tint.NONE, 0, Fx.MAP_CLIP))
        for (b in bakes) c.sprite(Key.key("minecraft", "map_decorations"), Key.key("kmap", b.sprite), 0.0, Codes.terrainMini())
        val frame = if (shape == MinimapShape.CIRCLE) "mini_circle" else "mini_square"
        c.glyph(frame, -3.0, -3, Codes.glyph(-3, Tint.NONE, 3))
        c.glyph("arrow", size / 2.0 - 4.5, size / 2 - 4, Codes.arrow(size / 2 - 4))
        return c.build()
    }

    fun plate(glyphs: Glyphs, size: Int, x: Int, y: Int, z: Int, visible: Boolean): Component {
        if (!visible) return Component.empty()
        val c = Canvas(glyphs)
        val text = "X: $x  Y: $y  Z: $z"
        val font = glyphs.small
        val w = font.width(text.uppercase())
        val plateW = w.toInt() + 6
        val left = ((size - plateW) / 2.0).coerceAtLeast(-3.0)
        val top = size + 4
        c.glyph("plate_l", left, top, Codes.glyph(top, Tint.NONE, 1))
        val mid = glyphs["plate_m"]
        val midCount = (plateW - 6).coerceAtLeast(0)
        c.repeat("plate_m", left + 3, top, midCount, Codes.glyph(top, Tint.NONE, 1))
        c.glyph("plate_r", left + 3 + midCount * mid.width, top, Codes.glyph(top, Tint.NONE, 1))
        c.text(font, left + 3, top + 2, text.uppercase(), Codes.glyph(top + 2, Tint.CREAM, 2))
        return c.build()
    }
}
