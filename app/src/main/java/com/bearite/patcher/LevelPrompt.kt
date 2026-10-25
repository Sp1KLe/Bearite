package com.bearite.patcher

import android.app.Activity
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Окно уровня, как у платформы запуска в игре: обложка, название, описание, «Вперёд!» и «Отмена».
 * Движок карт (libbearite_mod.so) кладёт в maps/_prompt строку «id\tназвание\tобложка\tописание»,
 * а ответ читает из maps/_answer: «go\t<id>» или «cancel».
 */
class LevelPrompt(private val act: Activity) {
    private val dir = File(act.filesDir, "maps")
    private val promptFile = File(dir, "_prompt")
    private val answerFile = File(dir, "_answer")
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var stopped = false
    private var answeredAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            try { poll() } catch (t: Throwable) { BLog.e("окно уровня: ${t.message}") }
            handler.postDelayed(this, 250)
        }
    }

    fun start() { stopped = false; handler.post(tick) }
    fun stop() { stopped = true; handler.removeCallbacks(tick); dialog?.dismiss(); dialog = null }

    private fun poll() {
        val showing = dialog?.isShowing == true
        if (!promptFile.exists()) {
            // движок закрыл запрос сам (таймаут или вход в мир) — прячем окно
            if (showing) { dialog?.dismiss(); dialog = null }
            return
        }
        if (showing) return
        // после ответа движок ещё пару тактов не успел убрать запрос — не показываем окно повторно
        if (answerFile.exists() || System.currentTimeMillis() - answeredAt < 2000) return
        val line = promptFile.readText().lineSequence().firstOrNull() ?: return
        val p = line.split('\t')
        if (p.size < 2) return
        show(p[0], p[1], p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
    }

    private fun answer(text: String) {
        try {
            val tmp = File(dir, "_answer.tmp")
            tmp.writeText(text)
            tmp.renameTo(answerFile)
        } catch (t: Throwable) { BLog.e("окно уровня: не записан ответ: ${t.message}") }
        answeredAt = System.currentTimeMillis()
        dialog = null
    }

    private fun show(id: String, name: String, cover: String, desc: String) {
        val d = act.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val purple = 0xFF6E6BB8.toInt()

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(purple); cornerRadius = dp(26).toFloat()
                setStroke(dp(4), 0xFF3F3C86.toInt())
            }
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        box.addView(TextView(act).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(dp(2).toFloat(), 0f, dp(2).toFloat(), 0xFF2A2866.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // обложка
        var bmp: android.graphics.Bitmap? = null
        if (cover.isNotEmpty()) try {
            bmp = BitmapFactory.decodeFile(MapStore(dir).texturePng(cover).path)
        } catch (_: Throwable) {}
        val pic = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GradientDrawable().apply { setColor(0xFF9C9AD6.toInt()); cornerRadius = dp(18).toFloat() }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: android.view.View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, dp(18).toFloat())
                }
            }
            if (bmp != null) setImageBitmap(bmp)
        }
        box.addView(pic, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(10) })

        if (desc.isNotBlank()) box.addView(TextView(act).apply {
            text = desc
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        fun btn(text: String, color: Int, onClick: () -> Unit) = TextView(act).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), 0x66000000)
            background = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(16).toFloat(); setStroke(dp(3), 0x55000000)
            }
            setOnClickListener { onClick() }
        }

        val dlg = Dialog(act)
        dialog = dlg
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Вперёд!", 0xFF53B848.toInt()) { dlg.dismiss(); answer("go\t$id") },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        row.addView(btn("Отмена", 0xFFE8872C.toInt()) { dlg.dismiss(); answer("cancel") },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dlg.setContentView(box)
        dlg.setCanceledOnTouchOutside(false)
        dlg.setOnCancelListener { answer("cancel") }
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = act.resources.displayMetrics
            setLayout(minOf(dp(380), (dm.widthPixels * 0.9f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.5f)
            // диалог не должен выбивать игру из полноэкранного режима
            addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            decorView.systemUiVisibility = act.window.decorView.systemUiVisibility
        }
        dlg.show()
        dlg.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        BLog.i("окно уровня показано: «$name»")
    }
}
