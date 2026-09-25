package com.github.ssquadteam.kmap.pack

import com.github.ssquadteam.kmap.KMapPlugin
import org.bukkit.Bukkit
import java.io.File
import java.util.zip.ZipInputStream

interface PackMerger {
    val pluginName: String

    fun install(zip: File, sha1: String): Boolean

    fun reload()
}

class FolderPackMerger(
    private val plugin: KMapPlugin,
    override val pluginName: String,
    private val target: File,
    private val reloadCommand: String,
) : PackMerger {
    private val manifest get() = File(target, ".kmap-files")

    override fun install(zip: File, sha1: String): Boolean {
        return try {
            val old = if (manifest.isFile) manifest.readLines() else emptyList()
            if (old.firstOrNull() == sha1) return true
            for (path in old.drop(1)) File(target, path).takeIf { it.isFile }?.delete()
            val written = ArrayList<String>()
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val e = input.nextEntry ?: break
                    if (e.isDirectory || e.name == "pack.mcmeta" || e.name == "pack.png") continue
                    val out = File(target, e.name)
                    if (!out.canonicalPath.startsWith(target.canonicalPath)) continue
                    out.parentFile.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                    written.add(e.name)
                }
            }
            target.mkdirs()
            manifest.writeText((listOf(sha1) + written).joinToString("\n"))
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { reload() }, 100L)
            true
        } catch (t: Throwable) {
            plugin.logger.warning("Could not merge the kMap pack into $pluginName: $t")
            false
        }
    }

    override fun reload() {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reloadCommand)
    }

    companion object {
        fun itemsAdder(plugin: KMapPlugin): FolderPackMerger {
            val root = File(plugin.dataFolder.parentFile, "ItemsAdder")
            val contents = File(root, "contents")
            val target = if (contents.isDirectory) File(contents, "kmap/resourcepack") else File(root, "data/resource_pack")
            return FolderPackMerger(plugin, "ItemsAdder", target, "iazip")
        }

        fun oraxen(plugin: KMapPlugin): FolderPackMerger =
            FolderPackMerger(plugin, "Oraxen", File(plugin.dataFolder.parentFile, "Oraxen/pack"), "oraxen reload pack")
    }
}
