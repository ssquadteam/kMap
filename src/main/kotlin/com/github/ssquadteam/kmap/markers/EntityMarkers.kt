package com.github.ssquadteam.kmap.markers

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.nms.Packets
import com.github.ssquadteam.kmap.render.Canvas
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.WorldIcon
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player as NmsPlayer
import net.minecraft.world.phys.AABB
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player

class EntityMarkers(private val plugin: KMapPlugin, private val player: Player) {
    private class Tracked(val icon: WorldIcon, val isPlayer: Boolean)

    private val tracked = HashMap<Int, Tracked>()
    private var lastParam = -1

    fun clear(out: MutableList<Packet<in ClientGamePacketListener>>) {
        if (tracked.isEmpty()) return
        out.add(Packets.remove(*tracked.values.map { it.icon.id }.toIntArray()))
        tracked.clear()
    }

    fun update(miniParam: Int, showPlayers: Boolean, showMobs: Boolean, showOnBig: Boolean, out: MutableList<Packet<in ClientGamePacketListener>>) {
        val cfg = plugin.cfg.entities
        if (!cfg.enabled) {
            clear(out)
            return
        }
        val handle = (player as CraftPlayer).handle
        val level = handle.level()
        val r = cfg.radiusBlocks.toDouble()
        val box = AABB(handle.x - r, handle.y - cfg.heightRadius, handle.z - r, handle.x + r, handle.y + cfg.heightRadius, handle.z + r)
        val found = try {
            level.getEntities(handle, box) { e ->
                e.isAlive && ((showMobs && cfg.mobs && e is Mob) || (showPlayers && cfg.players && e is NmsPlayer && !e.isSpectator && player.canSee(e.bukkitEntity)))
            }
        } catch (_: Throwable) {
            emptyList()
        }
        val sorted = found.sortedBy { it.distanceToSqr(handle) }.take(cfg.maxPerPlayer)
        val keep = HashSet<Int>()
        val y = (level.maxY + 40).toDouble()
        val paramChanged = miniParam != lastParam
        lastParam = miniParam
        for (e in sorted) {
            keep.add(e.id)
            val isPlayer = e is NmsPlayer
            val existing = tracked[e.id]
            if (existing == null) {
                val icon = WorldIcon(0.001f, cfg.animationTicks)
                out.addAll(icon.spawnPackets(e.x, y, e.z, text(isPlayer, miniParam, showOnBig)))
                tracked[e.id] = Tracked(icon, isPlayer)
            } else {
                existing.icon.move(e.x, y, e.z)?.let { out.add(it) }
                if (paramChanged) existing.icon.setText(text(isPlayer, miniParam, showOnBig))?.let { out.add(it) }
            }
        }
        val gone = tracked.keys.filter { it !in keep }
        if (gone.isNotEmpty()) {
            out.add(Packets.remove(*gone.map { tracked.remove(it)!!.icon.id }.toIntArray()))
        }
    }

    private fun text(isPlayer: Boolean, miniParam: Int, showOnBig: Boolean): net.kyori.adventure.text.Component {
        val glyphs = plugin.packs.glyphs
        val name = if (isPlayer) (glyphs.find("user_icons_" + plugin.cfg.entities.playerImage.substringBeforeLast('.'))?.name ?: "mark_player") else (glyphs.find("user_icons_" + plugin.cfg.entities.mobImage.substringBeforeLast('.'))?.name ?: "mark_mob")
        val size = plugin.cfg.entities.sizePx - if (isPlayer) 0 else 1
        return Canvas(glyphs).glyph(name, 0.0, 0, Codes.miniIcon(miniParam, size, false, if (isPlayer) 1 else 0, showOnBig)).build()
    }
}
