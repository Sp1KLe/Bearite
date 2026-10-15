package com.bearite.patcher;

/**
 * Мост к libbearite.so. Намеренно на Java без зависимостей от Kotlin:
 * этот класс грузится вторым экземпляром через ClassLoader игры, чтобы
 * libbearite попала в тот же linker namespace, что и libil2cpp.so.
 */
public final class Native {
    static {
        System.loadLibrary("bearite");
    }

    private Native() {}

    /** @param configPath файл со списком бандлов, по одному пути в строке */
    public static native void init(String configPath);
}
