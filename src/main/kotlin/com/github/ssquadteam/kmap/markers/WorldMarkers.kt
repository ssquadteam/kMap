package com.github.ssquadteam.kmap.markers

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.nms.EntityDataKeys
import com.github.ssquadteam.kmap.nms.FakeIds
import com.github.ssquadteam.kmap.nms.Packets
import com.github.ssquadteam.kmap.pack.ShaderDefines
import com.github.ssquadteam.kmap.render.Canvas
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.Glyphs
import com.github.ssquadteam.kmap.render.Tint
import com.github.ssquadteam.kmap.render.WorldIcon
import com.github.ssquadteam.kmap.session.PlayerMap
import com.github.ssquadteam.kmap.waypoints.Waypoint
import io.papermc.paper.adventure.PaperAdventure
import java.util.Locale
import kotlin.math.sqrt
import net.kyori.adventure.text.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.world.entity.EntityTypes
import org.joml.Quaternionf
import org.joml.Quaternionfc
import org.joml.Vector3f
import org.joml.Vector3fc

class WorldMarkers(private val plugin: KMapPlugin, private val map: PlayerMap) {
    private class Beam(val id: Int, var x: Double, var y: Double, var z: Double)

    private val markers = HashMap<String, WorldIcon>()
    private var beam: Pair<String, Beam>? = null
    private var dirty = true
    private var lastDistanceTick = 0

    fun markDirty() {
        dirty = true
    }

    fun clear(out: MutableList<Packet<in ClientGamePacketListener>>) {
        val ids = markers.values.map { it.id } + listOfNotNull(beam?.second?.id)
        if (ids.isNotEmpty()) out.add(Packets.remove(*ids.toIntArray()))
        markers.clear()
        beam = null
    }

    private class Target(val key: String, val x: Double, val y: Double, val z: Double, val name: String, val tint: Tint, val icon: String?, val letter: String, val letterTint: Tint)

    fun update(out: MutableList<Packet<in ClientGamePacketListener>>, ticks: Int) {
        val p = map.player
        if (map.band >= 2) return
        val loc = p.eyeLocation
        val world = p.world.name
        val targets = ArrayList<Target>()
        for (w in map.waypoints.inWorld(world)) {
            if (!w.visible) continue
            targets.add(Target("w:" + w.id, w.x + 0.5, w.y + 1.2, w.z + 0.5, w.name, Tint.nearest(Waypoint.COLORS[w.color.coerceIn(0, 7)]), w.icon, w.letter(), if (w.color == 7) Tint.CREAM else Tint.DARK))
        }
        val tracked = map.trackedPin
        val trackedLoc = tracked?.let { idx -> plugin.locations.all.getOrNull(idx) }?.takeIf { it.world == null || it.world == world }
        if (trackedLoc != null) {
            targets.add(Target("p:" + trackedLoc.index, trackedLoc.x + 0.5, (trackedLoc.y ?: loc.y) + 1.2, trackedLoc.z + 0.5, trackedLoc.name, Tint.GOLD, trackedLoc.icon ?: "waypoint", "", Tint.DARK))
        }
        val distanceTick = ticks - lastDistanceTick >= plugin.cfg.markerDistanceUpdateTicks
        if (distanceTick) lastDistanceTick = ticks
        val keep = HashSet<String>()
        val glyphs = plugin.packs.glyphs
        val top = (p.world.maxHeight + 40).toDouble()
        val reach = ((minOf(p.viewDistance, p.clientViewDistance) - 2) * 16).coerceIn(32, 400).toDouble()
        for (t in targets) {
            val dx = t.x - loc.x
            val dy = t.y - loc.y
            val dz = t.z - loc.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            keep.add(t.key)
            val k = if (d > reach) reach / d else 1.0
            val px = loc.x + dx * k
            val pz = loc.z + dz * k
            val lift = (loc.y + dy * k - top).toFloat()
            val distText = if (d < 1000) String.format(Locale.ROOT, "%.1fm", d) else String.format(Locale.ROOT, "%.1fkm", d / 1000)
            val m = markers[t.key]
            if (m == null) {
                val icon = WorldIcon(0.001f, 2)
                markers[t.key] = icon
                out.addAll(icon.spawnPackets(px, top, pz, label(t, distText, glyphs), lift))
                continue
            }
            if ((px - m.x) * (px - m.x) + (pz - m.z) * (pz - m.z) > 0.0625) m.move(px, top, pz)?.let { out.add(it) }
            m.setLift(lift)?.let { out.add(it) }
            if (distanceTick || dirty) m.setText(label(t, distText, glyphs))?.let { out.add(it) }
        }
        val gone = markers.keys.filter { it !in keep }
        if (gone.isNotEmpty()) out.add(Packets.remove(*gone.map { markers.remove(it)!!.id }.toIntArray()))
        updateBeam(trackedLoc?.takeIf { it.beam }?.let { Triple(it.x + 0.5, it.y ?: 70.0, it.z + 0.5) } ?: map.waypoints.all.firstOrNull { it.tracked && it.world == world }?.let { Triple(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }, out)
        dirty = false
    }

    private fun updateBeam(at: Triple<Double, Double, Double>?, out: MutableList<Packet<in ClientGamePacketListener>>) {
        val key = at?.toString()
        if (key == beam?.first) return
        beam?.let { out.add(Packets.remove(it.second.id)) }
        beam = null
        if (at == null) return
        val b = Beam(FakeIds.next(), at.first, at.second, at.third)
        beam = key!! to b
        val glyphs = plugin.packs.glyphs
        val text = Canvas(glyphs).glyph("beam", 0.0, 0, 0xFFF0B0).build()
        out.add(Packets.spawn(b.id, EntityTypes.TEXT_DISPLAY, b.x, b.y, b.z))
        out.add(
            Packets.data(
                b.id,
                listOf(
                    Packets.value(EntityDataKeys.TEXT, PaperAdventure.asVanilla(text)),
                    Packets.value(EntityDataKeys.BACKGROUND, 0),
                    Packets.value(EntityDataKeys.TEXT_OPACITY, (-1).toByte()),
                    Packets.value(EntityDataKeys.BILLBOARD, 2.toByte()),
                    Packets.value(EntityDataKeys.SCALE, Vector3f(3f, 40f, 3f) as Vector3fc),
                    Packets.value(EntityDataKeys.VIEW_RANGE, 16f),
                    Packets.value(EntityDataKeys.WIDTH, 0f),
                    Packets.value(EntityDataKeys.HEIGHT, 0f),
                    Packets.value(EntityDataKeys.BRIGHTNESS, 15 shl 4 or (15 shl 20)),
                ),
            ),
        )
    }

    private fun label(t: Target, dist: String, glyphs: Glyphs): Component {
        val c = Canvas(glyphs)
        val iconName = t.icon?.let { plugin.pins.iconGlyphName(it, glyphs)?.let { n -> glyphs.find(n + "_w")?.name ?: n } }
        if (iconName != null && glyphs.find(iconName)?.let { true } == true && iconName.endsWith("_w")) {
            c.glyph(iconName, 0.0, 0, Codes.billboard(0, 0, false, Tint.NONE, 0))
        } else {
            c.glyph("world_square", 0.0, 0, Codes.billboard(0, 0, false, t.tint, 0))
            if (t.letter.isNotEmpty()) c.glyph(glyphs.find("wglyph_" + t.letter)?.name ?: "wglyph_?", 0.0, 0, Codes.billboard(-3, 3, false, t.letterTint, 2))
        }
        row(c, glyphs, t.name, 0, Tint.CREAM)
        row(c, glyphs, dist, 1, Tint.GOLD)
        return c.build()
    }

    private fun row(c: Canvas, glyphs: Glyphs, text: String, row: Int, tint: Tint) {
        val w = ShaderDefines.WCELL_W
        val chars = text.take(24)
        val start = -(chars.length * w) / 2
        for ((i, ch) in chars.withIndex()) {
            if (ch == ' ') continue
            val g = glyphs.find("wglyph_$ch") ?: glyphs.find("wglyph_" + ch.uppercaseChar()) ?: glyphs["wglyph_?"]
            c.glyph(g, 0.0, 0, Codes.billboard(start + i * w, row, true, tint, 1))
        }
    }

    companion object {
        private val IDENTITY: Quaternionfc = Quaternionf()
    }
}
