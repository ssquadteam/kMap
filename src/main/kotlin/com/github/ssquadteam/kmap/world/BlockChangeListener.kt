package com.github.ssquadteam.kmap.world

import com.github.ssquadteam.kmap.KMapPlugin
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.world.StructureGrowEvent
import java.util.concurrent.ConcurrentHashMap

class BlockChangeListener(private val plugin: KMapPlugin) : Listener {
    private val dirty = ConcurrentHashMap<World, MutableSet<Long>>()

    private fun mark(b: Block) {
        dirty.computeIfAbsent(b.world) { ConcurrentHashMap.newKeySet() }.add((b.x shr 4).toLong() shl 32 or ((b.z shr 4).toLong() and 0xFFFFFFFFL))
    }

    fun consume(world: World, cx: Int, cz: Int): Boolean = dirty[world]?.remove((cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)) == true

    fun flush() {
        for ((world, set) in dirty) {
            val it = set.iterator()
            while (it.hasNext()) {
                val k = it.next()
                it.remove()
                plugin.worlds.invalidate(world, (k shr 32).toInt(), k.toInt())
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun place(e: BlockPlaceEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun brk(e: BlockBreakEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun burn(e: BlockBurnEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun fade(e: BlockFadeEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun form(e: BlockFormEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun grow(e: BlockGrowEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun spread(e: BlockSpreadEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun decay(e: LeavesDecayEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun flow(e: BlockFromToEvent) = mark(e.toBlock)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun bucket(e: PlayerBucketEmptyEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun fill(e: PlayerBucketFillEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun tree(e: StructureGrowEvent) = e.blocks.forEach { mark(it.block) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun boom(e: EntityExplodeEvent) = e.blockList().forEach { mark(it) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun boom2(e: BlockExplodeEvent) = e.blockList().forEach { mark(it) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun entity(e: org.bukkit.event.entity.EntityChangeBlockEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun pistonOut(e: org.bukkit.event.block.BlockPistonExtendEvent) = piston(e.block, e.blocks, e.direction)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun pistonIn(e: org.bukkit.event.block.BlockPistonRetractEvent) = piston(e.block, e.blocks, e.direction)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun sponge(e: org.bukkit.event.block.SpongeAbsorbEvent) { mark(e.block); e.blocks.forEach { mark(it.block) } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun fertilize(e: org.bukkit.event.block.BlockFertilizeEvent) = e.blocks.forEach { mark(it.block) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun fluid(e: org.bukkit.event.block.FluidLevelChangeEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun moisture(e: org.bukkit.event.block.MoistureChangeEvent) = mark(e.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun dispense(e: org.bukkit.event.block.BlockDispenseEvent) = mark(e.block.getRelative((e.block.blockData as? org.bukkit.block.data.Directional)?.facing ?: org.bukkit.block.BlockFace.SELF))
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) fun destroy(e: com.destroystokyo.paper.event.block.BlockDestroyEvent) = mark(e.block)

    private fun piston(base: Block, moved: List<Block>, dir: org.bukkit.block.BlockFace) {
        mark(base)
        mark(base.getRelative(dir))
        for (b in moved) {
            mark(b)
            mark(b.getRelative(dir))
        }
    }
}
