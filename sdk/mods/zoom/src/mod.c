/*
 * Зум камеры. Перехватывает Camera.set_fieldOfView и делит угол обзора на значение «Зум».
 * Значение меняется в мод-меню на паузе и подхватывается сразу, без перезапуска игры.
 *
 * Не проверено на реальной игре. Если в логе после запуска нет строки «первый вызов», игра задаёт
 * угол не этим методом: поменяй TARGET_* ниже (например на другой метод камеры) и пересобери.
 */
#include <stdio.h>

#include "bearite_api.h"
#include "bearite_il2cpp.h"

#define TARGET_NS     "UnityEngine"
#define TARGET_CLASS  "Camera"
#define TARGET_METHOD "set_fieldOfView"
#define TARGET_ARGS   1

static const BeariteApi* g_api;
static const char* g_id;
static int g_calls;
static void (*orig_set_fov)(void* self, float value, const void* method);

static void my_set_fov(void* self, float value, const void* method) {
    double zoom = g_api->get_float(g_id, "zoom", 1.0);
    if (zoom < 0.1) zoom = 1.0;
    float out = (float)(value / zoom);
    if (out < 5.0f) out = 5.0f;
    if (out > 170.0f) out = 170.0f;
    if (g_calls < 3) {
        char m[120];
        ++g_calls;
        snprintf(m, sizeof m, "первый вызов: угол %.1f -> %.1f (зум %.2f)", value, out, zoom);
        g_api->log(g_id, m);
    }
    orig_set_fov(self, out, method);
}

__attribute__((visibility("default")))
void bearite_mod_init(const BeariteApi* api, const char* mod_id) {
    g_api = api;
    g_id = mod_id;
    if (api->version != BEARITE_API_VERSION) return;
    if (!bearite_il2cpp_init(api)) {
        api->log(mod_id, "не нашлись функции IL2CPP");
        return;
    }
    bearite_hook_method(api, mod_id, TARGET_NS, TARGET_CLASS, TARGET_METHOD, TARGET_ARGS,
                        (void*)my_set_fov, (void**)&orig_set_fov);
}
