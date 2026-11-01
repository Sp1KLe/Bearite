/*
 * Читы для Super Bear Adventure (мод Bearite, API версии 1).
 * Методы игры найдены разведкой по логу Bearite. Все перехваты ставятся по имени через IL2CPP.
 * Внимание: написано без проверки на устройстве. Если чего-то нет в логе, мод пишет это в «Лог».
 */
#include <stdint.h>
#include "bearite_api.h"
#include "bearite_il2cpp.h"

static const BeariteApi* g_api = NULL;
static const char* g_id = "sba-cheats";

static void (*o_damage)(void* self, int type, float x, float y, float z, const void* mi);
static uint8_t (*o_is_grounded)(void* self, const void* mi);
static uint8_t (*o_emote_unlocked)(void* self, int emote, const void* mi);
static uint8_t (*o_moles_unlocked)(void* self, const void* mi);
static uint8_t (*o_fighter_unlocked)(void* self, int fighter, const void* mi);
static int32_t (*o_coin_balance)(void* self, const void* mi);
static void (*o_sub_coins)(void* self, int amount, const void* mi);
static int32_t (*o_token_count)(void* self, const void* mi);
static void (*o_sub_tokens)(void* self, int amount, const void* mi);

static int on(const char* key) { return g_api ? g_api->get_bool(g_id, key, 0) != 0 : 0; }

/* Урон: при бессмертии игнорируем */
static void h_damage(void* self, int type, float x, float y, float z, const void* mi) {
    if (on("god")) return;
    o_damage(self, type, x, y, z, mi);
}

/* Земля: при прыжке в воздухе считаем, что медведь стоит на земле */
static uint8_t h_is_grounded(void* self, const void* mi) {
    if (on("air_jump")) return 1;
    return o_is_grounded(self, mi);
}

/* Открытые эмоции и режимы: проверки открытия возвращают «да» */
static uint8_t h_emote_unlocked(void* self, int emote, const void* mi) {
    if (on("unlock")) return 1;
    return o_emote_unlocked(self, emote, mi);
}
static uint8_t h_moles_unlocked(void* self, const void* mi) {
    if (on("unlock")) return 1;
    return o_moles_unlocked(self, mi);
}
static uint8_t h_fighter_unlocked(void* self, int fighter, const void* mi) {
    if (on("unlock")) return 1;
    return o_fighter_unlocked(self, fighter, mi);
}

/* Монеты и жетоны: баланс показываем максимальным, списания пропускаем */
static int32_t h_coin_balance(void* self, const void* mi) {
    if (on("coins")) return 99999;
    return o_coin_balance(self, mi);
}
static void h_sub_coins(void* self, int amount, const void* mi) {
    if (on("coins")) return;
    o_sub_coins(self, amount, mi);
}
static int32_t h_token_count(void* self, const void* mi) {
    if (on("coins")) return 99;
    return o_token_count(self, mi);
}
static void h_sub_tokens(void* self, int amount, const void* mi) {
    if (on("coins")) return;
    o_sub_tokens(self, amount, mi);
}

__attribute__((visibility("default")))
void bearite_mod_init(const BeariteApi* api, const char* mod_id) {
    if (api->version != BEARITE_API_VERSION) return;
    g_api = api;
    if (mod_id) g_id = mod_id;
    if (!bearite_il2cpp_init(api)) {
        api->log(g_id, "Читы: не найдены функции IL2CPP, мод не работает");
        return;
    }
    const char* P = "PlayerControllerBase";
    const char* S = "SaveGame";
    bearite_hook_method(api, g_id, "", P, "DamagePlayer", 2, (void*)h_damage, (void**)&o_damage);
    bearite_hook_method(api, g_id, "", P, "IsGrounded", 0, (void*)h_is_grounded, (void**)&o_is_grounded);
    bearite_hook_method(api, g_id, "", S, "IsEmoteUnlocked", 1, (void*)h_emote_unlocked, (void**)&o_emote_unlocked);
    bearite_hook_method(api, g_id, "", S, "AreMolesUnlocked", 0, (void*)h_moles_unlocked, (void**)&o_moles_unlocked);
    bearite_hook_method(api, g_id, "", S, "IsFighterUnlocked", 1, (void*)h_fighter_unlocked, (void**)&o_fighter_unlocked);
    bearite_hook_method(api, g_id, "", S, "GetSavedCoinBalance", 0, (void*)h_coin_balance, (void**)&o_coin_balance);
    bearite_hook_method(api, g_id, "", S, "SubTotalCoinCount", 1, (void*)h_sub_coins, (void**)&o_sub_coins);
    bearite_hook_method(api, g_id, "", S, "GetCapitalusTokenCount", 0, (void*)h_token_count, (void**)&o_token_count);
    bearite_hook_method(api, g_id, "", S, "SubCapitalusTokens", 1, (void*)h_sub_tokens, (void**)&o_sub_tokens);
    api->log(g_id, "Читы: перехваты установлены");
}
