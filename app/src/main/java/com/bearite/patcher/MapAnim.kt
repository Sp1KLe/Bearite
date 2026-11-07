package com.bearite.patcher

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Анимации объектов карты. Формулы те же, что в нативном моде (bearite_mod.cpp, anim_eval): игра считает их от общего
 * «настенного» времени, поэтому у всех игроков в комнате объекты движутся одинаково.
 * Виды: move — туда-обратно на вектор (ax, ay, az), rotate — вращение со скоростью (ax, ay, az) градусов в секунду,
 * swing — качание на ±(ax, ay, az) градусов, pulse — пульсация размера на ±ax (доля, 0.3 = 30%).
 */
object MapAnim {
    val KINDS = listOf("", "move", "rotate", "swing", "pulse", "keys")
    val NAMES = mapOf(
        "" to "Нет", "move" to "Движение туда-обратно", "rotate" to "Вращение", "swing" to "Качание", "pulse" to "Пульсация", "keys" to "Свои ключевые кадры",
    )
    val HINTS = mapOf(
        "move" to "Сдвиг (X Y Z) от начальной позиции и обратно",
        "rotate" to "Скорость вращения, градусов в секунду (X Y Z)",
        "swing" to "Размах качания, градусов в каждую сторону (X Y Z)",
        "pulse" to "X = на сколько пульсирует размер (0.3 = ±30%)",
        "keys" to "Поставь объект в позу, выбери время на шкале и нажми «+ Ключ». Между ключами игра сама плавно переходит из позы в позу.",
    )

    /** Ключевой кадр: время (с) и полная поза x y z rx ry rz sx sy sz; ease: 0 линейно, 1 плавно, 2 рывком (держит позу до следующего). */
    class Key(var t: Double, val v: DoubleArray, var ease: Int = 1)
    val EASES = listOf("Линейно", "Плавно", "Рывком")
    val MODES = listOf("По кругу", "Туда-обратно", "Один раз")
    private val keyCache = HashMap<String, List<Key>>()

    fun parseKeys(s: String): List<Key> {
        if (s.isEmpty()) return emptyList()
        keyCache[s]?.let { return it }
        val out = ArrayList<Key>()
        for (part in s.split(';')) {
            val f = part.split('|')
            if (f.size < 11) continue
            val t = f[0].toDoubleOrNull() ?: continue
            val v = DoubleArray(9) { f[1 + it].toDoubleOrNull() ?: 0.0 }
            out.add(Key(t.coerceIn(0.0, 3600.0), v, (f[10].toIntOrNull() ?: 1).coerceIn(0, 2)))
            if (out.size >= 60) break
        }
        out.sortBy { it.t }
        if (keyCache.size > 200) keyCache.clear()
        keyCache[s] = out
        return out
    }

    fun fmtKeys(keys: List<Key>): String = keys.sortedBy { it.t }.take(60).joinToString(";") { k ->
        fun n(d: Double) = String.format(java.util.Locale.US, "%.3f", d).trimEnd('0').trimEnd('.').ifEmpty { "0" }
        n(k.t) + "|" + k.v.joinToString("|") { n(it) } + "|" + k.ease
    }

    private fun ease(u: Double, e: Int) = when (e) { 0 -> u; 2 -> 0.0; else -> u * u * (3 - 2 * u) }

    /** Поза по ключам в момент lt (секунды от начала цикла). */
    fun evalKeys(keys: List<Key>, lt: Double, out: DoubleArray) {
        if (keys.isEmpty()) return
        if (lt <= keys[0].t || keys.size == 1) { keys[0].v.copyInto(out); return }
        val last = keys[keys.size - 1]
        if (lt >= last.t) { last.v.copyInto(out); return }
        var i = 0
        while (i + 1 < keys.size && keys[i + 1].t <= lt) i++
        val a = keys[i]; val b = keys[i + 1]
        val span = b.t - a.t
        val u = if (span < 1e-6) 1.0 else ease((lt - a.t) / span, a.ease)
        for (k in 0 until 9) out[k] = a.v[k] + (b.v[k] - a.v[k]) * u
    }

    /** Время внутри цикла ключей: mode 0 по кругу, 1 туда-обратно, 2 один раз. */
    fun keyTime(dt: Double, dur: Double, mode: Int): Double {
        if (dt <= 0) return 0.0
        return when (mode) {
            2 -> minOf(dt, dur)
            1 -> { val m = dt % (2 * dur); if (m <= dur) m else 2 * dur - m }
            else -> dt % dur
        }
    }

    /** Положение объекта в момент t (секунды): out = x y z, rx ry rz, sx sy sz (до умножения на масштаб карты). */
    fun pose(o: MapObj, t: Double, out: DoubleArray) {
        out[0] = o.x; out[1] = o.y; out[2] = o.z
        out[3] = o.rx; out[4] = o.ry; out[5] = o.rz
        out[6] = o.sx; out[7] = o.sy; out[8] = o.sz
        if (o.an.isEmpty()) return
        if (o.an == "keys") {
            val keys = parseKeys(o.kf)
            if (keys.isNotEmpty()) evalKeys(keys, keyTime(t - o.ad, max(0.2, o.ap), o.km), out)
            return
        }
        val per = max(0.2, o.ap)
        val dt = t - o.ad
        val ph = ((dt % per) + per) % per / per
        when (o.an) {
            "move" -> {
                val k = 0.5 - 0.5 * cos(2 * PI * ph)
                out[0] += o.ax * k; out[1] += o.ay * k; out[2] += o.az * k
            }
            "rotate" -> {
                out[3] += (o.ax * dt) % 360.0; out[4] += (o.ay * dt) % 360.0; out[5] += (o.az * dt) % 360.0
            }
            "swing" -> {
                val k = sin(2 * PI * ph)
                out[3] += o.ax * k; out[4] += o.ay * k; out[5] += o.az * k
            }
            "pulse" -> {
                val k = 1 + o.ax * sin(2 * PI * ph)
                out[6] *= k; out[7] *= k; out[8] *= k
            }
        }
    }

    /** Длительность одного цикла (секунды) для экспорта: у вращения — полный оборот. */
    fun cycle(o: MapObj): Double {
        if (o.an == "rotate") {
            val s = max(kotlin.math.abs(o.ax), max(kotlin.math.abs(o.ay), kotlin.math.abs(o.az)))
            return if (s < 1e-6) 1.0 else 360.0 / s
        }
        if (o.an == "keys" && o.km == 1) return max(0.2, o.ap) * 2
        return max(0.2, o.ap)
    }
}
