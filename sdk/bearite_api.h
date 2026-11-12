/*
 * Bearite mod SDK, API версии 1.
 *
 * Нативный мод - это arm64-v8a .so, который экспортирует одну функцию:
 *
 *     extern "C" __attribute__((visibility("default")))
 *     void bearite_mod_init(const BeariteApi* api, const char* mod_id);
 *
 * Bearite вызывает её один раз после запуска IL2CPP игры (на потоке UnityMain).
 * Указатель mod_id действителен до конца процесса, его можно запомнить.
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

    /* Папка данных Bearite (filesDir приложения), без завершающего слеша. Моды могут хранить там свои файлы.
     * Добавлено позже остальных полей; версия API при этом не менялась. */
    const char* (*data_dir)(void);

    /* Звуки и песни. file: путь внутри папки мода (например "sounds/win.ogg"; wav, ogg, mp3, m4a). volume 0..1.
     * loop != 0 — повторять, пока не остановят. Возвращает 0, если команда принята, -1 если файла нет.
     * Звуки могут накладываться; песню с loop останавливает stop_sound (file = NULL или "" — остановить всё).
     * Добавлено позже остальных полей; версия API не менялась. Перед вызовом проверь, что api->play_sound != NULL. */
    int (*play_sound)(const char* mod_id, const char* file, float volume, int loop);
    void (*stop_sound)(const char* mod_id, const char* file);

    /* Метки объектов карт. В редакторе у объекта есть поле «Метка» (start, checkpoint, enemy…): мод находит такие
     * объекты и сам решает, что с ними делать. tag_count — сколько живых объектов с меткой (в сцене сейчас).
     * tag_get — мировая позиция xyz и размер xyz объекта с номером index (0..count-1), pos3/size3 могут быть NULL; 1 — успех.
     * tag_set_visible — спрятать (0) или вернуть (1) объект; index < 0 — все с этой меткой.
     * Добавлено позже остальных полей; перед вызовом проверь, что указатель не NULL. */
    int (*tag_count)(const char* tag);
    int (*tag_get)(const char* tag, int index, float* pos3, float* size3);
    void (*tag_set_visible)(const char* tag, int index, int visible);

    /* Достижения мода. Описываются в mod.json: "achievements": [{"id":"first_coin","name":"Первая монета","desc":"Подбери монету"}].
     * unlock_achievement открывает достижение id (повторный вызов ничего не делает): игрок видит уведомление,
     * а в паузе по кнопке «Достижения» — список своих достижений мода. Перед вызовом проверь указатель на NULL. */
    void (*unlock_achievement)(const char* mod_id, const char* achievement_id);
} BeariteApi;

typedef void (*BeariteModInitFn)(const BeariteApi* api, const char* mod_id);

#ifdef __cplusplus
}
#endif
