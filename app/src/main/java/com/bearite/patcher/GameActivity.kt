package com.bearite.patcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import dalvik.system.PathClassLoader
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Запускает UnityPlayer из установленной игры, не трогая её саму.
 *
 * Идея (как у лаунчера Geode): собираем свой ClassLoader из APK игры (+split'ы),
 * нашего APK и папок с .so обеих сторон. libunity/libil2cpp игры и наша
 * libbearite оказываются в одном linker namespace, а классы игры (UnityPlayer)
 * создаются поверх нашей Activity. Всё, что игра спрашивает у Context
 * (имя пакета, ресурсы, assets, путь к APK), мы подменяем на данные игры,
 * а системе отдаём настоящие значения лаунчера.
 *
 * Поверх игры рисуется кнопка «Моды», она открывает ModMenuActivity.
 */
class GameActivity : Activity() {
    private var gameCtx: Context? = null
    private var gameLoader: ClassLoader? = null

    /** Объект плеера Unity: View (старые версии) или UnityPlayerForActivityOrService (Unity 6). */
    private var player: Any? = null
    private var gamePkg: String = GAME_PKG

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gamePkg = intent.getStringExtra(EXTRA_PKG) ?: GAME_PKG
        BLog.init(this)
        BLog.i("GameActivity: старт, pid=${Process.myPid()}")
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            BLog.e("необработанное исключение в потоке ${t.name}", e)
            Process.killProcess(Process.myPid())
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            launchGame()
        } catch (t: Throwable) {
            BLog.e("запуск игры не удался", t)
            showError(t)
        }
    }

    private fun launchGame() {
        val info = packageManager.getApplicationInfo(gamePkg, 0)
        val ctx = createPackageContext(gamePkg, Context.CONTEXT_IGNORE_SECURITY)
        gameCtx = ctx

        val abi = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: Build.SUPPORTED_32_BIT_ABIS.first()
        val gameApks = ArrayList<String>()
        gameApks.add(info.sourceDir)
        info.splitSourceDirs?.let { gameApks.addAll(it) }
        val ourApk = applicationInfo.sourceDir

        val libPaths = ArrayList<String>()
        info.nativeLibraryDir?.let { libPaths.add(it) }
        gameApks.forEach { libPaths.add("$it!/lib/$abi") }
        applicationInfo.nativeLibraryDir?.let { libPaths.add(it) }
        libPaths.add("$ourApk!/lib/$abi")

        BLog.i("игра: ${info.sourceDir}, splits=${info.splitSourceDirs?.size ?: 0}, abi=$abi")
        BLog.i("libPath: ${libPaths.joinToString(":")}")

        // parent = BootClassLoader: наши классы приложения не просачиваются,
        // Native загрузится вторым экземпляром именно из этого загрузчика
        val loader = PathClassLoader(
            (gameApks + ourApk).joinToString(":"),
            libPaths.joinToString(":"),
            classLoader.parent
        )
        gameLoader = loader

        val cfg = ModManager(this).configFile.absolutePath
        loader.loadClass("com.bearite.patcher.Native")
            .getMethod("init", String::class.java)
            .invoke(null, cfg)

        // как в стандартном UnityPlayerActivity
        window.takeSurface(null)
        window.setFormat(PixelFormat.RGBX_8888)

        val p = createPlayer(loader)
        player = p
        val view = playerView(p)

        val root = FrameLayout(this)
        root.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(menuButton())
        setContentView(root)
        view.requestFocus()
        BLog.i("UnityPlayer создан: ${p.javaClass.name}")
    }

    /**
     * Unity 6: UnityPlayerForActivityOrService(Context, IUnityPlayerLifecycleEvents),
     * у самого UnityPlayer конструктор внутренний. Старые версии: UnityPlayer(Context).
     */
    private fun createPlayer(loader: ClassLoader): Any {
        val events = lifecycleEvents(loader)
        val report = StringBuilder()
        for (name in listOf(
            "com.unity3d.player.UnityPlayerForActivityOrService",
            "com.unity3d.player.UnityPlayer"
        )) {
            val cls = try {
                loader.loadClass(name)
            } catch (_: ClassNotFoundException) {
                report.append("нет класса ").append(name).append('\n')
                continue
            }
            for (c in cls.constructors) {
                val pt = c.parameterTypes
                if (pt.isEmpty() || !Context::class.java.isAssignableFrom(pt[0])) continue
                val args: Array<Any?> = when {
                    pt.size == 1 -> arrayOf(this)
                    pt.size == 2 && pt[1].name.endsWith("IUnityPlayerLifecycleEvents") -> arrayOf(this, events)
                    pt.size == 2 -> arrayOf(this, null)
                    else -> continue
                }
                BLog.i("конструктор плеера: ${cls.name}(${pt.joinToString { it.simpleName }})")
                return c.newInstance(*args)
            }
            report.append(cls.name).append(": ").append(cls.constructors.joinToString { it.toString() }).append('\n')
        }
        throw IllegalStateException("не нашёл подходящий конструктор плеера Unity:\n$report")
    }

    /** Реализация IUnityPlayerLifecycleEvents: выгрузка/выход плеера закрывают игру. */
    private fun lifecycleEvents(loader: ClassLoader): Any? = try {
        val iface = loader.loadClass("com.unity3d.player.IUnityPlayerLifecycleEvents")
        Proxy.newProxyInstance(loader, arrayOf(iface), InvocationHandler { _, method, _ ->
            when (method.name) {
                "onUnityPlayerQuitted" -> runOnUiThread { finish() }
                "onUnityPlayerUnloaded" -> runOnUiThread { moveTaskToBack(true) }
            }
            null
        })
    } catch (_: ClassNotFoundException) {
        null
    }

    private fun playerView(p: Any): View {
        if (p is View) return p
        val m = p.javaClass.methods.firstOrNull { it.name == "getFrameLayout" && it.parameterTypes.isEmpty() }
            ?: throw IllegalStateException("${p.javaClass.name} не View и не имеет getFrameLayout()")
        return m.invoke(p) as View
    }

    private fun menuButton(): View {
        val d = resources.displayMetrics.density
        val size = (44 * d).toInt()
        val b = ImageButton(this)
        b.setImageResource(R.drawable.ic_mods_white)
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x66000000)
        }
        b.alpha = 0.75f
        b.contentDescription = "Моды"
        b.setOnClickListener { startActivity(Intent(this, ModMenuActivity::class.java)) }
        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
        lp.setMargins(0, (12 * d).toInt(), (12 * d).toInt(), 0)
        b.layoutParams = lp
        return b
    }

    private fun showError(t: Throwable) {
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(32, 48, 32, 32)
            text = "Не удалось запустить игру:\n\n" + t.toString() +
                "\n\nПодробности в логе на главном экране Bearite."
        }
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.BLACK)
        sv.addView(tv)
        setContentView(sv)
    }

    // ---------------------------------------------------------- подмена Context
    // Игре (всё, что не android.*/java.*/наше) отдаём данные игры, системе - свои.

    private fun fromGame(): Boolean {
        val st = Throwable().stackTrace
        if (st.size < 3) return true
        val c = st[2].className
        return !(c.startsWith("android.") || c.startsWith("com.android.") ||
            c.startsWith("java.") || c.startsWith("javax.") || c.startsWith("dalvik.") ||
            c.startsWith("androidx.") || c.startsWith("com.bearite.") ||
            c.startsWith("com.google.android.material.") ||
            c.startsWith("kotlin.") || c.startsWith("libcore.") || c.startsWith("sun."))
    }

    override fun getPackageName(): String =
        if (gameCtx != null && fromGame()) gamePkg else super.getPackageName()

    override fun getResources(): Resources {
        val g = gameCtx
        return if (g != null && fromGame()) g.resources else super.getResources()
    }

    override fun getAssets(): AssetManager {
        val g = gameCtx
        return if (g != null && fromGame()) g.assets else super.getAssets()
    }

    override fun getApplicationInfo(): ApplicationInfo {
        val g = gameCtx
        return if (g != null && fromGame()) g.applicationInfo else super.getApplicationInfo()
    }

    override fun getPackageCodePath(): String {
        val g = gameCtx
        return if (g != null && fromGame()) g.packageCodePath else super.getPackageCodePath()
    }

    override fun getPackageResourcePath(): String {
        val g = gameCtx
        return if (g != null && fromGame()) g.packageResourcePath else super.getPackageResourcePath()
    }

    override fun getClassLoader(): ClassLoader {
        val l = gameLoader
        return if (l != null && fromGame()) l else super.getClassLoader()
    }

    // ------------------------------------------------------- жизненный цикл

    /** Вызывает первый найденный метод плеера из names (имена разные в версиях Unity). */
    private fun callPlayer(names: List<String>, vararg args: Any?) {
        val p = player ?: return
        try {
            val m = p.javaClass.methods.firstOrNull {
                it.name in names && it.parameterTypes.size == args.size
            } ?: return
            m.invoke(p, *args)
        } catch (t: Throwable) {
            BLog.e("UnityPlayer.${names.first()}", t)
        }
    }

    private fun callPlayer(name: String, vararg args: Any?) = callPlayer(listOf(name), *args)

    private fun inject(e: InputEvent): Boolean {
        val p = player ?: return false
        return try {
            val m = p.javaClass.methods.firstOrNull { it.name == "injectEvent" && it.parameterTypes.size == 1 }
                ?: return false
            (m.invoke(p, e) as? Boolean) ?: false
        } catch (_: Throwable) {
            false
        }
    }

    override fun onStart() { super.onStart(); callPlayer(listOf("onStart", "start")) }
    override fun onStop() { callPlayer(listOf("onStop", "stop")); super.onStop() }
    override fun onResume() { super.onResume(); callPlayer("resume") }
    override fun onPause() { callPlayer("pause"); super.onPause() }

    override fun onDestroy() {
        BLog.i("GameActivity: выход")
        val hasDestroy = player?.javaClass?.methods?.any { it.name == "destroy" && it.parameterTypes.isEmpty() } == true
        callPlayer(if (hasDestroy) "destroy" else "quit")
        super.onDestroy()
        // Unity нельзя перезапустить в том же процессе
        Process.killProcess(Process.myPid())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        callPlayer("newIntent", intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        callPlayer("configurationChanged", newConfig)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        callPlayer("windowFocusChanged", hasFocus)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        callPlayer("lowMemory")
    }

    // ввод: как в стандартном UnityPlayerActivity, события отдаём плееру
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE && player != null) return inject(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        inject(event) || super.onKeyUp(keyCode, event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        inject(event) || super.onKeyDown(keyCode, event)

    override fun onTouchEvent(event: MotionEvent): Boolean =
        inject(event) || super.onTouchEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        inject(event) || super.onGenericMotionEvent(event)

    companion object {
        const val EXTRA_PKG = "game_pkg"
    }
}
