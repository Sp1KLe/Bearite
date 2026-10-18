package com.bearite.patcher

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Лог в файл filesDir/bearite.log: его же пишет нативная часть и его читает UI. */
object BLog {
    @Volatile
    private var file: File? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "bearite.log")
    }

    fun i(msg: String) {
        Log.i("Bearite", msg)
        append("[app] $msg")
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e("Bearite", msg, t)
        append("[app] ОШИБКА: $msg" + if (t != null) "\n" + Log.getStackTraceString(t) else "")
    }

    fun tail(lines: Int): String {
        val f = file ?: return ""
        if (!f.exists()) return ""
        return try {
            f.readLines().takeLast(lines).joinToString("\n")
        } catch (_: Throwable) {
            ""
        }
    }

    fun clear() {
        try {
            file?.writeText("")
        } catch (_: Throwable) {
        }
    }

    private fun append(line: String) {
        try {
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            file?.appendText("$ts $line\n")
        } catch (_: Throwable) {
        }
    }
}
