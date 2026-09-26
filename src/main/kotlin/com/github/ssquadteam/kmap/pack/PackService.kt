package com.github.ssquadteam.kmap.pack

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.HostingMode
import com.github.ssquadteam.kmap.config.MergeTarget
import com.github.ssquadteam.kmap.render.Glyphs
import com.google.gson.JsonPrimitive
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class PackService(private val plugin: KMapPlugin) {
    private var host: PackHost? = null
    var base: BuiltPack? = null
        private set
    private var baseUrl: String? = null
    val baseId: UUID = UUID.nameUUIDFromBytes("kmap:base".toByteArray())
    lateinit var glyphs: Glyphs
        private set
    private val loaded = ConcurrentHashMap.newKeySet<UUID>()
    var merger: PackMerger? = null
        private set
    val mergedIntoPlugin: Boolean get() = merger != null
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
        for ((name, img) in plugin.contentIcons()) {
            val pin = GeneratedArt.pinOutline(img)
            art.add("user_$name", img, 33)
            art.add("user_${name}_pin", pin, 33)
            art.scaled("user_${name}_pin_big", "kmap:gen/user_${name}_pin.png", art.glyphs["user_${name}_pin"]!!, pin.height + PIN_GROW)
        }
        for (n in baseGlyphs.names().filter { it.startsWith("loc_") && it.endsWith("_outline") }) {
            val g = baseGlyphs[n]
            art.scaled(n + "_big", "kmap:ui/$n.png", g, g.height + PIN_GROW)
        }
        art.write(builder, spaceProvider())
        glyphs = baseGlyphs.withExtra(art.glyphs)

        builder.putText(
            "pack.mcmeta",
            "{\"pack\":{\"description\":${JsonPrimitive(cfg.packDescription)},\"min_format\":88,\"max_format\":88}}",
        )
        for (entry in cfg.mergePacks) mergeExternal(builder, File(plugin.dataFolder, entry))
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
        merger = pickMerger()?.takeIf { it.install(out, built.sha1Hex) }
        plugin.logger.info("Resource pack ${built.sha1Hex} (${built.bytes.size / 1024} KB)" + (merger?.let { ", merged into ${it.pluginName}" } ?: ""))
        if (merger == null) hostingNotice(out)
    }

    private fun pickMerger(): PackMerger? {
        val pm = Bukkit.getPluginManager()
        fun nexo() = if (pm.isPluginEnabled("Nexo")) NexoPackMerger(plugin) else null
        fun itemsAdder() = if (pm.isPluginEnabled("ItemsAdder")) FolderPackMerger.itemsAdder(plugin) else null
        fun oraxen() = if (pm.isPluginEnabled("Oraxen")) FolderPackMerger.oraxen(plugin) else null
        return when (plugin.cfg.mergeTarget) {
            MergeTarget.NONE -> null
            MergeTarget.NEXO -> nexo()
            MergeTarget.ITEMSADDER -> itemsAdder()
            MergeTarget.ORAXEN -> oraxen()
            MergeTarget.AUTO -> nexo() ?: itemsAdder() ?: oraxen()
        }
    }

    private fun hostingNotice(zip: File) {
        val cfg = plugin.cfg
        val log = plugin.logger
        when {
            cfg.hostingMode == HostingMode.NONE || (cfg.hostingMode == HostingMode.EXTERNAL && cfg.externalUrl.isBlank()) -> {
                log.warning("No Nexo, ItemsAdder or Oraxen found and no pack hosting is set up, so players will not get the kMap resource pack.")
                log.warning("Upload ${zip.path} to a resource pack host (for example mc-packs.net or your own web server),")
                log.warning("then set resourcepack.hosting.mode to EXTERNAL and resourcepack.hosting.external.url to the direct download link in config.yml.")
                log.warning("Or set resourcepack.hosting.mode to SELF_HOST to let kMap serve the pack on port ${cfg.selfHostPort}.")
            }
            cfg.hostingMode == HostingMode.SELF_HOST && baseUrl?.contains("127.0.0.1") == true -> {
                log.warning("kMap is serving its pack at $baseUrl, which only this machine can reach.")
                log.warning("Set resourcepack.hosting.selfHost.publicAddress to your public IP or domain so players can download it.")
            }
        }
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
            val m = merger
            if (m != null) m.reload() else for (p in Bukkit.getOnlinePlayers()) send(p)
        }, 40L)
    }

    fun send(player: Player) {
        val built = base ?: return
        val url = baseUrl ?: return
        if (merger != null) return
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

private const val PIN_GROW = 4
