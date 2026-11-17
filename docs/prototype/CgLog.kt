package com.childguard.app

import android.content.Context
import android.util.Log as AndroidLog
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

/**
 * CgLog — a pluggable logging chain that mirrors the architecture of the
 * competitor demo (Log + LoggerBundle + AndroidLogger/FileLogger/WebLogger)
 * but hardened and wired to ChildGuard's own transport.
 *
 *   CgLog.initialise(context)
 *   CgLog.addLogger(AndroidLogger(LoggerBundle(LogLevel.INFO)))
 *   CgLog.addLogger(FileLogger(context, DayLogBundle(maxDaysSaved = 4)))
 *   CgLog.addLogger(WebLogger(RestLoggerBundle(server, token)))
 *
 * Every logger is independent and the chain fans out — nothing blocks the
 * caller, and a failing logger never breaks the app (try/catch inside).
 */
object CgLog {

    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    interface Logger {
        fun log(level: Int, tag: String, msg: String, tr: Throwable?)
    }

    private val loggers = CopyOnWriteArrayList<Logger>()

    fun initialise(context: Context) {
        // nothing global to prep; kept for API parity with the demo
    }

    fun addLogger(logger: Logger) {
        loggers.add(logger)
    }

    fun removeLogger(logger: Logger) {
        loggers.remove(logger)
    }

    fun v(tag: String, msg: String) = emit(VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = emit(DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = emit(INFO, tag, msg, null)
    fun w(tag: String, msg: String) = emit(WARN, tag, msg, null)
    fun w(tag: String, msg: String, tr: Throwable) = emit(WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable?) = emit(ERROR, tag, msg, tr)

    private fun emit(level: Int, tag: String, msg: String, tr: Throwable?) {
        for (l in loggers) {
            try {
                l.log(level, tag, msg, tr)
            } catch (_: Throwable) {
                // A broken logger must never take the app down.
            }
        }
    }
}

/** Configuration carrier, mirroring the demo's LoggerBundle. */
open class LoggerBundle(
    val level: Int = CgLog.WARN,
    val tagPrefix: String = "CG"
)

/** Log only to logcat. */
class AndroidLogger(private val bundle: LoggerBundle) : CgLog.Logger {
    override fun log(level: Int, tag: String, msg: String, tr: Throwable?) {
        if (level < bundle.level) return
        val t = "${bundle.tagPrefix}/$tag"
        when (level) {
            CgLog.VERBOSE -> AndroidLog.v(t, msg)
            CgLog.DEBUG -> AndroidLog.d(t, msg)
            CgLog.INFO -> AndroidLog.i(t, msg)
            CgLog.WARN -> AndroidLog.w(t, msg, tr)
            else -> AndroidLog.e(t, msg, tr)
        }
    }
}

/** One file per day, keep up to maxDaysSaved days. */
class DayLogBundle(val maxDaysSaved: Int = 4) : LoggerBundle(CgLog.INFO)

/** One file until size cap, rotate over numOfFiles files. */
class CircleLogBundle(val numOfFiles: Int = 7) : LoggerBundle(CgLog.INFO)

/** Strict size-bounded rotation, never overflows. */
class StrictCircleLogBundle(val maxFileSizeMegaBytes: Int = 1) : LoggerBundle(CgLog.INFO)

/** REST push to a log server (our Rust collector). */
class RestLoggerBundle(val baseUrl: String, val token: String = "") : LoggerBundle(CgLog.INFO)

/** WebSocket push (kept for API parity; not used yet by ChildGuard). */
class WebSocketLoggerBundle(val url: String) : LoggerBundle(CgLog.INFO)

/** Append to local rotating files. */
class FileLogger(private val context: Context, private val bundle: LoggerBundle) : CgLog.Logger {

    private val dir: File = File(context.filesDir, "cg_logs").apply { mkdirs() }

    override fun log(level: Int, tag: String, msg: String, tr: Throwable?) {
        if (level < bundle.level) return
        try {
            val line = when (bundle) {
                is DayLogBundle -> dailyFile(bundle)
                is CircleLogBundle -> circleFile(bundle.numOfFiles)
                is StrictCircleLogBundle -> strictCircleFile(bundle.maxFileSizeMegaBytes)
                else -> dailyFile(DayLogBundle())
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val safe = msg.replace('\n', ' ')
            FileWriter(line, true).use { it.append("[$stamp][$level][$tag] $safe\n") }
        } catch (_: Throwable) {
        }
    }

    private fun dailyFile(bundle: DayLogBundle): File {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val f = File(dir, "cg_$day.log")
        // drop old files beyond retention
        val cutoff = System.currentTimeMillis() - bundle.maxDaysSaved * 86400_000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        return f
    }

    private fun circleFile(files: Int): File {
        val base = File(dir, "cg_rotate.log")
        if (base.exists() && base.length() > 4 * 1024 * 1024) {
            val backup = File(dir, "cg_rotate.1.log")
            backup.delete()
            base.renameTo(backup)
        }
        return base
    }

    private fun strictCircleFile(maxMb: Int): File {
        val maxBytes = maxMb.toLong() * 1024 * 1024
        val base = File(dir, "cg_strict.log")
        if (base.exists() && base.length() > maxBytes) {
            val chain = 1..4
            for (i in chain.reversed()) {
                val src = File(dir, "cg_strict.$i.log")
                if (src.exists()) {
                    val dst = File(dir, "cg_strict.${i + 1}.log")
                    src.renameTo(dst)
                }
            }
            base.renameTo(File(dir, "cg_strict.1.log"))
        }
        return base
    }
}

/** Push lines to the parent collector server (Feature 6 transport reuse). */
class WebLogger(private val bundle: LoggerBundle) : CgLog.Logger {

    override fun log(level: Int, tag: String, msg: String, tr: Throwable?) {
        if (level < bundle.level) return
        val rest = bundle as? RestLoggerBundle ?: return
        try {
            val body = JSONObject().apply {
                put("type", "cg_log")
                put("package", tag)
                put("data", msg)
                put("ts", System.currentTimeMillis())
            }
            val conn = URL("${rest.baseUrl.trimEnd('/')}/api/events").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (rest.token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer ${rest.token}")
            }
            (conn.outputStream as OutputStream).write(body.toString().toByteArray(Charsets.UTF_8))
            conn.responseCode
            conn.disconnect()
        } catch (_: Throwable) {
        }
    }
}
