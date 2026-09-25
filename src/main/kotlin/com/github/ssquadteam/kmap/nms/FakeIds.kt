package com.github.ssquadteam.kmap.nms

import net.minecraft.server.level.ServerLevel
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.CraftWorld

object FakeIds {
    private val level: ServerLevel by lazy { (Bukkit.getWorlds()[0] as CraftWorld).handle }

    fun next(): Int = level.nextEntityId
}
