package com.github.ssquadteam.kmap.terrain

import net.minecraft.world.level.DryFoliageColor
import net.minecraft.world.level.FoliageColor
import net.minecraft.world.level.GrassColor
import org.bukkit.plugin.java.JavaPlugin
import javax.imageio.ImageIO

object Colormaps {
    fun install(plugin: JavaPlugin) {
        if (GrassColor.getDefaultColor() != 0 && GrassColor.getDefaultColor() != -65281) return
        fun load(name: String): IntArray? = plugin.getResource("kmap/colormap/$name.png")?.use { input ->
            val img = ImageIO.read(input)
            IntArray(img.width * img.height).also { img.getRGB(0, 0, img.width, img.height, it, 0, img.width) }
        }
        load("grass")?.let { GrassColor.init(it) }
        load("foliage")?.let { FoliageColor.init(it) }
        load("dry_foliage")?.let { DryFoliageColor.init(it) }
    }
}
