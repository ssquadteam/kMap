package com.github.ssquadteam.kmap.session

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.MinimapShape
import com.github.ssquadteam.kmap.hud.BigPainter
import com.github.ssquadteam.kmap.hud.MiniPainter
import com.github.ssquadteam.kmap.markers.EntityMarkers
import com.github.ssquadteam.kmap.markers.HudMarkers
import com.github.ssquadteam.kmap.markers.WorldMarkers
import com.github.ssquadteam.kmap.nms.FakeIds
import com.github.ssquadteam.kmap.nms.PacketListener
import com.github.ssquadteam.kmap.nms.Packets
import com.github.ssquadteam.kmap.render.Carrier
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.Surface
import com.github.ssquadteam.kmap.screen.ScreenSession
import com.github.ssquadteam.kmap.terrain.ChunkBitmap
import com.github.ssquadteam.kmap.terrain.TerrainCache
import com.github.ssquadteam.kmap.terrain.ZoomAnim
import com.github.ssquadteam.kmap.waypoints.WaypointStore
import com.github.ssquadteam.kmap.world.WorldMap
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
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
import net.kyori.adventure.text.Component
import org.bukkit.GameMode
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType

class PlayerMap(val plugin: KMapPlugin, val player: Player, val settings: PlayerSettings) : PacketListener {
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
    private val streamer = TileStreamer(this)
    val discovered = ChunkBitmap()
    @Volatile
    private var discoveryWorld: String? = null
    @Volatile
    private var savedDiscoveryMod = 0L
    private var lastChunk = Long.MIN_VALUE
    private var lastBlock = Triple(Int.MIN_VALUE, 0, 0)
    private var ticks = 0
    private var ridersDirty = true
    private val shaderHintId = FakeIds.next()
    private var hintShown = false

    @Volatile
    private var realPassengers = IntArray(0)
    private var lastStreamError = -1200
    private val markers = EntityMarkers(plugin, player)
    val hudMarkers = HudMarkers(plugin, this)
    val worldMarkers = WorldMarkers(plugin, this)
    private val markerOffset = (player.entityId and 0xFFFF)
    val waypoints = WaypointStore(plugin.storage.waypointsFile(player.uniqueId), plugin.cfg.saveMarkers, plugin.files)

    @Volatile
    var trackedPin: Int? = null
        private set
    private var savedHeading = 0f

    private val pendingRotation = AtomicLong(Long.MIN_VALUE)
    private val rotationScheduled = AtomicBoolean(false)
    private var lastRightClick = 0L
    val sentPacks: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        waypoints.load()
    }

    fun miniParam(): Int = Codes.miniParam(settings.corner.ordinal, settings.shape == MinimapShape.CIRCLE, settings.zoom, settings.coords)

    fun worldMap(): WorldMap? = world

    fun savedHeading(): Float = savedHeading

    fun start() {
        if (active) {
            enterWorld()
            return
        }
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
        val ids = carriers.values.map { it.id }
        carriers.clear()
        streamer.hideAll()
        if (ids.isNotEmpty()) send(Packets.remove(*ids.toIntArray()))
        showShaderHint(false)
        ridersDirty = true
    }

    fun enterWorld() {
        if (screen != null) closeScreen()
        if (band != 0) setBand(0)
        despawnAll()
        world = plugin.worlds.of(player.world)
        if (discoveryWorld != player.world.name) {
            discovered.clear()
            plugin.storage.loadDiscovery(player, player.world, discovered)
            discoveryWorld = player.world.name
            savedDiscoveryMod = discovered.modCount
        }
        lastChunk = Long.MIN_VALUE
        lastBlock = Triple(Int.MIN_VALUE, 0, 0)
        streamer.enterWorld(player.world.name)
        spawnHud()
        pushTime()
        task?.cancel()
        lastTickNanos = System.nanoTime()
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
        if (settings.module.big) {
            val big = carrier("big", Surface.BIG, miniParam())
            big.setParam(miniParam())?.let { send(it) }
            big.setText(BigPainter.frame(glyphs, plugin.cfg.bigMapSize))?.let { send(it) }
        } else {
            removeCarrier("big")
            if (band == 1) setBand(0)
        }
        if (settings.module.minimap && settings.minimap) {
            val mini = carrier("mini", Surface.MINI, miniParam())
            mini.setParam(miniParam())?.let { send(it) }
            mini.setText(MiniPainter.frame(glyphs, settings.shape, size))?.let { send(it) }
            carrier("plate", Surface.MINI, miniParam()).setParam(miniParam())?.let { send(it) }
        } else {
            removeCarrier("mini")
            removeCarrier("plate")
        }
        lastBlock = Triple(Int.MIN_VALUE, 0, 0)
        streamer.resetMeta()
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

    private var guildVersion = -1

    fun syncGuild() {
        val g = plugin.guilds ?: return
        guildVersion = g.version
        val info = g.guildOf(player.uniqueId)?.takeIf { plugin.cfg.guilds.sharedWaypoints }
        val old = waypoints.shared.associateBy { it.id }
        waypoints.shared = info?.let { g.shared(it.id) }?.onEach { w ->
            old[w.id]?.let {
                w.visible = it.visible
                w.tracked = it.tracked
            }
        } ?: emptyList()
        onWaypointsChanged()
        screen?.let {
            it.invalidateMap()
            it.invalidateUi()
        }
    }

    fun setTracked(id: UUID?) {
        for (w in waypoints.every()) w.tracked = w.id == id
        if (id != null) trackedPin = null
        waypoints.save()
        onWaypointsChanged()
    }

    fun trackPin(index: Int?) {
        trackedPin = index
        if (index != null) for (w in waypoints.every()) w.tracked = false
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

    fun view(): View {
        val s = screen
        val sens = Codes.sensParam(settings.sensitivity)
        if (s == null) return View(miniParam(), sens, 0, 0, settings.screenZoom, ZoomAnim.NONE, 0, 0, 0, 0)
        val (qx, qz) = s.panQuarters()
        val (cx, cz) = s.viewCenter()
        val halfW = (ScreenSession.W / 2 / s.scale).toInt() + 32
        val halfH = (ScreenSession.H / 2 / s.scale).toInt() + 32
        return View(miniParam(), sens, qx, qz, (s.zoom and 15) or (if (s.dragState().first) 16 else 0), s.zoomAnim(), cx, cz, halfW, halfH)
    }

    fun syncTiles(out: MutableList<Packet<in ClientGamePacketListener>>) {
        streamer.syncMeta(view(), out)
    }

    fun setBand(value: Int) {
        if (band == value) return
        band = value
        pushTime()
        showShaderHint(value != 0)
    }

    private fun showShaderHint(show: Boolean) {
        val want = show && plugin.cfg.shaderHint
        if (want == hintShown) return
        hintShown = want
        if (want) {
            val loc = player.location
            sendAll(Carrier.shaderHint(shaderHintId, Component.text(plugin.lang.get(player, "shaders.hint")), loc.x, loc.y + 1.8, loc.z))
        } else {
            send(Packets.remove(shaderHintId))
        }
        ridersDirty = true
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
            if (hintShown) add(shaderHintId)
            streamer.riderIds(this)
        }
    }

    private fun syncRiders() {
        if (!ridersDirty) return
        ridersDirty = false
        sendAll(listOf(Packets.passengers(player.entityId, realPassengers + riderIds().toIntArray())) + streamer.liftPassengers())
    }

    @Volatile
    var lastTickNanos = System.nanoTime()
        private set

    private fun tick() {
        if (!active || !player.isOnline) return
        lastTickNanos = System.nanoTime()
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
        if (plugin.guilds?.version?.let { it != guildVersion } == true) syncGuild()
        val out = ArrayList<Packet<in ClientGamePacketListener>>()
        if ((ticks + markerOffset) % plugin.cfg.entities.updateTicks == 0 && band <= 1) {
            markers.update(miniParam(), settings.showPlayers, settings.showMobs, settings.showGuild, settings.module.big, out)
        }
        if (hudMarkers.dirty) hudMarkers.update(out)
        if (ticks % 2 == 0) worldMarkers.update(out, ticks)
        sendAll(out)
        screen?.tick()
        syncRiders()
    }

    private fun discover(cx: Int, cz: Int) {
        val r = (plugin.worldsConfig.entry(player.world.name)?.discoverRadiusChunks ?: plugin.cfg.discoverRadiusChunks).coerceAtMost((player.viewDistance - 1).coerceAtLeast(2))
        for (dx in -r..r) for (dz in -r..r) {
            if (dx * dx + dz * dz <= r * r + r) discovered.add(cx + dx, cz + dz)
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
        if (wm.ceiling != null) return Int.MIN_VALUE
        val handle = (player as CraftPlayer).handle
        val x = handle.blockX
        val z = handle.blockZ
        val surface = wm.cache.get(x shr 4, z shr 4) ?: return Int.MIN_VALUE
        val top = surface.heights[(z and 15) * 16 + (x and 15)].toInt()
        if (by >= top - 6) return Int.MIN_VALUE
        val sky = handle.level().getBrightness(LightLayer.SKY, handle.blockPosition())
        return if (by < top - 6 && sky == 0) Math.floorDiv(by + 2, plugin.cfg.caveLayerHeight) else Int.MIN_VALUE
    }

    private fun stream(bx: Int, by: Int, bz: Int) {
        val wm = world ?: return
        val cfg = plugin.cfg
        val cache = wm.cache
        val cx = bx shr 4
        val cz = bz shr 4
        val layer = wantedLayer(by)
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
        streamer.stream(wm, bx, bz, layer, screen, view(), ticks)
    }

    fun debug(): String = (screen?.debug() ?: "") + " active=$active band=$band tiles=${streamer.activeCount}/${streamer.cachedCount} carriers=${carriers.size} discovered=${discovered.size} cache=${world?.cache?.size()} layer=${streamer.caveLayer} screen=${screen != null}"
}
