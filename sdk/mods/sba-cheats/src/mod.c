/*
 * Читы для Super Bear Adventure (мод Bearite, API версии 1).
 * Методы игры найдены разведкой по логу Bearite. Все перехваты ставятся по имени через IL2CPP.
 * Настройки читаются при каждом обращении, поэтому меняются на лету из меню «Читы».
 */
#include <stdint.h>
#include <math.h>
#include "bearite_api.h"
#include "bearite_il2cpp.h"

static const BeariteApi* g_api = NULL;
static const char* g_id = "sba-cheats";

/* оригиналы перехваченных методов */
static void (*o_damage)(void* self, int type, float x, float y, float z, const void* mi);
static uint8_t (*o_is_grounded)(void* self, const void* mi);
static uint8_t (*o_has_any_ground)(void* self, const void* mi);
static void (*o_update)(void* self, const void* mi);
static uint8_t (*o_emote_unlocked)(void* self, int emote, const void* mi);
static uint8_t (*o_moles_unlocked)(void* self, const void* mi);
static uint8_t (*o_fighter_unlocked)(void* self, int fighter, const void* mi);
static uint8_t (*o_gate_opened)(void* self, void* level, const void* mi);
static uint8_t (*o_cosmetic_unlocked)(void* self, void* cosmetic, const void* mi);
static uint8_t (*o_cosmetic_max_unlocked)(void* self, void* cosmetic, const void* mi);
static void (*o_add_coins)(void* self, int amount, const void* mi);
static int32_t (*o_coin_balance)(void* self, const void* mi);
static void (*o_sub_coins)(void* self, int amount, const void* mi);
static int32_t (*o_token_count)(void* self, const void* mi);
static void (*o_sub_tokens)(void* self, int amount, const void* mi);

/* методы Unity для размера: Component.get_transform и Transform.set_localScale */
static void* m_get_transform = NULL;
static void* m_set_scale = NULL;
static int g_scaled = 0;

static int on(const char* key) { return g_api ? g_api->get_bool(g_id, key, 0) != 0 : 0; }
static double num(const char* key, double def) { return g_api ? g_api->get_float(g_id, key, def) : def; }
static long long integer(const char* key, long long def) { return g_api ? g_api->get_int(g_id, key, def) : def; }

/* урон: при бессмертии игнорируем */
static void h_damage(void* self, int type, float x, float y, float z, const void* mi) {
    if (on("god")) return;
    o_damage(self, type, x, y, z, mi);
}

/* земля: при прыжке в воздухе медведь считается стоящим на земле */
static uint8_t h_is_grounded(void* self, const void* mi) {
    if (on("air_jump")) return 1;
    return o_is_grounded(self, mi);
}
static uint8_t h_has_any_ground(void* self, const void* mi) {
    if (on("air_jump")) return 1;
    return o_has_any_ground(self, mi);
}

/* размер медведя: каждый кадр выставляем масштаб корневого объекта игрока */
static void apply_size(void* self) {
    double s = num("size", 1.0);
    if (s < 0.05) s = 1.0;
    int want = fabs(s - 1.0) > 1e-3;
    if (!want && !g_scaled) return;
    if (!m_get_transform || !m_set_scale) return;
    void* tr = bearite_invoke(m_get_transform, self, NULL);
    if (!tr) return;
    float v[3] = { (float)s, (float)s, (float)s };
    void* args[1] = { v };
    bearite_invoke(m_set_scale, tr, args);
    g_scaled = want;
}
static void h_update(void* self, const void* mi) {
    o_update(self, mi);
    apply_size(self);
}

/* открытые эмоции, режимы, ворота и косметика: проверки открытия возвращают «да» */
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
static uint8_t h_gate_opened(void* self, void* level, const void* mi) {
    if (on("open_gates")) return 1;
    return o_gate_opened(self, level, mi);
}
static uint8_t h_cosmetic_unlocked(void* self, void* cosmetic, const void* mi) {
    if (on("cosmetics")) return 1;
    return o_cosmetic_unlocked(self, cosmetic, mi);
}
static uint8_t h_cosmetic_max_unlocked(void* self, void* cosmetic, const void* mi) {
    if (on("cosmetics")) return 1;
    return o_cosmetic_max_unlocked(self, cosmetic, mi);
}

/* монеты: множитель за сбор, баланс показываем максимальным, списания пропускаем */
static void h_add_coins(void* self, int amount, const void* mi) {
    long long m = integer("coin_mult", 1);
    if (m < 1) m = 1;
    if (m > 10) m = 10;
    if (amount > 0 && m > 1) amount = (int)(amount * m);
    o_add_coins(self, amount, mi);
}
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
    const char* P = "PlayerBearController";  /* IsGrounded, HasAnyGround, DamagePlayer, Update переопределены здесь */
    const char* S = "SaveGame";
    m_get_transform = bearite_find_method("UnityEngine", "Component", "get_transform", 0);
    m_set_scale = bearite_find_method("UnityEngine", "Transform", "set_localScale", 1);
    if (!m_get_transform || !m_set_scale) api->log(g_id, "Читы: нет методов размера, размер медведя не работает");

    bearite_hook_method(api, g_id, "", P, "DamagePlayer", 2, (void*)h_damage, (void**)&o_damage);
    bearite_hook_method(api, g_id, "", P, "IsGrounded", 0, (void*)h_is_grounded, (void**)&o_is_grounded);
    bearite_hook_method(api, g_id, "", P, "HasAnyGround", 0, (void*)h_has_any_ground, (void**)&o_has_any_ground);
    bearite_hook_method(api, g_id, "", P, "Update", 0, (void*)h_update, (void**)&o_update);
    bearite_hook_method(api, g_id, "", S, "IsEmoteUnlocked", 1, (void*)h_emote_unlocked, (void**)&o_emote_unlocked);
    bearite_hook_method(api, g_id, "", S, "AreMolesUnlocked", 0, (void*)h_moles_unlocked, (void**)&o_moles_unlocked);
    bearite_hook_method(api, g_id, "", S, "IsFighterUnlocked", 1, (void*)h_fighter_unlocked, (void**)&o_fighter_unlocked);
    bearite_hook_method(api, g_id, "", S, "IsBearGateOpened", 1, (void*)h_gate_opened, (void**)&o_gate_opened);
    bearite_hook_method(api, g_id, "", S, "IsCosmeticUnlocked", 1, (void*)h_cosmetic_unlocked, (void**)&o_cosmetic_unlocked);
    bearite_hook_method(api, g_id, "", S, "IsCosmeticMaxLevelUnlocked", 1, (void*)h_cosmetic_max_unlocked, (void**)&o_cosmetic_max_unlocked);
    bearite_hook_method(api, g_id, "", S, "AddTotalCoinCount", 1, (void*)h_add_coins, (void**)&o_add_coins);
    bearite_hook_method(api, g_id, "", S, "GetSavedCoinBalance", 0, (void*)h_coin_balance, (void**)&o_coin_balance);
    bearite_hook_method(api, g_id, "", S, "SubTotalCoinCount", 1, (void*)h_sub_coins, (void**)&o_sub_coins);
    bearite_hook_method(api, g_id, "", S, "GetCapitalusTokenCount", 0, (void*)h_token_count, (void**)&o_token_count);
    bearite_hook_method(api, g_id, "", S, "SubCapitalusTokens", 1, (void*)h_sub_tokens, (void**)&o_sub_tokens);
    api->log(g_id, "Читы: перехваты установлены");
}
