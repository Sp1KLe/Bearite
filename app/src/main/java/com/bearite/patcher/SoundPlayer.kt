package com.bearite.patcher

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Звуки и песни модов. Нативный мод вызывает api->play_sound(...), команда дописывается в filesDir/_audio,
 * а здесь раз в ~150 мс её забирают и проигрывают через Android (wav, ogg, mp3, m4a).
 */
class SoundPlayer(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val queue = File(ctx.filesDir, "_audio")
    private val taken = File(ctx.filesDir, "_audio.rd")
    private val players = ArrayList<Pair<String, MediaPlayer>>()
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try { poll() } catch (t: Throwable) { BLog.e("звуки модов", t) }
            main.postDelayed(this, 150)
        }
    }

    fun start() {
        if (running) return
        running = true
        queue.delete(); taken.delete()   // команды прошлого запуска не нужны
        main.postDelayed(tick, 150)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        stopAll()
    }

    private fun poll() {
        if (!queue.isFile) return
        taken.delete()
        if (!queue.renameTo(taken)) return
        val lines = try { taken.readLines() } catch (_: Throwable) { emptyList() }
        taken.delete()
        for (l in lines.take(64)) {
            val p = l.split('\t')
            when (p.getOrNull(0)) {
                "play" -> if (p.size >= 4) play(p[1], p[2].toFloatOrNull() ?: 1f, p[3] == "1")
                "stop" -> if (p.size >= 2) stopPath(p[1])
                "stopall" -> stopAll()
            }
        }
    }

    private fun play(path: String, volume: Float, loop: Boolean) {
        val f = File(path)
        // только файлы из папок модов
        val mods = (ctx.getExternalFilesDir("mods") ?: File(ctx.filesDir, "mods")).canonicalPath + File.separator
        if (!f.isFile || !f.canonicalPath.startsWith(mods)) return
        if (loop) stopPath(path)   // песня с повтором одна: новая заменяет старую
        if (players.size >= 12) release(players.first().second)
        try {
            val mp = MediaPlayer()
            players.add(path to mp)
            mp.setDataSource(f.path)
            mp.setVolume(volume, volume)
            mp.isLooping = loop
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { release(it) }
            mp.setOnErrorListener { m, _, _ -> release(m); true }
            mp.prepareAsync()
        } catch (t: Throwable) {
            BLog.e("не удалось проиграть ${f.name}", t)
            players.lastOrNull { it.first == path }?.let { release(it.second) }
        }
    }

    private fun release(mp: MediaPlayer) {
        players.removeAll { it.second === mp }
        try { mp.release() } catch (_: Throwable) {}
    }

    private fun stopPath(path: String) {
        players.filter { it.first == path }.forEach { release(it.second) }
    }

    fun stopAll() {
        players.toList().forEach { release(it.second) }
    }
}
