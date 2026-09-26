package com.github.ssquadteam.kmap.hooks

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.PinLabel
import com.github.ssquadteam.kmap.locations.MapLocation
import com.github.ssquadteam.kmap.waypoints.Waypoint
import com.github.ssquadteam.kmap.waypoints.WaypointStore
import me.glaremasters.guilds.Guilds
import me.glaremasters.guilds.api.events.GuildRemoveEvent
import me.glaremasters.guilds.api.events.base.GuildEvent
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GuildInfo(
    val id: UUID,
    val name: String,
    val prefix: String,
    val master: UUID?,
    val members: Set<UUID>,
    val allies: Set<UUID>,
    val home: Location?,
)

enum class Relation { MEMBER, ALLY }

class Mate(val player: Player, val relation: Relation, val guild: GuildInfo)

class GuildsHook private constructor(private val plugin: KMapPlugin) : Listener {
    @Volatile
    private var byPlayer: Map<UUID, GuildInfo> = emptyMap()

    @Volatile
    private var byGuild: Map<UUID, GuildInfo> = emptyMap()
    private val pending = AtomicBoolean(false)
    private val stores = ConcurrentHashMap<UUID, WaypointStore>()
    private val changes = AtomicInteger()

    val version: Int get() = changes.get()

    private val cfg get() = plugin.cfg.guilds

    fun guildOf(player: UUID): GuildInfo? = if (cfg.enabled) byPlayer[player] else null

    fun relation(viewer: UUID, other: UUID): Relation? {
        val g = guildOf(viewer) ?: return null
        val o = guildOf(other) ?: return null
        return when {
            o.id == g.id -> Relation.MEMBER.takeIf { cfg.members }
            o.id in g.allies -> Relation.ALLY.takeIf { cfg.allies }
            else -> null
        }
    }

    fun mates(viewer: Player): List<Mate> {
        val g = guildOf(viewer.uniqueId) ?: return emptyList()
        val out = ArrayList<Mate>()
        fun add(info: GuildInfo, r: Relation) {
            for (id in info.members) {
                if (id == viewer.uniqueId) continue
                val p = Bukkit.getPlayer(id) ?: continue
                if (p.world !== viewer.world || p.gameMode == GameMode.SPECTATOR || !viewer.canSee(p)) continue
                out.add(Mate(p, r, info))
            }
        }
        if (cfg.members) add(g, Relation.MEMBER)
        if (cfg.allies) for (a in g.allies) byGuild[a]?.let { add(it, Relation.ALLY) }
        return out
    }

    fun home(player: Player): MapLocation? {
        if (!cfg.home) return null
        val g = guildOf(player.uniqueId) ?: return null
        val h = g.home ?: return null
        val lang = plugin.lang
        return MapLocation(
            HOME, lang.get(player, "guilds.home", g.name), h.world?.name, h.x, h.y, h.z, cfg.homeIcon, g.name, null, true,
            null, null, g.prefix.ifBlank { lang.get(player, "guilds.tag") }, cfg.memberColor, true, 14, null, PinLabel.HOVER, true, emptyList(),
        )
    }

    fun shared(guild: UUID): List<Waypoint> {
        val s = store(guild)
        return synchronized(s) { s.all.map { it.copy(guild) } }
    }

    fun share(guild: UUID, w: Waypoint) {
        val s = store(guild)
        synchronized(s) {
            val copy = w.copy(null).also { it.visible = true; it.tracked = false }
            val i = s.all.indexOfFirst { it.id == w.id }
            if (i >= 0) s.all[i] = copy else s.all.add(copy)
            s.save()
        }
        changes.incrementAndGet()
    }

    fun unshare(guild: UUID, id: UUID) {
        val s = store(guild)
        synchronized(s) { s.remove(id) }
        changes.incrementAndGet()
    }

    fun canEdit(player: UUID, w: Waypoint): Boolean {
        val g = w.guild ?: return true
        if (w.owner == player) return true
        return guildOf(player)?.takeIf { it.id == g }?.master == player
    }

    private fun store(guild: UUID): WaypointStore = stores.computeIfAbsent(guild) {
        WaypointStore(File(plugin.dataFolder, "data/guilds/$it/waypoints.yml"), plugin.cfg.saveMarkers, plugin.files).apply { load() }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChange(e: GuildEvent) {
        if (e is GuildRemoveEvent) {
            val s = store(e.guild.id)
            synchronized(s) { s.delete() }
            stores.remove(e.guild.id)
        }
        if (pending.compareAndSet(false, true)) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, {
                pending.set(false)
                refresh()
            }, 1L)
        }
    }

    private fun refresh() {
        val guilds = try {
            snapshot()
        } catch (t: Throwable) {
            plugin.logger.warning("Guilds snapshot failed, keeping the previous one: $t")
            return
        }
        byGuild = guilds
        byPlayer = HashMap<UUID, GuildInfo>().also { m -> for (g in guilds.values) for (id in g.members) m[id] = g }
        changes.incrementAndGet()
    }

    private fun snapshot(): Map<UUID, GuildInfo> {
        val out = HashMap<UUID, GuildInfo>()
        for (g in ArrayList(Guilds.getApi().guildHandler.guilds.values)) {
            val home = g.home?.asLocation?.takeIf { it.world != null }
            out[g.id] = GuildInfo(g.id, g.name, g.prefix ?: "", g.guildMaster?.uuid, g.members.mapTo(HashSet()) { it.uuid }, HashSet(g.allies), home)
        }
        return out
    }

    companion object {
        const val HOME = -1
        private const val REFRESH_TICKS = 600L

        fun install(plugin: KMapPlugin): GuildsHook? {
            return try {
                val hook = GuildsHook(plugin)
                plugin.server.pluginManager.registerEvents(hook, plugin)
                Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { hook.refresh() }, 1L, REFRESH_TICKS)
                plugin.locations.extras = { p -> listOfNotNull(hook.home(p)) }
                plugin.logger.info("Hooked into Guilds")
                hook
            } catch (t: Throwable) {
                plugin.logger.warning("Guilds hook failed, guild features are off: $t")
                null
            }
        }
    }
}
