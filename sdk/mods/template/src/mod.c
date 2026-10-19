/*
 * Шаблон нативного мода Bearite. Скопируй папку, поменяй id в mod.json и пиши код здесь.
 * Всё нужное лежит в двух заголовках рядом с build-mod.sh: bearite_api.h и bearite_il2cpp.h.
 */
#include "bearite_api.h"
#include "bearite_il2cpp.h"

/* Этот мод ничего не перехватывает. Чтобы перехватить метод игры, раскомментируй пример ниже. */

/*
static void (*orig_set_fov)(void* self, float value, const void* method);

static void my_set_fov(void* self, float value, const void* method) {
    orig_set_fov(self, value * 0.8f, method);  // свой код, потом оригинал
}
*/

__attribute__((visibility("default")))
void bearite_mod_init(const BeariteApi* api, const char* mod_id) {
    if (api->version != BEARITE_API_VERSION) return;

    /* настройки из mod.json: get_bool / get_int / get_float / get_string */
    if (api->get_bool(mod_id, "greet", 1)) api->log(mod_id, "Привет из мода!");

    /* перехват метода игры по имени:
    if (bearite_il2cpp_init(api)) {
        bearite_hook_method(api, mod_id, "UnityEngine", "Camera", "set_fieldOfView", 1,
                            (void*)my_set_fov, (void**)&orig_set_fov);
    }
    */
}
