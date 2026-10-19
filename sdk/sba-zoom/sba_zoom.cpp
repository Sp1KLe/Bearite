/*
 * SBA Zoom - мод для Super Bear Adventure (Bearite, API версии 1).
 * Приближает камеру: делит угол обзора (FOV) главной камеры на «силу зума».
 * Включается кнопкой «Зум» на экране игры, «+» и «−» меняют силу (см. "hud" в mod.json).
 * Методы игры ищутся по имени через IL2CPP, смещения из dump.cs не нужны.
 *
 * Как работает:
 *  - хук Camera.set_fieldOfView: когда игра сама задаёт угол главной камеры, делим его на зум;
 *  - хук Camera.get_main: игра зовёт его постоянно, так что он служит «тиком каждый кадр»:
 *    плавно меняет зум и применяет его, даже если игра угол не трогает.
 * Если зум не работает, в логе Bearite через 20 секунд после запуска будет строка «SBA Zoom: за 20 с ...»
 * со счётчиками вызовов: по ней видно, какой из методов игра вообще использует.
 */
#include <math.h>
#include <stddef.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

#include <thread>

/* ---- Копия описания Bearite SDK (чтобы файл был самодостаточным) ---- */
extern "C" {
#define BEARITE_API_VERSION 1

typedef struct BeariteApi {
    int version;
    void (*log)(const char* mod_id, const char* message);
    void* (*il2cpp_symbol)(const char* name);
    void* (*module_base)(const char* so_name_part);
    int (*hook)(void* target, void* replacement, void** original);
    int (*get_bool)(const char* mod_id, const char* key, int default_value);
    long long (*get_int)(const char* mod_id, const char* key, long long default_value);
    double (*get_float)(const char* mod_id, const char* key, double default_value);
    const char* (*get_string)(const char* mod_id, const char* key, const char* default_value);
} BeariteApi;
}

/* ---- Состояние ---- */
static const BeariteApi* g_api = nullptr;
static const char* g_id = "sba-zoom";

/* Типы функций IL2CPP */
typedef void*  (*DomainGetFn)();
typedef void** (*DomainGetAssembliesFn)(void* domain, size_t* count);
typedef void*  (*AssemblyGetImageFn)(void* assembly);
typedef void*  (*ClassFromNameFn)(void* image, const char* ns, const char* name);
typedef void*  (*ClassGetMethodFn)(void* klass, const char* name, int argc);

/* Методы Unity: последний аргумент - служебный MethodInfo* */
typedef void  (*SetFovFn)(void* self, float value, void* mi);
typedef float (*GetFovFn)(void* self, void* mi);
typedef void* (*GetMainFn)(void* mi);

static SetFovFn  orig_set_fov  = nullptr;  /* оригинал после установки хука */
static GetMainFn orig_get_main = nullptr;
static GetMainFn raw_get_main  = nullptr;  /* исходный адрес (пока get_main не перехвачен) */
static GetFovFn  fn_get_fov    = nullptr;

static void*  g_cam       = nullptr;  /* главная камера */
static float  g_baseFov   = 0.0f;     /* угол обзора без зума */
static float  g_zoom      = 1.0f;     /* текущий зум, 1 = без зума */
static float  g_applied   = 1.0f;     /* зум, который уже применён к камере из тика */
static double g_lastT     = 0.0;

/* настройки читаем не чаще 10 раз в секунду: get_main зовут очень часто */
static double g_cfgT      = 0.0;
static bool   g_on        = false;
static double g_amount    = 2.0;
static double g_smooth    = 8.0;

/* счётчики для диагностики */
static volatile int g_mainCalls = 0;
static volatile int g_setCalls  = 0;
static volatile int g_appliedCalls = 0;

static void say(const char* msg) {
    if (g_api && g_api->log) g_api->log(g_id, msg);
}

static double now_sec() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}

/* Ищет метод по имени во всех сборках игры, возвращает адрес кода метода */
static void* find_method(const char* ns, const char* cls, const char* name, int argc) {
    auto domain_get = (DomainGetFn)g_api->il2cpp_symbol("il2cpp_domain_get");
    auto get_asms   = (DomainGetAssembliesFn)g_api->il2cpp_symbol("il2cpp_domain_get_assemblies");
    auto asm_image  = (AssemblyGetImageFn)g_api->il2cpp_symbol("il2cpp_assembly_get_image");
    auto class_from = (ClassFromNameFn)g_api->il2cpp_symbol("il2cpp_class_from_name");
    auto get_method = (ClassGetMethodFn)g_api->il2cpp_symbol("il2cpp_class_get_method_from_name");
    if (!domain_get || !get_asms || !asm_image || !class_from || !get_method) return nullptr;

    void* domain = domain_get();
    if (!domain) return nullptr;
    size_t count = 0;
    void** asms = get_asms(domain, &count);
    if (!asms) return nullptr;

    for (size_t i = 0; i < count; i++) {
        void* image = asm_image(asms[i]);
        if (!image) continue;
        void* klass = class_from(image, ns, cls);
        if (!klass) continue;
        void* mi = get_method(klass, name, argc);
        if (mi) return *(void**)mi; /* первое поле MethodInfo - указатель на код */
    }
    return nullptr;
}

/* Главная камера без побочных эффектов (оригинальная функция, не наш хук) */
static void* main_camera() {
    GetMainFn f = orig_get_main ? orig_get_main : raw_get_main;
    return f ? f(nullptr) : nullptr;
}

static void reload_config(double now) {
    if (now - g_cfgT < 0.1) return;
    g_cfgT = now;
    g_on     = g_api->get_bool(g_id, "zoom_on", 0) != 0;
    g_amount = g_api->get_float(g_id, "zoom_amount", 2.0);
    g_smooth = g_api->get_float(g_id, "smooth", 8.0);
    if (g_amount < 1.0) g_amount = 1.0;
    if (g_smooth < 0.5) g_smooth = 0.5;
}

/* Один шаг плавного приближения */
static void step_zoom() {
    double now = now_sec();
    double dt = now - g_lastT;
    if (g_lastT == 0.0 || dt > 0.25) dt = 0.016;
    if (dt <= 0.0) return;
    g_lastT = now;

    reload_config(now);
    double target = g_on ? g_amount : 1.0;
    double k = 1.0 - exp(-g_smooth * dt);
    g_zoom += (float)((target - g_zoom) * k);
    if (fabsf(g_zoom - (float)target) < 0.003f) g_zoom = (float)target;  /* дошли до цели */
    if (g_zoom < 1.0f) g_zoom = 1.0f;
}

/* Хук: игра задаёт угол обзора камеры */
static void hook_set_fov(void* self, float value, void* mi) {
    ++g_setCalls;
    void* cam = main_camera();
    if (self != nullptr && self == cam) {
        g_cam = cam;
        g_baseFov = value;
        step_zoom();
        g_applied = g_zoom;
        orig_set_fov(self, value / g_zoom, mi);
    } else {
        orig_set_fov(self, value, mi);
    }
}

/* Хук: игра запрашивает главную камеру (служит «тиком» каждый кадр) */
static void* hook_get_main(void* mi) {
    void* cam = orig_get_main(mi);
    ++g_mainCalls;
    if (!cam) return cam;
    g_cam = cam;
    if (g_baseFov <= 0.0f && fn_get_fov) g_baseFov = fn_get_fov(cam, nullptr);
    if (g_baseFov > 0.0f) {
        step_zoom();
        if (fabsf(g_zoom - g_applied) > 0.002f) {  /* применяем, только когда зум меняется */
            orig_set_fov(cam, g_baseFov / g_zoom, nullptr);
            g_applied = g_zoom;
            ++g_appliedCalls;
        }
    }
    return cam;
}

/* Через 20 секунд пишет в лог, какие методы игра реально вызывала */
static void watchdog() {
    sleep(20);
    char m[220];
    snprintf(m, sizeof m, "SBA Zoom: за 20 с вызовов get_main=%d (камера найдена: %s), set_fieldOfView=%d, применений зума=%d, угол=%.1f",
             g_mainCalls, g_cam ? "да" : "нет", g_setCalls, g_appliedCalls, (double)g_baseFov);
    say(m);
    if (g_mainCalls == 0 && g_setCalls == 0) {
        say("SBA Zoom: игра не вызывает ни Camera.main, ни set_fieldOfView: зум этим способом не заработает");
    } else if (!g_cam) {
        say("SBA Zoom: Camera.main возвращает null (у камеры нет тега MainCamera?)");
    }
}

/* Точка входа мода: Bearite вызывает её один раз */
extern "C" __attribute__((visibility("default")))
void bearite_mod_init(const BeariteApi* api, const char* mod_id) {
    g_api = api;
    if (mod_id) g_id = mod_id;
    say("SBA Zoom: запуск");

    void* p_set  = find_method("UnityEngine", "Camera", "set_fieldOfView", 1);
    void* p_get  = find_method("UnityEngine", "Camera", "get_fieldOfView", 0);
    void* p_main = find_method("UnityEngine", "Camera", "get_main", 0);

    if (!p_set)  say("SBA Zoom: не найден Camera.set_fieldOfView");
    if (!p_get)  say("SBA Zoom: не найден Camera.get_fieldOfView");
    if (!p_main) say("SBA Zoom: не найден Camera.get_main");
    if (!p_set) {
        say("SBA Zoom: без set_fieldOfView зум не работает, выхожу");
        return;
    }

    fn_get_fov = (GetFovFn)p_get;
    raw_get_main = (GetMainFn)p_main;

    /* сначала get_main (тик), потом set_fieldOfView */
    if (p_main) {
        if (api->hook(p_main, (void*)hook_get_main, (void**)&orig_get_main) != 0) {
            say("SBA Zoom: не удалось повесить хук get_main, работаю только через set_fieldOfView");
            orig_get_main = nullptr;
        } else {
            say("SBA Zoom: хук get_main установлен");
        }
    }
    if (api->hook(p_set, (void*)hook_set_fov, (void**)&orig_set_fov) != 0) {
        say("SBA Zoom: не удалось повесить хук set_fieldOfView");
        orig_set_fov = nullptr;
        return;
    }
    say("SBA Zoom: хук set_fieldOfView установлен");

    std::thread(watchdog).detach();
    say("SBA Zoom: готов. Нажми кнопку «Зум» на экране игры");
}
