package com.github.ssquadteam.kmap.waypoints

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

class WaypointStore(private val file: File, private val persist: Boolean, private val files: com.github.ssquadteam.kmap.storage.AsyncFiles) {
    val all = ArrayList<Waypoint>()

    fun inWorld(world: String): List<Waypoint> = all.filter { it.world == world }

    fun add(w: Waypoint) {
        all.add(w)
        save()
    }

    fun remove(id: UUID) {
        all.removeIf { it.id == id }
        save()
    }

    fun get(id: UUID): Waypoint? = all.firstOrNull { it.id == id }

    fun load() {
        all.clear()
        if (!persist) return
        val bytes = files.read(file) ?: return
        val y = YamlConfiguration()
        if (runCatching { y.loadFromString(bytes.toString(Charsets.UTF_8)) }.isFailure) return
        for (m in y.getMapList("waypoints")) {
            runCatching {
                all.add(
                    Waypoint(
                        UUID.fromString(m["id"] as String), m["name"] as String, m["world"] as String,
                        (m["x"] as Number).toInt(), (m["y"] as Number).toInt(), (m["z"] as Number).toInt(),
                        (m["color"] as Number).toInt(), m["icon"] as? String, m["visible"] as? Boolean ?: true, m["tracked"] as? Boolean ?: false,
                    ),
                )
            }
        }
    }

    fun save() {
        if (!persist) return
        val y = YamlConfiguration()
        y.set("waypoints", all.map { mapOf("id" to it.id.toString(), "name" to it.name, "world" to it.world, "x" to it.x, "y" to it.y, "z" to it.z, "color" to it.color, "icon" to it.icon, "visible" to it.visible, "tracked" to it.tracked) })
        files.write(file, y.saveToString().toByteArray(Charsets.UTF_8))
    }
}
