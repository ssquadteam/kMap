package com.github.ssquadteam.kmap.markers

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.hooks.Relation
import com.github.ssquadteam.kmap.nms.Packets
import com.github.ssquadteam.kmap.render.Canvas
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.Tint
import com.github.ssquadteam.kmap.render.WorldIcon
import net.kyori.adventure.text.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player as NmsPlayer
import net.minecraft.world.phys.AABB
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import kotlin.math.sqrt

class EntityMarkers(private val plugin: KMapPlugin, private val player: Player) {
    companion object {
        private const val MIN_STEP = 0.35
        private const val MOB = 0
        private const val PLAYER = 1
        private const val MEMBER = 2
        private const val ALLY = 3
    }

    private class Tracked(val icon: WorldIcon, var kind: Int)

    private val tracked = HashMap<Int, Tracked>()
    private var lastParam = -1

    fun clear(out: MutableList<Packet<in ClientGamePacketListener>>) {
        if (tracked.isEmpty()) return
        out.add(Packets.remove(*tracked.values.map { it.icon.id }.toIntArray()))
        tracked.clear()
    }

    fun update(miniParam: Int, showPlayers: Boolean, showMobs: Boolean, showGuild: Boolean, showOnBig: Boolean, out: MutableList<Packet<in ClientGamePacketListener>>) {
        val cfg = plugin.cfg.entities
        val mates = if (showGuild) plugin.guilds?.mates(player) ?: emptyList() else emptyList()
        if (!cfg.enabled && mates.isEmpty()) {
            clear(out)
            return
        }
        val handle = (player as CraftPlayer).handle
        val level = handle.level()
        val mateIds = mates.mapTo(HashSet()) { it.player.entityId }
        val found = if (!cfg.enabled) emptyList() else try {
            val r = cfg.radiusBlocks.toDouble()
            val box = AABB(handle.x - r, handle.y - cfg.heightRadius, handle.z - r, handle.x + r, handle.y + cfg.heightRadius, handle.z + r)
            level.getEntities(handle, box) { e ->
                e.isAlive && e.id !in mateIds && ((showMobs && cfg.mobs && e is Mob) || (showPlayers && cfg.players && e is NmsPlayer && !e.isSpectator && player.canSee(e.bukkitEntity)))
            }
        } catch (_: Throwable) {
            emptyList()
        }
        val keep = HashSet<Int>()
        val y = (level.maxY + 40).toDouble()
        val paramChanged = miniParam != lastParam
        lastParam = miniParam
        fun place(id: Int, x: Double, z: Double, kind: Int) {
            keep.add(id)
            val existing = tracked[id]
            if (existing == null) {
                val icon = WorldIcon(0.001f, cfg.animationTicks)
                out.addAll(icon.spawnPackets(x, y, z, text(kind, miniParam, showOnBig)))
                tracked[id] = Tracked(icon, kind)
            } else {
                existing.icon.move(x, y, z, MIN_STEP)?.let { out.add(it) }
                if (paramChanged || existing.kind != kind) {
                    existing.kind = kind
                    existing.icon.setText(text(kind, miniParam, showOnBig))?.let { out.add(it) }
                }
            }
        }
        for (e in found.sortedBy { it.distanceToSqr(handle) }.take(cfg.maxPerPlayer)) place(e.id, e.x, e.z, if (e is NmsPlayer) PLAYER else MOB)
        val reach = ((player.viewDistance - 1) * 16).coerceIn(48, 400).toDouble()
        for (m in mates) {
            val h = (m.player as CraftPlayer).handle
            val dx = h.x - handle.x
            val dz = h.z - handle.z
            val d = sqrt(dx * dx + dz * dz)
            val k = if (d > reach) reach / d else 1.0
            place(h.id, handle.x + dx * k, handle.z + dz * k, if (m.relation == Relation.ALLY) ALLY else MEMBER)
        }
        val gone = tracked.keys.filter { it !in keep }
        if (gone.isNotEmpty()) {
            out.add(Packets.remove(*gone.map { tracked.remove(it)!!.icon.id }.toIntArray()))
        }
    }

    private val textCache = HashMap<Int, Component>()

    private fun text(kind: Int, miniParam: Int, showOnBig: Boolean): Component {
        val k = (miniParam shl 3) or (kind shl 1) or (if (showOnBig) 1 else 0)
        textCache[k]?.let { return it }
        if (textCache.size > 64) textCache.clear()
        return build(kind, miniParam, showOnBig).also { textCache[k] = it }
    }

    private fun build(kind: Int, miniParam: Int, showOnBig: Boolean): Component {
        val glyphs = plugin.packs.glyphs
        val cfg = plugin.cfg.entities
        val c = Canvas(glyphs)
        if (kind == MEMBER || kind == ALLY) {
            val g = plugin.cfg.guilds
            val tint = Tint.nearest(if (kind == ALLY) g.allyColor else g.memberColor, 16)
            return c.glyph("mark_square", 0.0, 0, Codes.miniIcon(miniParam, cfg.sizePx + 2, true, 2, showOnBig, tint)).build()
        }
        val isPlayer = kind == PLAYER
        val name = if (isPlayer) (glyphs.find("user_icons_" + cfg.playerImage.substringBeforeLast('.'))?.name ?: "mark_player") else (glyphs.find("user_icons_" + cfg.mobImage.substringBeforeLast('.'))?.name ?: "mark_mob")
        val size = cfg.sizePx - if (isPlayer) 0 else 1
        return c.glyph(name, 0.0, 0, Codes.miniIcon(miniParam, size, false, if (isPlayer) 1 else 0, showOnBig)).build()
    }
}
