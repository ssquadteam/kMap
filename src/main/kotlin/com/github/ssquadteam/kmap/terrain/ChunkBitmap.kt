package com.github.ssquadteam.kmap.terrain

import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

class ChunkBitmap {
    private val regions = ConcurrentHashMap<Long, AtomicLongArray>()
    private val count = AtomicInteger()
    private val mods = AtomicLong()

    val size: Int get() = count.get()
    val modCount: Long get() = mods.get()

    fun add(cx: Int, cz: Int): Boolean {
        val words = regions.computeIfAbsent(TerrainCache.key(cx shr 5, cz shr 5)) { AtomicLongArray(16) }
        val idx = ((cz and 31) shl 5) or (cx and 31)
        val bit = 1L shl (idx and 63)
        while (true) {
            val old = words.get(idx shr 6)
            if (old and bit != 0L) return false
            if (words.compareAndSet(idx shr 6, old, old or bit)) {
                count.incrementAndGet()
                mods.incrementAndGet()
                return true
            }
        }
    }

    fun contains(cx: Int, cz: Int): Boolean {
        val words = regions[TerrainCache.key(cx shr 5, cz shr 5)] ?: return false
        val idx = ((cz and 31) shl 5) or (cx and 31)
        return words.get(idx shr 6) and (1L shl (idx and 63)) != 0L
    }

    fun contains(key: Long): Boolean = contains((key shr 32).toInt(), key.toInt())

    fun clear() {
        regions.clear()
        count.set(0)
        mods.incrementAndGet()
    }

    fun write(out: DataOutputStream) {
        val snapshot = regions.entries.toList()
        out.writeInt(MAGIC)
        out.writeInt(snapshot.size)
        for ((key, words) in snapshot) {
            out.writeLong(key)
            for (i in 0 until 16) out.writeLong(words.get(i))
        }
    }

    fun read(input: DataInputStream) {
        val first = input.readInt()
        if (first != MAGIC) {
            repeat(first) {
                val k = input.readLong()
                add((k shr 32).toInt(), k.toInt())
            }
            return
        }
        repeat(input.readInt()) {
            val key = input.readLong()
            val words = regions.computeIfAbsent(key) { AtomicLongArray(16) }
            for (i in 0 until 16) {
                val v = input.readLong()
                val added = v and words.get(i).inv()
                words.set(i, words.get(i) or v)
                count.addAndGet(added.countOneBits())
            }
        }
        mods.incrementAndGet()
    }

    companion object {
        private const val MAGIC = 0x4B4D4232
    }
}
