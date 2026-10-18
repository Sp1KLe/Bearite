/*
 * SBA Zoom - мод для Super Bear Adventure (Bearite, API версии 1).
 * Приближает камеру: делит угол обзора (FOV) главной камеры на "силу зума".
 * Методы игры ищутся по имени через IL2CPP, смещения из dump.cs не нужны.
 */
#include <stddef.h>
#include <math.h>

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

/* ---- Глобальные переменные ---- */
static const BeariteApi* g_api = nullptr;
static const char* g_id = "sba-zoom";

/* Типы функций IL2CPP */
typedef void*  (*DomainGetFn)();
typedef void** (*DomainGetAssembliesFn)(void* domain, size_t* count);
typedef void*  (*AssemblyGetImageFn)(void* assembly);
typedef void*  (*ClassFromNameFn)(void* image, const char* ns, const char* name);
typedef void*  (*ClassGetMethodFn)(void* klass, const char* name, int argc);

/* Типы методов Unity (последний аргумент - служебный MethodInfo*) */
typedef void   (*SetFovFn)(void* self, float value, void* mi);
typedef float  (*GetFovFn)(void* self, void* mi);
typedef void*  (*GetMainFn)(void* mi);
typedef float  (*GetTimeFn)(void* mi);

static SetFovFn orig_set_fov  = nullptr;
static GetMainFn orig_get_main = nullptr;
static GetFovFn fn_get_fov    = nullptr;
static GetTimeFn fn_get_time  = nullptr;

static void*  g_cam     = nullptr; /* главная камера игры */
static float  g_baseFov = 0.0f;    /* обычный угол обзора (без зума) */
static float  g_zoom    = 1.0f;    /* текущий зум (1 = без зума) */
static double g_lastT   = 0.0;

static void logf_(const char* msg) {
    if (g_api && g_api->log) g_api->log(g_id, msg);
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

/* Один шаг плавного приближения (не чаще одного раза за кадр) */
static void step_zoom() {
    double now = fn_get_time ? (double)fn_get_time(nullptr) : 0.0;
    if (now <= g_lastT) return;

    double dt = now - g_lastT;
    if (g_lastT == 0.0 || dt > 0.25) dt = 0.016;
    g_lastT = now;

    bool on = g_api->get_bool(g_id, "zoom_on", 0) != 0;
    double amount = g_api->get_float(g_id, "zoom_amount", 2.0);
    double smooth = g_api->get_float(g_id, "smooth", 8.0);
    if (amount < 1.0) amount = 1.0;
    if (smooth < 0.5) smooth = 0.5;

    double target = on ? amount : 1.0;
    double k = 1.0 - exp(-smooth * dt);
    g_zoom += (float)((target - g_zoom) * k);
    if (g_zoom < 1.0f) g_zoom = 1.0f;
}

/* Хук: игра задаёт угол обзора камеры */
static void hook_set_fov(void* self, float value, void* mi) {
    if (self == g_cam && g_cam != nullptr) {
        g_baseFov = value;
        step_zoom();
        orig_set_fov(self, value / g_zoom, mi);
    } else {
        orig_set_fov(self, value, mi);
    }
}

/* Хук: игра запрашивает главную камеру (делает это часто, служит как "каждый кадр") */
static void* hook_get_main(void* mi) {
    void* cam = orig_get_main(mi);
    if (cam) {
        g_cam = cam;
        if (g_baseFov <= 0.0f && fn_get_fov) g_baseFov = fn_get_fov(cam, nullptr);
        step_zoom();
        if (g_baseFov > 0.0f && orig_set_fov) {
            orig_set_fov(cam, g_baseFov / g_zoom, nullptr);
        }
    }
    return cam;
}

/* Точка входа мода: Bearite вызывает её один раз */
extern "C" __attribute__((visibility("default")))
void bearite_mod_init(const BeariteApi* api, const char* mod_id) {
    g_api = api;
    if (mod_id) g_id = mod_id;
    logf_("SBA Zoom: запуск");

    void* p_set  = find_method("UnityEngine", "Camera", "set_fieldOfView", 1);
    void* p_get  = find_method("UnityEngine", "Camera", "get_fieldOfView", 0);
    void* p_main = find_method("UnityEngine", "Camera", "get_main", 0);
    void* p_time = find_method("UnityEngine", "Time", "get_unscaledTime", 0);

    if (!p_set)  logf_("SBA Zoom: не найден Camera.set_fieldOfView");
    if (!p_get)  logf_("SBA Zoom: не найден Camera.get_fieldOfView");
    if (!p_main) logf_("SBA Zoom: не найден Camera.get_main");
    if (!p_time) logf_("SBA Zoom: не найден Time.get_unscaledTime");
    if (!p_set || !p_main) {
        logf_("SBA Zoom: без этих методов зум не работает, выхожу");
        return;
    }

    fn_get_fov  = (GetFovFn)p_get;
    fn_get_time = (GetTimeFn)p_time;

    if (api->hook(p_set, (void*)hook_set_fov, (void**)&orig_set_fov) != 0) {
        logf_("SBA Zoom: не удалось повесить хук set_fieldOfView");
        orig_set_fov = nullptr;
        return;
    }
    if (api->hook(p_main, (void*)hook_get_main, (void**)&orig_get_main) != 0) {
        logf_("SBA Zoom: не удалось повесить хук get_main");
        return;
    }
    logf_("SBA Zoom: готов");
}
