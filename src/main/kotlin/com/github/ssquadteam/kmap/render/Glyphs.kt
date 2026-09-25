package com.github.ssquadteam.kmap.render

import com.google.gson.JsonParser
import net.kyori.adventure.key.Key
import java.io.InputStream

class GlyphInfo(val name: String, val font: Key, val codepoint: Int, val width: Int, val height: Int, val advance: Int) {
    val char: String = String(Character.toChars(codepoint))
}

class FontInfo(val key: Key, private val advances: Map<Int, Float>, val height: Int, val line: Int) {
    fun advance(cp: Int): Float = advances[cp] ?: advances['?'.code] ?: 4f

    fun has(cp: Int): Boolean = advances.containsKey(cp)

    fun width(text: String): Float {
        var w = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            w += advance(cp)
            i += Character.charCount(cp)
        }
        return w
    }
}

class Glyphs(private val fonts: Map<String, FontInfo>, private val glyphs: Map<String, GlyphInfo>) {
    val text: FontInfo get() = font("text")
    val bold: FontInfo get() = font("bold")
    val title: FontInfo get() = font("title")
    val small: FontInfo get() = font("small")

    fun font(name: String): FontInfo = fonts[name] ?: error("font $name")

    operator fun get(name: String): GlyphInfo = glyphs[name] ?: error("glyph $name")

    fun find(name: String): GlyphInfo? = glyphs[name]

    fun names(): Set<String> = glyphs.keys

    fun withExtra(extra: Map<String, GlyphInfo>, extraFonts: Map<String, FontInfo> = emptyMap()): Glyphs = Glyphs(fonts + extraFonts, glyphs + extra)

    companion object {
        fun load(input: InputStream): Glyphs {
            val root = JsonParser.parseReader(input.reader(Charsets.UTF_8)).asJsonObject
            val fonts = HashMap<String, FontInfo>()
            for ((name, el) in root.getAsJsonObject("fonts").entrySet()) {
                val o = el.asJsonObject
                val adv = HashMap<Int, Float>()
                for ((ch, v) in o.getAsJsonObject("advances").entrySet()) {
                    adv[ch.codePointAt(0)] = v.asFloat
                }
                fonts[name] = FontInfo(Key.key("kmap", name), adv, o.get("height").asInt, o.get("line").asInt)
            }
            val glyphs = HashMap<String, GlyphInfo>()
            for ((name, el) in root.getAsJsonObject("glyphs").entrySet()) {
                val o = el.asJsonObject
                glyphs[name] = GlyphInfo(name, Key.key("kmap", o.get("font").asString), o.get("cp").asInt, o.get("w").asInt, o.get("h").asInt, o.get("adv").asInt)
            }
            return Glyphs(fonts, glyphs)
        }
    }
}
