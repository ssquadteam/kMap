package com.github.ssquadteam.kmap.pack

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.TreeMap
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BuiltPack(val bytes: ByteArray, val sha1: ByteArray) {
    val sha1Hex: String = sha1.joinToString("") { "%02x".format(it) }
}

class PackBuilder {
    private val entries = TreeMap<String, ByteArray>()

    fun put(path: String, bytes: ByteArray): PackBuilder {
        entries[path] = bytes
        return this
    }

    fun putText(path: String, text: String): PackBuilder = put(path, text.toByteArray(Charsets.UTF_8))

    fun has(path: String): Boolean = entries.containsKey(path)

    fun get(path: String): ByteArray? = entries[path]

    fun remove(path: String) {
        entries.remove(path)
    }

    fun paths(): Set<String> = entries.keys

    fun addJarDirectory(jar: File, prefix: String): PackBuilder {
        JarFile(jar).use { jf ->
            for (entry in jf.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                put(entry.name.removePrefix(prefix), jf.getInputStream(entry).readBytes())
            }
        }
        return this
    }

    fun addDirectory(dir: File): PackBuilder {
        if (!dir.isDirectory) return this
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            put(f.relativeTo(dir).invariantSeparatorsPath, f.readBytes())
        }
        return this
    }

    fun build(): BuiltPack {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, bytes) in entries) {
                val e = ZipEntry(path)
                e.time = 0L
                zip.putNextEntry(e)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val bytes = out.toByteArray()
        return BuiltPack(bytes, MessageDigest.getInstance("SHA-1").digest(bytes))
    }
}
