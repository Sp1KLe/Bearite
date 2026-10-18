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

    /** Состояние паузы: -1 неизвестно (определение недоступно), 0 игра идёт, 1 пауза. */
    public static volatile int pauseState = -1;

    /** Вызывается из нативного потока при смене pauseState (из потока, не UI). */
    public static volatile Runnable pauseListener;

    /** Вызывается из libbearite. */
    @SuppressWarnings("unused")
    static void onPauseState(int state) {
        pauseState = state;
        Runnable l = pauseListener;
        if (l != null) l.run();
    }

    /** @param configPath файл со списком бандлов, по одному пути в строке */
    public static native void init(String configPath);
}
