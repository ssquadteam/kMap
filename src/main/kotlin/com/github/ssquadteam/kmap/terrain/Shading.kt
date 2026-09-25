package com.github.ssquadteam.kmap.terrain

import kotlin.math.exp
import kotlin.math.max

object Shading {
    private const val CONTOUR = 8
    private const val FOAM = 0xEEF4F0

    fun land(base: Int, h: Int, north: Int, west: Int, northWest: Int, farNorthWest: Int, brightness: Double): Int {
        val slope = (h - north) * 0.6 + (h - west) * 0.4
        var f = 1.0 + (slope * 0.11).coerceIn(-0.32, 0.24)
        val occluder = max(northWest - h, farNorthWest - h - 1)
        if (occluder >= 3) f *= 0.72 else if (occluder >= 1) f *= 0.86
        val band = Math.floorDiv(h, CONTOUR)
        if ((band != Math.floorDiv(north, CONTOUR) || band != Math.floorDiv(west, CONTOUR)) && occluder < 1) f *= 0.9
        return shade(vivid(base), f * brightness)
    }

    fun vivid(c: Int): Int {
        val r = c shr 16 and 255
        val g = c shr 8 and 255
        val b = c and 255
        val l = (r * 3 + g * 6 + b) / 10.0
        fun ch(v: Int) = ((l + (v - l) * 1.22) * 1.07).toInt().coerceIn(0, 255)
        return (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    fun water(floor: Int, water: Int, depth: Int, shore: Boolean, brightness: Double): Int {
        val t = 1.0 - exp(-depth / 5.0)
        val shallow = mix(tint(water, 0.82, 1.16, 1.02), floor, 0.35)
        val deep = shade(tint(water, 0.7, 0.85, 1.05), 0.62)
        var c = mix(shallow, deep, t)
        if (shore && depth <= 1) c = mix(c, FOAM, 0.3)
        return shade(c, brightness)
    }

    fun tint(c: Int, r: Double, g: Double, b: Double): Int =
        (((c shr 16 and 255) * r).toInt().coerceIn(0, 255) shl 16) or
            (((c shr 8 and 255) * g).toInt().coerceIn(0, 255) shl 8) or
            ((c and 255) * b).toInt().coerceIn(0, 255)

    fun shade(c: Int, f: Double): Int = tint(c, f, f, f)

    fun mix(a: Int, b: Int, t: Double): Int {
        val r = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * t).toInt()
        val g = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * t).toInt()
        val bl = ((a and 255) + ((b and 255) - (a and 255)) * t).toInt()
        return (r shl 16) or (g shl 8) or bl
    }
}
