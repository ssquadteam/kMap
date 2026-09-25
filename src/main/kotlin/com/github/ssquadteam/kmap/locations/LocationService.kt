package com.github.ssquadteam.kmap.locations

import com.github.ssquadteam.kmap.config.PinLabel
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LocationService(private val file: File, private val log: (String) -> Unit) {
    @Volatile
    var all: List<MapLocation> = emptyList()
        private set
    private val overrides = ConcurrentHashMap<UUID, MutableMap<String, Boolean>>()

    fun load(): Boolean {
        if (!file.isFile) {
            file.parentFile.mkdirs()
            file.writeText(TEMPLATE)
        }
        val y = YamlConfiguration()
        try {
            y.load(file)
        } catch (e: Exception) {
            log("locations.yml did not parse, keeping the previous list: ${e.message}")
            return false
        }
        val out = ArrayList<MapLocation>()
        for ((i, m) in y.getMapList("locations").withIndex()) {
            val name = m["name"] as? String ?: continue
            val actions = (m["actions"] as? List<*>)?.mapNotNull { a ->
                when (a) {
                    is String -> {
                        val type = a.substringBefore(':').trim().uppercase()
                        PinAction(type, a.substringAfter(':', "").trim(), emptyMap())
                    }
                    is Map<*, *> -> {
                        val type = (a["type"] as? String)?.uppercase() ?: return@mapNotNull null
                        PinAction(type, (a["value"] ?: a["command"] ?: a["message"] ?: a["text"] ?: a["sound"] ?: a["ticks"] ?: "").toString(), a.entries.associate { it.key.toString() to it.value })
                    }
                    else -> null
                }
            } ?: emptyList()
            out.add(
                MapLocation(
                    i, name, m["world"] as? String,
                    (m["x"] as? Number)?.toDouble() ?: 0.0, (m["y"] as? Number)?.toDouble(), (m["z"] as? Number)?.toDouble() ?: 0.0,
                    m["icon"] as? String, m["description"] as? String, m["permission"] as? String,
                    m["searchable"] as? Boolean ?: true,
                    color(m["nameColor"]), color(m["descColor"]), m["tag"] as? String, color(m["tagColor"]),
                    m["pin"] as? Boolean ?: false,
                    ((m["pinSize"] as? Number)?.toInt() ?: 14).coerceIn(4, 540),
                    m["pinIcon"] as? String,
                    runCatching { PinLabel.valueOf((m["pinLabel"] as? String ?: "HOVER").uppercase()) }.getOrDefault(PinLabel.HOVER),
                    m["beam"] as? Boolean ?: true,
                    actions,
                ),
            )
        }
        all = out
        return true
    }

    private fun color(v: Any?): Int? {
        val s = (v as? String)?.trim()?.removePrefix("#") ?: return null
        return s.toIntOrNull(16)
    }

    fun canSee(player: Player, loc: MapLocation): Boolean {
        val perm = loc.permission ?: return true
        overrides[player.uniqueId]?.get(perm)?.let { return it }
        return player.hasPermission(perm)
    }

    fun visible(player: Player, world: String): List<MapLocation> = all.filter { (it.world == null || it.world == world) && canSee(player, it) }

    fun setOverride(player: Player, node: String, value: Boolean?) {
        val map = overrides.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        if (value == null) map.remove(node) else map[node] = value
    }

    fun clearOverrides(player: UUID) {
        overrides.remove(player)
    }

    fun search(player: Player, world: String, query: String): List<MapLocation> {
        val q = query.trim().lowercase()
        val pool = visible(player, world).filter { it.searchable }
        if (q.isEmpty()) return pool.sortedBy { it.name.lowercase() }
        return pool.mapNotNull { l ->
            val n = l.name.lowercase()
            val d = l.description?.lowercase() ?: ""
            val score = when {
                n == q -> 0
                n.startsWith(q) -> 1
                n.split(' ', '-', '_').any { it.startsWith(q) } -> 2
                n.contains(q) -> 3
                d.contains(q) -> 4
                (l.tag?.lowercase() ?: "").contains(q) -> 5
                else -> return@mapNotNull null
            }
            score to l
        }.sortedWith(compareBy({ it.first }, { it.second.name.length }, { it.second.name.lowercase() })).map { it.second }
    }

    companion object {
        val TEMPLATE = """
            |# locations.yml - the places players can search for from the map, and optional map pins.
            |#
            |# Required: name, x, z. Everything else is optional.
            |#   world        only list the place in this world (omit to show it everywhere)
            |#   y            display only
            |#   icon         a PNG from plugins/kMap/contents/locations without .png, or a built-in one:
            |#                bank, cave, chest, dungeon, house, map, quest, store, teleport, waypoint
            |#   description  one line under the coordinates, searched too
            |#   permission   only players with this node see the place (row and pin)
            |#   searchable   false keeps it out of search, the pin still draws
            |#   nameColor / descColor / tag / tagColor   #RRGGBB and a short badge
            |#   pin          true draws the place on the maps; pinSize, pinIcon, pinLabel (HOVER, ALWAYS, NEVER), beam
            |#   actions      what a click on the pin does, in order:
            |#                RUN_COMMAND: cmd | CONSOLE_COMMAND: cmd | TELEPORT | CLOSE_MAP | TRACK | MESSAGE: text
            |#                ACTIONBAR: text | TITLE: title;subtitle | SOUND: minecraft:ui.button.click | DELAY: ticks
            |#                {player} is replaced with the player's name.
            |locations:
            |  - name: "Spawn"
            |    x: 0
            |    y: 64
            |    z: 0
            |    icon: "house"
            |    description: "The main spawn"
            |    tag: "HUB"
            |    tagColor: "#E0554D"
            |    pin: true
            |""".trimMargin()
    }
}
