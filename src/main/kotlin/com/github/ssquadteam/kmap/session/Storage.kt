package com.github.ssquadteam.kmap.session

import com.github.ssquadteam.kmap.KMapPlugin
import org.bukkit.World
import org.bukkit.entity.Player
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class Storage(private val plugin: KMapPlugin) {
    private val root get() = File(plugin.dataFolder, "data")

    fun settingsFile(id: UUID) = File(root, "players/$id/settings.yml")

    fun loadSettings(player: Player): PlayerSettings = PlayerSettings.load(settingsFile(player.uniqueId), plugin.cfg)

    fun saveSettings(player: Player, s: PlayerSettings) {
        runCatching { s.save(settingsFile(player.uniqueId)) }
    }

    private fun discoveryFile(id: UUID, world: World) = File(root, "players/$id/discovery/${world.name}.bin")

    fun loadDiscovery(player: Player, world: World): Set<Long>? {
        if (!plugin.cfg.saveDiscovery) return null
        val f = discoveryFile(player.uniqueId, world)
        if (!f.isFile) return null
        return runCatching {
            DataInputStream(InflaterInputStream(f.inputStream().buffered())).use { input ->
                val n = input.readInt()
                HashSet<Long>(n * 2).apply { repeat(n) { add(input.readLong()) } }
            }
        }.getOrNull()
    }

    fun saveDiscovery(id: UUID, world: World, keys: Collection<Long>) {
        if (!plugin.cfg.saveDiscovery) return
        val f = discoveryFile(id, world)
        f.parentFile.mkdirs()
        runCatching {
            DataOutputStream(DeflaterOutputStream(f.outputStream().buffered())).use { out ->
                out.writeInt(keys.size)
                for (k in keys) out.writeLong(k)
            }
        }
    }
}
