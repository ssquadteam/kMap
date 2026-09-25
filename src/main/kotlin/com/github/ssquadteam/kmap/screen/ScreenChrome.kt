package com.github.ssquadteam.kmap.screen

import com.github.ssquadteam.kmap.render.Fx
import com.github.ssquadteam.kmap.render.Tint

object ScreenChrome {
    fun render(s: ScreenSession, ui: Ui, plate: String) {
        val glyphs = ui.glyphs
        val font = glyphs.small
        val pw = font.width(plate.uppercase()).toInt() + 10
        val px = Math.round(ScreenSession.W / 2 - pw / 2.0).toDouble()
        ui.threeSlice("plate", px, 4, pw, Tint.NONE, 1)
        ui.text(font, px + 5, 6, plate.uppercase(), Tint.CREAM, 2)
        ui.glyph("seal", ScreenSession.W - 19, 4, Tint.NONE, 2, Fx.HOVER)
        ui.hit(ScreenSession.W - 19, 4.0, 15.0, 15.0, "close", { s.map.closeScreen() })
        val lang = s.plugin.lang
        val p = s.player
        val baked = s.map.worldMap()?.bake != null
        val buttons = ArrayList<Triple<String, String, () -> Unit>>()
        val g = s.cfg.guis
        if (g.search && baked) buttons += Triple("magnifier", lang.get(p, "bar.search")) { s.setPanel(SearchPanel()) }
        if (g.newWaypoint) buttons += Triple("compass", lang.get(p, "bar.new_waypoint")) {
            val l = s.player.location
            s.setPanel(WaypointEditPanel(null, Triple(l.blockX, l.blockY, l.blockZ)))
        }
        if (g.settings) buttons += Triple("gear", lang.get(p, "bar.settings")) { s.setPanel(SettingsPanel()) }
        if (g.faq) buttons += Triple("scroll", lang.get(p, "bar.faq")) { s.setPanel(FaqPanel()) }
        if (g.waypoints) buttons += Triple("book", lang.get(p, "bar.waypoints")) { s.setPanel(WaypointListPanel()) }
        if (g.zoomButtons) {
            buttons += Triple("zoom_in", lang.get(p, "bar.zoom_in")) { s.zoomBy(1) }
            buttons += Triple("zoom_out", lang.get(p, "bar.zoom_out")) { s.zoomBy(-1) }
        }
        if (g.resetView) buttons += Triple("reset", lang.get(p, "bar.reset")) { s.resetView() }
        val top = (ScreenSession.H / 2 - buttons.size * 22 / 2.0).toInt()
        for ((i, b) in buttons.withIndex()) {
            val y = top + i * 22
            val id = "bar:${b.first}"
            ui.iconButton("bar_button", "icon_" + b.first, 4.0, y, id, 1, b.third)
            if (ui.isHovered(id)) tooltipAt(ui, b.second, 26.0, y + 4)
        }
    }

    fun tooltipAt(ui: Ui, text: String, x: Double, y: Int) {
        val font = ui.glyphs.small
        val w = font.width(text.uppercase()).toInt() + 10
        ui.threeSlice("plate", x, y, w, Tint.NONE, 3)
        ui.text(font, x + 5, y + 2, text.uppercase(), Tint.CREAM, 3)
    }

    fun tooltip(ui: Ui, text: String, cx: Double, cy: Double) {
        tooltipAt(ui, text, cx + 10, (cy + 12).toInt())
    }
}
