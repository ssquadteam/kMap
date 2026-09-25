package com.github.ssquadteam.kmap.render

import com.github.ssquadteam.kmap.nms.EntityDataKeys
import com.github.ssquadteam.kmap.nms.FakeIds
import com.github.ssquadteam.kmap.nms.Packets
import io.papermc.paper.adventure.PaperAdventure
import net.kyori.adventure.text.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Quaternionfc
import org.joml.Vector3f
import org.joml.Vector3fc

class WorldIcon(private val scale: Float, private val glide: Int) {
    val id: Int = FakeIds.next()
    var x = 0.0
        private set
    var y = 0.0
        private set
    var z = 0.0
        private set
    private var sentText: Component? = null
    private var lift = 0f

    fun spawnPackets(x: Double, y: Double, z: Double, text: Component, lift: Float = 0f): List<Packet<in ClientGamePacketListener>> {
        this.x = x
        this.y = y
        this.z = z
        this.lift = lift
        sentText = text
        val s: Vector3fc = Vector3f(scale, scale, scale)
        return listOf(
            Packets.spawn(id, EntityTypes.TEXT_DISPLAY, x, y, z),
            Packets.data(
                id,
                listOf(
                    Packets.value(EntityDataKeys.TEXT, PaperAdventure.asVanilla(text)),
                    Packets.value(EntityDataKeys.LINE_WIDTH, 1_000_000),
                    Packets.value(EntityDataKeys.BACKGROUND, 0),
                    Packets.value(EntityDataKeys.TEXT_OPACITY, (-1).toByte()),
                    Packets.value(EntityDataKeys.BILLBOARD, 0.toByte()),
                    Packets.value(EntityDataKeys.SCALE, s),
                    Packets.value(EntityDataKeys.TRANSLATION, Vector3f(0f, lift, 0f) as Vector3fc),
                    Packets.value(EntityDataKeys.LEFT_ROTATION, IDENTITY),
                    Packets.value(EntityDataKeys.RIGHT_ROTATION, IDENTITY),
                    Packets.value(EntityDataKeys.VIEW_RANGE, 16f),
                    Packets.value(EntityDataKeys.WIDTH, 0f),
                    Packets.value(EntityDataKeys.HEIGHT, 0f),
                    Packets.value(EntityDataKeys.TELEPORT_DURATION, glide),
                    Packets.value(EntityDataKeys.BRIGHTNESS, 15 shl 4 or (15 shl 20)),
                ),
            ),
        )
    }

    fun move(x: Double, y: Double, z: Double, minStep: Double = 0.0): Packet<in ClientGamePacketListener>? {
        if (x == this.x && y == this.y && z == this.z) return null
        if (minStep > 0.0 && y == this.y) {
            val dx = x - this.x
            val dz = z - this.z
            if (dx * dx + dz * dz < minStep * minStep) return null
        }
        this.x = x
        this.y = y
        this.z = z
        return ClientboundTeleportEntityPacket(id, PositionMoveRotation(Vec3(x, y, z), Vec3.ZERO, 0f, 0f), emptySet(), false)
    }

    fun setLift(value: Float): Packet<in ClientGamePacketListener>? {
        if (kotlin.math.abs(value - lift) < 0.01f) return null
        lift = value
        return Packets.data(
            id,
            listOf(
                Packets.value(EntityDataKeys.INTERPOLATION_START, 0),
                Packets.value(EntityDataKeys.INTERPOLATION_DURATION, glide),
                Packets.value(EntityDataKeys.TRANSLATION, Vector3f(0f, value, 0f) as Vector3fc),
            ),
        )
    }

    fun setText(text: Component): Packet<in ClientGamePacketListener>? {
        if (text == sentText) return null
        sentText = text
        return Packets.data(id, listOf(Packets.value(EntityDataKeys.TEXT, PaperAdventure.asVanilla(text))))
    }

    companion object {
        private val IDENTITY: Quaternionfc = Quaternionf()
    }
}
