package com.github.ssquadteam.kmap.screen

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.PinLabel
import com.github.ssquadteam.kmap.locations.MapLocation
import com.github.ssquadteam.kmap.pack.ShaderDefines
import com.github.ssquadteam.kmap.render.Canvas
import com.github.ssquadteam.kmap.render.Carrier
import com.github.ssquadteam.kmap.render.Codes
import com.github.ssquadteam.kmap.render.Fx
import com.github.ssquadteam.kmap.render.Surface
import com.github.ssquadteam.kmap.render.Tint
import com.github.ssquadteam.kmap.session.PlayerMap
import com.github.ssquadteam.kmap.waypoints.Waypoint
import java.util.LinkedHashSet
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundGameEventPacket
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Abilities
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.craftbukkit.entity.CraftPlayer

class ScreenSession(val plugin: KMapPlugin, val map: PlayerMap) {
    val player get() = map.player
    val glyphs get() = plugin.packs.glyphs
    val cfg get() = plugin.cfg

    var zoom = 6
    var panX = 0.0
    var panZ = 0.0
    var cursorX = W / 2.0
    var cursorY = H / 2.0
    var hovered: String? = null
        private set
    private var hits: List<Hit> = emptyList()
    var panel: Panel? = null
        private set
    var textField: TextField? = null
    private var drag: Drag? = null
    private var dragStartX = 0.0
    private var dragStartY = 0.0
    private var panStartX = 0.0
    private var panStartZ = 0.0
    var tooltip: String? = null

    private var savedYaw = 0f
    private var savedPitch = 0f
    private var savedMode: GameMode = GameMode.SURVIVAL
    private var shell = HashMap<BlockPos, BlockState>()
    private var mapCarrier: Carrier? = null
    private var uiCarrier: Carrier? = null
    private var cursorCarrier: Carrier? = null
    private var lastPlate = ""
    private var dirtyMap = true
    private var dirtyUi = true
    var ticks = 0
        private set

    val scale get() = ShaderDefines.SCREEN_ZOOMS[zoom]

    private sealed class Drag {
        object Map : Drag()
        class Handle(val fn: (Double, Double) -> Unit) : Drag()
    }

    fun worldX(canvasX: Double) = panX + (canvasX - W / 2.0) / scale
    fun worldZ(canvasY: Double) = panZ + (canvasY - H / 2.0) / scale
    fun canvasX(wx: Double) = W / 2.0 + (wx - panX) * scale
    fun canvasY(wz: Double) = H / 2.0 + (wz - panZ) * scale

    fun open() {
        val p = player
        val h = (p as CraftPlayer).handle
        savedYaw = p.location.yaw
        savedPitch = p.location.pitch
        savedMode = p.gameMode
        panX = p.location.x
        panZ = p.location.z
        zoom = map.settings.screenZoom.coerceIn(0, ShaderDefines.SCREEN_ZOOMS.size - 1)
        clampPan()
        cursorX = W / 2.0
        cursorY = H / 2.0
        val out = ArrayList<Packet<in ClientGamePacketListener>>()
        out.add(ClientboundPlayerRotationPacket(0f, false, 0f, false))
        if (savedMode != GameMode.SURVIVAL) {
            out.add(ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, 0f))
            out.add(abilities())
        }
        for (i in 0 until 9) out.add(ClientboundContainerSetSlotPacket(0, 0, 36 + i, fakeItem()))
        out.add(ClientboundContainerSetSlotPacket(0, 0, 45, ItemStack.EMPTY))
        out.add(ClientboundSetHeldSlotPacket(4))
        out.add(ClientboundSetExperiencePacket(0f, 0, 0))
        buildShell()
        for ((pos, _) in shell) out.add(ClientboundBlockUpdatePacket(pos, Blocks.BARRIER.defaultBlockState()))
        val sens = Codes.sensParam(map.settings.sensitivity)
        mapCarrier = map.carrier("screen_map", Surface.SCREEN_MAP, sens)
        uiCarrier = map.carrier("screen_ui", Surface.SCREEN_UI, sens)
        cursorCarrier = map.carrier("screen_cursor", Surface.CURSOR, sens)
        map.sendAll(out)
        refreshCursor()
        dirtyMap = true
        dirtyUi = true
        render()
        map.setBand(2)
        map.onScreenChanged()
    }

    fun close() {
        val p = player
        map.clearOwn()
        textField = null
        panel?.onClose(this)
        panel = null
        drag = null
        map.setBand(0)
        val out = ArrayList<Packet<in ClientGamePacketListener>>()
        out.add(ClientboundPlayerRotationPacket(savedYaw, false, savedPitch, false))
        if (savedMode != GameMode.SURVIVAL) {
            out.add(ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, savedMode.ordinalId().toFloat()))
            out.add(ClientboundPlayerAbilitiesPacket((p as CraftPlayer).handle.abilities))
        }
        val level = (p as CraftPlayer).handle.level()
        for ((pos, _) in shell) out.add(ClientboundBlockUpdatePacket(level, pos))
        shell.clear()
        out.add(ClientboundSetHeldSlotPacket(p.inventory.heldItemSlot))
        out.add(ClientboundSetExperiencePacket(p.exp, p.totalExperience, p.level))
        map.sendAll(out)
        p.updateInventory()
        map.removeCarrier("screen_map")
        map.removeCarrier("screen_ui")
        map.removeCarrier("screen_cursor")
        mapCarrier = null
        uiCarrier = null
        cursorCarrier = null
        map.settings.screenZoom = zoom
        map.onScreenChanged()
    }

    private fun GameMode.ordinalId(): Int = when (this) {
        GameMode.SURVIVAL -> 0
        GameMode.CREATIVE -> 1
        GameMode.ADVENTURE -> 2
        GameMode.SPECTATOR -> 3
    }

    fun refreshCursor() {
        cursorCarrier?.setText(Canvas(glyphs).glyph("cursor_${map.settings.cursor}", 0.0, 0, Codes.cursor()).build())?.let { map.send(it) }
    }

    fun updateSensitivity() {
        val sens = Codes.sensParam(map.settings.sensitivity)
        for (c in listOfNotNull(mapCarrier, uiCarrier, cursorCarrier)) c.setParam(sens)?.let { map.send(it) }
        map.onScreenChanged()
    }

    fun abilities(): ClientboundPlayerAbilitiesPacket {
        val real = (player as CraftPlayer).handle.abilities
        val a = Abilities()
        a.invulnerable = real.invulnerable
        a.flying = real.flying
        a.mayfly = real.mayfly
        a.instabuild = false
        a.mayBuild = true
        a.flyingSpeed = 0f
        a.walkingSpeed = real.walkingSpeed
        return ClientboundPlayerAbilitiesPacket(a)
    }

    fun onRealGameMode(mode: GameMode) {
        savedMode = mode
    }

    private fun buildShell() {
        val h = (player as CraftPlayer).handle
        val level = h.level()
        val feet = h.blockPosition()
        shell.clear()
        for (dy in -1..2) for (dx in -1..1) for (dz in -1..1) {
            if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) continue
            val pos = feet.offset(dx, dy, dz)
            val state = level.getBlockState(pos)
            if (state.getCollisionShape(level, pos).isEmpty) shell[pos.immutable()] = state
        }
    }

    fun isShell(pos: BlockPos): Boolean = shell.containsKey(pos)

    fun fakeItem(): ItemStack {
        val s = ItemStack(Items.PAPER)
        s.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("kmap", "empty"))
        s.set(DataComponents.ITEM_NAME, Component.literal(""))
        s.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(true, LinkedHashSet()))
        return s
    }

    fun clampPan() {
        val bake = map.worldMap()?.bake
        if (bake != null) {
            panX = panX.coerceIn(bake.originX.toDouble(), bake.originX + bake.widthPx * bake.blocksPerPx)
            panZ = panZ.coerceIn(bake.originZ.toDouble(), bake.originZ + bake.heightPx * bake.blocksPerPx)
        } else {
            val r = cfg.screenPanRadiusBlocks.toDouble()
            val loc = player.location
            panX = panX.coerceIn(loc.x - r, loc.x + r)
            panZ = panZ.coerceIn(loc.z - r, loc.z + r)
        }
    }

    fun setPanel(p: Panel?) {
        if (hoveredWaypoint != null) {
            hoveredWaypoint = null
            dirtyMap = true
        }
        panel?.onClose(this)
        panel = p
        textField = null
        p?.onOpen(this)
        dirtyUi = true
        render()
    }

    fun invalidateUi() {
        dirtyUi = true
    }

    fun invalidateMap() {
        dirtyMap = true
    }

    private var lastYaw = 0f
    private var lastPitch = 0f

    fun debug(): String = "cursor=${cursorX.toInt()},${cursorY.toInt()} yaw=$lastYaw pitch=$lastPitch zoom=$zoom pan=${panX.toInt()},${panZ.toInt()} mode=$savedMode"

    fun onRotate(yaw: Float, pitch: Float) {
        lastYaw = yaw
        lastPitch = pitch
        val sens = map.settings.sensitivity
        val k = cfg.cursorDegreesScale * sens
        var y = yaw.toDouble()
        y -= floor((y + 180.0) / 360.0) * 360.0
        val maxYaw = (W / 2.0) / k
        val maxPitch = (H / 2.0) / k
        val cy = y.coerceIn(-maxYaw, maxYaw)
        val cp = pitch.toDouble().coerceIn(-maxPitch, maxPitch)
        if (abs(cy - y) > 1.5 || abs(cp - pitch) > 1.5) {
            map.send(ClientboundPlayerRotationPacket((cy - y).toFloat(), true, (cp - pitch).toFloat(), true))
        }
        cursorX = W / 2.0 + cy * k
        cursorY = H / 2.0 + cp * k
        val d = drag
        if (d is Drag.Handle) d.fn(cursorX, cursorY)
        updateHover()
    }

    var hoveredWaypoint: UUID? = null
        private set
    var ping: Pair<Double, Double>? = null
        private set
    private var pingUntil = 0

    private fun updateHover() {
        val h = hits.lastOrNull { it.contains(cursorX, cursorY) && (it.action != null || it.drag != null) }?.id
        if (h != hovered) {
            hovered = h
            dirtyUi = true
            if (h?.startsWith("pin:") == true || hovered?.startsWith("pin:") == true) dirtyMap = true
        }
        val wp = if (h == null && drag == null && panel?.modal != true) waypointAt(cursorX, cursorY)?.id else null
        if (wp != hoveredWaypoint) {
            hoveredWaypoint = wp
            dirtyMap = true
            dirtyUi = true
        }
    }

    fun onLeftDown() {
        if (textField != null) return
        val h = hits.lastOrNull { it.contains(cursorX, cursorY) && (it.action != null || it.drag != null) }
        if (h != null) {
            if (h.drag != null) {
                drag = Drag.Handle(h.drag)
                h.drag.invoke(cursorX, cursorY)
            }
            h.action?.let {
                click()
                it()
            }
            if (h.action != null || h.drag != null) {
                render()
                return
            }
        }
        if (panel?.modal == true) return
        if (hits.any { it.contains(cursorX, cursorY) && it.scroll != null }) return
        drag = Drag.Map
        dragStartX = cursorX
        dragStartY = cursorY
        panStartX = panX
        panStartZ = panZ
        dirtyMap = true
        map.onScreenChanged()
        render()
    }

    fun onLeftUp() {
        val d = drag ?: return
        drag = null
        if (d is Drag.Map) {
            panX = panStartX - (cursorX - dragStartX) / scale
            panZ = panStartZ - (cursorY - dragStartY) / scale
            clampPan()
            dirtyMap = true
            map.onScreenChanged()
            if (abs(cursorX - dragStartX) < 2 && abs(cursorY - dragStartY) < 2) clickMap()
        }
        render()
    }

    private fun clickMap() {
        val wp = waypointAt(cursorX, cursorY)
        if (wp != null) {
            setPanel(WaypointEditPanel(wp.id, null))
            return
        }
        val pin = pinAt(cursorX, cursorY)
        if (pin != null) {
            plugin.pins.activate(player, pin, this)
            return
        }
    }

    fun onRightClick() {
        if (textField != null) return
        if (panel?.modal == true) return
        val wp = waypointAt(cursorX, cursorY)
        if (wp != null) {
            setPanel(WaypointEditPanel(wp.id, null))
            return
        }
        if (!cfg.guis.newWaypoint) return
        val wx = floor(worldX(cursorX)).toInt()
        val wz = floor(worldZ(cursorY)).toInt()
        setPanel(WaypointEditPanel(null, Triple(wx, map.heightAt(wx, wz) ?: player.location.blockY, wz)))
    }

    fun onScroll(delta: Int) {
        val h = hits.lastOrNull { it.contains(cursorX, cursorY) && it.scroll != null }
        if (h != null) {
            h.scroll!!.invoke(delta)
            render()
            return
        }
        if (panel?.modal == true) return
        zoomBy(-delta, cursorX, cursorY)
    }

    fun zoomBy(step: Int, ax: Double = W / 2.0, ay: Double = H / 2.0) {
        val next = (zoom + step).coerceIn(0, ShaderDefines.SCREEN_ZOOMS.size - 1)
        if (next == zoom) return
        val wx = worldX(ax)
        val wz = worldZ(ay)
        zoom = next
        panX = wx - (ax - W / 2.0) / scale
        panZ = wz - (ay - H / 2.0) / scale
        clampPan()
        dirtyMap = true
        map.onScreenChanged()
        render()
    }

    fun resetView() {
        panX = player.location.x
        panZ = player.location.z
        zoom = if (map.worldMap()?.mode?.hd == true) 5 else 6
        clampPan()
        dirtyMap = true
        map.onScreenChanged()
        render()
    }

    fun flyTo(x: Double, z: Double) {
        ping = x to z
        pingUntil = ticks + PING_TICKS
        panX = x
        panZ = z
        if (zoom < 6) zoom = 7
        clampPan()
        dirtyMap = true
        map.onScreenChanged()
        render()
    }

    fun click() {
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.25f, 1.4f)
    }

    fun dragState(): Triple<Boolean, Double, Double> {
        val d = drag
        return if (d is Drag.Map) Triple(true, dragStartX, dragStartY) else Triple(false, 0.0, 0.0)
    }

    fun panForTiles(): Pair<Int, Int> {
        val (dragging, sx, sy) = dragState()
        return if (dragging) {
            Math.round(panStartX + (sx - W / 2.0) / scale).toInt() to Math.round(panStartZ + (sy - H / 2.0) / scale).toInt()
        } else {
            Math.round(panX).toInt() to Math.round(panZ).toInt()
        }
    }

    fun tick() {
        ticks++
        if (ticks % 2 == 0) updatePlate()
        if (ticks % cfg.entities.updateTicks == 0 && drag == null) dirtyMap = true
        if (ping != null) {
            if (ticks >= pingUntil) ping = null
            if (ticks % 5 == 0) dirtyMap = true
        }
        panel?.tick(this)
        render()
    }

    private fun updatePlate() {
        val wx = floor(worldX(cursorX)).toInt()
        val wz = floor(worldZ(cursorY)).toInt()
        val y = map.heightAt(wx, wz)
        val text = "X: $wx  Y: ${y ?: "?"}  Z: $wz"
        if (text != lastPlate) {
            lastPlate = text
            dirtyUi = true
        }
    }

    fun render() {
        if (dirtyMap) {
            dirtyMap = false
            mapCarrier?.setText(MapLayer.render(this))?.let { map.send(it) }
        }
        if (dirtyUi) {
            dirtyUi = false
            val ui = Ui(glyphs, hovered)
            ScreenChrome.render(this, ui, lastPlate)
            panel?.render(this, ui)
            tooltip?.let { ScreenChrome.tooltip(ui, it, cursorX, cursorY) }
            hits = ui.hits
            uiCarrier?.setText(ui.canvas.build())?.let { map.send(it) }
            updateHoverSilently()
        }
    }

    private fun updateHoverSilently() {
        val h = hits.lastOrNull { it.contains(cursorX, cursorY) && (it.action != null || it.drag != null) }?.id
        if (h != hovered) {
            hovered = h
            dirtyUi = true
        }
    }

    fun pinAt(cx: Double, cy: Double): MapLocation? {
        val world = player.world.name
        return plugin.locations.visible(player, world).filter { it.pin }.lastOrNull { l ->
            val size = pinSize(l)
            val px = canvasX(l.x)
            val py = canvasY(l.z)
            abs(cx - px) <= size / 2.0 + 1 && abs(cy - py) <= size / 2.0 + 1
        }
    }

    fun waypointAt(cx: Double, cy: Double): Waypoint? {
        return map.waypoints.inWorld(player.world.name).filter { it.visible }.lastOrNull { w ->
            abs(cx - canvasX(w.x + 0.5)) <= 8.5 && abs(cy - canvasY(w.z + 0.5)) <= 8.5
        }
    }

    fun pinSize(l: MapLocation): Int = if (l.pinSize <= 16) 16 else 20

    fun hoveredPin(): MapLocation? = if (drag == null) pinAt(cursorX, cursorY) else null

    fun onText(text: String) {
        val f = textField ?: return
        f.value = text.take(f.max)
        f.onChange(f.value)
        dirtyUi = true
        render()
    }

    fun onTextClosed() {
        val f = textField ?: return
        textField = null
        val out = ArrayList<Packet<in ClientGamePacketListener>>()
        for (i in 0 until 9) out.add(ClientboundContainerSetSlotPacket(0, 0, 36 + i, fakeItem()))
        out.add(ClientboundContainerSetSlotPacket(0, 0, 45, ItemStack.EMPTY))
        out.add(ClientboundSetHeldSlotPacket(4))
        map.sendAll(out)
        map.setBand(2)
        f.onDone(f.value)
        dirtyUi = true
        render()
    }

    fun focus(field: TextField) {
        textField = field
        map.setBand(3)
        plugin.textInput.open(player, field.value)
        dirtyUi = true
    }

    companion object {
        const val PING_TICKS = 60
        const val W = 640.0
        const val H = 360.0

        fun pinLabelVisible(l: MapLocation, hovered: Boolean): Boolean = when (l.pinLabel) {
            PinLabel.ALWAYS -> true
            PinLabel.NEVER -> false
            PinLabel.HOVER -> hovered
        }
    }
}

class TextField(var value: String, val max: Int, val onChange: (String) -> Unit, val onDone: (String) -> Unit)

interface Panel {
    val modal: Boolean get() = true

    fun onOpen(s: ScreenSession) {}

    fun onClose(s: ScreenSession) {}

    fun tick(s: ScreenSession) {}

    fun render(s: ScreenSession, ui: Ui)
}
