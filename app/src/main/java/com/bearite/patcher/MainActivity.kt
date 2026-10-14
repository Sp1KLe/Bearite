package com.bearite.patcher

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ApkPatcher(private val context: Context) {

    fun patchGameApk(sourceApk: String, outApk: String) {
        val srcFile = File(sourceApk)
        val destFile = File(outApk)

        val zip = ZipFile(srcFile)
        val zos = ZipOutputStream(FileOutputStream(destFile))

        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            
            // Пропускаем оригинальные подписи безопасности
            if (entry.name.startsWith("META-INF/")) continue

            zos.putNextEntry(ZipEntry(entry.name))
            zip.getInputStream(entry).use { it.copyTo(zos) }
            zos.closeEntry()
        }

        // Автоматически вшиваем наш скомпилированный патчер-модуль в папку игры
        injectNativeCore(zos)

        zos.close()
        zip.close()
    }

    private fun injectNativeCore(zos: ZipOutputStream) {
        val architectures = listOf("arm64-v8a", "armeabi-v7a")
        for (arch in architectures) {
            zos.putNextEntry(ZipEntry("lib/$arch/libBeariteCore.so"))
            context.assets.open("$arch/libBeariteCore.so").use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
}
