package com.autonomousone.messages.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import com.autonomousone.messages.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.Writer
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Privacy-aware, rotating on-device diagnostic log with system health snapshots.
 *
 * Callers never touch the disk: records are formatted on the calling thread and handed to a
 * bounded queue drained by one daemon writer thread, so logging from the main thread cannot
 * jank or ANR. When the queue is full the record is dropped and counted rather than blocking.
 * Every line passes through [LogRedactor] (tokens, JWTs, passwords, emails, phone numbers).
 */
object DiagnosticLog {
    private const val TAG = "DIAGNOSTICS"
    private const val CURRENT = "messages-diagnostics.log"
    private const val MAX_BYTES = 512 * 1024L
    private const val GENERATIONS = 4
    private const val QUEUE_CAPACITY = 4_096
    private const val BATCH = 256
    private const val WRITER_THREAD = "diagnostic-log-writer"

    @Volatile
    private var appContext: Context? = null

    private val queue = LinkedBlockingQueue<Any>(QUEUE_CAPACITY)
    private val dropped = AtomicLong(0)
    private val levelCounts = LongArray(LogLevel.entries.size)
    private val minFileLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO
    private val minLogcatLevel = if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.WARN

    private class FlushMarker(val latch: CountDownLatch)

    private val mainThreadWatchdog = MainThreadWatchdog()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        Thread(::drainLoop, WRITER_THREAD).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        i(
            "SESSION", "start",
            fields = mapOf(
                "version" to "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                "sdk" to Build.VERSION.SDK_INT,
                "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
                "cores" to Runtime.getRuntime().availableProcessors(),
                "heapMb" to Runtime.getRuntime().maxMemory() / 1024 / 1024
            )
        )
    }

    fun phoneToken(phone: String): String = PhoneToken.of(phone)

    fun d(tag: String, message: String, error: Throwable? = null, fields: Map<String, Any?> = emptyMap()) =
        log(LogLevel.DEBUG, tag, message, error, fields)

    fun i(tag: String, message: String, error: Throwable? = null, fields: Map<String, Any?> = emptyMap()) =
        log(LogLevel.INFO, tag, message, error, fields)

    fun w(tag: String, message: String, error: Throwable? = null, fields: Map<String, Any?> = emptyMap()) =
        log(LogLevel.WARN, tag, message, error, fields)

    fun e(tag: String, message: String, error: Throwable? = null, fields: Map<String, Any?> = emptyMap()) =
        log(LogLevel.ERROR, tag, message, error, fields)

    /** Legacy entry point: INFO, or ERROR when a throwable is attached. */
    fun event(category: String, message: String, error: Throwable? = null) =
        log(if (error != null) LogLevel.ERROR else LogLevel.INFO, category, message, error, emptyMap())

    fun log(level: LogLevel, tag: String, message: String, error: Throwable?, fields: Map<String, Any?>) {
        try {
            if (level >= minLogcatLevel) {
                val text = LogRedactor.redact(message)
                when (level) {
                    LogLevel.DEBUG -> Log.d(tag, text, error)
                    LogLevel.INFO -> Log.i(tag, text, error)
                    LogLevel.WARN -> Log.w(tag, text, error)
                    LogLevel.ERROR -> Log.e(tag, text, error)
                }
            }
            if (level < minFileLevel || appContext == null) return
            val line = LogLineFormatter.format(
                isoTime = Instant.now().toString(),
                level = level,
                tag = tag,
                thread = Thread.currentThread().name,
                message = message,
                fields = fields,
                stackTrace = error?.let { Log.getStackTraceString(it) }
            )
            synchronized(levelCounts) { levelCounts[level.ordinal]++ }
            if (!queue.offer(line)) dropped.incrementAndGet()
        } catch (loggingError: Throwable) {
            Log.w(TAG, "Unable to enqueue diagnostic log", loggingError)
        }
    }

    /** Blocks until everything enqueued so far is on disk, or [timeoutMs] elapses. */
    fun flush(timeoutMs: Long = 2_000): Boolean {
        if (appContext == null || Thread.currentThread().name == WRITER_THREAD) return true
        val latch = CountDownLatch(1)
        if (!queue.offer(FlushMarker(latch), timeoutMs, TimeUnit.MILLISECONDS)) return false
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun drainLoop() {
        val batch = ArrayList<Any>(BATCH)
        while (true) {
            try {
                batch.clear()
                batch.add(queue.take())
                queue.drainTo(batch, BATCH - 1)
                writeBatch(batch)
            } catch (_: InterruptedException) {
                return
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to write diagnostic log", error)
            }
        }
    }

    private fun writeBatch(batch: List<Any>) {
        val context = appContext ?: return
        val markers = ArrayList<FlushMarker>(1)
        try {
            if (currentFile(context).length() >= MAX_BYTES) rotate(context)
            val lost = dropped.getAndSet(0)
            FileOutputStream(currentFile(context), true).bufferedWriter(Charsets.UTF_8).use { writer ->
                if (lost > 0) {
                    writer.write(
                        LogLineFormatter.format(
                            Instant.now().toString(), LogLevel.WARN, TAG, WRITER_THREAD,
                            "log queue overflow", mapOf("dropped" to lost)
                        )
                    )
                }
                for (item in batch) {
                    when (item) {
                        is String -> writer.write(item)
                        is FlushMarker -> markers.add(item)
                    }
                }
            }
        } finally {
            markers.forEach { it.latch.countDown() }
        }
    }

    /** Creates a user-shareable snapshot in cache; originals stay app-private. */
    fun createExportFile(context: Context): File? = try {
        initialize(context)
        i("DIAGNOSTICS", "user requested export")
        flush()
        val output = File(context.cacheDir, "messages-diagnostics-${System.currentTimeMillis()}.txt")
        output.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("Messages diagnostics ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            writer.appendLine("Generated ${Instant.now()}")
            writer.appendLine("Message bodies, tokens, emails and full phone numbers are redacted.")
            val counts = synchronized(levelCounts) { levelCounts.copyOf() }
            writer.appendLine(
                "This session: " + LogLevel.entries.joinToString(" ") { "${it.name}=${counts[it.ordinal]}" }
            )
            writer.appendLine()

            writeSystemHealth(writer, context)

            diagnosticFiles(context).forEach { source ->
                if (!source.exists()) return@forEach
                writer.appendLine("===== ${source.name} =====")
                source.forEachLine(Charsets.UTF_8) { writer.appendLine(it) }
            }
        }
        output
    } catch (error: Throwable) {
        Log.e(TAG, "Unable to export diagnostics", error)
        null
    }

    private fun writeSystemHealth(writer: Writer, context: Context) {
        writer.appendLine("===== SYSTEM HEALTH SNAPSHOT =====")

        // Device info
        writer.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        writer.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        writer.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")

        // Memory
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
        val maxMb = rt.maxMemory() / 1024 / 1024
        writer.appendLine("Heap: ${usedMb}MB / ${maxMb}MB (${usedMb * 100 / maxMb}%)")
        writer.appendLine("Native heap: ${Debug.getNativeHeapAllocatedSize() / 1024 / 1024}MB")
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            writer.appendLine("System RAM: ${(mi.totalMem - mi.availMem) / 1024 / 1024}MB / ${mi.totalMem / 1024 / 1024}MB" +
                " lowMemory=${mi.lowMemory}")
        } catch (_: Exception) {}

        // Thread pool snapshot
        writer.appendLine()
        writer.appendLine("--- Active Threads ---")
        val threads = Thread.getAllStackTraces()
        val ioThreads = threads.keys.filter { it.name.contains("arch_disk_io") || it.name.contains("DefaultDispatcher") }
        val keystoreThreads = threads.keys.filter { it.name.contains("keystore") }

        writer.appendLine("Total threads: ${threads.size}")
        writer.appendLine("arch_disk_io threads: ${ioThreads.size}")
        for (t in ioThreads) {
            val stack = threads[t]
            val topFrame = stack?.firstOrNull()?.toString() ?: "idle"
            writer.appendLine("  ${t.name} [${t.state}] → $topFrame")
        }
        writer.appendLine("keystore-crypto threads: ${keystoreThreads.size}")
        for (t in keystoreThreads) {
            val stack = threads[t]
            val topFrame = stack?.firstOrNull()?.toString() ?: "idle"
            writer.appendLine("  ${t.name} [${t.state}] → $topFrame")
        }

        // Blocked threads
        val blocked = threads.entries.filter { it.key.state == Thread.State.BLOCKED }
        if (blocked.isNotEmpty()) {
            writer.appendLine()
            writer.appendLine("--- BLOCKED Threads (${blocked.size}) ---")
            for ((thread, stack) in blocked) {
                writer.appendLine("  ${thread.name}:")
                stack.take(5).forEach { writer.appendLine("    at $it") }
            }
        }

        // Waiting threads on IO
        val waitingIo = threads.entries.filter {
            (it.key.name.contains("arch_disk_io") || it.key.name.contains("DefaultDispatcher")) &&
                it.key.state == Thread.State.WAITING
        }
        if (waitingIo.isNotEmpty()) {
            writer.appendLine()
            writer.appendLine("--- IO threads WAITING (${waitingIo.size}) ---")
            for ((thread, stack) in waitingIo) {
                val binderFrame = stack.find { it.toString().contains("Binder") || it.toString().contains("Keystore") }
                if (binderFrame != null) {
                    writer.appendLine("  ${thread.name} → $binderFrame (KEYSTORE BINDER)")
                }
            }
        }

        // ANR risk
        val mainBlocked = mainThreadWatchdog.isMainThreadBlocked()
        writer.appendLine()
        writer.appendLine("Main thread responsive: ${!mainBlocked}")

        // Exit info (Android 11+)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val exits = am?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                if (!exits.isNullOrEmpty()) {
                    writer.appendLine()
                    writer.appendLine("--- Recent Process Exits ---")
                    for (info in exits) {
                        writer.appendLine("  reason=${info.reason} status=${info.status} " +
                            "importance=${info.importance} timestamp=${Instant.ofEpochMilli(info.timestamp)} " +
                            "description=${info.description ?: "none"}")
                    }
                }
            } catch (_: Exception) {}
        }

        writer.appendLine()
    }


    private fun directory(context: Context): File =
        File(context.filesDir, "diagnostics").apply { mkdirs() }

    private fun currentFile(context: Context): File = File(directory(context), CURRENT)

    private fun diagnosticFiles(context: Context): List<File> = buildList {
        for (generation in GENERATIONS downTo 1) {
            add(File(directory(context), "$CURRENT.$generation"))
        }
        add(currentFile(context))
    }

    /** Writer thread only. */
    private fun rotate(context: Context) {
        val dir = directory(context)
        File(dir, "$CURRENT.$GENERATIONS").delete()
        for (generation in GENERATIONS - 1 downTo 1) {
            val from = File(dir, "$CURRENT.$generation")
            if (from.exists()) from.renameTo(File(dir, "$CURRENT.${generation + 1}"))
        }
        val current = currentFile(context)
        if (current.exists()) current.renameTo(File(dir, "$CURRENT.1"))
    }

    /**
     * Lightweight main-thread responsiveness check.
     */
    private class MainThreadWatchdog {
        @Volatile private var pong = false

        // Called from the main thread (the export button) it would wait on itself and always
        // report "blocked"; the fact that we are running there proves it is responsive.
        fun isMainThreadBlocked(): Boolean =
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) false else try {
            pong = false
            android.os.Handler(android.os.Looper.getMainLooper()).post { pong = true }
            Thread.sleep(100)
            !pong
        } catch (_: Exception) { false }
    }
}
