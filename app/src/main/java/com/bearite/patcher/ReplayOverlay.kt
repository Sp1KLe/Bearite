package com.bearite.patcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.sin

/**
 * Просмотр записи поверх игры (по мотивам Replay Mod): внизу полоса времени с паузой, перемоткой и скоростью,
 * выбор камеры (игровая, свободная, по ключевым кадрам), кнопка «＋ Ключ». Видна только пока идёт просмотр.
 * В режиме свободной камеры экран делится: слева стик движения, справа вращение, справа внизу кнопки ▲ ▼.
 * Позу свободной камеры считает лаунчер и пишет в maps/_rpcam, нативный мод ставит по ней главную камеру.
 */
class ReplayOverlay(private val act: Activity, private val parent: FrameLayout) {
    private val d = UiScale.density(act)
    private fun dp(v: Int) = (v * d).toInt()
    private val ui = GameUi(act)
    private val link = ReplayLink(act)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var bar: LinearLayout
    private lateinit var freeLayer: FrameLayout
    private lateinit var note: TextView
    private lateinit var playBtn: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var speedBtn: TextView
    private lateinit var camBtn: TextView
    private lateinit var keyBtn: TextView
    private lateinit var keysText: TextView
    private lateinit var camSpeedBtn: TextView

    @Volatile private var running = false
    private var st: ReplayLink.Status? = null
    private var dragging = false
    private var lastSeekAt = 0L

    // свободная камера
    private var inFree = false
    private var cx = 0f; private var cy = 0f; private var cz = 0f
    private var cyaw = 0f; private var cpitch = 0f
    private var moveX = 0f; private var moveY = 0f; private var vert = 0f
    private var camSpeed = 8f
    private var dirty = false
    private var lastTick = 0L
    private var lastMsg = ""
    private var noteUntil = 0L

    private val speeds = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0)
    private val camSpeeds = floatArrayOf(2f, 4f, 8f, 16f, 32f)

    fun start() {
        build()
        running = true
        main.post(object : Runnable {
            override fun run() {
                if (!running) return
                try { tick() } catch (t: Throwable) { BLog.e("реплей: окно просмотра", t) }
                main.postDelayed(this, 33)
            }
        })
    }

    fun stop() { running = false }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun build() {
        // слой управления свободной камерой на весь экран (виден только в этом режиме)
        freeLayer = FrameLayout(act).apply { visibility = View.GONE; isClickable = true }
        var stickId = -1; var lookId = -1
        var sx = 0f; var sy = 0f; var lx = 0f; var ly = 0f
        freeLayer.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = e.actionIndex
                    if (e.getX(i) < v.width * 0.4f) { if (stickId < 0) { stickId = e.getPointerId(i); sx = e.getX(i); sy = e.getY(i) } }
                    else if (lookId < 0) { lookId = e.getPointerId(i); lx = e.getX(i); ly = e.getY(i) }
                }
                MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (id == stickId) {
                        moveX = ((e.getX(i) - sx) / dp(60)).coerceIn(-1f, 1f)
                        moveY = (-(e.getY(i) - sy) / dp(60)).coerceIn(-1f, 1f)
                        dirty = true
                    } else if (id == lookId) {
                        cyaw += (e.getX(i) - lx) * 0.2f
                        cpitch = (cpitch + (e.getY(i) - ly) * 0.2f).coerceIn(-89f, 89f)
                        lx = e.getX(i); ly = e.getY(i)
                        dirty = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val id = e.getPointerId(e.actionIndex)
                    if (id == stickId) { stickId = -1; moveX = 0f; moveY = 0f }
                    if (id == lookId) lookId = -1
                }
                MotionEvent.ACTION_CANCEL -> { stickId = -1; lookId = -1; moveX = 0f; moveY = 0f }
            }
            true
        }
        parent.addView(freeLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        for ((txt, dir) in listOf("▲" to 1f, "▼" to -1f)) {
            val b = ui.pill(txt, sizeSp = 18f) {}
            b.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { vert = dir; dirty = true }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> vert = 0f
                }
                true
            }
            freeLayer.addView(b, FrameLayout.LayoutParams(dp(64), dp(48), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = dp(14); bottomMargin = if (dir > 0) dp(150) else dp(96)
            })
        }

        // нижняя панель: время и камера
        bar = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.panelBg(16)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
            isClickable = true
        }
        val row1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playBtn = ui.pill("⏸", primary = true, sizeSp = 16f) { toggle() }
        row1.addView(playBtn, LinearLayout.LayoutParams(dp(56), dp(40)))
        seek = SeekBar(act).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val now = System.currentTimeMillis()
                    if (now - lastSeekAt > 120) { lastSeekAt = now; sendSeek(p) }
                }
                override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar?) { dragging = false; sendSeek(seek.progress) }
            })
        }
        row1.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        timeText = ui.label("0:00 / 0:00", 13f, GameUi.CREAM)
        row1.addView(timeText)
        bar.addView(row1)

        val row2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        speedBtn = ui.pill("1×", sizeSp = 13f) { cycleSpeed() }
        camBtn = ui.pill("Камера: игра", sizeSp = 13f) { cycleCam() }
        camSpeedBtn = ui.pill("Скорость 8", sizeSp = 13f) { cycleCamSpeed() }
        keyBtn = ui.pill("＋ Ключ", sizeSp = 13f) { addKey() }
        keysText = ui.label("", 12f, GameUi.MUTED, bold = false)
        val clear = ui.pill("Сбросить ключи", sizeSp = 12f) { clearKeys() }
        val exit = ui.pill("✕ Выйти", danger = true, sizeSp = 13f) { link.cmds("stopplay") }
        for ((v, last) in listOf(speedBtn to false, camBtn to false, camSpeedBtn to false, keyBtn to false, keysText to false, clear to false, exit to true)) {
            row2.addView(v, wrap().apply { rightMargin = if (last) 0 else dp(6) })
        }
        bar.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(row2) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        parent.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(8)
        })

        // короткие сообщения мода («Запись сохранена», «Открой ту же карту»…)
        note = ui.label("", 14f, GameUi.CREAM).apply {
            background = ui.panelBg(16)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
            isClickable = false
        }
        parent.addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })
    }

    private fun toggle() { val s = st ?: return; link.cmds(if (s.paused) "resume" else "pause") }

    private fun sendSeek(progress: Int) {
        val s = st ?: return
        link.cmds("seek\t" + (s.durMs * progress / 1000L))
    }

    private fun cycleSpeed() {
        val s = st ?: return
        var i = speeds.indexOfFirst { Math.abs(it - s.speed) < 0.01 }
        i = (i + 1) % speeds.size
        link.cmds("speed\t" + speeds[i])
    }

    private fun cycleCamSpeed() {
        val i = (camSpeeds.indexOfFirst { it == camSpeed } + 1) % camSpeeds.size
        camSpeed = camSpeeds[i]
        camSpeedBtn.text = "Скорость " + camSpeed.toInt()
    }

    /** Игровая камера → свободная → по ключевым кадрам (если ключей меньше двух, путь пропускается). */
    private fun cycleCam() {
        val s = st ?: return
        var next = (s.cam + 1) % 3
        if (next == 2 && s.keys < 2) { toast("Для пути нужно минимум 2 ключа: перейди в свободную камеру и нажимай «＋ Ключ»"); next = 0 }
        link.cmds("cam\t$next")
    }

    private fun addKey() {
        val s = st ?: return
        val name = ReplayLink.keyName
        val pose = if (inFree) floatArrayOf(cx, cy, cz, cyaw, cpitch) else floatArrayOf(s.cx, s.cy, s.cz, s.cyaw, s.cpitch)
        val keys = link.readKeys(name).filter { it.t != s.tMs }.toMutableList()
        keys.add(ReplayLink.Key(s.tMs, pose[0], pose[1], pose[2], pose[3], pose[4]))
        keys.sortBy { it.t }
        link.writeKeys(name, keys)
        link.cmds("keys\t$name")
        toast("Ключ на ${ReplayLink.fmtTime(s.tMs)} добавлен, всего ${keys.size}")
    }

    private fun clearKeys() {
        val name = ReplayLink.keyName
        link.writeKeys(name, emptyList())
        link.cmds("keys\t$name")
        toast("Ключи камеры удалены")
    }

    private fun toast(t: String) { Toast.makeText(act, t, Toast.LENGTH_SHORT).show() }

    private fun tick() {
        val s = link.status()
        st = s
        val now = System.currentTimeMillis()
        // сообщение мода показываем 4 секунды, когда оно изменилось
        val m = s?.msg.orEmpty()
        if (m != lastMsg) { lastMsg = m; if (m.isNotEmpty()) { note.text = m; note.visibility = View.VISIBLE; noteUntil = now + 4000 } }
        if (note.visibility == View.VISIBLE && now > noteUntil) note.visibility = View.GONE

        val active = s != null && s.sess
        bar.visibility = if (active) View.VISIBLE else View.GONE
        if (!active || s == null) { freeLayer.visibility = View.GONE; inFree = false; moveX = 0f; moveY = 0f; vert = 0f; return }

        playBtn.text = if (s.paused) "▶" else "⏸"
        if (!dragging) seek.progress = if (s.durMs > 0) (s.tMs * 1000L / s.durMs).toInt().coerceIn(0, 1000) else 0
        timeText.text = ReplayLink.fmtTime(s.tMs) + " / " + ReplayLink.fmtTime(s.durMs)
        speedBtn.text = (if (s.speed == Math.floor(s.speed)) s.speed.toInt().toString() else s.speed.toString()) + "×"
        camBtn.text = when (s.cam) { 1 -> "Камера: свободная"; 2 -> "Камера: путь"; else -> "Камера: игра" }
        keysText.text = "Ключей: ${s.keys}"
        val free = s.cam == 1
        camSpeedBtn.visibility = if (free) View.VISIBLE else View.GONE
        keyBtn.visibility = if (free || s.cam == 0) View.VISIBLE else View.GONE
        freeLayer.visibility = if (free) View.VISIBLE else View.GONE

        if (free) {
            if (!inFree) { inFree = true; cx = s.cx; cy = s.cy; cz = s.cz; cyaw = s.cyaw; cpitch = s.cpitch; lastTick = now; dirty = true }
            val dt = ((now - lastTick).coerceIn(1L, 100L)) / 1000f
            lastTick = now
            val yr = Math.toRadians(cyaw.toDouble()).toFloat()
            val pr = Math.toRadians(cpitch.toDouble()).toFloat()
            // вперёд — по взгляду, вправо — по горизонтали, ▲▼ — по вертикали
            val fx = sin(yr) * cos(pr); val fy = -sin(pr); val fz = cos(yr) * cos(pr)
            val rx = cos(yr); val rz = -sin(yr)
            if (moveX != 0f || moveY != 0f || vert != 0f) {
                cx += (fx * moveY + rx * moveX) * camSpeed * dt
                cy += (fy * moveY + vert) * camSpeed * dt
                cz += (fz * moveY + rz * moveX) * camSpeed * dt
                dirty = true
            }
            if (dirty) { dirty = false; link.writeFreeCam(cx, cy, cz, cyaw, cpitch) }
        } else inFree = false
    }
}
