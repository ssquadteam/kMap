package com.github.ssquadteam.kmap.screen

import com.github.ssquadteam.kmap.render.Canvas
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.FontInfo
import com.github.ssquadteam.kmap.render.Fx
import com.github.ssquadteam.kmap.render.Glyphs
import com.github.ssquadteam.kmap.render.Tint

class Hit(val x: Double, val y: Double, val w: Double, val h: Double, val id: String, val action: (() -> Unit)?, val scroll: ((Int) -> Unit)? = null, val drag: ((Double, Double) -> Unit)? = null) {
    fun contains(px: Double, py: Double) = px >= x && py >= y && px < x + w && py < y + h
}

class Ui(val glyphs: Glyphs, val hovered: String?) {
    val canvas = Canvas(glyphs)
    val hits = ArrayList<Hit>()
    private var edge = false

    fun code(y: Int, tint: Tint = Tint.NONE, layer: Int = 1, fx: Fx = Fx.NONE) = Codes.glyph(y, tint, layer, fx, edge)

    fun edge(block: () -> Unit) {
        edge = true
        block()
        edge = false
    }

    fun glyph(name: String, x: Double, y: Int, tint: Tint = Tint.NONE, layer: Int = 1, fx: Fx = Fx.NONE) {
        canvas.glyph(name, x, y, code(y, tint, layer, fx))
    }

    fun hit(x: Double, y: Double, w: Double, h: Double, id: String, action: (() -> Unit)? = null, scroll: ((Int) -> Unit)? = null, drag: ((Double, Double) -> Unit)? = null) {
        hits.add(Hit(x, y, w, h, id, action, scroll, drag))
    }

    fun isHovered(id: String) = hovered == id

    fun fit(font: FontInfo, text: String, max: Double): String {
        if (font.width(text) <= max) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t...") > max) t = t.dropLast(1)
        return "$t..."
    }

    fun text(font: FontInfo, x: Double, y: Int, text: String, tint: Tint, layer: Int = 2, fx: Fx = Fx.NONE, max: Double = 10000.0): Double {
        val t = fit(font, text, max)
        return canvas.text(font, x, y, t, code(y, tint, layer, fx))
    }

    fun centered(font: FontInfo, cx: Double, y: Int, text: String, tint: Tint, layer: Int = 2, fx: Fx = Fx.NONE, max: Double = 10000.0) {
        val t = fit(font, text, max)
        val w = font.width(t)
        canvas.text(font, Math.round(cx - w / 2.0).toDouble(), y, t, code(y, tint, layer, fx))
    }

    fun threeSlice(prefix: String, x: Double, y: Int, width: Int, tint: Tint = Tint.NONE, layer: Int = 1, fx: Fx = Fx.NONE) {
        val l = glyphs[prefix + "_l"]
        val m = glyphs[prefix + "_m"]
        val r = glyphs[prefix + "_r"]
        glyph(l.name, x, y, tint, layer, fx)
        val mid = (width - l.width - r.width).coerceAtLeast(0)
        val c = code(y, tint, layer, fx)
        for (i in 0 until mid) canvas.glyph(m, x + l.width + i, y, c)
        glyph(r.name, x + l.width + mid, y, tint, layer, fx)
    }

    fun ribbon(x: Double, y: Int, label: String, layer: Int = 2) {
        val font = glyphs.small
        val w = font.width(label).toInt() + 12
        threeSlice("ribbon", x, y, w, Tint.NONE, layer)
        text(font, x + 6, y + 1, label, Tint.CREAM, layer + 1)
    }

    fun tag(x: Double, y: Int, label: String, layer: Int = 2): Int {
        val font = glyphs.small
        val w = font.width(label).toInt() + 14
        threeSlice("tag", x, y, w, Tint.NONE, layer)
        text(font, x + 8, y + 2, label, Tint.CREAM, layer + 1)
        return w
    }

    fun chip(x: Double, y: Int, label: String, layer: Int = 2): Int {
        val font = glyphs.small
        val w = font.width(label).toInt() + 6
        threeSlice("chip", x, y, w, Tint.NONE, layer)
        text(font, x + 3, y + 1, label, Tint.CREAM, layer + 1)
        return w
    }

    fun keycap(cx: Double, y: Int, label: String, layer: Int = 2) {
        val font = glyphs.small
        val w = font.width(label).toInt() + 8
        val x = Math.round(cx - w / 2.0).toDouble()
        threeSlice("key", x, y, w, Tint.NONE, layer)
        text(font, x + 4, y + 2, label, Tint.INK, layer + 1)
    }

    fun cbutton(style: String, width: Int, x: Double, y: Int, label: String, id: String, action: () -> Unit) {
        val name = "cbtn_${style}_$width"
        glyph(name, x, y, Tint.NONE, 2, Fx.HOVER)
        centered(glyphs.small, x + width / 2.0, y + 4, label, if (style == "red") Tint.CREAM else Tint.INK, 3, max = width - 6.0)
        hit(x, y.toDouble(), width.toDouble(), 15.0, id, action)
    }

    fun pill(x: Double, y: Int, label: String, tint: Tint, layer: Int = 2): Double {
        val font = glyphs.small
        val w = font.width(label).toInt() + 5
        threeSlice("pill", x, y, w, tint, layer)
        text(font, x + 2, y + 1, label, Tint.CREAM, layer + 1)
        return w.toDouble()
    }

    fun button(glyph: String, x: Double, y: Int, label: String, id: String, labelTint: Tint = Tint.INK, action: () -> Unit) {
        val g = glyphs[glyph]
        glyph(glyph, x, y, Tint.NONE, 2, Fx.HOVER)
        centered(glyphs.small, x + g.width / 2.0, y + (g.height - 5) / 2 - 1, label, labelTint, 3)
        hit(x, y.toDouble(), g.width.toDouble(), g.height.toDouble(), id, action)
    }

    fun iconButton(frame: String, icon: String, x: Double, y: Int, id: String, layer: Int = 2, action: () -> Unit) {
        val f = glyphs[frame]
        val i = glyphs[icon]
        glyph(frame, x, y, Tint.NONE, layer, Fx.HOVER)
        glyph(icon, x + (f.width - i.width) / 2, y + (f.height - i.height) / 2, Tint.NONE, layer + 1)
        hit(x, y.toDouble(), f.width.toDouble(), f.height.toDouble(), id, action)
    }
}
