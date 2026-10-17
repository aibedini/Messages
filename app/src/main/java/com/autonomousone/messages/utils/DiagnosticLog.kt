package com.autonomousone.messages.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import com.autonomousone.messages.BuildConfig
import java.io.File
import java.io.Writer
import java.time.Instant

/**
 * Privacy-aware, rotating on-device diagnostic log with system health snapshots.
 *
 * It intentionally records state transitions and raw numeric result codes,
 * never SMS bodies or full phone numbers. Files live in app-private storage;
 * the user can explicitly export a merged copy from Settings.
 */
object DiagnosticLog {
    private const val TAG = "DIAGNOSTICS"
    private const val CURRENT = "messages-diagnostics.log"
    private const val MAX_BYTES = 384 * 1024L
    private const val GENERATIONS = 3
    private val phonePattern = Regex("(?<![\\dA-Za-z])\\+?\\d{7,15}(?!\\d)")

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastAnrCheckMs = 0L

    private val mainThreadWatchdog = MainThreadWatchdog()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        event(
            "SESSION",
            "start version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL} " +
                "cores=${Runtime.getRuntime().availableProcessors()} " +
                "heap=${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB"
        )
    }

    fun phoneToken(phone: String): String = PhoneToken.of(phone)

    @Synchronized
    fun event(category: String, message: String, error: Throwable? = null) {
        val context = appContext ?: return
        try {
            val file = currentFile(context)
            if (file.length() >= MAX_BYTES) rotate(context)
            val trace = error?.let { "\n" + Log.getStackTraceString(it) }.orEmpty()
            val line = buildString {
                append(Instant.now().toString())
                append(" [")
                append(Thread.currentThread().name)
                append("] ")
                append(category.take(40))
                append(" | ")
                append(sanitize(message + trace))
                append('\n')
            }
            currentFile(context).appendText(line, Charsets.UTF_8)
        } catch (loggingError: Throwable) {
            Log.w(TAG, "Unable to write diagnostic log", loggingError)
        }
    }

    /** Creates a user-shareable snapshot in cache; originals stay app-private. */
    @Synchronized
    fun createExportFile(context: Context): File? = try {
        initialize(context)
        event("DIAGNOSTICS", "user requested export")
        val output = File(context.cacheDir, "messages-diagnostics-${System.currentTimeMillis()}.txt")
        output.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("Messages diagnostics ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            writer.appendLine("Generated ${Instant.now()}")
            writer.appendLine("SMS bodies and full phone numbers are intentionally excluded.")
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

    private fun sanitize(value: String): String =
        phonePattern.replace(value.take(24_000)) { match -> "phone#${phoneToken(match.value)}" }

    private fun directory(context: Context): File =
        File(context.filesDir, "diagnostics").apply { mkdirs() }

    private fun currentFile(context: Context): File = File(directory(context), CURRENT)

    private fun diagnosticFiles(context: Context): List<File> = buildList {
        for (generation in GENERATIONS downTo 1) {
            add(File(directory(context), "$CURRENT.$generation"))
        }
        add(currentFile(context))
    }

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
