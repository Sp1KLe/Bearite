/*
 * Пример нативного мода Bearite. Ничего не меняет в игре: при запуске пишет
 * в лог строку с настройками по умолчанию (у примера их нет, но код читает их
 * так же, как читал бы у любого мода с настройками) и проверяет, что SDK отдаёт
 * адреса IL2CPP. Сборка: sdk/example-mod/README.md.
 */
#include <stdio.h>

#include "bearite_api.h"

__attribute__((visibility("default")))
void bearite_mod_init(const BeariteApi* api, const char* mod_id) {
    char msg[320];

    if (api->version != BEARITE_API_VERSION) {
        snprintf(msg, sizeof msg, "версия API %d, ожидалась %d", api->version, BEARITE_API_VERSION);
        api->log(mod_id, msg);
        return;
    }

    /* get_string возвращает общий буфер потока: копируем сразу, до следующего вызова */
    char greeting[128];
    char mode[64];
    snprintf(greeting, sizeof greeting, "%s", api->get_string(mod_id, "greeting", "Привет!"));
    snprintf(mode, sizeof mode, "%s", api->get_string(mod_id, "mode", "обычный"));

    int loud = api->get_bool(mod_id, "loud", 0);
    long long times = api->get_int(mod_id, "times", 1);
    double gain = api->get_float(mod_id, "gain", 1.0);

    snprintf(msg, sizeof msg, "greeting='%s' loud=%d times=%lld gain=%.2f mode='%s'", greeting,
             loud, times, gain, mode);
    for (long long i = 0; i < times && i < 5; ++i) api->log(mod_id, msg);

    void* base = api->module_base("libil2cpp.so");
    void* sym = api->il2cpp_symbol("il2cpp_domain_get");
    snprintf(msg, sizeof msg, "libil2cpp: база=%p, il2cpp_domain_get=%p", base, sym);
    api->log(mod_id, msg);
}
