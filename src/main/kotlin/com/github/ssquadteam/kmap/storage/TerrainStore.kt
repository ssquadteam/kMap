package com.github.ssquadteam.kmap.storage

import com.github.ssquadteam.kmap.terrain.ChunkSurface
import com.github.ssquadteam.kmap.terrain.TerrainCache
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class TerrainStore(private val dir: File, private val files: AsyncFiles, private val signature: Int) {
    private val index = ConcurrentHashMap<Long, AtomicIntegerArray>()
    private val dirty = ConcurrentHashMap<Long, ConcurrentHashMap<Int, ChunkSurface>>()
    private val thumbDirty = ConcurrentHashMap<Long, ConcurrentHashMap<Int, ShortArray>>()
    private val regionCache = object : LinkedHashMap<Long, HashMap<Int, ByteArray>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, HashMap<Int, ByteArray>>?) = size > REGION_CACHE
    }

    @Volatile
    private var indexDirty = false

    private val indexFile get() = File(dir, "index.kmi")

    private fun regionFile(rk: Long) = File(dir, "r.${(rk shr 32).toInt()}.${rk.toInt()}.kmt")

    private fun thumbFile(rk: Long) = File(dir, "h.${(rk shr 32).toInt()}.${rk.toInt()}.kmh")

    fun regionHasChunks(rk: Long): Boolean {
        val arr = index[rk] ?: return false
        for (i in 0 until 1024) if (arr.get(i) != 0) return true
        return false
    }

    fun markThumb(cx: Int, cz: Int, thumb: ShortArray) {
        thumbDirty.computeIfAbsent(rk(cx, cz)) { ConcurrentHashMap() }[idx(cx, cz)] = thumb
    }

    fun loadThumbs(rk: Long, compute: (ChunkSurface) -> ShortArray, done: (Map<Int, ShortArray>) -> Unit) = files.submit {
        val out = HashMap<Int, ShortArray>(decodeThumbs(files.read(thumbFile(rk))))
        thumbDirty[rk]?.let { out.putAll(it) }
        val arr = index[rk]
        if (arr != null) {
            val missing = (0 until 1024).filter { arr.get(it) != 0 && it !in out }
            if (missing.isNotEmpty()) {
                val region = region(rk)
                val rx = (rk shr 32).toInt()
                val rz = rk.toInt()
                for (i in missing) {
                    val s = dirty[rk]?.get(i)
                        ?: region[i]?.let { b -> runCatching { decodeChunk((rx shl 5) + (i and 31), (rz shl 5) + (i shr 5), b) }.getOrNull() }
                        ?: continue
                    val t = compute(s)
                    out[i] = t
                    thumbDirty.computeIfAbsent(rk) { ConcurrentHashMap() }[i] = t
                }
            }
        }
        done(out)
    }

    private fun decodeThumbs(bytes: ByteArray?): Map<Int, ShortArray> {
        val map = HashMap<Int, ShortArray>()
        if (bytes == null) return map
        runCatching {
            DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes))).use { input ->
                if (input.readInt() != THUMB_MAGIC) return map
                repeat(input.readInt()) {
                    val i = input.readUnsignedShort()
                    map[i] = ShortArray(64) { input.readShort() }
                }
            }
        }
        return map
    }

    private fun encodeThumbs(thumbs: Map<Int, ShortArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bos, Deflater(6))).use { out ->
            out.writeInt(THUMB_MAGIC)
            out.writeInt(thumbs.size)
            for ((i, t) in thumbs) {
                out.writeShort(i)
                for (v in t) out.writeShort(v.toInt())
            }
        }
        return bos.toByteArray()
    }

    private fun rk(cx: Int, cz: Int) = TerrainCache.key(cx shr 5, cz shr 5)

    private fun idx(cx: Int, cz: Int) = ((cz and 31) shl 5) or (cx and 31)

    fun open() = files.submit {
        val bytes = files.read(indexFile)
        if (bytes == null) {
            dir.listFiles { f -> f.name.endsWith(".kmt") }?.forEach { it.delete() }
            return@submit
        }
        DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes))).use { input ->
            if (input.readInt() != INDEX_MAGIC || input.readInt() != signature) {
                dir.listFiles()?.forEach { it.delete() }
                return@use
            }
            repeat(input.readInt()) {
                val key = input.readLong()
                val arr = index.computeIfAbsent(key) { AtomicIntegerArray(1024) }
                for (i in 0 until 1024) {
                    val h = input.readInt()
                    if (h != 0) arr.compareAndSet(i, 0, h)
                }
            }
        }
    }

    fun hashOf(cx: Int, cz: Int): Int = index[rk(cx, cz)]?.get(idx(cx, cz)) ?: 0

    fun has(cx: Int, cz: Int): Boolean = hashOf(cx, cz) != 0

    fun mark(surface: ChunkSurface) {
        val rk = rk(surface.cx, surface.cz)
        val i = idx(surface.cx, surface.cz)
        index.computeIfAbsent(rk) { AtomicIntegerArray(1024) }.set(i, surface.hash)
        dirty.computeIfAbsent(rk) { ConcurrentHashMap() }[i] = surface
        indexDirty = true
    }

    fun load(cx: Int, cz: Int, done: (ChunkSurface?) -> Unit) {
        if (!has(cx, cz)) {
            done(null)
            return
        }
        files.submit { done(readNow(cx, cz)) }
    }

    private fun readNow(cx: Int, cz: Int): ChunkSurface? {
        val rk = rk(cx, cz)
        val i = idx(cx, cz)
        dirty[rk]?.get(i)?.let { return it }
        val bytes = region(rk)[i] ?: return null
        return runCatching { decodeChunk(cx, cz, bytes) }.getOrNull()
    }

    private fun region(rk: Long): HashMap<Int, ByteArray> = regionCache.getOrPut(rk) { decodeRegion(files.read(regionFile(rk))) }

    fun flush() = files.submit { flushNow() }

    fun flushNow() {
        for (rk in dirty.keys.toList()) {
            val changes = dirty.remove(rk) ?: continue
            val region = region(rk)
            for ((i, s) in changes) region[i] = encodeChunk(s)
            files.writeNow(regionFile(rk), encodeRegion(region))
        }
        for (rk in thumbDirty.keys.toList()) {
            val changes = thumbDirty.remove(rk) ?: continue
            val all = HashMap(decodeThumbs(files.read(thumbFile(rk))))
            all.putAll(changes)
            files.writeNow(thumbFile(rk), encodeThumbs(all))
        }
        if (indexDirty) {
            indexDirty = false
            files.writeNow(indexFile, encodeIndex())
        }
    }

    private fun encodeIndex(): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bos, Deflater(Deflater.BEST_SPEED))).use { out ->
            val snapshot = index.entries.toList()
            out.writeInt(INDEX_MAGIC)
            out.writeInt(signature)
            out.writeInt(snapshot.size)
            for ((key, arr) in snapshot) {
                out.writeLong(key)
                for (i in 0 until 1024) out.writeInt(arr.get(i))
            }
        }
        return bos.toByteArray()
    }

    private fun encodeRegion(region: Map<Int, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bos, Deflater(6))).use { out ->
            out.writeInt(REGION_MAGIC)
            out.writeInt(region.size)
            for ((i, b) in region) {
                out.writeShort(i)
                out.writeShort(b.size)
                out.write(b)
            }
        }
        return bos.toByteArray()
    }

    private fun decodeRegion(bytes: ByteArray?): HashMap<Int, ByteArray> {
        val map = HashMap<Int, ByteArray>()
        if (bytes == null) return map
        runCatching {
            DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes))).use { input ->
                if (input.readInt() != REGION_MAGIC) return map
                repeat(input.readInt()) {
                    val i = input.readUnsignedShort()
                    val b = ByteArray(input.readUnsignedShort())
                    input.readFully(b)
                    map[i] = b
                }
            }
        }
        return map
    }

    private fun encodeChunk(s: ChunkSurface): ByteArray {
        val rgb = s.rgb.isNotEmpty()
        var waterCols = 0
        if (rgb) for (i in 0 until 256) if (s.water[i].toInt() != 0) waterCols++
        val out = ByteArray(1 + 256 * 2 + 256 + 256 + (if (rgb) 256 * 3 else 0) + waterCols * 3)
        var p = 0
        out[p++] = if (rgb) 1 else 0
        for (i in 0 until 256) {
            val h = s.heights[i].toInt()
            out[p++] = (h shr 8).toByte()
            out[p++] = h.toByte()
        }
        System.arraycopy(s.mapColor, 0, out, p, 256)
        p += 256
        System.arraycopy(s.water, 0, out, p, 256)
        p += 256
        if (!rgb) return out
        for (i in 0 until 256) {
            val c = s.rgb[i]
            out[p++] = (c shr 16).toByte()
            out[p++] = (c shr 8).toByte()
            out[p++] = c.toByte()
        }
        for (i in 0 until 256) {
            if (s.water[i].toInt() == 0) continue
            val c = s.waterRgb[i]
            out[p++] = (c shr 16).toByte()
            out[p++] = (c shr 8).toByte()
            out[p++] = c.toByte()
        }
        return out
    }

    private fun decodeChunk(cx: Int, cz: Int, b: ByteArray): ChunkSurface {
        val rgb = b[0].toInt() == 1
        val s = ChunkSurface.empty(cx, cz, rgb)
        var p = 1
        for (i in 0 until 256) {
            s.heights[i] = (((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)).toShort()
            p += 2
        }
        System.arraycopy(b, p, s.mapColor, 0, 256)
        p += 256
        System.arraycopy(b, p, s.water, 0, 256)
        p += 256
        if (!rgb) {
            s.fromDisk = true
            return s
        }
        for (i in 0 until 256) {
            s.rgb[i] = ((b[p].toInt() and 255) shl 16) or ((b[p + 1].toInt() and 255) shl 8) or (b[p + 2].toInt() and 255)
            p += 3
        }
        for (i in 0 until 256) {
            if (s.water[i].toInt() == 0) continue
            s.waterRgb[i] = ((b[p].toInt() and 255) shl 16) or ((b[p + 1].toInt() and 255) shl 8) or (b[p + 2].toInt() and 255)
            p += 3
        }
        s.fromDisk = true
        return s
    }

    companion object {
        private const val INDEX_MAGIC = 0x4B4D4931
        private const val REGION_MAGIC = 0x4B4D5431
        private const val THUMB_MAGIC = 0x4B4D4831
        private const val REGION_CACHE = 12
        const val FORMAT = 3
    }
}
