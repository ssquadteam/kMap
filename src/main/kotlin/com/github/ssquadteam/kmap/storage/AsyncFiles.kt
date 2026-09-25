package com.github.ssquadteam.kmap.storage

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class AsyncFiles(private val logger: Logger) {
    private val pending = ConcurrentHashMap<File, ByteArray>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kMap-io").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun write(file: File, bytes: ByteArray) {
        pending[file] = bytes
        submit {
            val b = pending[file] ?: return@submit
            writeNow(file, b)
            pending.remove(file, b)
        }
    }

    fun read(file: File): ByteArray? = pending[file] ?: if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null

    fun submit(task: () -> Unit) {
        if (executor.isShutdown) {
            task()
            return
        }
        executor.execute {
            try {
                task()
            } catch (t: Throwable) {
                logger.warning("kMap storage task failed: $t")
            }
        }
    }

    fun writeNow(file: File, bytes: ByteArray) {
        try {
            file.parentFile.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (t: Throwable) {
            logger.warning("kMap could not write ${file.path}: $t")
        }
    }

    fun shutdown() {
        executor.shutdown()
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) logger.warning("kMap storage did not finish writing in time")
        for ((file, bytes) in pending) writeNow(file, bytes)
        pending.clear()
    }
}
