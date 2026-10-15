// Bearite native loader.
//
// Грузится в процесс игры (в тот же ClassLoader/namespace, что и libil2cpp.so),
// ждёт запуска IL2CPP, ставит inline-хук на il2cpp_runtime_invoke и из него,
// то есть уже на потоке Unity, загружает AssetBundle'ы включённых модов.
//
// Все шаги пишутся в logcat (тег Bearite) и в файл bearite.log рядом с конфигом.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <sys/prctl.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "dobby.h"

#define TAG "Bearite"

static std::string g_cfgPath;
static std::string g_logPath;
static std::mutex g_logMu;

static void blog(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", buf);
    if (g_logPath.empty()) return;
    std::lock_guard<std::mutex> lk(g_logMu);
    FILE* f = fopen(g_logPath.c_str(), "a");
    if (!f) return;
    time_t t = time(nullptr);
    struct tm tmv;
    localtime_r(&t, &tmv);
    char ts[16];
    strftime(ts, sizeof ts, "%H:%M:%S", &tmv);
    fprintf(f, "%s [native] %s\n", ts, buf);
    fclose(f);
}

// ---------------------------------------------------------------- IL2CPP API

#define IL2CPP_API(X)                                                         \
    X(il2cpp_domain_get, void*, (void))                                       \
    X(il2cpp_domain_get_assemblies, void**, (void*, size_t*))                 \
    X(il2cpp_assembly_get_image, void*, (void*))                              \
    X(il2cpp_class_from_name, void*, (void*, const char*, const char*))       \
    X(il2cpp_class_get_method_from_name, void*, (void*, const char*, int))    \
    X(il2cpp_class_get_name, const char*, (void*))                            \
    X(il2cpp_object_get_class, void*, (void*))                                \
    X(il2cpp_string_new, void*, (const char*))                                \
    X(il2cpp_runtime_invoke, void*, (void*, void*, void**, void**))           \
    X(il2cpp_gchandle_new, uint32_t, (void*, int))                            \
    X(il2cpp_array_length, size_t, (void*))

#define IL2CPP_DECL(name, ret, args) static ret(*p_##name) args = nullptr;
IL2CPP_API(IL2CPP_DECL)
#undef IL2CPP_DECL

// Libs грузятся через ClassLoader игры; мы в том же namespace, поэтому
// dlopen(RTLD_NOLOAD) находит уже загруженные библиотеки.
static void* find_symbol(const char* name) {
    static const char* libs[] = {"libil2cpp.so", "libunity.so", "libmain.so"};
    for (const char* l : libs) {
        void* h = dlopen(l, RTLD_NOW | RTLD_NOLOAD);
        if (!h) continue;
        void* s = dlsym(h, name);
        dlclose(h);
        if (s) return s;
    }
    return dlsym(RTLD_DEFAULT, name);
}

static bool resolve_api() {
    bool ok = true;
#define IL2CPP_RESOLVE(name, ret, args)                              \
    p_##name = reinterpret_cast<decltype(p_##name)>(find_symbol(#name)); \
    if (!p_##name) {                                                 \
        blog("нет символа %s", #name);                               \
        ok = false;                                                  \
    }
    IL2CPP_API(IL2CPP_RESOLVE)
#undef IL2CPP_RESOLVE
    return ok;
}

static void* find_class(const char* ns, const char* name) {
    size_t n = 0;
    void** asms = p_il2cpp_domain_get_assemblies(p_il2cpp_domain_get(), &n);
    if (!asms) return nullptr;
    for (size_t i = 0; i < n; ++i) {
        void* img = p_il2cpp_assembly_get_image(asms[i]);
        if (!img) continue;
        void* k = p_il2cpp_class_from_name(img, ns, name);
        if (k) return k;
    }
    return nullptr;
}

// ------------------------------------------------------------------- хук

typedef void* (*invoke_fn)(void*, void*, void**, void**);
static invoke_fn g_orig_invoke = nullptr;

// Вызов управляемого метода в обход нашего хука
static void* invoke(void* method, void* obj, void** params) {
    void* exc = nullptr;
    void* r = g_orig_invoke(method, obj, params, &exc);
    if (exc) {
        void* k = p_il2cpp_object_get_class(exc);
        blog("управляемое исключение: %s", k ? p_il2cpp_class_get_name(k) : "?");
        return nullptr;
    }
    return r;
}

static std::vector<std::string> read_bundle_list() {
    std::vector<std::string> out;
    std::ifstream in(g_cfgPath);
    std::string line;
    while (std::getline(in, line)) {
        while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
        if (!line.empty()) out.push_back(line);
    }
    return out;
}

static void run_mods() {
    blog("загрузка модов на потоке tid=%d", (int)gettid());
    std::vector<std::string> bundles = read_bundle_list();
    blog("бандлов в конфиге: %zu", bundles.size());
    if (bundles.empty()) return;

    void* abClass = find_class("UnityEngine", "AssetBundle");
    if (!abClass) {
        blog("класс UnityEngine.AssetBundle не найден (нет модуля AssetBundle в игре?)");
        return;
    }
    void* loadFromFile = p_il2cpp_class_get_method_from_name(abClass, "LoadFromFile", 1);
    void* loadAll = p_il2cpp_class_get_method_from_name(abClass, "LoadAllAssets", 0);
    if (!loadFromFile) {
        blog("метод AssetBundle.LoadFromFile(string) не найден");
        return;
    }
    for (const std::string& path : bundles) {
        if (access(path.c_str(), R_OK) != 0) {
            blog("нет файла: %s", path.c_str());
            continue;
        }
        void* params[1] = {p_il2cpp_string_new(path.c_str())};
        void* bundle = invoke(loadFromFile, nullptr, params);
        if (!bundle) {
            blog("бандл не загрузился (версия Unity/платформа не те?): %s", path.c_str());
            continue;
        }
        p_il2cpp_gchandle_new(bundle, 0);  // держим бандл живым
        size_t count = 0;
        if (loadAll) {
            void* arr = invoke(loadAll, bundle, nullptr);
            if (arr) count = p_il2cpp_array_length(arr);
        }
        blog("бандл загружен: %s, ассетов: %zu", path.c_str(), count);
    }
    blog("загрузка модов завершена");
}

static std::atomic<uint64_t> g_calls{0};
static std::atomic<int> g_state{0};  // 0 ждём, 1 выполняем, 2 готово
static std::chrono::steady_clock::time_point g_hookTime;

// Загружать бандлы безопаснее на главном потоке Unity, а не на случайном рабочем.
static bool on_unity_main_thread() {
    char name[17] = {0};
    prctl(PR_GET_NAME, name, 0, 0, 0);
    return strcmp(name, "UnityMain") == 0;
}

static void* hook_invoke(void* method, void* obj, void** params, void** exc) {
    if (g_state.load(std::memory_order_relaxed) == 0) {
        uint64_t n = g_calls.fetch_add(1) + 1;
        // даём движку прогреться: достаточно вызовов и прошло несколько секунд;
        // если поток UnityMain так и не встретился за 40 с, берём любой
        auto since = std::chrono::steady_clock::now() - g_hookTime;
        if (n > 1500 && since > std::chrono::seconds(4) &&
            (on_unity_main_thread() || since > std::chrono::seconds(40))) {
            int expected = 0;
            if (g_state.compare_exchange_strong(expected, 1)) {
                run_mods();
                g_state.store(2);
            }
        }
    }
    return g_orig_invoke(method, obj, params, exc);
}

// Подходит и к новой (dobby_dummy_func_t), и к старой (void*) сигнатуре DobbyHook
template <typename A, typename Fn, typename On>
static int call_dobby(int (*f)(A, Fn, On), void* target, void* repl, void* orig_out) {
    return f(target, reinterpret_cast<Fn>(repl), reinterpret_cast<On>(orig_out));
}

static void waiter() {
    blog("жду запуска IL2CPP...");
    for (int i = 0; i < 600 && !find_symbol("il2cpp_domain_get"); ++i) usleep(200 * 1000);
    if (!find_symbol("il2cpp_domain_get")) {
        blog("IL2CPP не появился за 120 с, выхожу");
        return;
    }
    if (!resolve_api()) {
        blog("не все функции IL2CPP найдены, выхожу");
        return;
    }
    blog("IL2CPP найден, жду инициализации домена");
    for (int i = 0; i < 600 && !p_il2cpp_domain_get(); ++i) usleep(200 * 1000);
    if (!p_il2cpp_domain_get()) {
        blog("домен IL2CPP не поднялся, выхожу");
        return;
    }
    void* target = reinterpret_cast<void*>(p_il2cpp_runtime_invoke);
    g_hookTime = std::chrono::steady_clock::now();
    int rc = call_dobby(DobbyHook, target, reinterpret_cast<void*>(hook_invoke),
                        reinterpret_cast<void*>(&g_orig_invoke));
    if (rc != 0 || !g_orig_invoke) {
        blog("DobbyHook не сработал, код %d", rc);
        return;
    }
    blog("хук il2cpp_runtime_invoke установлен");
}

extern "C" JNIEXPORT void JNICALL Java_com_bearite_patcher_Native_init(JNIEnv* env, jclass,
                                                                       jstring cfg) {
    const char* c = env->GetStringUTFChars(cfg, nullptr);
    g_cfgPath = c ? c : "";
    if (c) env->ReleaseStringUTFChars(cfg, c);
    size_t slash = g_cfgPath.find_last_of('/');
    g_logPath = (slash == std::string::npos ? std::string(".") : g_cfgPath.substr(0, slash)) +
                "/bearite.log";
    blog("libbearite загружена, конфиг: %s", g_cfgPath.c_str());
    static std::once_flag once;
    std::call_once(once, [] { std::thread(waiter).detach(); });
}
