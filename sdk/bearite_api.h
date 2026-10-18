/*
 * Bearite mod SDK, API версии 1.
 *
 * Нативный мод - это arm64-v8a .so, который экспортирует одну функцию:
 *
 *     extern "C" __attribute__((visibility("default")))
 *     void bearite_mod_init(const BeariteApi* api, const char* mod_id);
 *
 * Bearite вызывает её один раз после запуска IL2CPP игры (на потоке UnityMain).
 * Библиотека мода указывается в mod.json в поле "libs".
 *
 * Мод не линкуется с Bearite: всё нужное приходит через структуру BeariteApi.
 */
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#define BEARITE_API_VERSION 1
#define BEARITE_MOD_INIT_SYMBOL "bearite_mod_init"

typedef struct BeariteApi {
    int version; /* BEARITE_API_VERSION */

    /* Строка в logcat (тег Bearite) и в лог на вкладке «Лог». */
    void (*log)(const char* mod_id, const char* message);

    /* Адрес экспортируемой функции il2cpp_* (например "il2cpp_class_from_name"). NULL, если нет. */
    void* (*il2cpp_symbol)(const char* name);

    /* База загрузки (load bias) первой загруженной библиотеки, в чьём пути есть подстрока
     * (например "libil2cpp.so"). Адрес функции = база + смещение из дампа. NULL, если не найдена. */
    void* (*module_base)(const char* so_name_part);

    /* Инлайн-хук (Dobby). Возвращает 0 при успехе. *original получает вызов оригинала. */
    int (*hook)(void* target, void* replacement, void** original);

    /* Значения настроек мода (из экрана настроек). Если ключа нет, возвращается default_value.
     * Файл настроек перечитывается при изменении, так что вызывать можно в любой момент. */
    int (*get_bool)(const char* mod_id, const char* key, int default_value);
    long long (*get_int)(const char* mod_id, const char* key, long long default_value);
    double (*get_float)(const char* mod_id, const char* key, double default_value);
    /* Указатель действителен до следующего вызова get_string из того же потока. */
    const char* (*get_string)(const char* mod_id, const char* key, const char* default_value);
} BeariteApi;

typedef void (*BeariteModInitFn)(const BeariteApi* api, const char* mod_id);

#ifdef __cplusplus
}
#endif
