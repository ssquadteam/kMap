package com.github.ssquadteam.kmap.nms

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import net.minecraft.network.protocol.Packet
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player

interface PacketListener {
    fun inbound(packet: Packet<*>): Packet<*>?

    fun outbound(packet: Packet<*>): Packet<*>?
}

object PacketInterceptor {
    private const val NAME = "kmap_packets"

    fun inject(player: Player, listener: PacketListener) {
        val channel = (player as CraftPlayer).handle.connection.connection.channel ?: return
        channel.eventLoop().execute {
            val pipeline = channel.pipeline()
            if (pipeline.get(NAME) != null) pipeline.remove(NAME)
            if (pipeline.get("packet_handler") == null) return@execute
            pipeline.addBefore("packet_handler", NAME, Handler(listener))
        }
    }

    fun eject(player: Player) {
        val channel = (player as CraftPlayer).handle.connection.connection.channel ?: return
        channel.eventLoop().execute {
            if (channel.pipeline().get(NAME) != null) channel.pipeline().remove(NAME)
        }
    }

    private class Handler(private val listener: PacketListener) : ChannelDuplexHandler() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is Packet<*>) {
                val out = try {
                    listener.inbound(msg)
                } catch (t: Throwable) {
                    t.printStackTrace()
                    msg
                }
                if (out == null) return
                super.channelRead(ctx, out)
                return
            }
            super.channelRead(ctx, msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is Packet<*>) {
                val out = try {
                    listener.outbound(msg)
                } catch (t: Throwable) {
                    t.printStackTrace()
                    msg
                }
                if (out == null) {
                    promise.setSuccess()
                    return
                }
                super.write(ctx, out, promise)
                return
            }
            super.write(ctx, msg, promise)
        }
    }
}
