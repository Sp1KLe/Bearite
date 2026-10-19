/*
 * Помощники для нативных модов Bearite: найти класс или метод IL2CPP по ИМЕНИ и перехватить его,
 * без адресов и смещений из дампа. Подключай после bearite_api.h. Один заголовок, работает из C и C++.
 *
 *     #include "bearite_api.h"
 *     #include "bearite_il2cpp.h"
 *
 *     static void (*orig)(void* self, float value, const void* method);
 *     static void my_set_fov(void* self, float value, const void* method) {
 *         orig(self, value / 2.0f, method);      // вызов оригинала
 *     }
 *
 *     void bearite_mod_init(const BeariteApi* api, const char* mod_id) {
 *         bearite_il2cpp_init(api);
 *         bearite_hook_method(api, mod_id, "UnityEngine", "Camera", "set_fieldOfView", 1,
 *                             (void*)my_set_fov, (void**)&orig);
 *     }
 *
 * Сигнатура перехватчика: для метода экземпляра первым идёт `self`, дальше аргументы метода,
 * последним всегда указатель на MethodInfo (его передаёт IL2CPP). Для статического метода `self` нет.
 * Типы: int/bool/float передаются как есть, объекты Unity и строки как void*.
 */
#pragma once

#include <stddef.h>
#include <stdio.h>

#include "bearite_api.h"

#ifdef __cplusplus
extern "C" {
#define BEARITE_CAST(T, p) reinterpret_cast<T>(p)
#else
#define BEARITE_CAST(T, p) ((T)(p))
#endif

typedef struct BeariteIl2cpp {
    void* (*domain_get)(void);
    void** (*domain_get_assemblies)(void* domain, size_t* count);
    void* (*assembly_get_image)(void* assembly);
    void* (*class_from_name)(void* image, const char* ns, const char* name);
    void* (*class_get_method_from_name)(void* klass, const char* name, int argc);
    void* (*runtime_invoke)(void* method, void* obj, void** params, void** exc);
    void* (*string_new)(const char* utf8);
} BeariteIl2cpp;

static BeariteIl2cpp bearite_il2 __attribute__((unused));

/* Находит функции IL2CPP. Вызови один раз в начале bearite_mod_init. 1 = всё нашлось. */
static inline int bearite_il2cpp_init(const BeariteApi* api) {
    bearite_il2.domain_get = BEARITE_CAST(void* (*)(void), api->il2cpp_symbol("il2cpp_domain_get"));
    bearite_il2.domain_get_assemblies = BEARITE_CAST(void** (*)(void*, size_t*), api->il2cpp_symbol("il2cpp_domain_get_assemblies"));
    bearite_il2.assembly_get_image = BEARITE_CAST(void* (*)(void*), api->il2cpp_symbol("il2cpp_assembly_get_image"));
    bearite_il2.class_from_name = BEARITE_CAST(void* (*)(void*, const char*, const char*), api->il2cpp_symbol("il2cpp_class_from_name"));
    bearite_il2.class_get_method_from_name = BEARITE_CAST(void* (*)(void*, const char*, int), api->il2cpp_symbol("il2cpp_class_get_method_from_name"));
    bearite_il2.runtime_invoke = BEARITE_CAST(void* (*)(void*, void*, void**, void**), api->il2cpp_symbol("il2cpp_runtime_invoke"));
    bearite_il2.string_new = BEARITE_CAST(void* (*)(const char*), api->il2cpp_symbol("il2cpp_string_new"));
    return bearite_il2.domain_get && bearite_il2.domain_get_assemblies && bearite_il2.assembly_get_image &&
           bearite_il2.class_from_name && bearite_il2.class_get_method_from_name && bearite_il2.runtime_invoke &&
           bearite_il2.string_new;
}

/* Класс по пространству имён и имени (например "UnityEngine", "Camera") или NULL. */
static inline void* bearite_find_class(const char* ns, const char* name) {
    if (!bearite_il2.domain_get) return NULL;
    size_t n = 0;
    void** asms = bearite_il2.domain_get_assemblies(bearite_il2.domain_get(), &n);
    if (!asms) return NULL;
    for (size_t i = 0; i < n; ++i) {
        void* img = bearite_il2.assembly_get_image(asms[i]);
        if (!img) continue;
        void* k = bearite_il2.class_from_name(img, ns, name);
        if (k) return k;
    }
    return NULL;
}

/* MethodInfo* метода по классу, имени и числу аргументов, или NULL. */
static inline void* bearite_find_method(const char* ns, const char* cls, const char* method, int argc) {
    void* k = bearite_find_class(ns, cls);
    return k ? bearite_il2.class_get_method_from_name(k, method, argc) : NULL;
}

/* Адрес кода метода: первое поле MethodInfo. */
static inline void* bearite_method_code(void* method_info) {
    return method_info ? *(void**)method_info : NULL;
}

/* Вызов управляемого метода. obj = NULL для статического. Вернёт NULL при исключении. */
static inline void* bearite_invoke(void* method_info, void* obj, void** args) {
    void* exc = NULL;
    void* r = bearite_il2.runtime_invoke(method_info, obj, args, &exc);
    return exc ? NULL : r;
}

/*
 * Перехватывает метод по имени и пишет результат в лог Bearite.
 * Возвращает 0 при успехе. В *original попадает указатель на оригинал.
 */
static inline int bearite_hook_method(const BeariteApi* api, const char* mod_id, const char* ns,
                                      const char* cls, const char* method, int argc,
                                      void* replacement, void** original) {
    char msg[220];
    void* mi = bearite_find_method(ns, cls, method, argc);
    if (!mi) {
        snprintf(msg, sizeof msg, "не найден метод %s.%s::%s (аргументов: %d)", ns, cls, method, argc);
        api->log(mod_id, msg);
        return -1;
    }
    void* code = bearite_method_code(mi);
    if (!code) {
        snprintf(msg, sizeof msg, "у метода %s.%s::%s нет кода для перехвата", ns, cls, method);
        api->log(mod_id, msg);
        return -2;
    }
    int rc = api->hook(code, replacement, original);
    snprintf(msg, sizeof msg, "перехват %s.%s::%s: %s", ns, cls, method, rc == 0 ? "готово" : "не удался");
    api->log(mod_id, msg);
    return rc;
}

#ifdef __cplusplus
}
#endif
