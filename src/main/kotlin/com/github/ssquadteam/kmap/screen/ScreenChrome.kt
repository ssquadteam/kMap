package com.github.ssquadteam.kmap.screen

import com.github.ssquadteam.kmap.hooks.Relation
import com.github.ssquadteam.kmap.render.Fx
import com.github.ssquadteam.kmap.render.Tint

object ScreenChrome {
    private const val SEARCH_W = 16
    private const val ZOOM_X = ScreenSession.W - 22
    private val ZOOM_LABELS = arrayOf("1/8", "3/16", "1/4", "3/8", "1/2", "3/4", "1X", "1.5X", "2X", "3X", "4X", "6X")

    fun render(s: ScreenSession, ui: Ui, plate: String) {
        val lang = s.plugin.lang
        val p = s.player
        topBar(s, ui, plate)
        ui.edge { rails(s, ui) }
        s.hoveredWaypoint?.let { id -> s.map.waypoints.get(id) }?.let { w ->
            tooltip(ui, w.name + "  -  " + lang.get(p, if (s.canEdit(w)) "waypoint.click_edit" else "waypoint.click_track"), s.cursorX, s.cursorY)
        }
        s.hoveredMate?.let { id -> s.mates().firstOrNull { it.player.uniqueId == id } }?.let { m ->
            tooltip(ui, lang.get(p, if (m.relation == Relation.ALLY) "guilds.ally_tooltip" else "guilds.member_tooltip", m.player.name, m.guild.name), s.cursorX, s.cursorY)
        }
    }

    private fun rails(s: ScreenSession, ui: Ui) {
        val lang = s.plugin.lang
        val p = s.player
        val g = s.cfg.guis
        ui.glyph("seal", ScreenSession.W - 19, 4, Tint.NONE, 2, Fx.HOVER)
        ui.hit(ScreenSession.W - 19, 4.0, 15.0, 15.0, "close", { s.map.closeScreen() })
        val buttons = ArrayList<Triple<String, String, () -> Unit>>()
        if (g.newWaypoint) buttons += Triple("compass", lang.get(p, "bar.new_waypoint")) {
            val l = s.player.location
            s.setPanel(WaypointEditPanel(null, Triple(l.blockX, l.blockY, l.blockZ)))
        }
        if (g.waypoints) buttons += Triple("book", lang.get(p, "bar.waypoints")) { s.setPanel(WaypointListPanel()) }
        if (g.settings) buttons += Triple("gear", lang.get(p, "bar.settings")) { s.setPanel(SettingsPanel()) }
        if (g.faq) buttons += Triple("scroll", lang.get(p, "bar.faq")) { s.setPanel(FaqPanel()) }
        if (g.resetView) buttons += Triple("reset", lang.get(p, "bar.reset")) { s.resetView() }
        val top = (ScreenSession.H / 2 - buttons.size * 22 / 2.0).toInt()
        for ((i, b) in buttons.withIndex()) {
            val y = top + i * 22
            val id = "bar:${b.first}"
            ui.iconButton("bar_button", "icon_" + b.first, 4.0, y, id, 1, b.third)
            if (ui.isHovered(id)) tooltipAt(ui, b.second, 26.0, y + 4)
        }
        if (g.zoomButtons) zoomStack(s, ui)
    }

    private fun topBar(s: ScreenSession, ui: Ui, plate: String) {
        val font = ui.glyphs.small
        val text = plate.uppercase()
        val plaque = font.width(text).toInt() + 13
        val x = Math.round(ScreenSession.W / 2 - (plaque + SEARCH_W) / 2.0).toDouble()
        ui.threeSlice("topbar", x, 4, plaque, Tint.NONE, 1)
        ui.text(font, x + 7, 7, text, Tint.CREAM, 2)
        if (!s.cfg.guis.search) return
        val open = s.panel is SearchPanel
        val id = "bar:search"
        ui.glyph(if (open || ui.isHovered(id)) "topbar_search_on" else "topbar_search", x + plaque, 4, Tint.NONE, 1)
        ui.hit(x + plaque, 4.0, SEARCH_W.toDouble(), 13.0, id, { s.setPanel(if (open) null else SearchPanel()) })
        if (ui.isHovered(id) && !open) tooltipAt(ui, s.plugin.lang.get(s.player, "bar.search"), x + plaque + SEARCH_W + 3, 5)
    }

    private fun zoomStack(s: ScreenSession, ui: Ui) {
        val lang = s.plugin.lang
        val p = s.player
        val bottom = ScreenSession.H.toInt() - 8
        val minusY = bottom - 14
        val readY = minusY - 13
        val plusY = readY - 16
        ui.glyph("compass_rose", ZOOM_X - 43, plusY + 2, Tint.NONE, 1)
        ui.glyph(if (ui.isHovered("zoom:in")) "zoom_plus_on" else "zoom_plus", ZOOM_X, plusY, Tint.NONE, 1)
        ui.hit(ZOOM_X, plusY.toDouble(), 16.0, 14.0, "zoom:in", { s.zoomBy(1) })
        val label = ZOOM_LABELS[s.zoom.coerceIn(0, ZOOM_LABELS.size - 1)]
        val font = ui.glyphs.small
        val w = font.width(label).toInt() + 7
        val px = ZOOM_X + 8 - w / 2.0
        ui.threeSlice("plate", Math.round(px).toDouble(), readY, w, Tint.NONE, 1)
        ui.text(font, Math.round(px).toDouble() + 4, readY + 2, label, Tint.CREAM, 2)
        ui.glyph(if (ui.isHovered("zoom:out")) "zoom_minus_on" else "zoom_minus", ZOOM_X, minusY, Tint.NONE, 1)
        ui.hit(ZOOM_X, minusY.toDouble(), 16.0, 14.0, "zoom:out", { s.zoomBy(-1) })
        if (ui.isHovered("zoom:in")) tooltipAt(ui, lang.get(p, "bar.zoom_in"), leftOf(ui, lang.get(p, "bar.zoom_in"), ZOOM_X), plusY + 2)
        if (ui.isHovered("zoom:out")) tooltipAt(ui, lang.get(p, "bar.zoom_out"), leftOf(ui, lang.get(p, "bar.zoom_out"), ZOOM_X), minusY + 2)
    }

    private fun leftOf(ui: Ui, text: String, x: Double): Double = x - 4 - (ui.glyphs.small.width(text.uppercase()).toInt() + 9)

    fun tooltipAt(ui: Ui, text: String, x: Double, y: Int) {
        val font = ui.glyphs.small
        val w = font.width(text.uppercase()).toInt() + 9
        ui.threeSlice("plate", x, y, w, Tint.NONE, 3)
        ui.text(font, x + 5, y + 2, text.uppercase(), Tint.CREAM, 3)
    }

    fun tooltip(ui: Ui, text: String, cx: Double, cy: Double) {
        tooltipAt(ui, text, cx + 10, (cy + 12).toInt())
    }
}
