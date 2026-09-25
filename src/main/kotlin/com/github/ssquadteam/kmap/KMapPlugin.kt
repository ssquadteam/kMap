package com.github.ssquadteam.kmap

import com.github.ssquadteam.kmap.config.KMapConfig
import com.github.ssquadteam.kmap.config.WorldsConfig
import com.github.ssquadteam.kmap.pack.PackService
import com.github.ssquadteam.kmap.session.MapService
import com.github.ssquadteam.kmap.session.Storage
import com.github.ssquadteam.kmap.terrain.BlockColors
import com.github.ssquadteam.kmap.terrain.MapPalette
import com.github.ssquadteam.kmap.terrain.SurfaceSampler
import com.github.ssquadteam.kmap.world.BlockChangeListener
import com.github.ssquadteam.kmap.world.WorldMaps
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class KMapPlugin : JavaPlugin() {
    lateinit var cfg: KMapConfig
        private set
    lateinit var worldsConfig: WorldsConfig
        private set
    lateinit var packs: PackService
        private set
    lateinit var maps: MapService
        private set
    lateinit var worlds: WorldMaps
        private set
    lateinit var storage: Storage
        private set
    lateinit var sampler: SurfaceSampler
        private set
    lateinit var lang: com.github.ssquadteam.kmap.lang.Lang
        private set
    lateinit var locations: com.github.ssquadteam.kmap.locations.LocationService
        private set
    lateinit var pins: com.github.ssquadteam.kmap.locations.PinService
        private set
    lateinit var textInput: com.github.ssquadteam.kmap.screen.TextInput
        private set
    lateinit var binds: com.github.ssquadteam.kmap.binds.BindService
        private set
    lateinit var screens: com.github.ssquadteam.kmap.screen.ScreenService
        private set
    lateinit var bakes: com.github.ssquadteam.kmap.world.BakeService
        private set
    lateinit var areas: com.github.ssquadteam.kmap.world.AreaService
        private set
    lateinit var files: com.github.ssquadteam.kmap.storage.AsyncFiles
        private set
    lateinit var blocks: BlockChangeListener
        private set
    private val tasks = ArrayList<ScheduledTask>()

    fun pluginFile(): File = file

    override fun onEnable() {
        saveDefaultConfig()
        loadConfigs()
        getResource("kmap/map_palette.txt")!!.use { MapPalette.load(it) }
        com.github.ssquadteam.kmap.terrain.Colormaps.install(this)
        sampler = SurfaceSampler(getResource("kmap/block_colors.txt")!!.use { BlockColors(it) })
        files = com.github.ssquadteam.kmap.storage.AsyncFiles(logger)
        storage = Storage(this)
        lang = com.github.ssquadteam.kmap.lang.Lang(this, cfg.language)
        lang.load()
        locations = com.github.ssquadteam.kmap.locations.LocationService(File(dataFolder, "locations.yml")) { logger.warning(it) }
        locations.load()
        pins = com.github.ssquadteam.kmap.locations.PinService(this)
        textInput = com.github.ssquadteam.kmap.screen.TextInput(this)
        binds = com.github.ssquadteam.kmap.binds.BindService(this)
        screens = com.github.ssquadteam.kmap.screen.ScreenService(this)
        bakes = com.github.ssquadteam.kmap.world.BakeService(this)
        areas = com.github.ssquadteam.kmap.world.AreaService(this)
        worlds = WorldMaps(this)
        packs = PackService(this)
        packs.setClips(listOf(com.github.ssquadteam.kmap.screen.Win.LIST_CLIP, com.github.ssquadteam.kmap.screen.Win.SEARCH_CLIP))
        packs.start()
        maps = MapService(this)
        blocks = BlockChangeListener(this)
        server.pluginManager.registerEvents(maps, this)
        server.pluginManager.registerEvents(blocks, this)
        server.pluginManager.registerEvents(worlds, this)
        server.pluginManager.registerEvents(binds, this)
        server.pluginManager.registerEvents(screens, this)
        server.pluginManager.registerEvents(bakes, this)
        bakes.start()
        tasks += Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, { worlds.pump() }, 1L, 1L)
        tasks += Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, { blocks.flush() }, 20L, 20L)
        tasks += Bukkit.getAsyncScheduler().runAtFixedRate(this, { worlds.evict() }, 30L, 30L, java.util.concurrent.TimeUnit.SECONDS)
        val autosave = cfg.autosaveSeconds.toLong()
        tasks += Bukkit.getAsyncScheduler().runAtFixedRate(this, { autosave() }, autosave, autosave, java.util.concurrent.TimeUnit.SECONDS)
        lifecycleManager.registerEventHandler(io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS) { e ->
            e.registrar().register("kmap", listOf("kminimap"), com.github.ssquadteam.kmap.commands.KMapCommand(this))
            e.registrar().register("sens", listOf("sensitivity"), com.github.ssquadteam.kmap.commands.SensitivityCommand(this))
            for (c in binds.commandBinds()) {
                val parts = c.split(' ')
                if (parts.size == 1) runCatching { e.registrar().register(parts[0], binds.commandExecutor()) }
            }
        }
        for (p in Bukkit.getOnlinePlayers()) {
            maps.attach(p)
            p.scheduler.run(this, { packs.send(p) }, null)
        }
    }

    override fun onDisable() {
        tasks.forEach { it.cancel() }
        for (p in Bukkit.getOnlinePlayers()) maps.detach(p)
        worlds.flush()
        files.shutdown()
        worlds.flushNow()
        packs.stop()
    }

    private fun autosave() {
        for (m in maps.all()) storage.autosave(m)
        worlds.flush()
    }

    fun reload() {
        loadConfigs()
        lang = com.github.ssquadteam.kmap.lang.Lang(this, cfg.language)
        lang.load()
        locations.load()
        packs.rebuild()
        for (m in maps.all()) {
            m.player.scheduler.run(this, { m.onSettingsChanged(); m.onWaypointsChanged() }, null)
        }
    }

    fun loadConfigs() {
        val defaults = YamlConfiguration.loadConfiguration(getResource("config.yml")!!.reader())
        cfg = KMapConfig.load(File(dataFolder, "config.yml"), defaults)
        worldsConfig = WorldsConfig(File(dataFolder, "worlds.yml"))
        worldsConfig.load { logger.warning(it) }
    }

    fun contentIcons(): List<Pair<String, BufferedImage>> {
        val out = ArrayList<Pair<String, BufferedImage>>()
        for (dir in listOf("contents/icons", "contents/locations")) {
            val d = File(dataFolder, dir)
            d.mkdirs()
            d.listFiles { f -> f.isFile && f.name.endsWith(".png", true) }?.sortedBy { it.name }?.forEach { f ->
                val img = runCatching { ImageIO.read(f) }.getOrNull() ?: return@forEach
                if (img.width > 256 || img.height > 256) {
                    logger.warning("${f.name} is larger than 256 px and cannot be packed as a map icon")
                    return@forEach
                }
                val argb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
                argb.graphics.drawImage(img, 0, 0, null)
                out += (dir.substringAfter('/') + "_" + f.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9_]"), "_")) to argb
            }
        }
        return out
    }
}
