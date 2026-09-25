package com.github.ssquadteam.kmap.render

import com.github.ssquadteam.kmap.nms.EntityDataKeys
import com.github.ssquadteam.kmap.nms.FakeIds
import com.github.ssquadteam.kmap.nms.Packets
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import java.util.Optional

class TileFrame(val mapId: Int) {
    val id: Int = FakeIds.next()
    val id2: Int = FakeIds.next()
    val ids: IntArray get() = intArrayOf(id, id2)

    fun spawnPackets(x: Double, y: Double, z: Double): List<Packet<in ClientGamePacketListener>> {
        val stack = ItemStack(Items.FILLED_MAP)
        stack.set(DataComponents.MAP_ID, MapId(mapId))
        return listOf(
            Packets.spawn(id, EntityTypes.ITEM_FRAME, x, y, z, data = 1),
            Packets.data(id, listOf(Packets.value(EntityDataKeys.FRAME_ITEM, stack), Packets.value(EntityDataKeys.SHARED_FLAGS, 0x20.toByte()))),
            Packets.spawn(id2, EntityTypes.ITEM_FRAME, x, y, z, data = 0),
            Packets.data(id2, listOf(Packets.value(EntityDataKeys.FRAME_ITEM, stack.copy()), Packets.value(EntityDataKeys.SHARED_FLAGS, 0x20.toByte()))),
        )
    }

    fun full(colors: ByteArray): ClientboundMapItemDataPacket = patch(0, 0, 128, 128, colors)

    fun patch(x: Int, y: Int, w: Int, h: Int, colors: ByteArray): ClientboundMapItemDataPacket =
        ClientboundMapItemDataPacket(MapId(mapId), 0, true, Optional.empty(), Optional.of(MapItemSavedData.MapPatch(x, y, w, h, colors)))
}
