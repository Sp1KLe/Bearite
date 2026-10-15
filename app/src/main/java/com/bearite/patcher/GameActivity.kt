package com.bearite.patcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import dalvik.system.PathClassLoader

const val GAME_PKG = "com.EarthkwakGames.SuperBearAdventure"

/**
 * Запускает UnityPlayer из установленной игры, не трогая её саму.
 *
 * Идея (как у лаунчера Geode): собираем свой ClassLoader из APK игры (+split'ы),
 * нашего APK и папок с .so обеих сторон. libunity/libil2cpp игры и наша
 * libbearite оказываются в одном linker namespace, а классы игры (UnityPlayer)
 * создаются поверх нашей Activity. Всё, что игра спрашивает у Context
 * (имя пакета, ресурсы, assets, путь к APK), мы подменяем на данные игры,
 * а системе отдаём настоящие значения лаунчера.
 */
class GameActivity : Activity() {
    private var gameCtx: Context? = null
    private var gameLoader: ClassLoader? = null
    private var player: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val info = packageManager.getApplicationInfo(GAME_PKG, 0)
        val ctx = createPackageContext(GAME_PKG, Context.CONTEXT_IGNORE_SECURITY)
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

        val upClass = loader.loadClass("com.unity3d.player.UnityPlayer")
        val ctor = upClass.constructors.firstOrNull { c ->
            val p = c.parameterTypes
            p.isNotEmpty() && p.size <= 2 && Context::class.java.isAssignableFrom(p[0])
        } ?: throw IllegalStateException(
            "у UnityPlayer нет подходящего конструктора: " +
                upClass.constructors.joinToString { it.toString() }
        )
        val args = arrayOfNulls<Any>(ctor.parameterTypes.size)
        args[0] = this
        val p = ctor.newInstance(*args) as View
        player = p
        setContentView(p)
        p.requestFocus()
        BLog.i("UnityPlayer создан")
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
            c.startsWith("kotlin.") || c.startsWith("libcore.") || c.startsWith("sun."))
    }

    override fun getPackageName(): String =
        if (gameCtx != null && fromGame()) GAME_PKG else super.getPackageName()

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

    private fun callPlayer(name: String, vararg args: Any?) {
        val p = player ?: return
        try {
            val m = p.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == args.size
            } ?: return
            m.invoke(p, *args)
        } catch (t: Throwable) {
            BLog.e("UnityPlayer.$name", t)
        }
    }

    override fun onStart() { super.onStart(); callPlayer("start") }
    override fun onStop() { callPlayer("stop"); super.onStop() }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE && player != null) {
            callPlayer("injectEvent", event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
