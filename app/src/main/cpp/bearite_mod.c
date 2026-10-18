/*
 * Встроенный мод Bearite. Собирается вместе с лаунчером (libbearite_mod.so) и загружается
 * в игру как обычный нативный мод, но ставить и подтверждать его не нужно.
 * Пока только сообщает в лог, что лаунчер и SDK работают.
 */
#include <stdio.h>

#include "bearite_api.h"

__attribute__((visibility("default")))
void bearite_mod_init(const BeariteApi* api, const char* mod_id) {
    char msg[200];
    if (api->version != BEARITE_API_VERSION) {
        snprintf(msg, sizeof msg, "версия API %d, ожидалась %d", api->version, BEARITE_API_VERSION);
        api->log(mod_id, msg);
        return;
    }
    void* base = api->module_base("libil2cpp.so");
    snprintf(msg, sizeof msg, "встроенный мод Bearite запущен, libil2cpp: база=%p", base);
    api->log(mod_id, msg);
}
