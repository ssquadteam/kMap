package com.github.ssquadteam.kmap.render

import com.github.ssquadteam.kmap.nms.EntityDataKeys
import com.github.ssquadteam.kmap.nms.FakeIds
import com.github.ssquadteam.kmap.nms.Packets
import com.github.ssquadteam.kmap.pack.ShaderDefines
import io.papermc.paper.adventure.PaperAdventure
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.EntityTypes
import org.joml.Quaternionf
import org.joml.Quaternionfc
import org.joml.Vector3f
import org.joml.Vector3fc

class Carrier(val surface: Surface) {
    val id: Int = FakeIds.next()
    var param: Int = 0
        private set
    private var text: Component = Component.empty()
    private var sentText: Component? = null

    private fun translation(): Vector3fc = Vector3f(1_000_000f + surface.id * 200_000f, 0f, param * 1000f)

    fun spawnPackets(x: Double, y: Double, z: Double): List<Packet<in ClientGamePacketListener>> {
        sentText = text
        val values: List<SynchedEntityData.DataValue<*>> = listOf(
            Packets.value(EntityDataKeys.TEXT, PaperAdventure.asVanilla(text)),
            Packets.value(EntityDataKeys.LINE_WIDTH, 1_000_000),
            Packets.value(EntityDataKeys.BACKGROUND, 0),
            Packets.value(EntityDataKeys.TEXT_OPACITY, (-1).toByte()),
            Packets.value(EntityDataKeys.TEXT_FLAGS, 0.toByte()),
            Packets.value(EntityDataKeys.BILLBOARD, 0.toByte()),
            Packets.value(EntityDataKeys.TRANSLATION, translation()),
            Packets.value(EntityDataKeys.SCALE, SCALE),
            Packets.value(EntityDataKeys.LEFT_ROTATION, IDENTITY),
            Packets.value(EntityDataKeys.RIGHT_ROTATION, IDENTITY),
            Packets.value(EntityDataKeys.VIEW_RANGE, 64f),
            Packets.value(EntityDataKeys.WIDTH, 0f),
            Packets.value(EntityDataKeys.HEIGHT, 0f),
            Packets.value(EntityDataKeys.BRIGHTNESS, 15 shl 4 or (15 shl 20)),
        )
        return listOf(Packets.spawn(id, EntityTypes.TEXT_DISPLAY, x, y, z), Packets.data(id, values))
    }

    fun setText(component: Component): Packet<in ClientGamePacketListener>? {
        text = component
        if (component == sentText) return null
        sentText = component
        return Packets.data(id, listOf(Packets.value(EntityDataKeys.TEXT, PaperAdventure.asVanilla(component))))
    }

    fun setParam(value: Int): Packet<in ClientGamePacketListener>? {
        if (value == param) return null
        param = value
        return Packets.data(
            id,
            listOf(
                Packets.value(EntityDataKeys.INTERPOLATION_DURATION, 0),
                Packets.value(EntityDataKeys.INTERPOLATION_START, -1),
                Packets.value(EntityDataKeys.TRANSLATION, translation()),
            ),
        )
    }

    fun initParam(value: Int) {
        param = value
    }

    companion object {
        private val SCALE: Vector3fc = Vector3f(4000f, 4000f, 4000f)
        private val IDENTITY: Quaternionfc = Quaternionf()

        fun shaderHint(id: Int, text: Component, x: Double, y: Double, z: Double): List<Packet<in ClientGamePacketListener>> {
            val values: List<SynchedEntityData.DataValue<*>> = listOf(
                Packets.value(EntityDataKeys.TEXT, PaperAdventure.asVanilla(text.color(TextColor.color(ShaderDefines.SHADER_ONLY_RGB)))),
                Packets.value(EntityDataKeys.BACKGROUND, 0),
                Packets.value(EntityDataKeys.BILLBOARD, 3.toByte()),
                Packets.value(EntityDataKeys.TRANSLATION, Vector3f(0f, -0.35f, -2f)),
                Packets.value(EntityDataKeys.SCALE, Vector3f(0.3f, 0.3f, 0.3f)),
                Packets.value(EntityDataKeys.BRIGHTNESS, 15 shl 4 or (15 shl 20)),
            )
            return listOf(Packets.spawn(id, EntityTypes.TEXT_DISPLAY, x, y, z), Packets.data(id, values))
        }
    }
}
