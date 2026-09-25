package com.github.ssquadteam.kmap.screen

import com.github.ssquadteam.kmap.config.Corner
import com.github.ssquadteam.kmap.config.MinimapShape
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.Fx
import com.github.ssquadteam.kmap.render.Tint
import com.github.ssquadteam.kmap.session.PlayerSettings
import com.github.ssquadteam.kmap.waypoints.Waypoint
import java.util.Locale
import java.util.UUID
import net.kyori.adventure.text.Component

object Win {
    const val W = 228
    const val EDIT_X = 206.0
    const val EDIT_Y = 82
    const val LIST_X = 206.0
    const val LIST_Y = 73
    const val SET_X = 206.0
    const val SET_Y = 72
    const val FAQ_X = 192.0
    const val FAQ_Y = 64
    val LIST_CLIP = doubleArrayOf(LIST_X + 11, LIST_Y + 35.0, 206.0, 146.0)
    const val SEARCH_X = 204.0
    const val SEARCH_Y = 21
    const val SEARCH_W = 232
    val SEARCH_CLIP = doubleArrayOf(SEARCH_X + 6, SEARCH_Y + 36.0, 220.0, 146.0)
}

fun header(s: ScreenSession, ui: Ui, bg: String, icon: String, title: String, chip: String?, x: Double, y: Int, width: Int) {
    ui.glyph(bg, x, y, Tint.NONE, 0)
    ui.glyph("icon_plate", x + 6, y + 5, Tint.NONE, 1)
    val ig = ui.glyphs["icon_$icon"]
    ui.glyph("icon_$icon", x + 6 + (18 - ig.width) / 2, y + 5 + (18 - ig.height) / 2, Tint.NONE, 2)
    ui.text(ui.glyphs.title, x + 28, y + 8, title.uppercase(), Tint.CREAM, 2, max = width - 90.0)
    if (chip != null) {
        val w = ui.glyphs.small.width(chip.uppercase()).toInt() + 6
        ui.chip(x + width - 24 - w, y + 10, chip.uppercase())
    }
    ui.glyph("seal", x + width - 21, y + 6, Tint.NONE, 2, Fx.HOVER)
    ui.hit(x + width - 21, y + 6.0, 15.0, 15.0, "panel_close", { s.setPanel(null) })
}

class WaypointEditPanel(private val editing: UUID?, private val at: Triple<Int, Int, Int>?) : Panel {
    private var name = ""
    private var x = 0
    private var y = 0
    private var z = 0
    private var color = 0
    private var icon: String? = null
    private var focus: String? = null

    override fun onOpen(s: ScreenSession) {
        val w = editing?.let { s.map.waypoints.get(it) }
        if (w != null) {
            name = w.name
            x = w.x
            y = w.y
            z = w.z
            color = w.color
            icon = w.icon
        } else if (at != null) {
            x = at.first
            y = at.second
            z = at.third
            color = s.map.waypoints.all.size % 8
        }
    }

    private fun edit(s: ScreenSession, key: String, value: String, max: Int) {
        focus = key
        s.focus(TextField(value, max, { v ->
            when (key) {
                "name" -> name = v
                "x" -> v.trim().toIntOrNull()?.let { x = it }
                "y" -> v.trim().toIntOrNull()?.let { y = it }
                "z" -> v.trim().toIntOrNull()?.let { z = it }
            }
        }, { focus = null }))
    }

    override fun render(s: ScreenSession, ui: Ui) {
        val p = s.player
        val lang = s.plugin.lang
        val X = Win.EDIT_X
        val Y = Win.EDIT_Y
        header(s, ui, "win2_waypoint", "pencil", lang.get(p, if (editing == null) "waypoint.new_title" else "waypoint.edit_title"), "$x, $y, $z", X, Y, Win.W)
        val text = ui.glyphs.text
        ui.tag(X + 12, Y + 40, lang.get(p, "waypoint.name").uppercase())
        val shown = if (focus == "name") s.textField?.value ?: name else name
        if (shown.isEmpty() && focus != "name") {
            ui.text(text, X + 65, Y + 40, lang.get(p, "waypoint.name_placeholder"), Tint.MUTED, 2)
        } else {
            val w = ui.text(text, X + 65, Y + 40, shown, Tint.CREAM, 2, max = 140.0)
            if (focus == "name") ui.text(text, X + 66 + w, Y + 40, "_", Tint.GOLD, 2)
        }
        ui.hit(X + 62, Y + 40.0, 149.0, 12.0, "field:name", { edit(s, "name", name, 32) })
        ui.text(ui.glyphs.small, X + 63, Y + 53, lang.get(p, "waypoint.type_hint").uppercase(), Tint.HINT, 2, max = 150.0)

        ui.tag(X + 12, Y + 70, lang.get(p, "waypoint.position").uppercase())
        for ((i, pair) in listOf("x" to x, "y" to y, "z" to z).withIndex()) {
            val fx = X + 76 + i * 46
            ui.text(ui.glyphs.small, fx - 9, Y + 72, pair.first.uppercase(), Tint.INK, 2)
            val v = if (focus == pair.first) s.textField?.value ?: pair.second.toString() else pair.second.toString()
            ui.text(text, fx + 3, Y + 69, v, Tint.CREAM, 2, max = 28.0)
            ui.hit(fx, Y + 70.0, 33.0, 11.0, "field:" + pair.first, { edit(s, pair.first, pair.second.toString(), 9) })
        }

        ui.tag(X + 12, Y + 95, lang.get(p, "waypoint.marker").uppercase())
        val slots = listOf<String?>(null) + ICONS
        for ((i, n) in slots.withIndex()) {
            val tx = X + 62 + (i % 4) * 19
            val ty = Y + 94 + (i / 4) * 19
            val on = icon == n
            ui.glyph(if (on) "tile_18_on" else "tile_18", tx, ty, Tint.NONE, 2, Fx.HOVER)
            if (n == null) {
                ui.centered(ui.glyphs.bold, tx + 9, ty + 3, "Aa", Tint.INK, 3)
            } else {
                ui.glyph("loc_$n", tx + 1, ty + 1, Tint.NONE, 3)
            }
            ui.hit(tx, ty.toDouble(), 18.0, 18.0, "icon:${n ?: "letter"}", { icon = n })
        }
        for (i in 0 until 8) {
            val gx = X + 146 + (i % 2) * 15
            val gy = Y + 96 + (i / 2) * 15
            ui.glyph("gem_$i", gx, gy, Tint.NONE, 2, Fx.HOVER)
            if (color == i) ui.glyph("gem_select", gx - 2, gy - 2, Tint.NONE, 3)
            ui.hit(gx, gy.toDouble(), 12.0, 12.0, "gem:$i", { color = i })
        }
        ui.glyph("tile_18", X + 184, Y + 104, Tint.NONE, 2)
        val preview = icon?.let { s.plugin.pins.iconGlyphName(it, ui.glyphs) }
        if (preview != null) {
            ui.glyph(preview, X + 185, Y + 105, Tint.NONE, 3)
        } else {
            ui.glyph("mark_square", X + 188, Y + 108, Tint.nearest(Waypoint.COLORS[color]), 3)
            val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            ui.centered(ui.glyphs.small, X + 193.5, Y + 110, letter, if (color == 7) Tint.CREAM else Tint.DARK, 4)
        }
        ui.centered(ui.glyphs.small, X + 193, Y + 126, lang.get(p, "waypoint.preview").uppercase(), Tint.INK_SOFT, 2)

        ui.cbutton("parch", 56, X + 12, Y + 175, lang.get(p, "button.cancel").uppercase(), "cancel") { s.setPanel(null) }
        ui.cbutton("gold", 92, X + Win.W - 12 - 92, Y + 175, lang.get(p, if (editing == null) "waypoint.create" else "waypoint.save").uppercase(), "confirm") { confirm(s) }
    }

    private fun confirm(s: ScreenSession) {
        val store = s.map.waypoints
        val finalName = name.trim().ifEmpty { s.plugin.lang.get(s.player, "waypoint.default_name") }
        val existing = editing?.let { store.get(it) }
        if (existing != null) {
            existing.name = finalName
            existing.x = x
            existing.y = y
            existing.z = z
            existing.color = color
            existing.icon = icon
            store.save()
        } else {
            store.add(Waypoint(UUID.randomUUID(), finalName, s.player.world.name, x, y, z, color, icon))
        }
        s.map.onWaypointsChanged()
        s.invalidateMap()
        s.setPanel(null)
    }

    companion object {
        val ICONS = listOf("bank", "cave", "chest", "dungeon", "house", "map", "quest", "store", "teleport", "waypoint")
    }
}

class WaypointListPanel : Panel {
    private var scroll = 0
    private var selected: UUID? = null
    private var armed: UUID? = null

    override fun render(s: ScreenSession, ui: Ui) {
        val p = s.player
        val lang = s.plugin.lang
        val X = Win.LIST_X
        val Y = Win.LIST_Y
        val list = s.map.waypoints.inWorld(p.world.name)
        header(s, ui, "win2_waypoints", "book", lang.get(p, "waypoints.title"), lang.get(p, "waypoints.count", list.size), X, Y, Win.W)
        val rowH = 24
        val visible = 6
        val maxScroll = ((list.size - visible) * rowH + 2).coerceAtLeast(0)
        scroll = scroll.coerceIn(0, maxScroll)
        if (list.isEmpty()) {
            ui.centered(ui.glyphs.text, X + Win.W / 2.0, Y + 100, lang.get(p, "waypoints.empty"), Tint.INK_SOFT, 2)
            ui.centered(ui.glyphs.small, X + Win.W / 2.0, Y + 114, lang.get(p, "waypoints.empty_hint").uppercase(), Tint.HINT, 2)
        }
        for ((i, w) in list.withIndex()) {
            val ry = Y + 38 + i * rowH - scroll
            if (ry < Y + 38 - rowH || ry > Y + 182) continue
            val id = "row:${w.id}"
            val state = when {
                selected == w.id -> "card_wp_selected"
                ui.isHovered(id) -> "card_wp_hover"
                else -> "card_wp"
            }
            val rx = X + 14
            ui.glyph(state, rx, ry, Tint.NONE, 1, Fx.CLIP_A)
            val iconName = w.icon?.let { s.plugin.pins.iconGlyphName(it, ui.glyphs) }
            if (iconName != null) {
                ui.glyph(iconName, rx + 5, ry + 3, Tint.NONE, 2, Fx.CLIP_A)
            } else {
                ui.glyph("mark_square", rx + 7, ry + 5, Tint.nearest(Waypoint.COLORS[w.color.coerceIn(0, 7)]), 2, Fx.CLIP_A)
                ui.text(ui.glyphs.small, rx + 10, ry + 7, w.letter(), if (w.color == 7) Tint.CREAM else Tint.DARK, 3, Fx.CLIP_A)
            }
            ui.text(ui.glyphs.bold, rx + 24, ry + 1, w.name, Tint.INK, 2, Fx.CLIP_A, 74.0)
            ui.text(ui.glyphs.small, rx + 24, ry + 13, "${w.x}, ${w.y}, ${w.z}", Tint.INK_SOFT, 2, Fx.CLIP_A)
            ui.hit(rx, ry.toDouble(), 98.0, rowH - 2.0, id, { selected = w.id })
            val actions = listOf(
                (if (w.visible) "eye" else "eye_off") to { w.visible = !w.visible; s.map.waypoints.save(); s.map.onWaypointsChanged(); s.invalidateMap() },
                "target" to { s.map.setTracked(if (w.tracked) null else w.id); s.invalidateMap() },
                "pencil" to { s.setPanel(WaypointEditPanel(w.id, null)) },
                (if (armed == w.id) "trash_armed" else "trash") to {
                    if (armed == w.id) {
                        s.map.waypoints.remove(w.id)
                        s.map.onWaypointsChanged()
                        s.invalidateMap()
                        armed = null
                    } else {
                        armed = w.id
                    }
                },
            )
            for ((j, a) in actions.withIndex()) {
                val bx = rx + 100 + j * 17
                val on = (j == 1 && w.tracked) || (j == 3 && armed == w.id)
                ui.glyph(if (on) "cbtn_gold_13" else "cbtn_parch_13", bx, ry + 4, Tint.NONE, 2, Fx.CLIP_A)
                val ig = ui.glyphs["icon_" + a.first]
                ui.glyph("icon_" + a.first, bx + (13 - ig.width) / 2, ry + 4 + (13 - ig.height) / 2, Tint.NONE, 3, Fx.CLIP_A)
                ui.hit(bx, ry + 4.0, 13.0, 13.0, "act:${w.id}:$j", a.second)
            }
        }
        ui.hit(X + 11, Y + 35.0, 206.0, 146.0, "list", null, { d -> scroll = (scroll + d * rowH).coerceIn(0, maxScroll) })
        ui.glyph("rail_132", X + 199, Y + 42, Tint.NONE, 1)
        if (maxScroll > 0) {
            val thumbY = Y + 42 + ((132 - 22) * scroll / maxScroll)
            ui.glyph("knob_22", X + 199, thumbY, Tint.NONE, 2, Fx.HOVER)
            ui.hit(X + 196, Y + 42.0, 13.0, 132.0, "thumb", null, null) { _, cy ->
                scroll = (((cy - (Y + 53)) / (132 - 22)) * maxScroll).toInt().coerceIn(0, maxScroll)
                s.invalidateUi()
            }
        }
        val sel = selected?.let { s.map.waypoints.get(it) }
        if (sel != null) ui.chip(X + 12, Y + 196, (lang.get(p, "waypoints.editing") + " " + sel.name).uppercase())
        ui.cbutton("gold", 64, X + Win.W - 12 - 64, Y + 193, lang.get(p, "waypoints.edit").uppercase(), "edit") {
            val id = selected
            if (id != null) s.setPanel(WaypointEditPanel(id, null))
        }
    }
}

class SettingsPanel : Panel {
    override fun render(s: ScreenSession, ui: Ui) {
        val p = s.player
        val lang = s.plugin.lang
        val st = s.map.settings
        val X = Win.SET_X
        val Y = Win.SET_Y
        header(s, ui, "win2_settings", "gear", lang.get(p, "settings.title"), null, X, Y, Win.W)
        ui.tag(X + 14, Y + 38, lang.get(p, "settings.visibility").uppercase())
        val rows = listOf(
            Triple(lang.get(p, "settings.row_mobs"), st.showMobs) { st.showMobs = !st.showMobs },
            Triple(lang.get(p, "settings.row_players"), st.showPlayers) { st.showPlayers = !st.showPlayers },
            Triple(lang.get(p, "settings.row_coords"), st.coords) { st.coords = !st.coords },
            Triple(lang.get(p, "settings.row_minimap"), st.minimap) { st.minimap = !st.minimap },
        )
        for ((i, r) in rows.withIndex()) {
            val ry = Y + 56 + i * 23
            ui.text(ui.glyphs.text, X + 16, ry, r.first, Tint.INK, 2, max = 66.0)
            ui.glyph(if (r.second) "lamp_on" else "lamp_off", X + 86, ry, Tint.NONE, 2, Fx.HOVER)
            ui.hit(X + 12, ry - 2.0, 98.0, 14.0, "toggle:$i", { r.third(); s.map.onSettingsChanged() })
        }
        ui.tag(X + 120, Y + 38, lang.get(p, "settings.minimap").uppercase())
        ui.text(ui.glyphs.small, X + 122, Y + 56, lang.get(p, "settings.shape").uppercase(), Tint.INK_SOFT, 2)
        for ((i, shape) in MinimapShape.entries.withIndex()) {
            val bx = X + 122 + i * 21
            val on = st.shape == shape
            val g = "shape_" + shape.name.lowercase() + if (on) "_on" else ""
            ui.glyph(g, bx, Y + 64, Tint.NONE, 2, Fx.HOVER)
            ui.hit(bx, Y + 64.0, 18.0, 18.0, "shape:$i", { st.shape = shape; s.map.onSettingsChanged() })
        }
        ui.text(ui.glyphs.small, X + 170, Y + 56, lang.get(p, "settings.corner").uppercase(), Tint.INK_SOFT, 2)
        ui.glyph("screen_mock", X + 170, Y + 63, Tint.NONE, 2)
        for ((i, c) in Corner.entries.withIndex()) {
            val cx = X + 173 + (i % 2) * 22
            val cy = Y + 66 + (i / 2) * 10
            ui.glyph(if (st.corner == c) "corner_dot_on" else "corner_dot", cx, cy, Tint.NONE, 3, Fx.HOVER)
            ui.hit(cx - 2, cy - 2.0, 10.0, 10.0, "corner:$i", { st.corner = c; s.map.onSettingsChanged() })
        }
        ui.text(ui.glyphs.small, X + 122, Y + 96, lang.get(p, "settings.sensitivity").uppercase(), Tint.INK_SOFT, 2)
        ui.glyph("cbtn_parch_13", X + 122, Y + 105, Tint.NONE, 2, Fx.HOVER)
        ui.centered(ui.glyphs.bold, X + 128.5, Y + 106, "-", Tint.INK, 3)
        ui.hit(X + 122, Y + 105.0, 13.0, 13.0, "sens:-", { st.sensitivity = (st.sensitivity - 0.1).coerceIn(1.0, 3.0); s.map.onSettingsChanged() })
        ui.centered(ui.glyphs.bold, X + 152, Y + 106, String.format(Locale.ROOT, "%.1fx", st.sensitivity), Tint.INK, 2)
        ui.glyph("cbtn_parch_13", X + 169, Y + 105, Tint.NONE, 2, Fx.HOVER)
        ui.centered(ui.glyphs.bold, X + 175.5, Y + 106, "+", Tint.INK, 3)
        ui.hit(X + 169, Y + 105.0, 13.0, 13.0, "sens:+", { st.sensitivity = (st.sensitivity + 0.1).coerceIn(1.0, 3.0); s.map.onSettingsChanged() })
        ui.text(ui.glyphs.small, X + 122, Y + 126, lang.get(p, "settings.module").uppercase(), Tint.INK_SOFT, 2)
        ui.text(ui.glyphs.small, X + 122, Y + 136, lang.get(p, "module." + st.module.name.lowercase()).uppercase(), Tint.INK, 2, max = 92.0)
        ui.tag(X + 14, Y + 166, lang.get(p, "settings.cursor").uppercase())
        for (i in 0 until PlayerSettings.CURSORS) {
            val tx = X + 72 + i * 18
            ui.glyph(if (st.cursor == i) "tile_18_on" else "tile_18", tx, Y + 163, Tint.NONE, 1, Fx.HOVER)
            ui.glyph("cursor_$i", tx + 5, Y + 165, Tint.NONE, 2)
            ui.hit(tx, Y + 163.0, 18.0, 18.0, "cursor:$i", { st.cursor = i; s.map.onSettingsChanged() })
        }
        ui.cbutton("parch", 56, X + 12, Y + 195, lang.get(p, "settings.reset").uppercase(), "reset") {
            val d = PlayerSettings.defaults(s.cfg)
            st.shape = d.shape
            st.corner = d.corner
            st.coords = d.coords
            st.showMobs = true
            st.showPlayers = true
            st.minimap = true
            st.sensitivity = d.sensitivity
            st.cursor = d.cursor
            s.map.onSettingsChanged()
        }
        ui.cbutton("gold", 56, X + Win.W - 12 - 56, Y + 195, lang.get(p, "button.done").uppercase(), "done") { s.setPanel(null) }
    }
}

class FaqPanel : Panel {
    override fun render(s: ScreenSession, ui: Ui) {
        val p = s.player
        val lang = s.plugin.lang
        val X = Win.FAQ_X
        val Y = Win.FAQ_Y
        header(s, ui, "win2_faq", "scroll", lang.get(p, "faq.title"), lang.get(p, "faq.subtitle"), X, Y, 256)
        val rows = listOf(
            s.plugin.binds.describe(p) to lang.get(p, "faq.toggle"),
            lang.get(p, "faq.k_right_map") to lang.get(p, "faq.right_map"),
            lang.get(p, "faq.k_right_wp") to lang.get(p, "faq.right_wp"),
            lang.get(p, "faq.k_drag") to lang.get(p, "faq.drag"),
            lang.get(p, "faq.k_scroll") to lang.get(p, "faq.scroll"),
            lang.get(p, "faq.k_bar") to lang.get(p, "faq.bar"),
            lang.get(p, "faq.k_thumb") to lang.get(p, "faq.thumb"),
            lang.get(p, "faq.k_trash") to lang.get(p, "faq.trash"),
            lang.get(p, "faq.k_esc") to lang.get(p, "faq.esc"),
        )
        for ((i, r) in rows.withIndex()) {
            val ry = Y + 36 + i * 21
            ui.keycap(X + 52, ry, r.first.uppercase())
            ui.glyph("lined_row", X + 96, ry, Tint.NONE, 1)
            ui.text(ui.glyphs.small, X + 99, ry + 3, r.second.uppercase(), Tint.INK, 2, max = 146.0)
        }
    }
}

class SearchPanel : Panel {
    private var query = ""
    private var scroll = 0
    override val modal: Boolean get() = false

    override fun onOpen(s: ScreenSession) {
        focus(s)
    }

    private fun focus(s: ScreenSession) {
        s.focus(TextField(query, 40, { v -> query = v; scroll = 0 }, { }))
    }

    override fun render(s: ScreenSession, ui: Ui) {
        val p = s.player
        val lang = s.plugin.lang
        val X = Win.SEARCH_X
        val Y = Win.SEARCH_Y
        val W = Win.SEARCH_W
        ui.threeSlice("sfield", X, Y, W, Tint.NONE, 1)
        ui.glyph("icon_magnifier", X + 6, Y + 4, Tint.NONE, 2)
        val shown = s.textField?.value ?: query
        if (shown.isEmpty()) {
            ui.text(ui.glyphs.text, X + 18, Y + 4, lang.get(p, "search.placeholder"), Tint.MUTED, 2)
        } else {
            val w = ui.text(ui.glyphs.text, X + 18, Y + 4, shown, Tint.CREAM, 2, max = 170.0)
            if (s.textField != null && (s.ticks / 10) % 2 == 0) ui.text(ui.glyphs.text, X + 19 + w, Y + 4, "_", Tint.GOLD, 2)
        }
        ui.hit(X, Y.toDouble(), W - 36.0, 16.0, "search_field", { focus(s) })
        ui.keycap(X + W - 36, Y + 2, "ESC")
        ui.glyph("close_small", X + W - 15, Y + 3, Tint.NONE, 2, Fx.HOVER)
        ui.hit(X + W - 15, Y + 3.0, 10.0, 10.0, "search_close", { s.setPanel(null) })

        val results = s.plugin.locations.search(p, p.world.name, shown)
        val ry = Y + 18
        val rows = results.size.coerceIn(1, 5)
        val bodyH = rows * 29 + 1
        ui.glyph("win3_top", X, ry, Tint.NONE, 0)
        val mid = ui.glyphs["win3_mid"]
        for (i in 0 until bodyH) ui.canvas.glyph(mid, X, ry + 19 + i, Codes.glyph(ry + 19 + i, Tint.NONE, 0))
        ui.glyph("win3_bot", X, ry + 19 + bodyH, Tint.NONE, 0)
        ui.text(ui.glyphs.small, X + 8, ry + 5, lang.get(p, "search.results").uppercase(), Tint.CREAM, 2)
        val found = lang.get(p, "search.found", results.size).uppercase()
        ui.text(ui.glyphs.small, X + W - 34 - ui.glyphs.small.width(found), ry + 5, found, Tint.GOLD, 2)
        val rowH = 29
        val visible = 5
        val maxScroll = (results.size - visible).coerceAtLeast(0)
        scroll = scroll.coerceIn(0, maxScroll)
        if (maxScroll > 0) {
            arrow(ui, "up", X + W - 29, ry + 4) { scroll = (scroll - 1).coerceAtLeast(0) }
            arrow(ui, "down", X + W - 19, ry + 4) { scroll = (scroll + 1).coerceAtMost(maxScroll) }
        }
        if (results.isEmpty()) ui.centered(ui.glyphs.text, X + W / 2.0, ry + 29, lang.get(p, "search.none"), Tint.INK_SOFT, 2)
        val here = p.location
        for (row in 0 until visible) {
            val l = results.getOrNull(scroll + row) ?: break
            val cy = ry + 19 + row * rowH
            val cx = X + 10
            val id = "result:${l.index}"
            ui.glyph(if (ui.isHovered(id)) "rcard_hover" else "rcard", cx, cy, Tint.NONE, 1)
            val icon = l.icon?.let { s.plugin.pins.iconGlyphName(it, ui.glyphs) } ?: "loc_waypoint"
            val ig = ui.glyphs[icon]
            ui.glyph(icon, cx + 4 + (18 - ig.width) / 2, cy + (28 - ig.height) / 2, Tint.NONE, 2)
            val nameTint = l.nameColor?.let { Tint.nearest(it) } ?: Tint.INK
            val nw = ui.text(ui.glyphs.bold, cx + 27, cy + 2, l.name, nameTint, 2, max = 110.0)
            if (l.tag != null) {
                val font = ui.glyphs.small
                val label = l.tag.uppercase()
                val tw = font.width(label).toInt() + 8
                val tx = cx + 30 + nw
                val tint = l.tagColor?.let { Tint.nearest(it) }
                if (tint == null) ui.threeSlice("ribbon2", tx, cy + 2, tw, Tint.NONE, 2) else ui.threeSlice("pill", tx, cy + 3, tw, tint, 2)
                ui.text(font, tx + 4, cy + 3, label, Tint.CREAM, 3)
            }
            val coords = "X ${l.x.toInt()}  " + (l.y?.let { "Y ${it.toInt()}  " } ?: "") + "Z ${l.z.toInt()}"
            ui.text(ui.glyphs.small, cx + 27, cy + 12, coords, Tint.INK_SOFT, 2)
            if (l.world == null || l.world == p.world.name) {
                val dist = distance(Math.hypot(l.x - here.x, l.z - here.z))
                ui.text(ui.glyphs.small, cx + 196 - ui.glyphs.small.width(dist), cy + 12, dist, Tint.INK, 2)
            }
            if (l.description != null) ui.text(ui.glyphs.small, cx + 27, cy + 19, l.description, l.descColor?.let { Tint.nearest(it) } ?: Tint.MUTED, 2, max = 170.0)
            ui.hit(cx, cy.toDouble(), 212.0, 28.0, id, {
                s.flyTo(l.x, l.z)
                s.player.sendActionBar(Component.text(lang.get(p, "search.moved", l.name, l.x.toInt(), l.y?.toInt() ?: "?", l.z.toInt())))
                s.setPanel(null)
            })
        }
        ui.hit(X + 6, ry + 18.0, 220.0, bodyH.toDouble(), "results", null, { d -> scroll = (scroll + d).coerceIn(0, maxScroll) })
    }

    private fun arrow(ui: Ui, dir: String, x: Double, y: Int, action: () -> Unit) {
        val id = "search_$dir"
        ui.glyph(if (ui.isHovered(id)) "arrow_${dir}_on" else "arrow_$dir", x, y, Tint.NONE, 2)
        ui.hit(x, y.toDouble(), 9.0, 7.0, id, action)
    }

    private fun distance(blocks: Double): String =
        if (blocks < 1000) "${blocks.toInt()}M" else String.format(Locale.ROOT, "%.1fKM", blocks / 1000)
}
