package com.github.ssquadteam.kmap.session

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.terrain.ChunkBitmap
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class Storage(private val plugin: KMapPlugin) {
    private val root get() = File(plugin.dataFolder, "data")

    fun settingsFile(id: UUID) = File(root, "players/$id/settings.yml")

    fun waypointsFile(id: UUID) = File(root, "players/$id/waypoints.yml")

    fun loadSettings(player: Player): PlayerSettings {
        val bytes = plugin.files.read(settingsFile(player.uniqueId)) ?: return PlayerSettings.defaults(plugin.cfg)
        val y = YamlConfiguration()
        return if (runCatching { y.loadFromString(bytes.toString(Charsets.UTF_8)) }.isSuccess) PlayerSettings.load(y, plugin.cfg) else PlayerSettings.defaults(plugin.cfg)
    }

    fun saveSettings(player: Player, s: PlayerSettings) {
        plugin.files.write(settingsFile(player.uniqueId), s.toYaml().saveToString().toByteArray(Charsets.UTF_8))
    }

    private fun discoveryFile(id: UUID, world: String) = File(root, "players/$id/discovery/$world.bin")

    fun loadDiscovery(player: Player, world: World, into: ChunkBitmap) {
        if (!plugin.cfg.saveDiscovery) return
        val bytes = plugin.files.read(discoveryFile(player.uniqueId, world.name)) ?: return
        runCatching { DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes))).use { into.read(it) } }
    }

    fun saveDiscovery(id: UUID, world: String, bits: ChunkBitmap) {
        if (!plugin.cfg.saveDiscovery) return
        val bos = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bos, Deflater(Deflater.BEST_SPEED))).use { bits.write(it) }
        plugin.files.write(discoveryFile(id, world), bos.toByteArray())
    }

    fun autosave(map: PlayerMap) {
        val world = map.discoveryWorld() ?: return
        if (!map.discoveryDirty()) return
        saveDiscovery(map.player.uniqueId, world, map.discovered)
    }
}
