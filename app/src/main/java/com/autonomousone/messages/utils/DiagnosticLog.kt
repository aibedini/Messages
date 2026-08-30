package com.autonomousone.messages.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.autonomousone.messages.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Small privacy-aware, rotating on-device diagnostic log.
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
    // Broad international/mobile-like sequence. This may also redact an
    // occasional long numeric id, which is preferable to leaking a number.
    private val phonePattern = Regex("(?<![\\dA-Za-z])\\+?\\d{7,15}(?!\\d)")

    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        event(
            "SESSION",
            "start version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                "sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}"
        )
    }

    fun phoneToken(phone: String): String {
        if (phone.isBlank()) return "none"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(phone.trim().toByteArray(Charsets.UTF_8))
        return digest.take(5).joinToString("") { "%02x".format(it) }
    }

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

    private fun sanitize(value: String): String =
        phonePattern.replace(value.take(24_000)) { match -> "phone#${phoneToken(match.value)}" }

    private fun directory(context: Context): File =
        File(context.filesDir, "diagnostics").apply { mkdirs() }

    private fun currentFile(context: Context): File = File(directory(context), CURRENT)

    /** Oldest first, current last, for a naturally chronological export. */
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
}
