package com.github.ssquadteam.kmap.markers

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.nms.Packets
import com.github.ssquadteam.kmap.render.Canvas
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.Tint
import com.github.ssquadteam.kmap.render.WorldIcon
import com.github.ssquadteam.kmap.session.PlayerMap
import com.github.ssquadteam.kmap.waypoints.Waypoint
import net.kyori.adventure.text.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import kotlin.math.sqrt

class HudMarkers(private val plugin: KMapPlugin, private val map: PlayerMap) {
    private val icons = HashMap<String, WorldIcon>()
    var dirty = true
        private set

    fun markDirty() {
        dirty = true
    }

    fun clear(out: MutableList<Packet<in ClientGamePacketListener>>) {
        if (icons.isNotEmpty()) out.add(Packets.remove(*icons.values.map { it.id }.toIntArray()))
        icons.clear()
        dirty = true
    }

    private class Want(val key: String, val x: Double, val z: Double, val text: Component)

    fun update(out: MutableList<Packet<in ClientGamePacketListener>>) {
        dirty = false
        val p = map.player
        val loc = p.location
        val world = p.world.name
        val glyphs = plugin.packs.glyphs
        val param = map.miniParam()
        val onBig = map.settings.module.big
        val reach = ((p.viewDistance - 1) * 16).coerceIn(48, 400).toDouble()
        val y = (p.world.maxHeight + 40).toDouble()
        val wants = ArrayList<Want>()

        fun place(key: String, tx: Double, tz: Double, clamp: Boolean, text: (Boolean) -> Component) {
            val dx = tx - loc.x
            val dz = tz - loc.z
            val d = sqrt(dx * dx + dz * dz)
            if (d > reach) {
                if (!clamp) return
                wants.add(Want(key, loc.x + dx / d * reach, loc.z + dz / d * reach, text(true)))
            } else {
                wants.add(Want(key, tx, tz, text(clamp)))
            }
        }

        for (w in map.waypoints.inWorld(world)) {
            if (!w.visible) continue
            place("w:" + w.id, w.x + 0.5, w.z + 0.5, w.tracked) { clamp -> waypointText(w, param, onBig, clamp) }
        }
        val tracked = map.trackedPin
        for (l in plugin.locations.visible(p, world)) {
            if (!l.pin && tracked != l.index) continue
            val isTracked = tracked == l.index
            place("p:" + l.index, l.x, l.z, isTracked) { clamp ->
                val name = plugin.pins.pinGlyph(l, glyphs)
                Canvas(glyphs).glyph(name, 0.0, 0, Codes.miniIcon(param, if (isTracked) 11 else 9, clamp, 2, onBig)).build()
            }
        }
        val keep = HashSet<String>()
        for (w in wants) {
            keep.add(w.key)
            val icon = icons[w.key]
            if (icon == null) {
                val n = WorldIcon(0.001f, 2)
                out.addAll(n.spawnPackets(w.x, y, w.z, w.text))
                icons[w.key] = n
            } else {
                icon.move(w.x, y, w.z)?.let { out.add(it) }
                icon.setText(w.text)?.let { out.add(it) }
            }
        }
        val gone = icons.keys.filter { it !in keep }
        if (gone.isNotEmpty()) out.add(Packets.remove(*gone.map { icons.remove(it)!!.id }.toIntArray()))
    }

    private fun waypointText(w: Waypoint, param: Int, onBig: Boolean, clamp: Boolean): Component {
        val glyphs = plugin.packs.glyphs
        val c = Canvas(glyphs)
        val icon = w.icon?.let { plugin.pins.iconGlyphName(it, glyphs) }
        if (icon != null) {
            c.glyph(icon, 0.0, 0, Codes.miniIcon(param, 9, clamp, 2, onBig))
        } else {
            val tint = Tint.nearest(Waypoint.COLORS[w.color.coerceIn(0, 7)])
            c.glyph("mark_square", 0.0, 0, Codes.miniIcon(param, 8, clamp, 2, onBig, tint))
            val letter = glyphs.find("mletter_" + w.letter()) ?: glyphs["mletter_?"]
            c.glyph(letter, 12.0, 0, Codes.miniIcon(param, 6, clamp, 3, onBig, if (w.color == 7) Tint.CREAM else Tint.DARK))
        }
        return c.build()
    }
}
