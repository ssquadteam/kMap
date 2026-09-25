package com.github.ssquadteam.kmap.world

import com.github.ssquadteam.kmap.KMapPlugin
import com.github.ssquadteam.kmap.config.AreaEntry
import com.github.ssquadteam.kmap.config.BakeSource
import com.github.ssquadteam.kmap.config.WorldEntry
import com.github.ssquadteam.kmap.pack.PackBuilder
import com.github.ssquadteam.kmap.session.PlayerMap
import com.github.ssquadteam.kmap.terrain.SampleOptions
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.imageio.ImageIO

class BakeService(private val plugin: KMapPlugin) : Listener {
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val packs = ConcurrentHashMap<String, Pair<ByteArray, String>>()

    private val bakeDir get() = File(plugin.dataFolder, "data/bakes")
    private val cacheDir get() = File(plugin.dataFolder, "cache/bakepacks")

    fun mergedIntoBase(): Boolean = plugin.cfg.mergeBakes

    fun onEnterWorld(map: PlayerMap) {
        val wm = map.worldMap() ?: return
        if (wm.bakes.isEmpty() || mergedIntoBase()) return
        val pack = packs[wm.world.name] ?: return
        if (!map.sentPacks.add(wm.world.name + ":" + pack.second)) return
        val url = plugin.packs.publish("bake-${pack.second}.zip", pack.first) ?: return
        plugin.packs.sendExtra(map.player, packId(wm.world.name), url, pack.second)
    }

    fun packId(world: String): UUID = UUID.nameUUIDFromBytes("kmap:bake:$world".toByteArray())

    fun start() {
        for (w in Bukkit.getWorlds()) prepare(w, false)
        for (entry in plugin.worldsConfig.all()) {
            if (Bukkit.getWorld(entry.name) != null || !entry.loadAtStart) continue
            val folder = File(Bukkit.getWorldContainer(), entry.name)
            if (!File(folder, "level.dat").isFile) {
                plugin.logger.warning("worlds.yml names '${entry.name}' but no world folder with a level.dat exists there")
                continue
            }
            if (isFolia()) {
                plugin.logger.warning("World '${entry.name}' is not loaded and Folia cannot load worlds at runtime; load it with your world setup instead")
                continue
            }
            Bukkit.getGlobalRegionScheduler().execute(plugin) { runCatching { org.bukkit.WorldCreator(entry.name).createWorld() } }
        }
    }

    private fun isFolia(): Boolean = runCatching { Class.forName("io.papermc.paper.threadedregions.RegionizedServer") }.isSuccess

    @EventHandler
    fun onWorldLoad(e: WorldLoadEvent) {
        prepare(e.world, false)
    }

    private fun areasOf(entry: WorldEntry): List<AreaEntry> = entry.areas.ifEmpty {
        listOf(AreaEntry("main", entry.x, entry.z, entry.sizeX.coerceAtMost(8192), entry.sizeZ.coerceAtMost(8192)))
    }

    fun prepare(world: World, force: Boolean, sender: CommandSender? = null) {
        val entry = plugin.worldsConfig.entry(world.name) ?: return
        val wm = plugin.worlds.of(world)
        if (!wm.mode.baked || entry.bake == BakeSource.OFF) return
        val areas = areasOf(entry)
        val loaded = ArrayList<BakedMap>()
        var missing = false
        for (a in areas) {
            val file = File(bakeDir, "${world.name}/${a.name}.png")
            val meta = File(bakeDir, "${world.name}/${a.name}.yml")
            if (!force && entry.persistBake && file.isFile && meta.isFile && metaMatches(meta, entry, a)) {
                loaded.add(bakedFrom(world.name, a, meta))
            } else {
                missing = true
            }
        }
        if (!missing) {
            wm.bakes = loaded
            rebuildPack(world.name)
            return
        }
        if (!entry.autoRender && !force) return
        if (!running.add(world.name)) return
        val started = System.currentTimeMillis()
        plugin.logger.info("Map of '${world.name}' (${entry.bake}) is rendering - no cached map yet. ${areas.size} area(s), rgb=true. This can take a few minutes...")
        CompletableFuture.runAsync {
            try {
                val results = ArrayList<BakedMap>()
                for (a in areas) {
                    val grid = scan(world, entry, a)
                    if (grid == null) {
                        plugin.logger.warning("Bake of '${world.name}' area '${a.name}' found no generated chunks in its box (centre ${a.centerX}, ${a.centerZ}, ${a.width} x ${a.height})")
                        continue
                    }
                    val pixels = BakeRenderer.render(grid, entry.bake, 1.0 + entry.surfaceBrightness / 100.0)
                    val ox = a.centerX - a.width / 2
                    val oz = a.centerZ - a.height / 2
                    val img = BakeRenderer.sprite(pixels, grid.width, grid.height, ox, oz, 1.0)
                    val dir = File(bakeDir, world.name)
                    dir.mkdirs()
                    ImageIO.write(img, "png", File(dir, "${a.name}.png"))
                    val y = YamlConfiguration()
                    y.set("originX", ox)
                    y.set("originZ", oz)
                    y.set("width", grid.width)
                    y.set("height", grid.height)
                    y.set("source", entry.bake.name)
                    y.set("centerX", a.centerX)
                    y.set("centerZ", a.centerZ)
                    y.set("sizeX", a.width)
                    y.set("sizeZ", a.height)
                    y.save(File(dir, "${a.name}.yml"))
                    results.add(bakedFrom(world.name, a, File(dir, "${a.name}.yml")))
                }
                wm.bakes = results
                rebuildPack(world.name)
                val secs = (System.currentTimeMillis() - started) / 1000
                plugin.logger.info("Map of '${world.name}' is READY - ${results.sumOf { it.widthPx }}x${results.maxOfOrNull { it.heightPx } ?: 0} px in ${secs}s (cached to data/bakes/).")
                sender?.sendMessage(Component.text(plugin.lang.get(sender as? org.bukkit.entity.Player, "command.map_ready", world.name)))
                for (m in plugin.maps.all()) {
                    if (m.player.world == world) m.player.scheduler.run(plugin, { m.enterWorld() }, null)
                }
            } catch (t: Throwable) {
                plugin.logger.warning("Bake of '${world.name}' failed: ${t.message}")
                t.printStackTrace()
            } finally {
                running.remove(world.name)
            }
        }
    }

    fun rebake(world: World, sender: CommandSender) {
        File(bakeDir, world.name).deleteRecursively()
        prepare(world, true, sender)
    }

    private fun metaMatches(meta: File, entry: WorldEntry, a: AreaEntry): Boolean {
        val y = YamlConfiguration.loadConfiguration(meta)
        return y.getInt("centerX") == a.centerX && y.getInt("centerZ") == a.centerZ && y.getInt("sizeX") == a.width && y.getInt("sizeZ") == a.height
    }

    private fun bakedFrom(world: String, a: AreaEntry, meta: File): BakedMap {
        val y = YamlConfiguration.loadConfiguration(meta)
        return BakedMap(world, y.getInt("originX"), y.getInt("originZ"), y.getInt("width"), y.getInt("height"), 1.0, spriteName(world, a.name), File(meta.parentFile, "${a.name}.png"))
    }

    fun spriteName(world: String, area: String): String = "bake/" + (world + "_" + area).lowercase().replace(Regex("[^a-z0-9_]"), "_")

    private fun scan(world: World, entry: WorldEntry, a: AreaEntry): BakeGrid? {
        val w = a.width
        val h = a.height
        val ox = a.centerX - w / 2
        val oz = a.centerZ - h / 2
        val grid = BakeGrid(w, h)
        val ceiling = entry.ceiling ?: if (world.environment == World.Environment.NETHER) 123 else null
        val options = SampleOptions(ceiling, entry.skipDecoration, entry.skipBlocks, entry.biomeTint || entry.bake == BakeSource.HD_SURFACE)
        val cx0 = Math.floorDiv(ox, 16)
        val cz0 = Math.floorDiv(oz, 16)
        val cx1 = Math.floorDiv(ox + w - 1, 16)
        val cz1 = Math.floorDiv(oz + h - 1, 16)
        val permits = Semaphore(24)
        var any = false
        val total = (cx1 - cx0 + 1).toLong() * (cz1 - cz0 + 1)
        var done = 0L
        var lastLog = System.currentTimeMillis()
        for (cz in cz0..cz1) {
            val row = ArrayList<CompletableFuture<Void>>()
            for (cx in cx0..cx1) {
                permits.acquire()
                val f = CompletableFuture<Void>()
                row.add(f)
                world.getChunkAtAsync(cx, cz, false).whenComplete { chunk, _ ->
                    if (chunk == null) {
                        permits.release()
                        f.complete(null)
                        return@whenComplete
                    }
                    Bukkit.getRegionScheduler().execute(plugin, world, cx, cz) {
                        try {
                            val nms = (world as CraftWorld).handle.getChunkIfLoaded(cx, cz)
                            if (nms != null) {
                                val s = plugin.sampler.sample(nms, options)
                                synchronized(grid) {
                                    any = true
                                    for (lz in 0 until 16) for (lx in 0 until 16) {
                                        val gx = cx * 16 + lx - ox
                                        val gz = cz * 16 + lz - oz
                                        if (gx < 0 || gz < 0 || gx >= w || gz >= h) continue
                                        val si = lz * 16 + lx
                                        val hh = s.heights[si].toInt()
                                        if (hh == Short.MIN_VALUE.toInt()) continue
                                        val gi = gz * w + gx
                                        grid.heights[gi] = hh
                                        grid.rgb[gi] = s.rgb[si]
                                        grid.water[gi] = s.water[si]
                                        grid.waterRgb[gi] = s.waterRgb[si]
                                    }
                                }
                            }
                        } finally {
                            permits.release()
                            f.complete(null)
                        }
                    }
                }
            }
            CompletableFuture.allOf(*row.toTypedArray()).join()
            done += row.size
            if (plugin.cfg.debug && System.currentTimeMillis() - lastLog > 5000) {
                lastLog = System.currentTimeMillis()
                plugin.logger.info("Baking '${world.name}': ${done * 100 / total}%")
            }
        }
        return if (any) grid else null
    }

    fun rebuildPack(world: String) {
        val wm = plugin.worlds.all().firstOrNull { it.world.name == world } ?: return
        if (wm.bakes.isEmpty()) return
        val builder = PackBuilder()
        builder.putText("pack.mcmeta", "{\"pack\":{\"description\":\"kMap map of $world\",\"min_format\":88,\"max_format\":88}}")
        addBakeFiles(builder, wm.bakes, world)
        val built = builder.build()
        packs[world] = built.bytes to built.sha1Hex
        cacheDir.mkdirs()
        File(cacheDir, "$world.zip").writeBytes(built.bytes)
        if (mergedIntoBase()) plugin.packs.rebuildLater()
    }

    fun addBakeFiles(builder: PackBuilder, bakes: List<BakedMap>, world: String) {
        val sources = StringBuilder()
        for (b in bakes) {
            val png = if (b.file.isFile) b.file.readBytes() else continue
            builder.put("assets/kmap/textures/${b.sprite}.png", png)
            if (sources.isNotEmpty()) sources.append(',')
            sources.append("{\"type\":\"minecraft:single\",\"resource\":\"kmap:${b.sprite}\",\"sprite\":\"kmap:${b.sprite}\"}")
        }
        builder.putText("assets/minecraft/atlases/map_decorations.json", "{\"sources\":[{\"type\":\"minecraft:directory\",\"source\":\"map/decorations\",\"prefix\":\"\"},$sources]}")
    }

    fun allBakeFiles(builder: PackBuilder) {
        val sources = StringBuilder()
        for (wm in plugin.worlds.all()) {
            val tmp = PackBuilder()
            addBakeFiles(tmp, wm.bakes, wm.world.name)
            for (p in tmp.paths()) {
                if (p.endsWith(".json")) {
                    val json = tmp.get(p)!!.toString(Charsets.UTF_8)
                    val inner = json.substringAfter("\"prefix\":\"\"},", "").removeSuffix("]}")
                    if (inner.isNotBlank()) {
                        if (sources.isNotEmpty()) sources.append(',')
                        sources.append(inner)
                    }
                } else {
                    builder.put(p, tmp.get(p)!!)
                }
            }
        }
        if (sources.isNotEmpty()) builder.putText("assets/minecraft/atlases/map_decorations.json", "{\"sources\":[{\"type\":\"minecraft:directory\",\"source\":\"map/decorations\",\"prefix\":\"\"},$sources]}")
    }

    @Suppress("unused")
    private fun png(img: java.awt.image.BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
}
