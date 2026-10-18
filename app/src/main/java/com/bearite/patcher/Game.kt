package com.bearite.patcher

import android.content.Intent
import android.content.pm.PackageManager

/** Настоящий пакет Super Bear Adventure в Google Play (Earthkwak Games). */
const val GAME_PKG = "com.Earthkwak.Platformer"

data class GameInfo(val pkg: String, val version: String)

object Game {
    /**
     * Ищет установленную игру: сначала по известному имени пакета, затем по
     * названию среди приложений с иконкой в лаунчере (на случай другой сборки).
     */
    fun find(pm: PackageManager): GameInfo? {
        fun info(pkg: String): GameInfo? = try {
            GameInfo(pkg, pm.getPackageInfo(pkg, 0).versionName ?: "?")
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        info(GAME_PKG)?.let { return it }

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            val pkg = ri.activityInfo.packageName
            val label = ri.loadLabel(pm).toString()
            if (label.contains("Super Bear", ignoreCase = true) ||
                pkg.startsWith("com.Earthkwak", ignoreCase = true)
            ) {
                info(pkg)?.let { return it }
            }
        }
        return null
    }
}
