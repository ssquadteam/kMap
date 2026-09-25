package com.github.ssquadteam.kmap.pack

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.HostingMode
import com.github.ssquadteam.kmap.config.MergeTarget
import com.github.ssquadteam.kmap.render.Glyphs
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

class PackService(private val plugin: KMapPlugin) {
    private var host: PackHost? = null
    var base: BuiltPack? = null
        private set
    private var baseUrl: String? = null
    val baseId: UUID = UUID.nameUUIDFromBytes("kmap:base".toByteArray())
    lateinit var glyphs: Glyphs
        private set
    private val loaded = ConcurrentHashMap.newKeySet<UUID>()
    var mergedIntoNexo = false
        private set
    private var clips: List<DoubleArray> = emptyList()

    fun setClips(value: List<DoubleArray>) {
        clips = value
    }

    fun isLoaded(player: Player): Boolean = player.uniqueId in loaded

    fun markLoaded(player: Player, value: Boolean) {
        if (value) loaded.add(player.uniqueId) else loaded.remove(player.uniqueId)
    }

    fun start() {
        val cfg = plugin.cfg
        if (cfg.hostingMode == HostingMode.SELF_HOST && host == null) {
            val address = if (cfg.selfHostPublicAddress.equals("auto", true)) {
                Bukkit.getIp().ifBlank { "127.0.0.1" }.let { if (it == "0.0.0.0") "127.0.0.1" else it }
            } else {
                cfg.selfHostPublicAddress
            }
            host = PackHost(cfg.selfHostBindIp, cfg.selfHostPort, address).also { it.start() }
        }
        rebuild()
    }

    fun stop() {
        host?.stop()
        host = null
    }

    fun publish(name: String, bytes: ByteArray): String? = host?.publish(name, bytes)

    private fun spaceProvider(): String {
        val sb = StringBuilder("{\"type\":\"space\",\"advances\":{")
        for (i in 0 until 14) {
            sb.append("\"\\u%04x\":%d,".format(0xE100 + i, 1 shl i))
            sb.append("\"\\u%04x\":%d,".format(0xE120 + i, -(1 shl i)))
        }
        sb.append("\"\\ue140\":0.5,\"\\ue141\":-0.5}}")
        return sb.toString()
    }

    fun rebuild() {
        val cfg = plugin.cfg
        val builder = PackBuilder().addJarDirectory(plugin.pluginFile(), "pack/")
        val baseGlyphs = plugin.getResource("kmap/glyphs.json")!!.use { Glyphs.load(it) }
        val art = GeneratedArt()
        art.add("mini_square", GeneratedArt.squareFrame(cfg.minimapSize))
        art.add("mini_circle", GeneratedArt.circleFrame(cfg.minimapSize))
        art.add("mini_bg_square", GeneratedArt.rect(cfg.minimapSize, cfg.minimapSize, 0xFF141418.toInt()))
        for ((n, img) in GeneratedArt.bigFrameSegments()) art.add(n, img)
        plugin.contentIcons().forEach { (name, img) -> art.add("user_$name", img, 33) }
        art.write(builder, spaceProvider())
        glyphs = baseGlyphs.withExtra(art.glyphs)

        builder.putText(
            "pack.mcmeta",
            "{\"pack\":{\"description\":${com.google.gson.JsonPrimitive(cfg.packDescription)},\"min_format\":88,\"max_format\":88}}",
        )
        for (entry in cfg.mergePacks) mergeExternal(builder, File(plugin.dataFolder, entry))
        if (cfg.mergeBakes) plugin.bakes.allBakeFiles(builder)
        builder.addDirectory(File(plugin.dataFolder, "contents/pack_overlay"))
        builder.addDirectory(File(plugin.dataFolder, "dev-pack"))
        for (path in listOf("assets/minecraft/shaders/core/text.vsh")) {
            val text = builder.get(path)!!.toString(Charsets.UTF_8).replace("/*KMAP_DEFINES*/", ShaderDefines.vertex(cfg, clips))
            builder.putText(path, text)
        }
        run {
            val path = "assets/minecraft/shaders/core/text.fsh"
            builder.putText(path, builder.get(path)!!.toString(Charsets.UTF_8).replace("/*KMAP_FDEFINES*/", ShaderDefines.fragment()))
        }
        val built = builder.build()
        base = built
        val out = File(plugin.dataFolder, "resourcepack/generated.zip")
        out.parentFile.mkdirs()
        out.writeBytes(built.bytes)
        baseUrl = when (cfg.hostingMode) {
            HostingMode.SELF_HOST -> host?.publish("kmap-${built.sha1Hex}.zip", built.bytes)
            HostingMode.EXTERNAL -> cfg.externalUrl.ifBlank { null }
            HostingMode.NONE -> null
        }
        mergedIntoNexo = false
        if (cfg.mergeTarget != MergeTarget.NONE && Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            mergedIntoNexo = NexoHook.install(plugin, out)
        }
        plugin.logger.info("Resource pack ${built.sha1Hex} (${built.bytes.size / 1024} KB)" + if (mergedIntoNexo) ", merged into Nexo" else "")
    }

    private fun mergeExternal(builder: PackBuilder, file: File) {
        if (file.isDirectory) {
            builder.addDirectory(file)
        } else if (file.isFile) {
            ZipFile(file).use { zip ->
                for (e in zip.entries()) {
                    if (e.isDirectory) continue
                    if (builder.has(e.name)) continue
                    builder.put(e.name, zip.getInputStream(e).readBytes())
                }
            }
        }
    }

    @Volatile
    private var rebuildQueued = false

    fun rebuildLater() {
        if (rebuildQueued) return
        rebuildQueued = true
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, {
            rebuildQueued = false
            rebuild()
            if (mergedIntoNexo) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "nexo reload pack")
            } else {
                for (p in Bukkit.getOnlinePlayers()) send(p)
            }
        }, 40L)
    }

    fun send(player: Player) {
        val built = base ?: return
        val url = baseUrl ?: return
        if (mergedIntoNexo) return
        val info = ResourcePackInfo.resourcePackInfo(baseId, URI.create(url), built.sha1Hex)
        player.sendResourcePacks(
            ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .replace(false)
                .required(plugin.cfg.packRequired)
                .prompt(Component.text(plugin.cfg.packPrompt))
                .build(),
        )
    }

    fun sendExtra(player: Player, id: UUID, url: String, sha1: String) {
        player.sendResourcePacks(
            ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(id, URI.create(url), sha1))
                .replace(false)
                .required(false)
                .build(),
        )
    }
}
