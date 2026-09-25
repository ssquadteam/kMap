package com.github.ssquadteam.kmap.nms

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.EntityType
import net.minecraft.world.phys.Vec3
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import java.util.UUID

object Packets {
    fun send(player: Player, packet: Packet<*>) {
        (player as CraftPlayer).handle.connection.send(packet)
    }

    fun bundle(packets: List<Packet<in ClientGamePacketListener>>): ClientboundBundlePacket = ClientboundBundlePacket(packets)

    fun spawn(id: Int, type: EntityType<*>, x: Double, y: Double, z: Double, yaw: Float = 0f, pitch: Float = 0f, data: Int = 0): ClientboundAddEntityPacket =
        ClientboundAddEntityPacket(id, UUID.randomUUID(), x, y, z, pitch, yaw, type, data, Vec3.ZERO, yaw.toDouble())

    fun data(id: Int, values: List<SynchedEntityData.DataValue<*>>): ClientboundSetEntityDataPacket = ClientboundSetEntityDataPacket(id, values)

    fun <T : Any> value(key: EntityDataAccessor<T>, value: T): SynchedEntityData.DataValue<T> = SynchedEntityData.DataValue.create(key, value)

    fun passengers(vehicle: Int, riders: IntArray): ClientboundSetPassengersPacket {
        val buf = FriendlyByteBuf(Unpooled.buffer())
        buf.writeVarInt(vehicle)
        buf.writeVarIntArray(riders)
        return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf)
    }

    fun remove(vararg ids: Int): ClientboundRemoveEntitiesPacket = ClientboundRemoveEntitiesPacket(*ids)
}
