package com.github.ssquadteam.kmap.pack

import com.github.ssquadteam.kmap.render.GlyphInfo
import net.kyori.adventure.key.Key
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.sqrt

class GeneratedArt {
    private val entries = ArrayList<Pair<String, BufferedImage>>()
    private var next = 0xF900
    val glyphs = HashMap<String, GlyphInfo>()
    private val font: Key = Key.key("kmap", "gen")
    private val scaled = ArrayList<Pair<String, String>>()

    fun add(name: String, image: BufferedImage, magicCode: Int? = null) {
        if (magicCode != null) {
            for ((x, y) in listOf(0 to 0, image.width - 1 to 0, 0 to image.height - 1, image.width - 1 to image.height - 1)) {
                image.setRGB(x, y, (8 shl 24) or (107 shl 16) or (77 shl 8) or magicCode)
            }
        }
        val cp = next++
        entries.add(name to image)
        var adv = 0
        loop@ for (x in image.width - 1 downTo 0) {
            for (y in 0 until image.height) {
                if (image.getRGB(x, y) ushr 24 != 0) {
                    adv = x + 1
                    break@loop
                }
            }
        }
        glyphs[name] = GlyphInfo(name, font, cp, image.width, image.height, adv + 1)
    }

    fun scaled(name: String, file: String, source: GlyphInfo, height: Int) {
        val cp = next++
        val k = height.toDouble() / source.height
        glyphs[name] = GlyphInfo(name, font, cp, Math.round(source.width * k).toInt(), height, (0.5 + (source.advance - 1) * k).toInt() + 1)
        scaled.add(name to file)
    }

    fun write(pack: PackBuilder, spaceProvider: String) {
        val providers = StringBuilder()
        providers.append(spaceProvider)
        for ((name, image) in entries) {
            val out = ByteArrayOutputStream()
            ImageIO.write(image, "png", out)
            pack.put("assets/kmap/textures/gen/$name.png", out.toByteArray())
            val g = glyphs[name]!!
            providers.append(",{\"type\":\"bitmap\",\"file\":\"kmap:gen/$name.png\",\"height\":${image.height},\"ascent\":0,\"chars\":[\"\\u%04x\"]}".format(g.codepoint))
        }
        for ((name, file) in scaled) {
            val g = glyphs[name]!!
            providers.append(",{\"type\":\"bitmap\",\"file\":\"$file\",\"height\":${g.height},\"ascent\":0,\"chars\":[\"\\u%04x\"]}".format(g.codepoint))
        }
        pack.putText("assets/kmap/font/gen.json", "{\"providers\":[$providers]}")
    }

    companion object {
        private const val OUT = 0xFF22120A.toInt()
        private const val DARK = 0xFF4A2C18.toInt()
        private const val WOOD = 0xFF7C5030.toInt()
        private const val LIGHT = 0xFFB07C4E.toInt()
        private const val GOLD = 0xFFE8B84A.toInt()
        private const val GOLD_D = 0xFFB0802A.toInt()

        fun pinOutline(icon: BufferedImage): BufferedImage {
            val img = BufferedImage(icon.width + 4, icon.height + 4, BufferedImage.TYPE_INT_ARGB)
            img.graphics.drawImage(icon, 2, 2, null)
            ring(img, 0xFFFFFAEC.toInt())
            ring(img, OUT)
            return img
        }

        private fun ring(img: BufferedImage, color: Int) {
            val w = img.width
            val h = img.height
            val solid = Array(h) { y -> BooleanArray(w) { x -> (img.getRGB(x, y) ushr 24) > 128 } }
            for (y in 0 until h) for (x in 0 until w) {
                if (img.getRGB(x, y) ushr 24 != 0) continue
                var hit = false
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until w && yy in 0 until h && solid[yy][xx]) hit = true
                }
                if (hit) img.setRGB(x, y, color)
            }
        }

        fun squareFrame(size: Int): BufferedImage {
            val s = size + 6
            val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until s) {
                for (x in 0 until s) {
                    val edge = minOf(x, y, s - 1 - x, s - 1 - y)
                    val c = when (edge) {
                        0 -> OUT
                        1 -> GOLD
                        2 -> WOOD
                        3 -> DARK
                        else -> 0
                    }
                    if (c != 0) img.setRGB(x, y, c)
                }
            }
            for ((x, y) in listOf(0 to 0, s - 1 to 0, 0 to s - 1, s - 1 to s - 1)) img.setRGB(x, y, 0)
            for (x in 1 until s - 1) img.setRGB(x, 1, 0xFFFADE84.toInt())
            return img
        }

        fun circleFrame(size: Int): BufferedImage {
            val s = size + 6
            val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
            val c = (s - 1) / 2.0
            val r = size / 2.0
            for (y in 0 until s) {
                for (x in 0 until s) {
                    val d = sqrt((x - c) * (x - c) + (y - c) * (y - c))
                    val col = when {
                        d > r + 3.0 -> 0
                        d > r + 2.2 -> OUT
                        d > r + 1.3 -> if (y < c - r * 0.5) 0xFFFADE84.toInt() else GOLD
                        d > r + 0.5 -> WOOD
                        d > r - 0.2 -> DARK
                        else -> 0
                    }
                    if (col != 0) img.setRGB(x, y, col)
                }
            }
            return img
        }

        fun disc(size: Int, argb: Int): BufferedImage {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val c = (size - 1) / 2.0
            for (y in 0 until size) for (x in 0 until size) {
                if (sqrt((x - c) * (x - c) + (y - c) * (y - c)) <= size / 2.0) img.setRGB(x, y, argb)
            }
            return img
        }

        fun rect(w: Int, h: Int, argb: Int): BufferedImage {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, argb)
            return img
        }

        fun bigFrameSegments(): List<Pair<String, BufferedImage>> {
            val h = BufferedImage(32, 6, BufferedImage.TYPE_INT_ARGB)
            val v = BufferedImage(6, 32, BufferedImage.TYPE_INT_ARGB)
            val cols = intArrayOf(OUT, GOLD, WOOD, WOOD, GOLD_D, DARK)
            for (i in 0 until 32) for (j in 0 until 6) {
                h.setRGB(i, j, cols[j])
                v.setRGB(j, i, cols[j])
            }
            return listOf("big_h" to h, "big_v" to v)
        }
    }
}
