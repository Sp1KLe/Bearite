package com.bearite.patcher

/**
 * Снимает системный logcat своего приложения (Android отдаёт приложению только его собственные
 * записи, в том числе строки нативного краша: «Fatal signal», backtrace от debuggerd, лог Unity).
 */
object LogcatCapture {
    private val interesting = listOf(
        "Fatal signal", "SIGSEGV", "SIGABRT", "SIGBUS", "SIGILL", "backtrace", "Abort message",
        "FATAL", "AndroidRuntime", "JNI DETECTED", "Unity", "il2cpp", "Bearite", "DEBUG",
    )

    /** Есть ли среди строк настоящий краш (а не просто фоновый лог). */
    fun hasCrash(lines: List<String>): Boolean =
        lines.any { it.contains("Fatal signal") || it.contains("FATAL EXCEPTION") }

    /** Строки вокруг последнего краша или, если краша не видно, самые значимые за последнее время. */
    fun crashLines(): List<String> = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "5000")
            .redirectErrorStream(true)
            .start()
        val all = p.inputStream.bufferedReader().readLines()
        p.waitFor()

        val crashAt = all.indexOfLast { it.contains("Fatal signal") || it.contains("FATAL EXCEPTION") }
        val picked = if (crashAt >= 0) {
            // сам краш и его backtrace (строки tag DEBUG идут сразу после)
            all.subList((crashAt - 25).coerceAtLeast(0), (crashAt + 90).coerceAtMost(all.size))
        } else {
            all.filter { l -> interesting.any { l.contains(it) } || l.contains(" E ") || l.contains("dlopen") || l.contains("UnsatisfiedLink") }.takeLast(100)
        }
        if (picked.isEmpty()) listOf("(logcat пуст или недоступен)") else picked.map { it.take(240) }
    } catch (t: Throwable) {
        listOf("logcat недоступен: $t")
    }
}
