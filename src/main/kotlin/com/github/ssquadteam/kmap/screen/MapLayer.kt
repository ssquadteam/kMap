package com.github.ssquadteam.kmap.screen

import com.github.ssquadteam.kmap.hooks.Relation
import com.github.ssquadteam.kmap.render.Canvas
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.Fx
import com.github.ssquadteam.kmap.render.Tint
import com.github.ssquadteam.kmap.waypoints.Waypoint
import net.kyori.adventure.text.Component
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player as NmsPlayer
import net.minecraft.world.phys.AABB
import org.bukkit.craftbukkit.entity.CraftPlayer
import kotlin.math.roundToInt

object MapLayer {
    private const val W = ScreenSession.W
    private const val H = ScreenSession.H

    fun render(s: ScreenSession): Component {
        val glyphs = s.glyphs
        val c = Canvas(glyphs)
        val (dragging, sx, sy) = s.dragState()
        val offX = if (dragging) -(sx - W / 2.0) else 0.0
        val offY = if (dragging) -(sy - H / 2.0) else 0.0
        val fx = if (dragging) Fx.DRAG else Fx.NONE
        c.glyph("fill", 0.0, 0, Codes.fill(Tint.BG_DARK))
        fun visible(x: Double, y: Double) = x > -64 && y > -64 && x < W + 64 && y < H + 64

        val p = s.player
        val handle = (p as CraftPlayer).handle
        val hoveredPin = s.hoveredPin()
        for (l in s.plugin.locations.visible(p, p.world.name)) {
            if (!l.pin) continue
            val px = s.canvasX(l.x) + offX
            val py = s.canvasY(l.z) + offY
            if (!visible(px, py)) continue
            val hovered = hoveredPin === l
            val name = s.plugin.pins.pinGlyph(l, glyphs)
            val g = (if (hovered) glyphs.find(name + "_big") else null) ?: glyphs[name]
            val x = (px - g.width / 2.0).roundToInt().toDouble()
            val y = (py - g.height / 2.0).roundToInt()
            c.glyph(g, x, y, Codes.glyph(y, Tint.NONE, if (hovered) 2 else 1, fx))
            if (ScreenSession.pinLabelVisible(l, hovered) || s.map.trackedPin == l.index) {
                label(c, l.name, px, y - LABEL_H - 2, if (l.nameColor != null) Tint.nearest(l.nameColor) else Tint.CREAM, fx)
            }
        }
        for (w in s.map.waypoints.inWorld(p.world.name)) {
            if (!w.visible) continue
            val px = s.canvasX(w.x + 0.5) + offX
            val py = s.canvasY(w.z + 0.5) + offY
            if (!visible(px, py)) continue
            waypoint(c, w, px, py, fx, s)
        }
        val cfg = s.cfg.entities
        val mates = s.mates()
        val mateIds = mates.mapTo(HashSet()) { it.player.entityId }
        if (cfg.enabled) {
            val r = cfg.radiusBlocks.toDouble() * 2
            val found = try {
                handle.level().getEntities(handle, AABB(handle.x - r, handle.y - 16, handle.z - r, handle.x + r, handle.y + 16, handle.z + r)) { e ->
                    e.isAlive && e.id !in mateIds && ((s.map.settings.showMobs && cfg.mobs && e is Mob) || (s.map.settings.showPlayers && cfg.players && e is NmsPlayer && !e.isSpectator && p.canSee(e.bukkitEntity)))
                }.take(cfg.maxPerPlayer * 2)
            } catch (_: Throwable) {
                emptyList()
            }
            for (e in found) {
                val px = s.canvasX(e.x) + offX
                val py = s.canvasY(e.z) + offY
                if (!visible(px, py)) continue
                val g = glyphs[if (e is NmsPlayer) "mark_player" else "mark_mob"]
                val y = (py - g.height / 2.0).roundToInt()
                c.glyph(g, (px - g.width / 2.0).roundToInt().toDouble(), y, Codes.glyph(y, Tint.NONE, 1, fx))
            }
        }
        val gc = s.cfg.guilds
        for (m in mates) {
            val l = m.player.location
            val px = s.canvasX(l.x) + offX
            val py = s.canvasY(l.z) + offY
            if (!visible(px, py)) continue
            val g = glyphs["mark_square"]
            val y = (py - g.height / 2.0).roundToInt()
            val tint = Tint.nearest(if (m.relation == Relation.ALLY) gc.allyColor else gc.memberColor, 16)
            c.glyph(g, (px - g.width / 2.0).roundToInt().toDouble(), y, Codes.glyph(y, tint, 2, fx))
            val dot = glyphs["mark_player"]
            val dy = (py - dot.height / 2.0).roundToInt()
            c.glyph(dot, (px - dot.width / 2.0).roundToInt().toDouble(), dy, Codes.glyph(dy, Tint.NONE, 3, fx))
        }
        s.ping?.let { (tx, tz) ->
            val px = s.canvasX(tx) + offX
            val py = s.canvasY(tz) + offY
            if (visible(px, py)) {
                val g = glyphs[if ((s.ticks / 5) % 2 == 0) "ping_0" else "ping_1"]
                val y = (py - g.height / 2.0).roundToInt()
                c.glyph(g, (px - g.width / 2.0).roundToInt().toDouble(), y, Codes.glyph(y, Tint.NONE, 3, fx))
            }
        }
        val ax = s.canvasX(p.location.x) + offX
        val ay = s.canvasY(p.location.z) + offY
        if (visible(ax, ay)) {
            val angle = (((s.map.savedHeading() + 180f) / 360f * 256f).roundToInt()) and 255
            val y = (ay - 4.5).roundToInt()
            c.glyph(glyphs["arrow"], (ax - 4.5).roundToInt().toDouble(), y, Codes.arrow(y, angle, dragging))
        }
        return c.build()
    }

    private const val LABEL_H = 13

    private fun label(c: Canvas, text: String, cx: Double, top: Int, tint: Tint, fx: Fx) {
        val g = c.glyphs
        val font = g.bold
        val w = font.width(text).toInt() + 7
        val x = (cx - w / 2.0).roundToInt().toDouble()
        val code = Codes.glyph(top, Tint.NONE, 3, fx)
        c.glyph(g["label_l"], x, top, code)
        val mid = (w - 6).coerceAtLeast(0)
        for (i in 0 until mid) c.glyph(g["label_m"], x + 3 + i, top, code)
        c.glyph(g["label_r"], x + 3 + mid, top, code)
        c.text(font, x + 4, top + 1, text, Codes.glyph(top + 1, tint, 3, fx))
    }

    fun waypoint(c: Canvas, w: Waypoint, px: Double, py: Double, fx: Fx, s: ScreenSession) {
        val glyphs = c.glyphs
        if (s.hoveredWaypoint == w.id) {
            val glow = glyphs["wp_glow"]
            val gy = (py - glow.height / 2.0).roundToInt()
            c.glyph(glow, (px - glow.width / 2.0).roundToInt().toDouble(), gy, Codes.glyph(gy, Tint.NONE, 1, fx))
        }
        val sq = glyphs["mark_square"]
        val x = (px - sq.width / 2.0).roundToInt().toDouble()
        val y = (py - sq.height / 2.0).roundToInt()
        val icon = w.icon?.let { s.plugin.pins.iconGlyphName(it, glyphs) }
        if (icon != null) {
            val g = glyphs[icon]
            val yy = (py - g.height / 2.0).roundToInt()
            c.glyph(g, (px - g.width / 2.0).roundToInt().toDouble(), yy, Codes.glyph(yy, Tint.NONE, 2, fx))
        } else {
            val tint = Tint.nearest(Waypoint.COLORS[w.color.coerceIn(0, 7)])
            c.glyph(sq, x, y, Codes.glyph(y, tint, 2, fx))
            val font = glyphs.small
            val letter = w.letter()
            val lw = font.width(letter)
            c.text(font, (px - lw / 2.0 + 0.5).roundToInt().toDouble(), y + 2, letter, Codes.glyph(y + 2, if (w.color == 7) Tint.CREAM else Tint.DARK, 3, fx))
        }
        if (w.tracked) {
            val ring = glyphs["swatch_ring"]
            val ry = (py - ring.height / 2.0).roundToInt()
            c.glyph(ring, (px - ring.width / 2.0).roundToInt().toDouble(), ry, Codes.glyph(ry, Tint.NONE, 1, fx))
        }
    }
}
