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
    val KINDS = listOf("", "move", "rotate", "swing", "pulse")
    val NAMES = mapOf(
        "" to "Нет", "move" to "Движение туда-обратно", "rotate" to "Вращение", "swing" to "Качание", "pulse" to "Пульсация",
    )
    val HINTS = mapOf(
        "move" to "Сдвиг (X Y Z) от начальной позиции и обратно",
        "rotate" to "Скорость вращения, градусов в секунду (X Y Z)",
        "swing" to "Размах качания, градусов в каждую сторону (X Y Z)",
        "pulse" to "X = на сколько пульсирует размер (0.3 = ±30%)",
    )

    /** Положение объекта в момент t (секунды): out = x y z, rx ry rz, sx sy sz (до умножения на масштаб карты). */
    fun pose(o: MapObj, t: Double, out: DoubleArray) {
        out[0] = o.x; out[1] = o.y; out[2] = o.z
        out[3] = o.rx; out[4] = o.ry; out[5] = o.rz
        out[6] = o.sx; out[7] = o.sy; out[8] = o.sz
        if (o.an.isEmpty()) return
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
        return max(0.2, o.ap)
    }
}
