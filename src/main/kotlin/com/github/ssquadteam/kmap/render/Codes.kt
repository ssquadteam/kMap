package com.github.ssquadteam.kmap.render

enum class Surface(val id: Int) {
    MINI(0),
    BIG(1),
    SCREEN_MAP(2),
    SCREEN_UI(3),
    CURSOR(4),
    HUD(5),
}

enum class Fx(val id: Int) {
    NONE(0),
    DRAG(1),
    HOVER(2),
    HOVER_ONLY(3),
    MAP_CLIP(4),
    CLIP_A(5),
    CLIP_B(6),
    CLIP_C(7),
}

object Codes {
    fun glyph(y: Int, tint: Tint = Tint.NONE, layer: Int = 1, fx: Fx = Fx.NONE): Int {
        val yy = (y + 128).coerceIn(0, 511)
        return (0 shl 20) or yy or (tint.ordinal shl 9) or ((layer and 3) shl 15) or (fx.id shl 17)
    }

    fun terrainMini(): Int = 1 shl 20

    fun terrainScreen(panZ: Int, zoom: Int, drag: Boolean): Int =
        (1 shl 20) or (panZ.coerceIn(0, 16383)) or ((zoom and 15) shl 14) or ((if (drag) 1 else 0) shl 18)

    fun arrow(y: Int, angle256: Int = 0, drag: Boolean = false): Int =
        (2 shl 20) or (y + 128).coerceIn(0, 511) or ((angle256 and 255) shl 9) or ((if (drag) 1 else 0) shl 17)

    fun fill(tint: Tint): Int = (3 shl 20) or (tint.ordinal and 63)

    fun cursor(): Int = 4 shl 20

    fun miniIcon(miniParam: Int, size: Int, clampEdge: Boolean, layer: Int, onBig: Boolean, tint: Tint = Tint.NONE): Int =
        (8 shl 20) or (miniParam and 63) or (((size - 1).coerceIn(0, 63)) shl 6) or ((if (clampEdge) 1 else 0) shl 12) or
            ((layer and 3) shl 13) or ((if (onBig) 1 else 0) shl 15) or ((tint.ordinal and 15) shl 16)

    fun billboard(dx: Int, row: Int, reveal: Boolean, tint: Tint, variant: Int): Int =
        (9 shl 20) or ((dx + 128).coerceIn(0, 255)) or ((row and 3) shl 8) or ((if (reveal) 1 else 0) shl 10) or
            ((tint.ordinal and 63) shl 11) or ((variant and 7) shl 17)

    fun miniParam(corner: Int, circle: Boolean, zoom: Int, coords: Boolean = true): Int =
        (corner and 3) or ((if (circle) 1 else 0) shl 2) or ((zoom and 7) shl 3) or ((if (coords) 1 else 0) shl 6)

    fun sensParam(sens: Double): Int = (Math.round((sens - 1.0) * 10.0).toInt()).coerceIn(0, 31)
}
