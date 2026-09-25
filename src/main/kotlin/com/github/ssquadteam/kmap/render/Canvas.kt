package com.github.ssquadteam.kmap.render

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

class Canvas(val glyphs: Glyphs) {
    private open class Run(val font: Key, val color: Int) {
        val text = StringBuilder()
    }

    private class SpriteRun(val atlas: Key, val sprite: Key, color: Int) : Run(UI_FONT, color)

    private val runs = ArrayList<Run>()
    private var pen = 0.0

    val isEmpty: Boolean get() = runs.isEmpty()

    private fun run(font: Key, color: Int): Run {
        val last = runs.lastOrNull()
        if (last != null && last !is SpriteRun && last.font == font && last.color == color) return last
        val r = Run(font, color)
        runs.add(r)
        return r
    }

    private fun spaceRun(): Run = runs.lastOrNull() ?: run(UI_FONT, 0).also { }

    fun moveTo(x: Double) {
        val target = Math.round(x).toDouble()
        var delta = target - pen
        if (kotlin.math.abs(delta) < 0.5) {
            pen = target
            return
        }
        val sb = spaceRun().text
        val neg = delta < 0
        delta = kotlin.math.abs(delta)
        var whole = Math.round(delta).toInt()
        while (whole >= 8192) {
            sb.append((if (neg) 0xE120 + 13 else 0xE100 + 13).toChar())
            whole -= 8192
        }
        var bit = 0
        while (whole > 0) {
            if (whole and 1 == 1) sb.append((if (neg) 0xE120 + bit else 0xE100 + bit).toChar())
            whole = whole shr 1
            bit++
        }
        pen = target
    }

    fun glyph(g: GlyphInfo, x: Double, y: Int, code: Int = Codes.glyph(y)): Canvas {
        moveTo(x)
        run(g.font, code).text.append(g.char)
        pen += g.advance
        return this
    }

    fun glyph(name: String, x: Double, y: Int, code: Int = Codes.glyph(y)): Canvas = glyph(glyphs[name], x, y, code)

    fun sprite(atlas: Key, sprite: Key, x: Double, code: Int): Canvas {
        moveTo(x)
        runs.add(SpriteRun(atlas, sprite, code))
        pen += 8.0
        return this
    }

    fun repeat(name: String, x: Double, y: Int, count: Int, code: Int = Codes.glyph(y)): Canvas {
        if (count <= 0) return this
        val g = glyphs[name]
        for (i in 0 until count) glyph(g, x + i * g.width, y, code)
        return this
    }

    fun text(font: FontInfo, x: Double, y: Int, text: String, code: Int = Codes.glyph(y)): Double {
        moveTo(x)
        val r = run(font.key, code)
        var i = 0
        var w = 0.0
        while (i < text.length) {
            var cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (!font.has(cp)) {
                val up = Character.toUpperCase(cp)
                cp = when {
                    font.has(up) -> up
                    font.has(Character.toLowerCase(cp)) -> Character.toLowerCase(cp)
                    else -> '?'.code
                }
            }
            r.text.appendCodePoint(cp)
            w += font.advance(cp)
        }
        pen += w
        return w
    }

    fun append(other: Canvas, dx: Double = 0.0) {
        for (r in other.runs) {
            run(r.font, r.color).text.append(r.text)
        }
        pen += other.pen
    }

    fun build(): Component {
        moveTo(0.0)
        val root = Component.text()
        for (r in runs) {
            if (r is SpriteRun) {
                root.append(
                    Component.`object`(net.kyori.adventure.text.`object`.ObjectContents.sprite(r.atlas, r.sprite))
                        .color(TextColor.color(r.color))
                        .decoration(TextDecoration.ITALIC, false),
                )
                if (r.text.isNotEmpty()) root.append(Component.text(r.text.toString()).font(UI_FONT))
                continue
            }
            if (r.text.isEmpty()) continue
            root.append(
                Component.text(r.text.toString())
                    .font(r.font)
                    .color(TextColor.color(r.color))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, false),
            )
        }
        return root.build()
    }

    companion object {
        val UI_FONT: Key = Key.key("kmap", "ui")
        val EMPTY: TextComponent = Component.text("")
    }
}
