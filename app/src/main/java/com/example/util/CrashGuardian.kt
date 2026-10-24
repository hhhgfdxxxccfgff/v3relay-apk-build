package com.example.util

import android.content.Context
import android.content.Intent
import android.os.Looper
import com.example.MainActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the app process alive when an unexpected exception escapes.
 *
 * - Background-thread crashes are logged and swallowed (the thread dies, the app lives).
 * - Main-thread crashes are logged and the message loop is re-entered so the UI
 *   keeps running instead of the process being killed.
 * - A burst guard hands control back to the system after repeated crashes so a
 *   permanently broken UI cannot spin forever.
 */
object CrashGuardian {

    private const val FILE_NAME = "crash_log.txt"
    private const val MAX_BYTES = 48 * 1024
    private const val MAX_BURST = 3
    private const val BURST_WINDOW_MS = 10_000L

    @Volatile
    private var installed = false

    private var burstStart = 0L
    private var burstCount = 0

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val fallback = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val fatal = synchronized(this) {
                val now = System.currentTimeMillis()
                if (now - burstStart > BURST_WINDOW_MS) {
                    burstStart = now
                    burstCount = 0
                }
                ++burstCount > MAX_BURST
            }
            appendCrash(app, thread, throwable)
            if (fatal) {
                fallback?.uncaughtException(thread, throwable)
                return@setDefaultUncaughtExceptionHandler
            }
            val isMainThread = thread === Looper.getMainLooper().thread
            if (isMainThread) {
                if (recoverUi(app)) {
                    keepMainLoopAlive(app, fallback)
                } else {
                    fallback?.uncaughtException(thread, throwable)
                }
            }
            // Background thread: swallow, app continues.
        }
    }

    /**
     * Rebuild the UI in a fresh Activity instance after a main-thread crash so the
     * process (and any live VPN session) survives with a working screen.
     */
    private fun recoverUi(context: Context): Boolean = try {
        val prefs = context.getSharedPreferences("crash_guardian", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_recovery", 0L)
        if (now - last < 60_000L) {
            false
        } else {
            prefs.edit().putLong("last_recovery", now).apply()
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            )
            true
        }
    } catch (_: Throwable) {
        false
    }

    private fun keepMainLoopAlive(context: Context, fallback: Thread.UncaughtExceptionHandler?) {
        while (true) {
            try {
                Looper.loop()
            } catch (t: Throwable) {
                val fatal = synchronized(this) {
                    val now = System.currentTimeMillis()
                    if (now - burstStart > BURST_WINDOW_MS) {
                        burstStart = now
                        burstCount = 0
                    }
                    ++burstCount > MAX_BURST
                }
                appendCrash(context, Thread.currentThread(), t)
                if (fatal) {
                    fallback?.uncaughtException(Thread.currentThread(), t)
                    return
                }
            }
        }
    }

    private fun appendCrash(context: Context, thread: Thread, throwable: Throwable) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("\n=== $stamp | ${thread.name} ===\n${throwable.stackTraceToString()}\n")
            if (file.length() > MAX_BYTES) {
                file.writeText(file.readText().takeLast(MAX_BYTES / 2))
            }
        } catch (_: Throwable) {
        }
    }

    fun readLog(context: Context): String = try {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.readText().trim() else ""
    } catch (_: Throwable) {
        ""
    }

    fun clearLog(context: Context) {
        try {
            File(context.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }
}
