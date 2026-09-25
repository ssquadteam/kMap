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
import com.github.ssquadteam.kmap.terrain.MapPalette
import com.github.ssquadteam.kmap.terrain.TerrainCache
import com.github.ssquadteam.kmap.terrain.TileAssembler
import com.github.ssquadteam.kmap.terrain.TileData
import com.github.ssquadteam.kmap.terrain.TileMeta
import com.github.ssquadteam.kmap.waypoints.WaypointStore
import com.github.ssquadteam.kmap.world.WorldMap
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
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
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.bukkit.GameMode
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PlayerMap(val plugin: KMapPlugin, val player: Player, val settings: PlayerSettings) : PacketListener {
    private class TileSlot(val key: Long, val frame: TileFrame, val originX: Int, val originZ: Int, val kind: Int, val layer: Int) {
        var sentVersion = -1L
        var sentMeta: ByteArray? = null
        var spawned = false
    }

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
    private val discovered = ConcurrentHashMap.newKeySet<Long>()
    private var lastChunk = Long.MIN_VALUE
    private var lastBlock = Triple(Int.MIN_VALUE, 0, 0)
    private var ticks = 0
    private var ridersDirty = true

    @Volatile
    private var realPassengers = IntArray(0)
    private var caveLayer = Int.MIN_VALUE
    private val assembler = TileAssembler()
    private val markers = EntityMarkers(plugin, player)
    val hudMarkers = HudMarkers(plugin, this)
    val worldMarkers = WorldMarkers(plugin, this)
    private val markerOffset = (player.entityId and 0xFFFF)
    val waypoints = WaypointStore(File(plugin.dataFolder, "data/players/${player.uniqueId}/waypoints.yml"), plugin.cfg.saveMarkers)

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

    private val own = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Packet<*>, Boolean>()))

    fun send(packet: Packet<in ClientGamePacketListener>) {
        if (screen != null) {
            own.add(packet)
            if (packet is ClientboundBundlePacket) packet.subPackets().forEach { own.add(it) }
        }
        (player as CraftPlayer).handle.connection.send(packet)
    }

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
        if (ids.isNotEmpty()) send(Packets.remove(*ids.toIntArray()))
        ridersDirty = true
    }

    fun enterWorld() {
        if (screen != null) closeScreen()
        if (band != 0) setBand(0)
        despawnAll()
        world = plugin.worlds.of(player.world)
        discovered.clear()
        plugin.storage.loadDiscovery(player, player.world)?.let { discovered.addAll(it) }
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
            if (player.openInventory.topInventory.type != org.bukkit.event.inventory.InventoryType.CRAFTING) player.closeInventory()
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
                pendingRotation.set((java.lang.Float.floatToIntBits(yaw).toLong() shl 32) or (java.lang.Float.floatToIntBits(pitch).toLong() and 0xFFFFFFFFL))
                if (rotationScheduled.compareAndSet(false, true)) {
                    onEntity {
                        rotationScheduled.set(false)
                        val v = pendingRotation.get()
                        screen?.onRotate(java.lang.Float.intBitsToFloat((v shr 32).toInt()), java.lang.Float.intBitsToFloat(v.toInt()))
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
        if (own.remove(packet)) return packet
        val s = screen ?: return packet
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
        val loc = player.location
        val bx = loc.blockX
        val by = loc.blockY
        val bz = loc.blockZ
        if (Triple(bx, by, bz) != lastBlock) {
            lastBlock = Triple(bx, by, bz)
            carriers["plate"]?.setText(MiniPainter.plate(plugin.packs.glyphs, plugin.cfg.minimapSize, bx, by, bz, settings.coords))?.let { send(it) }
        }
        val chunkKey = TerrainCache.key(bx shr 4, bz shr 4)
        if (chunkKey != lastChunk) {
            lastChunk = chunkKey
            discover(bx shr 4, bz shr 4)
            hudMarkers.markDirty()
        }
        if (ticks % plugin.cfg.flushIntervalTicks == 0) stream(bx, by, bz)
        val out = ArrayList<Packet<in ClientGamePacketListener>>()
        if ((ticks + markerOffset) % plugin.cfg.entities.updateTicks == 0 && band <= 1) {
            markers.update(miniParam(), settings.showPlayers, settings.showMobs, settings.module.big, out)
        }
        if (ticks % 10 == 0 || hudMarkers.dirty) hudMarkers.update(out)
        if (ticks % 2 == 0) worldMarkers.update(out, ticks)
        sendAll(out)
        screen?.tick()
        syncRiders()
    }

    private fun discover(cx: Int, cz: Int) {
        val r = plugin.worldsConfig.entry(player.world.name)?.discoverRadiusChunks ?: plugin.cfg.discoverRadiusChunks
        for (dx in -r..r) for (dz in -r..r) {
            if (dx * dx + dz * dz <= r * r + r) discovered.add(TerrainCache.key(cx + dx, cz + dz))
        }
    }

    fun discoveredChunks(): Set<Long> = discovered

    private fun wantedLayer(by: Int): Int {
        val wm = world ?: return Int.MIN_VALUE
        if (!wm.mode.caves || wm.ceiling != null) return Int.MIN_VALUE
        val loc = player.location
        val surface = wm.cache.get(loc.blockX shr 4, loc.blockZ shr 4) ?: return Int.MIN_VALUE
        val i = (loc.blockZ and 15) * 16 + (loc.blockX and 15)
        val top = surface.heights[i].toInt()
        val sky = loc.block.lightFromSky.toInt()
        return if (by < top - 6 && sky == 0) Math.floorDiv(by + 2, plugin.cfg.caveLayerHeight) else Int.MIN_VALUE
    }

    private fun screenMeta(): Triple<Int, Int, Int> {
        val s = screen ?: return Triple(0, 0, settings.screenZoom)
        val (px, pz) = s.panForTiles()
        val drag = s.dragState().first
        return Triple(px, pz, (s.zoom and 15) or (if (drag) 16 else 0))
    }

    private fun sendTile(slot: TileSlot, data: ByteArray, meta: TileMeta, version: Long) {
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
            val k = TerrainCache.key(cx + dx, cz + dz)
            if (!discovered.contains(k)) continue
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
        val want = HashMap<Long, Pair<Int, Int>>()
        fun addRange(x0: Int, x1: Int, z0: Int, z1: Int) {
            for (tx in TileAssembler.tileX(x0, wide)..TileAssembler.tileX(x1, wide)) {
                for (tz in TileAssembler.tileZ(z0)..TileAssembler.tileZ(z1)) {
                    want[TerrainCache.key(tx, tz)] = tx * wide to tz * TileData.STRIDE_Z
                }
            }
        }
        val radius = tileRadiusBlocks()
        addRange(bx - radius, bx + radius, bz - radius, bz + radius)
        if (s != null) {
            val hw = (320 / s.scale).toInt() + 16
            val hh = (180 / s.scale).toInt() + 16
            val r = cfg.screenPanRadiusBlocks + 64
            addRange(maxOf(panX - hw, bx - r), minOf(panX + hw, bx + r), maxOf(panZ - hh, bz - r), minOf(panZ + hh, bz + r))
        }
        val ordered = want.entries.sortedBy { (_, o) ->
            val ox = o.first + wide / 2 - (if (s != null) panX else bx)
            val oz = o.second + 64 - (if (s != null) panZ else bz)
            ox.toLong() * ox + oz.toLong() * oz
        }.take(MAX_TILES)
        val keep = ordered.map { it.key }.toHashSet()
        val drop = tiles.keys.filter { it !in keep }
        if (drop.isNotEmpty()) {
            val ids = drop.flatMap { k -> tiles.remove(k)?.also { freeMapIds.add(it.frame.mapId) }?.frame?.ids?.toList() ?: emptyList() }
            send(Packets.remove(*ids.toIntArray()))
            ridersDirty = true
        }
        var budget = cfg.tilesPerFlush * (if (s != null) 2 else 1)
        for ((key, origin) in ordered) {
            var slot = tiles[key]
            if (slot == null) {
                val id = freeMapIds.removeFirstOrNull() ?: continue
                slot = TileSlot(key, TileFrame(id), origin.first, origin.second, kind, layer)
                tiles[key] = slot
            }
            val meta = TileMeta(kind, slot.originX, slot.originZ, flags, miniParam(), panX, panZ, screenByte, Codes.sensParam(settings.sensitivity))
            val latest = latestVersion(cache, slot, layer)
            if (latest != slot.sentVersion) {
                if (budget <= 0) continue
                budget--
                val source = if (layer == Int.MIN_VALUE) TileAssembler.Source { x, z -> cache.get(x, z) } else TileAssembler.Source { x, z -> cache.slice(x, z, layer) }
                val known = TileAssembler.Discovered { x, z -> discovered.contains(TerrainCache.key(x, z)) }
                val data = if (kind == TileData.RGB) assembler.rgb(slot.originX, slot.originZ, source, known, wm.brightness) else assembler.palette(slot.originX, slot.originZ, source, known)
                sendTile(slot, data, meta, latest)
            } else {
                refreshMeta(slot, meta, metaForce)
            }
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
                    slot = TileSlot(key, TileFrame(id), ocx * 16, ocz * 16, TileData.FOG, Int.MIN_VALUE)
                    tiles[key] = slot
                }
                val meta = TileMeta(TileData.FOG, slot.originX, slot.originZ, flags, miniParam(), panX, panZ, screenByte, Codes.sensParam(settings.sensitivity))
                val version = discovered.size.toLong()
                if (version != slot.sentVersion) {
                    if (budget-- <= 0) continue
                    val data = ByteArray(128 * 128)
                    for (r in 0 until 128) {
                        for (c in 0 until 128) {
                            if (!discovered.contains(TerrainCache.key(ocx + c, ocz + r))) data[r * 128 + c] = MapPalette.UNKNOWN
                        }
                    }
                    sendTile(slot, data, meta, version)
                } else {
                    refreshMeta(slot, meta, metaForce)
                }
            }
        }
    }

    private fun latestVersion(cache: TerrainCache, slot: TileSlot, layer: Int): Long {
        var v = discovered.size.toLong() shl 40
        val wide = if (slot.kind == TileData.RGB) 64 else 128
        val cx0 = slot.originX shr 4
        val cx1 = (slot.originX + wide - 1) shr 4
        val cz0 = slot.originZ shr 4
        val cz1 = (slot.originZ + 127) shr 4
        for (x in cx0 - 1..cx1) {
            for (z in cz0 - 1..cz1) {
                val s = if (layer == Int.MIN_VALUE) cache.get(x, z) else cache.slice(x, z, layer)
                if (s != null) v += s.version * 31 + x * 7 + z
            }
        }
        return v
    }

    private fun tileRadiusBlocks(): Int {
        val cfg = plugin.cfg
        val mini = (cfg.minimapBlocks / 0.5 * 0.75).toInt()
        val big = if (settings.module.big) (cfg.bigMapBlocks / 0.5 * 0.75).toInt().coerceAtMost(cfg.streamRadiusChunks * 16) else 0
        return maxOf(mini, big, 64)
    }

    private fun dropAllTiles() {
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
