// Bearite native loader.
//
// Грузится в процесс игры (в тот же ClassLoader/namespace, что и libil2cpp.so),
// ждёт запуска IL2CPP, затем в собственном потоке (il2cpp_thread_attach) загружает
// AssetBundle'ы включённых модов и нативные библиотеки модов (SDK: sdk/bearite_api.h).
// Код игры при этом не патчится: хуки ставят только сами моды через api->hook.
//
// Все шаги пишутся в logcat (тег Bearite) и в файл bearite.log рядом с конфигом.
//
// Формат конфига (строки, поля через TAB):
//   B <путь бандла>
//   M <id мода> <путь к settings.txt>
//   L <id мода> <путь к .so>

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <link.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "bearite_api.h"
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
    X(il2cpp_array_length, size_t, (void*))                                   \
    X(il2cpp_thread_attach, void*, (void*))                                   \
    X(il2cpp_thread_detach, void, (void*))

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

// ------------------------------------------------------------- вызовы IL2CPP

// Вызов управляемого метода; исключение логируем и возвращаем nullptr
static void* invoke(void* method, void* obj, void** params) {
    void* exc = nullptr;
    void* r = p_il2cpp_runtime_invoke(method, obj, params, &exc);
    if (exc) {
        void* k = p_il2cpp_object_get_class(exc);
        blog("управляемое исключение: %s", k ? p_il2cpp_class_get_name(k) : "?");
        return nullptr;
    }
    return r;
}

// Подходит и к новой (dobby_dummy_func_t), и к старой (void*) сигнатуре DobbyHook
template <typename A, typename Fn, typename On>
static int call_dobby(int (*f)(A, Fn, On), void* target, void* repl, void* orig_out) {
    return f(target, reinterpret_cast<Fn>(repl), reinterpret_cast<On>(orig_out));
}

// ------------------------------------------------------------------ конфиг

struct Config {
    std::vector<std::string> bundles;
    std::vector<std::pair<std::string, std::string>> libs;  // id, путь .so
    std::map<std::string, std::string> settingsPaths;        // id -> settings.txt
};

static std::vector<std::string> split_tab(const std::string& s) {
    std::vector<std::string> out;
    size_t start = 0;
    while (true) {
        size_t p = s.find('\t', start);
        if (p == std::string::npos) {
            out.push_back(s.substr(start));
            break;
        }
        out.push_back(s.substr(start, p - start));
        start = p + 1;
    }
    return out;
}

static Config read_config() {
    Config cfg;
    std::ifstream in(g_cfgPath);
    std::string line;
    while (std::getline(in, line)) {
        while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
        if (line.empty()) continue;
        std::vector<std::string> f = split_tab(line);
        if (f[0] == "B" && f.size() >= 2) {
            cfg.bundles.push_back(f[1]);
        } else if (f[0] == "M" && f.size() >= 3) {
            cfg.settingsPaths[f[1]] = f[2];
        } else if (f[0] == "L" && f.size() >= 3) {
            cfg.libs.emplace_back(f[1], f[2]);
        } else if (f.size() == 1) {
            cfg.bundles.push_back(f[0]);  // старый формат: просто путь бандла
        } else {
            blog("конфиг: не понял строку '%s'", line.c_str());
        }
    }
    return cfg;
}

// --------------------------------------------------------- настройки модов

struct SettingsFile {
    long long stamp = -1;
    std::map<std::string, std::string> kv;
};
static std::mutex g_setMu;
static std::map<std::string, std::string> g_setPath;  // id -> путь
static std::map<std::string, SettingsFile> g_setCache;

static bool setting_get(const char* mod, const char* key, std::string& out) {
    if (!mod || !key) return false;
    std::lock_guard<std::mutex> lk(g_setMu);
    auto pit = g_setPath.find(mod);
    if (pit == g_setPath.end()) return false;
    struct stat st;
    if (stat(pit->second.c_str(), &st) != 0) return false;
    SettingsFile& sf = g_setCache[mod];
    long long stamp = (long long)st.st_mtim.tv_sec * 1000000000LL + st.st_mtim.tv_nsec + st.st_size;
    if (stamp != sf.stamp) {  // файл изменился - перечитываем
        sf.stamp = stamp;
        sf.kv.clear();
        std::ifstream in(pit->second);
        std::string line;
        while (std::getline(in, line)) {
            while (!line.empty() && line.back() == '\r') line.pop_back();
            size_t eq = line.find('=');
            if (eq == std::string::npos) continue;
            sf.kv[line.substr(0, eq)] = line.substr(eq + 1);
        }
    }
    auto it = sf.kv.find(key);
    if (it == sf.kv.end()) return false;
    out = it->second;
    return true;
}

// ----------------------------------------------------------------- API модов

static void api_log(const char* mod, const char* msg) {
    blog("[мод %s] %s", mod ? mod : "?", msg ? msg : "");
}

static void* api_il2cpp_symbol(const char* name) { return name ? find_symbol(name) : nullptr; }

struct BaseQuery {
    const char* part;
    void* base;
};

static int phdr_cb(struct dl_phdr_info* info, size_t, void* data) {
    auto* q = static_cast<BaseQuery*>(data);
    if (info->dlpi_name && strstr(info->dlpi_name, q->part)) {
        q->base = reinterpret_cast<void*>(info->dlpi_addr);
        return 1;
    }
    return 0;
}

static void* api_module_base(const char* part) {
    if (!part || !*part) return nullptr;
    BaseQuery q{part, nullptr};
    dl_iterate_phdr(phdr_cb, &q);
    return q.base;
}

static int api_hook(void* target, void* replacement, void** original) {
    if (!target || !replacement) return -1;
    return call_dobby(DobbyHook, target, replacement, reinterpret_cast<void*>(original));
}

static int api_get_bool(const char* mod, const char* key, int def) {
    std::string v;
    if (!setting_get(mod, key, v)) return def;
    return (v == "true" || v == "1") ? 1 : 0;
}

static long long api_get_int(const char* mod, const char* key, long long def) {
    std::string v;
    if (!setting_get(mod, key, v)) return def;
    char* end = nullptr;
    long long r = strtoll(v.c_str(), &end, 10);
    return (end && end != v.c_str()) ? r : def;
}

static double api_get_float(const char* mod, const char* key, double def) {
    std::string v;
    if (!setting_get(mod, key, v)) return def;
    char* end = nullptr;
    double r = strtod(v.c_str(), &end);
    return (end && end != v.c_str()) ? r : def;
}

static const char* api_get_string(const char* mod, const char* key, const char* def) {
    thread_local std::string buf;
    std::string v;
    if (!setting_get(mod, key, v)) return def;
    buf = v;
    return buf.c_str();
}

static const BeariteApi g_api = {
    BEARITE_API_VERSION, api_log,      api_il2cpp_symbol, api_module_base, api_hook,
    api_get_bool,        api_get_int,  api_get_float,     api_get_string,
};

static void load_native_mods(const Config& cfg) {
    for (const auto& m : cfg.libs) {
        const std::string& id = m.first;
        const std::string& path = m.second;
        void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!h) {
            const char* e = dlerror();
            blog("мод %s: dlopen не удался: %s", id.c_str(), e ? e : "?");
            continue;
        }
        auto init = reinterpret_cast<BeariteModInitFn>(dlsym(h, BEARITE_MOD_INIT_SYMBOL));
        if (!init) {
            blog("мод %s: нет функции %s", id.c_str(), BEARITE_MOD_INIT_SYMBOL);
            continue;
        }
        blog("мод %s: запускаю %s", id.c_str(), BEARITE_MOD_INIT_SYMBOL);
        init(&g_api, id.c_str());
        blog("мод %s: готов", id.c_str());
    }
}

static void load_bundles(const std::vector<std::string>& bundles) {
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
}

static void run_mods() {
    blog("загрузка модов в потоке tid=%d", (int)gettid());
    Config cfg = read_config();
    {
        std::lock_guard<std::mutex> lk(g_setMu);
        g_setPath = cfg.settingsPaths;
    }
    blog("в конфиге: бандлов %zu, нативных библиотек %zu", cfg.bundles.size(), cfg.libs.size());
    if (!cfg.bundles.empty()) load_bundles(cfg.bundles);
    if (!cfg.libs.empty()) load_native_mods(cfg);
    blog("загрузка модов завершена");
}

// Сколько секунд после появления libil2cpp не трогаем IL2CPP: движок инициализируется
static const int kWarmupSec = 10;

static void waiter() {
    blog("жду загрузки libil2cpp...");
    for (int i = 0; i < 600 && !find_symbol("il2cpp_domain_get"); ++i) usleep(200 * 1000);
    if (!find_symbol("il2cpp_domain_get")) {
        blog("IL2CPP не появился за 120 с, выхожу");
        return;
    }
    blog("libil2cpp загружена, ждём %d с, пока игра инициализируется", kWarmupSec);
    sleep(kWarmupSec);
    if (!resolve_api()) {
        blog("не все функции IL2CPP найдены, выхожу");
        return;
    }
    void* domain = nullptr;
    for (int i = 0; i < 300 && !(domain = p_il2cpp_domain_get()); ++i) usleep(200 * 1000);
    if (!domain) {
        blog("домен IL2CPP не поднялся, выхожу");
        return;
    }
    blog("домен IL2CPP готов, подключаю свой поток");
    void* thread = p_il2cpp_thread_attach(domain);
    if (!thread) {
        blog("il2cpp_thread_attach вернул null, выхожу");
        return;
    }
    // ждём, пока загрузятся сборки Unity (до 60 с)
    for (int i = 0; i < 120 && !find_class("UnityEngine", "Object"); ++i) usleep(500 * 1000);
    run_mods();
    p_il2cpp_thread_detach(thread);  // поток завершается: обязательно отсоединить
    blog("поток модов завершён");
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
