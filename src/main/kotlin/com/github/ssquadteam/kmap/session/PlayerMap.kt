package com.github.ssquadteam.kmap.session

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.MinimapShape
import com.github.ssquadteam.kmap.hud.BigPainter
import com.github.ssquadteam.kmap.hud.MiniPainter
import com.github.ssquadteam.kmap.markers.EntityMarkers
import com.github.ssquadteam.kmap.markers.HudMarkers
import com.github.ssquadteam.kmap.markers.WorldMarkers
import com.github.ssquadteam.kmap.nms.PacketListener
import com.github.ssquadteam.kmap.nms.Packets
import com.github.ssquadteam.kmap.render.Carrier
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.Surface
import com.github.ssquadteam.kmap.render.TileFrame
import com.github.ssquadteam.kmap.screen.ScreenSession
import com.github.ssquadteam.kmap.terrain.ChunkBitmap
import com.github.ssquadteam.kmap.terrain.MapPalette
import com.github.ssquadteam.kmap.terrain.TerrainCache
import com.github.ssquadteam.kmap.terrain.TileAssembler
import com.github.ssquadteam.kmap.terrain.TileData
import com.github.ssquadteam.kmap.terrain.TileMeta
import com.github.ssquadteam.kmap.waypoints.WaypointStore
import com.github.ssquadteam.kmap.world.WorldMap
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.network.protocol.game.ClientboundSetTimePacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket
import net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket
import net.minecraft.network.protocol.game.ServerboundSwingPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.bukkit.GameMode
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType

class PlayerMap(val plugin: KMapPlugin, val player: Player, val settings: PlayerSettings) : PacketListener {
    private class TileSlot(val key: Long, val frame: TileFrame, val originX: Int, val originZ: Int, val kind: Int, val layer: Int, val tx: Int, val tz: Int) {
        var sentVersion = -1L
        var sentMeta: ByteArray? = null
        var spawned = false
        var sigs: LongArray? = null
        var mods = -1L
        var discMod = -1L
        var missing = false
    }

    private class Want(val key: Long, val tx: Int, val tz: Int, val originX: Int, val originZ: Int)

    @Volatile
    var band = 0
        private set

    @Volatile
    var active = false
        private set

    @Volatile
    var screen: ScreenSession? = null
        private set
    private var task: ScheduledTask? = null
    private var world: WorldMap? = null
    private val carriers = LinkedHashMap<String, Carrier>()
    private val tiles = HashMap<Long, TileSlot>()
    private val freeMapIds = ArrayDeque<Int>()
    val discovered = ChunkBitmap()
    @Volatile
    private var discoveryWorld: String? = null
    @Volatile
    private var savedDiscoveryMod = 0L
    private var lastChunk = Long.MIN_VALUE
    private var lastBlock = Triple(Int.MIN_VALUE, 0, 0)
    private var ticks = 0
    private var ridersDirty = true

    @Volatile
    private var realPassengers = IntArray(0)
    private var caveLayer = Int.MIN_VALUE
    private val assembler = TileAssembler()
    private val rangeScratch = IntArray(8)
    private val lastRange = IntArray(8) { Int.MIN_VALUE }
    private var orderedAt = 0
    private var ordered: List<Want> = emptyList()
    private val sigScratch = LongArray(12 * 12)
    private var lastStreamError = -1200
    private var fogMinX = Int.MAX_VALUE
    private var fogMinZ = Int.MAX_VALUE
    private var fogMaxX = Int.MIN_VALUE
    private var fogMaxZ = Int.MIN_VALUE
    private val markers = EntityMarkers(plugin, player)
    val hudMarkers = HudMarkers(plugin, this)
    val worldMarkers = WorldMarkers(plugin, this)
    private val markerOffset = (player.entityId and 0xFFFF)
    val waypoints = WaypointStore(plugin.storage.waypointsFile(player.uniqueId), plugin.cfg.saveMarkers, plugin.files)

    @Volatile
    var trackedPin: Int? = null
        private set
    private var savedHeading = 0f
    private var screenMetaDirty = true

    private val pendingRotation = AtomicLong(Long.MIN_VALUE)
    private val rotationScheduled = AtomicBoolean(false)
    private var lastRightClick = 0L
    val sentPacks: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        for (i in 0 until MAX_TILES) freeMapIds.add(MAP_ID_BASE - i)
        waypoints.load()
    }

    fun miniParam(): Int = Codes.miniParam(settings.corner.ordinal, settings.shape == MinimapShape.CIRCLE, settings.zoom, settings.coords)

    fun worldMap(): WorldMap? = world

    fun savedHeading(): Float = savedHeading

    fun start() {
        if (active) return
        active = true
        enterWorld()
    }

    fun stop() {
        if (screen != null) closeScreen()
        active = false
        task?.cancel()
        task = null
        despawnAll()
    }

    private val own = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Packet<*>, Boolean>()))

    fun send(packet: Packet<in ClientGamePacketListener>) {
        if (screen != null) {
            own.add(packet)
            if (packet is ClientboundBundlePacket) packet.subPackets().forEach { own.add(it) }
        }
        (player as CraftPlayer).handle.connection.send(packet)
    }

    fun clearOwn() = own.clear()

    fun sendAll(packets: List<Packet<in ClientGamePacketListener>>) {
        if (packets.isEmpty()) return
        send(ClientboundBundlePacket(packets))
    }

    private fun despawnAll() {
        val out = ArrayList<Packet<in ClientGamePacketListener>>()
        markers.clear(out)
        hudMarkers.clear(out)
        worldMarkers.clear(out)
        sendAll(out)
        val ids = carriers.values.map { it.id } + tiles.values.flatMap { it.frame.ids.toList() }
        carriers.clear()
        for (t in tiles.values) freeMapIds.add(t.frame.mapId)
        tiles.clear()
        lastRange[0] = Int.MIN_VALUE
        if (ids.isNotEmpty()) send(Packets.remove(*ids.toIntArray()))
        ridersDirty = true
    }

    fun enterWorld() {
        if (screen != null) closeScreen()
        if (band != 0) setBand(0)
        despawnAll()
        world = plugin.worlds.of(player.world)
        discovered.clear()
        plugin.storage.loadDiscovery(player, player.world, discovered)
        discoveryWorld = player.world.name
        savedDiscoveryMod = discovered.modCount
        lastChunk = Long.MIN_VALUE
        lastBlock = Triple(Int.MIN_VALUE, 0, 0)
        caveLayer = Int.MIN_VALUE
        plugin.bakes.onEnterWorld(this)
        spawnHud()
        pushTime()
        task?.cancel()
        task = player.scheduler.runAtFixedRate(plugin, { tick() }, null, 1L, 1L)
    }

    fun carrier(name: String, surface: Surface, param: Int): Carrier {
        val existing = carriers[name]
        if (existing != null) return existing
        val c = Carrier(surface)
        c.initParam(param)
        carriers[name] = c
        val loc = player.location
        sendAll(c.spawnPackets(loc.x, loc.y + 1.8, loc.z))
        ridersDirty = true
        return c
    }

    fun removeCarrier(name: String) {
        val c = carriers.remove(name) ?: return
        send(Packets.remove(c.id))
        ridersDirty = true
    }

    private fun spawnHud() {
        refreshHud()
    }

    fun refreshHud() {
        val glyphs = plugin.packs.glyphs
        val size = plugin.cfg.minimapSize
        val baked = world?.bakes ?: emptyList()
        if (settings.module.big) {
            val big = carrier("big", Surface.BIG, miniParam())
            big.setParam(miniParam())?.let { send(it) }
            big.setText(BigPainter.frame(glyphs, plugin.cfg.bigMapSize, baked))?.let { send(it) }
        } else {
            removeCarrier("big")
            if (band == 1) setBand(0)
        }
        if (settings.module.minimap && settings.minimap) {
            val mini = carrier("mini", Surface.MINI, miniParam())
            mini.setParam(miniParam())?.let { send(it) }
            mini.setText(MiniPainter.frame(glyphs, settings.shape, size, baked))?.let { send(it) }
            carrier("plate", Surface.MINI, miniParam()).setParam(miniParam())?.let { send(it) }
        } else {
            removeCarrier("mini")
            removeCarrier("plate")
        }
        lastBlock = Triple(Int.MIN_VALUE, 0, 0)
        for (t in tiles.values) t.sentMeta = null
        screenMetaDirty = true
        hudMarkers.markDirty()
    }

    fun onSettingsChanged() {
        refreshHud()
        plugin.storage.saveSettings(player, settings)
        screen?.let {
            it.updateSensitivity()
            it.refreshCursor()
            it.invalidateUi()
            it.invalidateMap()
            it.render()
        }
    }

    fun onWaypointsChanged() {
        hudMarkers.markDirty()
        worldMarkers.markDirty()
    }

    fun setTracked(id: UUID?) {
        for (w in waypoints.all) w.tracked = w.id == id
        if (id != null) trackedPin = null
        waypoints.save()
        onWaypointsChanged()
    }

    fun trackPin(index: Int?) {
        trackedPin = index
        if (index != null) for (w in waypoints.all) w.tracked = false
        waypoints.save()
        onWaypointsChanged()
    }

    fun toggle() {
        val m = settings.module
        when {
            m.big -> setBand(if (band == 1) 0 else 1)
            m.screen -> if (screen != null) closeScreen() else openScreen()
        }
    }

    fun toggleFromCommand() {
        player.scheduler.run(plugin, {
            if (player.openInventory.topInventory.type != InventoryType.CRAFTING) player.closeInventory()
            if (!active) start()
            toggle()
        }, null)
    }

    fun openScreen() {
        if (screen != null || !active) return
        savedHeading = player.location.yaw
        val s = ScreenSession(plugin, this)
        screen = s
        s.open()
    }

    fun closeScreen() {
        val s = screen ?: return
        screen = null
        s.close()
    }

    fun onScreenChanged() {
        screenMetaDirty = true
    }

    fun setBand(value: Int) {
        if (band == value) return
        band = value
        pushTime()
    }

    private fun pushTime() {
        send(ClientboundSetTimePacket(player.world.gameTime, emptyMap()))
    }

    private fun virtualTime(real: Long): Long = band * 6000L + Math.floorMod(real, 5000L)

    fun heightAt(x: Int, z: Int): Int? {
        val s = world?.cache?.get(x shr 4, z shr 4) ?: return null
        val h = s.heights[(z and 15) * 16 + (x and 15)].toInt()
        return if (h == Short.MIN_VALUE.toInt()) null else h
    }

    private fun onEntity(block: () -> Unit) {
        player.scheduler.run(plugin, { block() }, null)
    }

    override fun inbound(packet: Packet<*>): Packet<*>? {
        when (packet) {
            is ServerboundSeenAdvancementsPacket -> {
                if (packet.action == ServerboundSeenAdvancementsPacket.Action.OPENED_TAB && plugin.binds.onAdvancementsOpened(player)) return null
            }
            is ServerboundRenameItemPacket -> {
                if (plugin.textInput.isOpen(player)) {
                    val name = packet.name
                    onEntity { screen?.onText(name) }
                }
            }
        }
        if (screen == null) return packet
        when (packet) {
            is ServerboundMovePlayerPacket -> {
                if (!packet.hasRotation()) return packet
                val yaw = packet.getYRot(0f)
                val pitch = packet.getXRot(0f)
                pendingRotation.set((yaw.toRawBits().toLong() shl 32) or (pitch.toRawBits().toLong() and 0xFFFFFFFFL))
                if (rotationScheduled.compareAndSet(false, true)) {
                    onEntity {
                        rotationScheduled.set(false)
                        val v = pendingRotation.get()
                        screen?.onRotate(Float.fromBits((v shr 32).toInt()), Float.fromBits(v.toInt()))
                    }
                }
                if (!packet.hasPosition()) return null
                return ServerboundMovePlayerPacket.Pos(Vec3(packet.getX(0.0), packet.getY(0.0), packet.getZ(0.0)), packet.isOnGround, packet.horizontalCollision())
            }
            is ServerboundPlayerActionPacket -> {
                when (packet.action) {
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK -> onEntity { screen?.onLeftDown() }
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK -> onEntity { screen?.onLeftUp() }
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND -> onEntity { closeScreen() }
                    else -> {}
                }
                if (packet.sequence > 0) send(ClientboundBlockChangedAckPacket(packet.sequence))
                return null
            }
            is ServerboundUseItemOnPacket -> {
                send(ClientboundBlockChangedAckPacket(packet.sequence))
                rightClick()
                return null
            }
            is ServerboundUseItemPacket -> {
                send(ClientboundBlockChangedAckPacket(packet.sequence))
                rightClick()
                return null
            }
            is ServerboundSwingPacket, is ServerboundInteractPacket -> return null
            is ServerboundSetCarriedItemPacket -> {
                val delta = when (Math.floorMod(packet.slot - 4, 9)) {
                    1 -> 1
                    8 -> -1
                    else -> 0
                }
                send(ClientboundSetHeldSlotPacket(4))
                if (delta != 0) onEntity { screen?.onScroll(delta) }
                return null
            }
            is ServerboundContainerClickPacket -> {
                if (packet.containerId == 0) onEntity { closeScreen() }
            }
        }
        return packet
    }

    private fun rightClick() {
        val now = System.currentTimeMillis()
        val repeat = now - lastRightClick < 180
        lastRightClick = now
        if (!repeat) onEntity { screen?.onRightClick() }
    }

    override fun outbound(packet: Packet<*>): Packet<*>? {
        when (packet) {
            is ClientboundSetTimePacket -> return ClientboundSetTimePacket(virtualTime(packet.gameTime), packet.clockUpdates)
            is ClientboundSetPassengersPacket -> {
                if (packet.vehicle == player.entityId) {
                    val ours = riderIds()
                    val real = packet.passengers.filter { it !in ours }.toIntArray()
                    realPassengers = real
                    if (ours.isEmpty()) return packet
                    return Packets.passengers(packet.vehicle, real + ours.toIntArray())
                }
            }
            is ClientboundBundlePacket -> {
                var changed = false
                val list = packet.subPackets().map { p ->
                    val o = outbound(p)
                    if (o !== p) changed = true
                    o
                }
                if (changed) {
                    @Suppress("UNCHECKED_CAST")
                    return ClientboundBundlePacket(list.filterNotNull() as List<Packet<in ClientGamePacketListener>>)
                }
                return packet
            }
        }
        val s = screen ?: return packet
        if (own.remove(packet)) return packet
        when (packet) {
            is ClientboundContainerSetSlotPacket -> {
                if (packet.containerId == 0 && packet.slot in 36..45) {
                    return ClientboundContainerSetSlotPacket(0, packet.stateId, packet.slot, if (packet.slot == 45) ItemStack.EMPTY else s.fakeItem())
                }
                if (packet.containerId != 0 && packet.slot in 30..38) {
                    return ClientboundContainerSetSlotPacket(packet.containerId, packet.stateId, packet.slot, s.fakeItem())
                }
            }
            is ClientboundContainerSetContentPacket -> {
                if (packet.containerId == 0 && packet.items.size > 45) {
                    val items = ArrayList(packet.items)
                    for (i in 36..44) items[i] = s.fakeItem()
                    items[45] = ItemStack.EMPTY
                    return ClientboundContainerSetContentPacket(0, packet.stateId, items, packet.carriedItem)
                }
                if (packet.containerId != 0 && packet.items.size >= 9) {
                    val items = ArrayList(packet.items)
                    for (i in items.size - 9 until items.size) items[i] = s.fakeItem()
                    return ClientboundContainerSetContentPacket(packet.containerId, packet.stateId, items, packet.carriedItem)
                }
            }
            is ClientboundSetHeldSlotPacket -> return ClientboundSetHeldSlotPacket(4)
            is ClientboundBlockUpdatePacket -> {
                if (s.isShell(packet.pos) && packet.blockState.getCollisionShape((player as CraftPlayer).handle.level(), packet.pos).isEmpty) {
                    return ClientboundBlockUpdatePacket(packet.pos, Blocks.BARRIER.defaultBlockState())
                }
            }
            is ClientboundGameEventPacket -> {
                if (packet.event == ClientboundGameEventPacket.CHANGE_GAME_MODE) {
                    val mode = when (packet.param.toInt()) {
                        1 -> GameMode.CREATIVE
                        2 -> GameMode.ADVENTURE
                        3 -> GameMode.SPECTATOR
                        else -> GameMode.SURVIVAL
                    }
                    s.onRealGameMode(mode)
                    return null
                }
            }
            is ClientboundPlayerAbilitiesPacket -> {
                if (packet.canInstabuild()) return s.abilities()
            }
        }
        return packet
    }

    fun markRidersDirty() {
        ridersDirty = true
    }

    private fun riderIds(): Set<Int> = HashSet<Int>().apply {
        runCatching {
            carriers.values.toList().forEach { add(it.id) }
            tiles.values.toList().filter { it.spawned }.forEach { add(it.frame.id); add(it.frame.id2) }
        }
    }

    private fun syncRiders() {
        if (!ridersDirty) return
        ridersDirty = false
        send(Packets.passengers(player.entityId, realPassengers + riderIds().toIntArray()))
    }

    private fun tick() {
        if (!active || !player.isOnline) return
        ticks++
        val handle = (player as CraftPlayer).handle
        val bx = handle.blockX
        val by = handle.blockY
        val bz = handle.blockZ
        if (bx != lastBlock.first || by != lastBlock.second || bz != lastBlock.third) {
            lastBlock = Triple(bx, by, bz)
            carriers["plate"]?.setText(MiniPainter.plate(plugin.packs.glyphs, plugin.cfg.minimapSize, bx, by, bz, settings.coords))?.let { send(it) }
        }
        val chunkKey = TerrainCache.key(bx shr 4, bz shr 4)
        if (chunkKey != lastChunk) {
            lastChunk = chunkKey
            discover(bx shr 4, bz shr 4)
            hudMarkers.markDirty()
        }
        if (ticks % plugin.cfg.flushIntervalTicks == 0) {
            try {
                stream(bx, by, bz)
            } catch (t: Throwable) {
                if (ticks - lastStreamError > 1200) {
                    lastStreamError = ticks
                    plugin.logger.log(Level.WARNING, "Map streaming failed for ${player.name}", t)
                }
            }
        }
        val out = ArrayList<Packet<in ClientGamePacketListener>>()
        if ((ticks + markerOffset) % plugin.cfg.entities.updateTicks == 0 && band <= 1) {
            markers.update(miniParam(), settings.showPlayers, settings.showMobs, settings.module.big, out)
        }
        if (hudMarkers.dirty) hudMarkers.update(out)
        if (ticks % 2 == 0) worldMarkers.update(out, ticks)
        sendAll(out)
        screen?.tick()
        syncRiders()
    }

    private fun discover(cx: Int, cz: Int) {
        val r = plugin.worldsConfig.entry(player.world.name)?.discoverRadiusChunks ?: plugin.cfg.discoverRadiusChunks
        for (dx in -r..r) for (dz in -r..r) {
            if (dx * dx + dz * dz <= r * r + r && discovered.add(cx + dx, cz + dz)) {
                if (cx + dx < fogMinX) fogMinX = cx + dx
                if (cx + dx > fogMaxX) fogMaxX = cx + dx
                if (cz + dz < fogMinZ) fogMinZ = cz + dz
                if (cz + dz > fogMaxZ) fogMaxZ = cz + dz
            }
        }
    }

    fun discoveryWorld(): String? = discoveryWorld

    fun discoveryDirty(): Boolean {
        val m = discovered.modCount
        if (m == savedDiscoveryMod) return false
        savedDiscoveryMod = m
        return true
    }

    private fun wantedLayer(by: Int): Int {
        val wm = world ?: return Int.MIN_VALUE
        if (!wm.mode.caves || wm.ceiling != null) return Int.MIN_VALUE
        val handle = (player as CraftPlayer).handle
        val x = handle.blockX
        val z = handle.blockZ
        val surface = wm.cache.get(x shr 4, z shr 4) ?: return Int.MIN_VALUE
        val top = surface.heights[(z and 15) * 16 + (x and 15)].toInt()
        if (by >= top - 6) return Int.MIN_VALUE
        val sky = handle.level().getBrightness(LightLayer.SKY, handle.blockPosition())
        return if (by < top - 6 && sky == 0) Math.floorDiv(by + 2, plugin.cfg.caveLayerHeight) else Int.MIN_VALUE
    }

    private fun screenMeta(): Triple<Int, Int, Int> {
        val s = screen ?: return Triple(0, 0, settings.screenZoom)
        val (px, pz) = s.panForTiles()
        val drag = s.dragState().first
        return Triple(px, pz, (s.zoom and 15) or (if (drag) 16 else 0))
    }

    private fun sendTile(slot: TileSlot, data: ByteArray, meta: TileMeta, version: Long = 0L) {
        TileData.writeMeta(data, meta)
        slot.sentMeta = TileData.meta(meta)
        slot.sentVersion = version
        val packets = ArrayList<Packet<in ClientGamePacketListener>>()
        packets.add(slot.frame.full(data))
        if (!slot.spawned) {
            val loc = player.location
            packets.addAll(slot.frame.spawnPackets(loc.x, loc.y + 1.8, loc.z))
            slot.spawned = true
            ridersDirty = true
        }
        sendAll(packets)
    }

    private fun refreshMeta(slot: TileSlot, meta: TileMeta, force: Boolean) {
        if (!force && slot.sentMeta != null) return
        val m = TileData.meta(meta)
        if (!m.contentEquals(slot.sentMeta)) {
            slot.sentMeta = m
            send(slot.frame.patch(0, 0, TileData.META_WIDTH, 1, m))
        }
    }

    private fun stream(bx: Int, by: Int, bz: Int) {
        val wm = world ?: return
        val cfg = plugin.cfg
        val cache = wm.cache
        val cx = bx shr 4
        val cz = bz shr 4
        val layer = wantedLayer(by)
        if (layer != caveLayer) {
            caveLayer = layer
            dropAllTiles()
        }
        val sr = cfg.streamRadiusChunks.coerceAtMost(player.viewDistance + 1)
        for (dx in -sr..sr) for (dz in -sr..sr) {
            if (dx * dx + dz * dz > sr * sr + sr) continue
            if (!discovered.contains(cx + dx, cz + dz)) continue
            if (layer == Int.MIN_VALUE) {
                cache.request(cx + dx, cz + dz)
            } else if (dx * dx + dz * dz <= cfg.caveComputeRadiusChunks * cfg.caveComputeRadiusChunks) {
                cache.requestSlice(cx + dx, cz + dz, layer)
            }
        }
        val s = screen
        val flags = TileData.FLAG_MINI or TileData.FLAG_BIG or (if (s != null) TileData.FLAG_SCREEN else 0)
        val (panX, panZ, screenByte) = screenMeta()
        val metaForce = screenMetaDirty
        screenMetaDirty = false
        if (wm.bake != null) {
            if (wm.mode.discovery) streamFog(flags, panX, panZ, screenByte, metaForce)
            return
        }
        val kind = if (wm.rgb) TileData.RGB else TileData.PALETTE
        val wide = if (kind == TileData.RGB) 64 else 128
        val radius = tileRadiusBlocks()
        val range = rangeScratch
        range[0] = TileAssembler.tileX(bx - radius, wide)
        range[1] = TileAssembler.tileX(bx + radius, wide)
        range[2] = TileAssembler.tileZ(bz - radius)
        range[3] = TileAssembler.tileZ(bz + radius)
        if (s != null) {
            val hw = (320 / s.scale).toInt() + 16
            val hh = (180 / s.scale).toInt() + 16
            val r = cfg.screenPanRadiusBlocks + 64
            range[4] = TileAssembler.tileX(maxOf(panX - hw, bx - r), wide)
            range[5] = TileAssembler.tileX(minOf(panX + hw, bx + r), wide)
            range[6] = TileAssembler.tileZ(maxOf(panZ - hh, bz - r))
            range[7] = TileAssembler.tileZ(minOf(panZ + hh, bz + r))
        } else {
            range[4] = 0
            range[5] = -1
            range[6] = 0
            range[7] = -1
        }
        if (!range.contentEquals(lastRange) || ticks - orderedAt >= 40) {
            range.copyInto(lastRange)
            orderedAt = ticks
            val want = HashMap<Long, Want>()
            fun addRange(tx0: Int, tx1: Int, tz0: Int, tz1: Int) {
                for (tx in tx0..tx1) for (tz in tz0..tz1) {
                    val k = TerrainCache.key(tx, tz)
                    if (k !in want) want[k] = Want(k, tx, tz, tx * wide, tz * TileData.STRIDE_Z)
                }
            }
            addRange(range[0], range[1], range[2], range[3])
            addRange(range[4], range[5], range[6], range[7])
            val cxw = if (s != null) panX else bx
            val czw = if (s != null) panZ else bz
            ordered = want.values.sortedBy { w ->
                val ox = (w.originX + wide / 2 - cxw).toLong()
                val oz = (w.originZ + 64 - czw).toLong()
                ox * ox + oz * oz
            }.take(MAX_TILES)
            val keep = ordered.mapTo(HashSet()) { it.key }
            val drop = tiles.keys.filter { it !in keep }
            if (drop.isNotEmpty()) {
                val ids = drop.flatMap { k -> tiles.remove(k)?.also { freeMapIds.add(it.frame.mapId) }?.frame?.ids?.toList() ?: emptyList() }
                send(Packets.remove(*ids.toIntArray()))
                ridersDirty = true
            }
        }
        var budget = cfg.tilesPerFlush * (if (s != null) 2 else 1)
        val discMod = discovered.modCount
        for (w in ordered) {
            var slot = tiles[w.key]
            if (slot == null) {
                val id = freeMapIds.removeFirstOrNull() ?: continue
                slot = TileSlot(w.key, TileFrame(id), w.originX, w.originZ, kind, layer, w.tx, w.tz)
                tiles[w.key] = slot
            }
            val mods = cache.tileMod(wide, slot.tx, slot.tz)
            if (slot.sigs != null && mods == slot.mods && discMod == slot.discMod && !(slot.missing && (ticks + slot.tx + slot.tz) % 20 == 0)) {
                if (metaForce) refreshMeta(slot, TileMeta(kind, slot.originX, slot.originZ, flags, miniParam(), panX, panZ, screenByte, Codes.sensParam(settings.sensitivity)), true)
                continue
            }
            val cx0 = (slot.originX shr 4) - 1
            val cx1 = ((slot.originX + wide - 1) shr 4) + 1
            val cz0 = (slot.originZ shr 4) - 1
            val cz1 = ((slot.originZ + 127) shr 4) + 1
            val nx = cx1 - cx0 + 1
            val nz = cz1 - cz0 + 1
            val prev = slot.sigs
            val sig = sigScratch
            var minX = Int.MAX_VALUE
            var minZ = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxZ = Int.MIN_VALUE
            var missing = false
            for (z in 0 until nz) {
                for (x in 0 until nx) {
                    val wx = cx0 + x
                    val wz = cz0 + z
                    val i = z * nx + x
                    val old = prev?.get(i) ?: -1L
                    val v = if (!discovered.contains(wx, wz)) {
                        0L
                    } else {
                        val su = if (layer == Int.MIN_VALUE) cache.get(wx, wz) else cache.slice(wx, wz, layer)
                        if (su != null) {
                            su.version * 2 + 2
                        } else {
                            if (layer == Int.MIN_VALUE) cache.request(wx, wz)
                            missing = true
                            if (old >= 2) old else 1L
                        }
                    }
                    sig[i] = v
                    if (v != old) {
                        if (wx < minX) minX = wx
                        if (wx > maxX) maxX = wx
                        if (wz < minZ) minZ = wz
                        if (wz > maxZ) maxZ = wz
                    }
                }
            }
            val n = nx * nz
            slot.missing = missing
            if (minX == Int.MAX_VALUE) {
                slot.mods = mods
                slot.discMod = discMod
                if (metaForce || slot.sentMeta == null) refreshMeta(slot, TileMeta(kind, slot.originX, slot.originZ, flags, miniParam(), panX, panZ, screenByte, Codes.sensParam(settings.sensitivity)), metaForce)
                continue
            }
            if (budget <= 0) continue
            budget--
            val source = if (layer == Int.MIN_VALUE) TileAssembler.Source { x, z -> cache.get(x, z) } else TileAssembler.Source { x, z -> cache.slice(x, z, layer) }
            val known = TileAssembler.Discovered { x, z -> discovered.contains(x, z) }
            val scale = if (kind == TileData.RGB) 2 else 1
            var x0 = ((minX shl 4) - 1 - slot.originX).coerceAtLeast(0) * scale
            var x1 = (((maxX shl 4) + 17 - slot.originX) * scale + scale - 1).coerceAtMost(127)
            val y0 = ((minZ shl 4) - 1 - slot.originZ).coerceAtLeast(0)
            val y1 = ((maxZ shl 4) + 17 - slot.originZ).coerceAtMost(127)
            if (scale == 2) {
                x0 = x0 and 1.inv()
                x1 = x1 or 1
            }
            val w = x1 - x0 + 1
            val h = y1 - y0 + 1
            val sentMeta = slot.sentMeta
            if (prev == null || sentMeta == null || w <= 0 || h <= 0 || w * h > 128 * 128 * 3 / 5) {
                val data = if (kind == TileData.RGB) assembler.rgb(slot.originX, slot.originZ, source, known, wm.brightness) else assembler.palette(slot.originX, slot.originZ, source, known)
                sendTile(slot, data, TileMeta(kind, slot.originX, slot.originZ, flags, miniParam(), panX, panZ, screenByte, Codes.sensParam(settings.sensitivity)))
            } else {
                val data = if (kind == TileData.RGB) assembler.rgb(slot.originX, slot.originZ, source, known, wm.brightness, x0, y0, w, h) else assembler.palette(slot.originX, slot.originZ, source, known, x0, y0, w, h)
                if (y0 == 0) for (c in x0 until minOf(x0 + w, TileData.META_WIDTH)) data[c - x0] = sentMeta[c]
                send(slot.frame.patch(x0, y0, w, h, data))
            }
            slot.sigs = if (prev != null && prev.size == n) sig.copyInto(prev, 0, 0, n) else sig.copyOf(n)
            slot.mods = mods
            slot.discMod = discMod
        }
    }

    private fun streamFog(flags: Int, panX: Int, panZ: Int, screenByte: Int, metaForce: Boolean) {
        val bake = world?.bake ?: return
        val originCx = Math.floorDiv(bake.originX, 16)
        val originCz = Math.floorDiv(bake.originZ, 16)
        val wChunks = ((bake.widthPx * bake.blocksPerPx).toInt() + 15) / 16 + 1
        val hChunks = ((bake.heightPx * bake.blocksPerPx).toInt() + 15) / 16 + 1
        var budget = 2
        for (fx in 0 until (wChunks + 127) / 128) {
            for (fz in 0 until (hChunks + 126) / 127) {
                val key = TerrainCache.key(1_000_000 + fx, 1_000_000 + fz)
                val ocx = originCx + fx * 128
                val ocz = originCz + fz * 127
                var slot = tiles[key]
                if (slot == null) {
                    val id = freeMapIds.removeFirstOrNull() ?: continue
                    slot = TileSlot(key, TileFrame(id), ocx * 16, ocz * 16, TileData.FOG, Int.MIN_VALUE, fx, fz)
                    tiles[key] = slot
                }
                val meta = TileMeta(TileData.FOG, slot.originX, slot.originZ, flags, miniParam(), panX, panZ, screenByte, Codes.sensParam(settings.sensitivity))
                val sentMeta = slot.sentMeta
                if (slot.sentVersion < 0 || sentMeta == null) {
                    if (budget-- <= 0) continue
                    val data = ByteArray(128 * 128)
                    for (r in 0 until 128) {
                        for (c in 0 until 128) {
                            if (!discovered.contains(ocx + c, ocz + r)) data[r * 128 + c] = MapPalette.UNKNOWN
                        }
                    }
                    sendTile(slot, data, meta, 1L)
                } else {
                    if (fogMinX != Int.MAX_VALUE) {
                        val x0 = (fogMinX - ocx).coerceAtLeast(0)
                        val x1 = (fogMaxX - ocx).coerceAtMost(127)
                        val y0 = (fogMinZ - ocz).coerceAtLeast(0)
                        val y1 = (fogMaxZ - ocz).coerceAtMost(127)
                        if (x0 <= x1 && y0 <= y1) {
                            val w = x1 - x0 + 1
                            val h = y1 - y0 + 1
                            val data = ByteArray(w * h)
                            for (r in 0 until h) for (c in 0 until w) {
                                if (!discovered.contains(ocx + x0 + c, ocz + y0 + r)) data[r * w + c] = MapPalette.UNKNOWN
                            }
                            if (y0 == 0) for (c in x0 until minOf(x0 + w, TileData.META_WIDTH)) data[c - x0] = sentMeta[c]
                            send(slot.frame.patch(x0, y0, w, h, data))
                        }
                    }
                    refreshMeta(slot, meta, metaForce)
                }
            }
        }
        fogMinX = Int.MAX_VALUE
        fogMinZ = Int.MAX_VALUE
        fogMaxX = Int.MIN_VALUE
        fogMaxZ = Int.MIN_VALUE
    }

    private fun tileRadiusBlocks(): Int {
        val cfg = plugin.cfg
        val mini = (cfg.minimapBlocks / 0.5 * 0.75).toInt()
        val big = if (settings.module.big) (cfg.bigMapBlocks / 0.5 * 0.75).toInt().coerceAtMost(cfg.streamRadiusChunks * 16) else 0
        return maxOf(mini, big, 64)
    }

    private fun dropAllTiles() {
        lastRange[0] = Int.MIN_VALUE
        if (tiles.isEmpty()) return
        val ids = tiles.values.flatMap { freeMapIds.add(it.frame.mapId); it.frame.ids.toList() }
        tiles.clear()
        send(Packets.remove(*ids.toIntArray()))
        ridersDirty = true
    }

    fun debug(): String = (screen?.debug() ?: "") + " active=$active band=$band tiles=${tiles.size} carriers=${carriers.size} discovered=${discovered.size} cache=${world?.cache?.size()} mode=${world?.mode} layer=$caveLayer screen=${screen != null}"

    companion object {
        const val MAP_ID_BASE = 2_000_000_000
        const val MAX_TILES = 96
    }
}
