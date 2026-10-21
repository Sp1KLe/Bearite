/*
 * Встроенный мод Bearite (libbearite_mod.so). Собирается вместе с лаунчером и грузится в игру как обычный
 * нативный мод, подтверждать его не нужно.
 *
 * Делает движок карт: читает файлы <filesDir>/maps/<id>.map, которые правятся во вкладке «Карты» меню Bearite,
 * и строит по ним объекты в текущей сцене (примитивы Unity с цветом и коллайдером). Изменения файла
 * применяются на лету. Состояние движка пишется в maps/_state.txt (сцена, позиция камеры, ошибки),
 * его читает редактор.
 *
 * Все вызовы Unity должны идти из главного потока, поэтому движок «тикает» из хуков на Time.get_*,
 * которые игра вызывает каждый кадр. Camera.get_main не трогаем: его перехватывают другие моды (например зум),
 * а Dobby не умеет вешать два хука на одну функцию.
 */
#include <dirent.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "bearite_api.h"
#include "bearite_il2cpp.h"

namespace {

const BeariteApi* A = nullptr;
const char* ID = "bearite";

// ------------------------------------------------------------------ IL2CPP

uint32_t (*gch_new)(void*, int);
void* (*gch_target)(uint32_t);
void (*gch_free)(uint32_t);
void* (*obj_unbox)(void*);
void* (*obj_class)(void*);
const char* (*class_name)(void*);
const uint16_t* (*str_chars)(void*);
int (*str_len)(void*);
void* (*class_type)(void*);
void* (*type_object)(void*);
void* (*class_methods)(void*, void**);
const char* (*method_name)(void*);
uint32_t (*method_pcount)(void*);
void* (*method_param)(void*, uint32_t);
void* (*class_from_type)(void*);

void logf_(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void logf_(const char* fmt, ...) {
    char buf[400];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    A->log(ID, buf);
}

int g_exc_logged = 0;
bool g_in_sync = false;       // идёт построение объектов: каждый вызов пишем в _guard
std::string g_guardPath;      // maps/_guard
std::string g_ctx;            // "карта/объект" для хлебных крошек

/** Пишет, что именно вызываем сейчас. Если игра упадёт, файл покажет последний шаг. */
void breadcrumb(void* m, void* obj) {
    if (!g_in_sync || g_guardPath.empty() || !m) return;
    FILE* f = fopen(g_guardPath.c_str(), "w");
    if (!f) return;
    void* k = obj ? obj_class(obj) : nullptr;
    fprintf(f, "%s::%s | %s", k ? class_name(k) : "static", method_name(m), g_ctx.c_str());
    fclose(f);
}

/** Вызов управляемого метода. ok=false при исключении (первые несколько пишем в лог). */
void* inv(void* m, void* obj, void** args = nullptr, bool* ok = nullptr) {
    void* exc = nullptr;
    void* r = nullptr;
    breadcrumb(m, obj);
    if (m) r = bearite_il2.runtime_invoke(m, obj, args, &exc);
    if (ok) *ok = m != nullptr && exc == nullptr;
    if (exc && g_exc_logged < 12) {
        ++g_exc_logged;
        void* k = obj_class(exc);
        logf_("исключение в управляемом методе: %s", k ? class_name(k) : "?");
    }
    return exc ? nullptr : r;
}

void* find_method_p0(void* klass, const char* name, int argc, const char* p0) {
    if (!klass) return nullptr;
    void* it = nullptr;
    while (void* m = class_methods(klass, &it)) {
        if (strcmp(method_name(m), name) != 0 || (int)method_pcount(m) != argc) continue;
        if (!p0) return m;
        void* k = class_from_type(method_param(m, 0));
        if (k && strcmp(class_name(k), p0) == 0) return m;
    }
    return nullptr;
}

void dump_class(const char* ns, const char* name) {
    void* k = bearite_find_class(ns, name);
    if (!k) {
        logf_("класса %s.%s нет в игре", ns, name);
        return;
    }
    std::string s;
    void* it = nullptr;
    int n = 0;
    while (void* m = class_methods(k, &it)) {
        if (n++ > 60) break;
        s += method_name(m);
        s += '/';
        s += std::to_string(method_pcount(m));
        s += ' ';
    }
    logf_("методы %s.%s: %s", ns, name, s.c_str());
}

std::string utf8(void* il2str) {
    std::string out;
    if (!il2str) return out;
    const uint16_t* c = str_chars(il2str);
    int n = str_len(il2str);
    for (int i = 0; i < n; ++i) {
        uint32_t u = c[i];
        if (u >= 0xD800 && u < 0xDC00 && i + 1 < n) u = 0x10000 + ((u - 0xD800) << 10) + (c[++i] - 0xDC00);
        if (u < 0x80) out += (char)u;
        else if (u < 0x800) { out += (char)(0xC0 | (u >> 6)); out += (char)(0x80 | (u & 63)); }
        else if (u < 0x10000) { out += (char)(0xE0 | (u >> 12)); out += (char)(0x80 | ((u >> 6) & 63)); out += (char)(0x80 | (u & 63)); }
        else { out += (char)(0xF0 | (u >> 18)); out += (char)(0x80 | ((u >> 12) & 63)); out += (char)(0x80 | ((u >> 6) & 63)); out += (char)(0x80 | (u & 63)); }
    }
    return out;
}

// Методы Unity
struct Fns {
    void *create_primitive, *get_transform, *go_transform, *set_position, *set_scale, *set_euler, *get_position, *get_forward;
    void *cam_main, *set_name, *destroy, *get_active, *set_layer, *get_component, *get_material, *set_color,
        *collider_enabled, *get_scene, *scene_name;
    void *type_renderer, *type_collider;
} F{};

struct V3 { float x, y, z; };
struct Col { float r, g, b, a; };

// ------------------------------------------------------------------ карты

struct Obj {
    std::string name, shape;
    float p[3] = {0, 0, 0}, s[3] = {1, 1, 1}, r[3] = {0, 0, 0};
    unsigned color = 0x8D5338;
    bool solid = true;
};

struct MapSpec {
    std::string id, name, scene;
    bool enabled = true;
    float off[3] = {0, 0, 0};
    float scale = 1;
    int layer = 0;
    std::vector<Obj> objs;
};

/** То, что реально применено к объекту в сцене. */
struct Live {
    uint32_t h = 0;  // GC-handle на GameObject, 0 = объекта нет
    std::string shape;
    bool solid = true;
    int layer = 0;
    std::string name;
    float pos[3] = {1e9f, 0, 0}, size[3] = {0, 0, 0}, rot[3] = {1e9f, 0, 0};
    unsigned color = 0xFFFFFFFF;
};

std::string g_mapsDir;
std::vector<MapSpec> g_specs;
std::map<std::string, std::vector<Live>> g_live;
std::string g_dirStamp = "?";
std::string g_scene;
bool g_dirty = true;
std::string g_status = "unknown";
std::string g_msg;
int g_liveCount = 0;
bool g_engineOk = false;

float num(const std::string& s, float d) {
    char* e = nullptr;
    double v = strtod(s.c_str(), &e);
    return e && e != s.c_str() ? (float)v : d;
}

std::vector<std::string> split_tab(const std::string& s) {
    std::vector<std::string> out;
    size_t st = 0;
    while (true) {
        size_t p = s.find('\t', st);
        if (p == std::string::npos) { out.push_back(s.substr(st)); break; }
        out.push_back(s.substr(st, p - st));
        st = p + 1;
    }
    return out;
}

bool parse_map(const std::string& id, const std::string& path, MapSpec& m) {
    FILE* f = fopen(path.c_str(), "r");
    if (!f) return false;
    m.id = id;
    m.name = id;
    char line[1024];
    while (fgets(line, sizeof line, f)) {
        std::string l = line;
        while (!l.empty() && (l.back() == '\n' || l.back() == '\r')) l.pop_back();
        if (l.empty()) continue;
        if (l.compare(0, 2, "O\t") == 0) {
            auto p = split_tab(l);
            if (p.size() < 14) continue;
            Obj o;
            o.name = p[1];
            o.shape = p[2];
            for (int i = 0; i < 3; ++i) { o.p[i] = num(p[3 + i], 0); o.s[i] = num(p[6 + i], 1); o.r[i] = num(p[9 + i], 0); }
            o.color = (unsigned)strtoul(p[12].c_str(), nullptr, 16) & 0xFFFFFF;
            o.solid = p[13] == "1";
            m.objs.push_back(o);
            continue;
        }
        size_t eq = l.find('=');
        if (eq == std::string::npos) continue;
        std::string k = l.substr(0, eq), v = l.substr(eq + 1);
        if (k == "name") m.name = v;
        else if (k == "enabled") m.enabled = v == "1" || v == "true";
        else if (k == "scene") m.scene = v;
        else if (k == "ox") m.off[0] = num(v, 0);
        else if (k == "oy") m.off[1] = num(v, 0);
        else if (k == "oz") m.off[2] = num(v, 0);
        else if (k == "scale") m.scale = num(v, 1);
        else if (k == "layer") m.layer = atoi(v.c_str());
    }
    fclose(f);
    if (m.scale < 0.01f) m.scale = 0.01f;
    return true;
}

/** Перечитывает папку карт, если файлы изменились. */
void reload_maps() {
    DIR* d = opendir(g_mapsDir.c_str());
    if (!d) return;
    std::vector<std::string> names;
    std::string stamp;
    while (dirent* e = readdir(d)) {
        size_t n = strlen(e->d_name);
        if (n < 5 || strcmp(e->d_name + n - 4, ".map") != 0 || e->d_name[0] == '.') continue;
        struct stat st;
        std::string path = g_mapsDir + "/" + e->d_name;
        if (stat(path.c_str(), &st) != 0) continue;
        names.push_back(e->d_name);
        stamp += e->d_name;
        stamp += ':' + std::to_string((long long)st.st_mtim.tv_sec * 1000000000LL + st.st_mtim.tv_nsec) + ':' +
                 std::to_string((long long)st.st_size) + ';';
    }
    closedir(d);
    if (stamp == g_dirStamp) return;
    g_dirStamp = stamp;
    std::vector<MapSpec> specs;
    for (const auto& n : names) {
        MapSpec m;
        if (parse_map(n.substr(0, n.size() - 4), g_mapsDir + "/" + n, m)) specs.push_back(std::move(m));
    }
    g_specs = std::move(specs);
    g_dirty = true;
}

// ------------------------------------------------------------- объекты в сцене

void* target(uint32_t h) { return h ? gch_target(h) : nullptr; }

void destroy_live(Live& L) {
    if (!L.h) return;
    void* go = target(L.h);
    if (go && F.destroy) {
        void* a[1] = {go};
        inv(F.destroy, nullptr, a);
    }
    gch_free(L.h);
    L = Live();
    if (g_liveCount > 0) --g_liveCount;
}

void drop_live(Live& L) {  // объект уже уничтожен Unity (смена сцены): только освобождаем handle
    if (L.h) { gch_free(L.h); if (g_liveCount > 0) --g_liveCount; }
    L = Live();
}

int primitive_id(const std::string& s) {
    // UnityEngine.PrimitiveType: Sphere=0, Capsule=1, Cylinder=2, Cube=3, Plane=4, Quad=5
    if (s == "sphere") return 0;
    if (s == "capsule") return 1;
    if (s == "cylinder") return 2;
    if (s == "plane") return 4;
    return 3;
}

bool create_live(Live& L, const Obj& o, const MapSpec& m, const std::string& fullName) {
    int t = primitive_id(o.shape);
    void* a[1] = {&t};
    bool ok = false;
    void* go = inv(F.create_primitive, nullptr, a, &ok);
    if (!go) return false;
    L.h = gch_new(go, 0);
    ++g_liveCount;
    L.shape = o.shape;
    L.solid = o.solid;
    L.layer = m.layer;
    L.name = "";
    L.pos[0] = 1e9f; L.rot[0] = 1e9f; L.size[0] = 0;
    L.color = 0xFFFFFFFF;
    if (F.set_layer) { int ly = m.layer; void* b[1] = {&ly}; inv(F.set_layer, go, b); }
    if (!o.solid && F.type_collider && F.get_component && F.collider_enabled) {
        void* b[1] = {F.type_collider};
        void* col = inv(F.get_component, go, b);
        if (col) { uint8_t off = 0; void* c[1] = {&off}; inv(F.collider_enabled, col, c); }
    }
    (void)fullName;
    return true;
}

void apply_live(Live& L, const Obj& o, const MapSpec& m, const std::string& fullName) {
    void* go = target(L.h);
    if (!go) return;
    if (L.name != fullName && F.set_name) {
        void* a[1] = {bearite_il2.string_new(fullName.c_str())};
        inv(F.set_name, go, a);
        L.name = fullName;
    }
    void* tr = inv(F.go_transform, go);  // у GameObject свой get_transform, не Component'ский
    if (!tr) return;
    V3 pos{m.off[0] + m.scale * o.p[0], m.off[1] + m.scale * o.p[1], m.off[2] + m.scale * o.p[2]};
    V3 size{m.scale * o.s[0], m.scale * o.s[1], m.scale * o.s[2]};
    V3 rot{o.r[0], o.r[1], o.r[2]};
    if (memcmp(L.pos, &pos, sizeof pos) != 0) { void* a[1] = {&pos}; inv(F.set_position, tr, a); memcpy(L.pos, &pos, sizeof pos); }
    if (memcmp(L.size, &size, sizeof size) != 0) { void* a[1] = {&size}; inv(F.set_scale, tr, a); memcpy(L.size, &size, sizeof size); }
    if (memcmp(L.rot, &rot, sizeof rot) != 0) { void* a[1] = {&rot}; inv(F.set_euler, tr, a); memcpy(L.rot, &rot, sizeof rot); }
    if (L.color != o.color && F.type_renderer && F.get_component && F.get_material && F.set_color) {
        void* b[1] = {F.type_renderer};
        void* rend = inv(F.get_component, go, b);
        void* mat = rend ? inv(F.get_material, rend) : nullptr;
        if (mat) {
            Col c{((o.color >> 16) & 255) / 255.f, ((o.color >> 8) & 255) / 255.f, (o.color & 255) / 255.f, 1.f};
            void* a[1] = {&c};
            inv(F.set_color, mat, a);
            L.color = o.color;
        }
    }
}

/** Приводит объекты в сцене в соответствие с картами. */
void sync_all() {
    g_in_sync = true;
    // карты, которых больше нет в файлах
    for (auto it = g_live.begin(); it != g_live.end();) {
        bool found = false;
        for (const auto& s : g_specs) if (s.id == it->first) { found = true; break; }
        if (!found) {
            for (auto& L : it->second) destroy_live(L);
            it = g_live.erase(it);
        } else ++it;
    }
    for (const auto& m : g_specs) {
        auto& live = g_live[m.id];
        bool want = m.enabled && (m.scene.empty() || m.scene == g_scene) && g_engineOk;
        if (!want) {
            for (auto& L : live) destroy_live(L);
            live.clear();
            continue;
        }
        while (live.size() > m.objs.size()) { destroy_live(live.back()); live.pop_back(); }
        live.resize(m.objs.size());
        for (size_t i = 0; i < m.objs.size(); ++i) {
            const Obj& o = m.objs[i];
            Live& L = live[i];
            if (L.h && (L.shape != o.shape || L.solid != o.solid || L.layer != m.layer)) destroy_live(L);
            g_ctx = m.id + " объект " + std::to_string(i + 1);
            std::string full = "Bearite/" + m.name + "/" + (o.name.empty() ? std::to_string(i + 1) : o.name);
            if (!L.h && !create_live(L, o, m, full)) {
                g_status = "error";
                g_msg = "не удалось создать объект (см. вкладку «Лог»)";
                continue;
            }
            apply_live(L, o, m, full);
        }
    }
}

/** Проверка, что объекты ещё живы (сцена могла смениться и уничтожить их). */
bool check_alive() {
    if (!F.get_active) return true;
    for (auto& kv : g_live)
        for (auto& L : kv.second) {
            if (!L.h) continue;
            void* go = target(L.h);
            bool ok = false;
            if (go && F.get_active) inv(F.get_active, go, nullptr, &ok);
            return ok;
        }
    return true;
}

void drop_all_live() {
    for (auto& kv : g_live) { for (auto& L : kv.second) drop_live(L); kv.second.clear(); }
}

// -------------------------------------------------------- состояние для редактора

void write_state(const std::string& cam) {
    std::string path = g_mapsDir + "/_state.txt";
    std::string tmp = g_mapsDir + "/.state.tmp";
    FILE* f = fopen(tmp.c_str(), "w");
    if (!f) return;
    fprintf(f, "status=%s\nmsg=%s\nscene=%s\nobjects=%d\n", g_status.c_str(), g_msg.c_str(), g_scene.c_str(), g_liveCount);
    if (!cam.empty()) fprintf(f, "cam=%s\n", cam.c_str());
    fclose(f);
    rename(tmp.c_str(), path.c_str());
}

std::string read_scene() {
    if (!F.get_scene || !F.scene_name) return "";
    void* sc = inv(F.get_scene, nullptr);  // boxed Scene: единственное поле - int handle
    if (!sc) return "";
    int handle = *(const int*)obj_unbox(sc);
    void* a[1] = {&handle};
    return utf8(inv(F.scene_name, nullptr, a));  // статический Scene.GetNameInternal(int): без this-указателя
}

std::string read_camera() {
    if (!F.cam_main || !F.get_transform || !F.get_position || !F.get_forward) return "";
    void* cam = inv(F.cam_main, nullptr);
    if (!cam) return "";
    void* tr = inv(F.get_transform, cam);
    if (!tr) return "";
    void* p = inv(F.get_position, tr);
    void* f = inv(F.get_forward, tr);
    if (!p || !f) return "";
    const float* a = (const float*)obj_unbox(p);
    const float* b = (const float*)obj_unbox(f);
    char buf[200];
    snprintf(buf, sizeof buf, "%.3f %.3f %.3f %.3f %.3f %.3f", a[0], a[1], a[2], b[0], b[1], b[2]);
    return buf;
}

// ------------------------------------------------------------------- тик

long long now_ms() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

long long g_lastTick = 0, g_lastSlow = 0;
std::string g_lastCam;

void update() {
    long long now = now_ms();
    reload_maps();
    bool slow = now - g_lastSlow >= 500;
    if (slow) {
        g_lastSlow = now;
        std::string sc = read_scene();
        if (!sc.empty() && sc != g_scene) {
            g_scene = sc;
            drop_all_live();  // Unity сам уничтожил объекты прошлой сцены
            g_dirty = true;
        } else if (!check_alive()) {
            drop_all_live();
            g_dirty = true;
        }
    }
    if (g_dirty && g_engineOk) {
        g_dirty = false;
        if (g_status != "ok") { g_status = "ok"; g_msg.clear(); }
        // защита от цикла падений: файл остаётся, если игра упала внутри sync_all
        std::string guard = g_mapsDir + "/_guard";
        if (FILE* gf = fopen(guard.c_str(), "w")) { fputs("sync", gf); fclose(gf); }
        sync_all();
        g_in_sync = false;
        unlink(guard.c_str());
    }
    if (slow) {
        std::string cam = read_camera();
        write_state(cam);
    }
}

std::atomic<pid_t> g_main{0};
std::mutex g_latchMu;
pid_t g_ids[8];
int g_cnt[8];
int g_nids = 0, g_total = 0;

void latch(pid_t me) {
    std::lock_guard<std::mutex> lk(g_latchMu);
    if (g_main.load() != 0) return;
    int i = 0;
    for (; i < g_nids; ++i) if (g_ids[i] == me) break;
    if (i == g_nids) { if (g_nids >= 8) return; g_ids[g_nids++] = me; g_cnt[i] = 0; }
    ++g_cnt[i];
    if (++g_total >= 300) {
        int best = 0;
        for (int k = 1; k < g_nids; ++k) if (g_cnt[k] > g_cnt[best]) best = k;
        g_main.store(g_ids[best]);
        logf_("главный поток Unity: tid=%d (%d из %d вызовов)", (int)g_ids[best], g_cnt[best], g_total);
    }
}

thread_local bool t_busy = false;

void on_tick() {
    pid_t me = gettid();
    pid_t m = g_main.load(std::memory_order_relaxed);
    if (m == 0) { latch(me); return; }
    if (m != me || t_busy) return;
    long long now = now_ms();
    if (now - g_lastTick < 100) return;
    g_lastTick = now;
    t_busy = true;
    update();
    t_busy = false;
}

float (*o_dt)(const void*);
float (*o_udt)(const void*);
float (*o_time)(const void*);
float (*o_utime)(const void*);
float h_dt(const void* mi) { float r = o_dt(mi); on_tick(); return r; }
float h_udt(const void* mi) { float r = o_udt(mi); on_tick(); return r; }
float h_time(const void* mi) { float r = o_time(mi); on_tick(); return r; }
float h_utime(const void* mi) { float r = o_utime(mi); on_tick(); return r; }

template <typename T>
T sym(const char* n) { return reinterpret_cast<T>(A->il2cpp_symbol(n)); }

bool bind_il2cpp() {
    gch_new = sym<uint32_t (*)(void*, int)>("il2cpp_gchandle_new");
    gch_target = sym<void* (*)(uint32_t)>("il2cpp_gchandle_get_target");
    gch_free = sym<void (*)(uint32_t)>("il2cpp_gchandle_free");
    obj_unbox = sym<void* (*)(void*)>("il2cpp_object_unbox");
    obj_class = sym<void* (*)(void*)>("il2cpp_object_get_class");
    class_name = sym<const char* (*)(void*)>("il2cpp_class_get_name");
    str_chars = sym<const uint16_t* (*)(void*)>("il2cpp_string_chars");
    str_len = sym<int (*)(void*)>("il2cpp_string_length");
    class_type = sym<void* (*)(void*)>("il2cpp_class_get_type");
    type_object = sym<void* (*)(void*)>("il2cpp_type_get_object");
    class_methods = sym<void* (*)(void*, void**)>("il2cpp_class_get_methods");
    method_name = sym<const char* (*)(void*)>("il2cpp_method_get_name");
    method_pcount = sym<uint32_t (*)(void*)>("il2cpp_method_get_param_count");
    method_param = sym<void* (*)(void*, uint32_t)>("il2cpp_method_get_param");
    class_from_type = sym<void* (*)(void*)>("il2cpp_class_from_type");
    return gch_new && gch_target && gch_free && obj_unbox && obj_class && class_name && str_chars && str_len &&
           class_type && type_object && class_methods && method_name && method_pcount && method_param && class_from_type;
}

void* type_of(const char* ns, const char* name) {
    void* k = bearite_find_class(ns, name);
    if (!k) return nullptr;
    void* o = type_object(class_type(k));
    if (o) gch_new(o, 0);  // держим объект Type живым навсегда
    return o;
}

void bind_unity() {
    void* go = bearite_find_class("UnityEngine", "GameObject");
    void* tr = bearite_find_class("UnityEngine", "Transform");
    void* cm = bearite_find_class("UnityEngine", "Component");
    void* ob = bearite_find_class("UnityEngine", "Object");
    void* rd = bearite_find_class("UnityEngine", "Renderer");
    void* mt = bearite_find_class("UnityEngine", "Material");
    void* co = bearite_find_class("UnityEngine", "Collider");
    void* ca = bearite_find_class("UnityEngine", "Camera");
    void* sm = bearite_find_class("UnityEngine.SceneManagement", "SceneManager");
    void* sc = bearite_find_class("UnityEngine.SceneManagement", "Scene");
    auto m0 = [](void* k, const char* n, int argc) { return k ? bearite_il2.class_get_method_from_name(k, n, argc) : nullptr; };
    F.create_primitive = m0(go, "CreatePrimitive", 1);
    F.get_transform = m0(cm, "get_transform", 0);  // для компонентов (камера)
    F.go_transform = m0(go, "get_transform", 0);   // для GameObject
    F.set_position = m0(tr, "set_position", 1);
    F.set_scale = m0(tr, "set_localScale", 1);
    F.set_euler = m0(tr, "set_eulerAngles", 1);
    F.get_position = m0(tr, "get_position", 0);
    F.get_forward = m0(tr, "get_forward", 0);
    F.cam_main = m0(ca, "get_main", 0);
    F.set_name = m0(ob, "set_name", 1);
    F.destroy = m0(ob, "Destroy", 1);
    F.get_active = m0(go, "get_activeSelf", 0);
    F.set_layer = m0(go, "set_layer", 1);
    F.get_component = find_method_p0(go, "GetComponent", 1, "Type");
    F.get_material = m0(rd, "get_material", 0);
    F.set_color = m0(mt, "set_color", 1);
    F.collider_enabled = m0(co, "set_enabled", 1);
    F.get_scene = m0(sm, "GetActiveScene", 0);
    F.scene_name = m0(sc, "GetNameInternal", 1);
    F.type_renderer = type_of("UnityEngine", "Renderer");
    F.type_collider = type_of("UnityEngine", "Collider");
}

int hook_time(const char* name, void* repl, void** orig) {
    return bearite_hook_method(A, ID, "UnityEngine", "Time", name, 0, repl, orig);
}

}  // namespace

extern "C" __attribute__((visibility("default"))) void bearite_mod_init(const BeariteApi* api, const char* mod_id) {
    A = api;
    ID = mod_id;
    if (api->version != BEARITE_API_VERSION || !api->data_dir) {
        api->log(mod_id, "несовместимая версия API лаунчера");
        return;
    }
    char msg[200];
    snprintf(msg, sizeof msg, "встроенный мод Bearite запущен, libil2cpp: база=%p", api->module_base("libil2cpp.so"));
    api->log(mod_id, msg);

    g_mapsDir = std::string(api->data_dir()) + "/maps";
    g_guardPath = g_mapsDir + "/_guard";
    mkdir(g_mapsDir.c_str(), 0775);

    if (!bearite_il2cpp_init(api) || !bind_il2cpp()) {
        g_status = "error";
        g_msg = "не найдены функции IL2CPP";
        write_state("");
        return;
    }
    bind_unity();

    std::string missing;
    if (!F.create_primitive) missing += " GameObject.CreatePrimitive";
    if (!F.go_transform) missing += " GameObject.get_transform";
    if (!F.set_position) missing += " Transform.set_position";
    if (!F.set_scale) missing += " Transform.set_localScale";
    if (!F.set_euler) missing += " Transform.set_eulerAngles";
    if (!F.destroy) missing += " Object.Destroy";
    g_engineOk = missing.empty();
    std::string guard = g_mapsDir + "/_guard";
    if (g_engineOk && access(guard.c_str(), F_OK) == 0) {
        g_engineOk = false;
        g_status = "error";
        g_msg = "в прошлый раз игра упала при построении карты, карты отключены (во вкладке «Карты» есть кнопка сброса)";
        logf_("%s", g_msg.c_str());
    } else if (!g_engineOk) {
        g_status = "error";
        g_msg = "в игре вырезаны методы Unity:" + missing;
        logf_("движок карт не запущен, нет методов:%s", missing.c_str());
        dump_class("UnityEngine", "GameObject");
        dump_class("UnityEngine", "Transform");
    } else {
        g_status = "ok";
        if (!F.get_component || !F.get_material || !F.set_color || !F.type_renderer)
            logf_("цвет объектов недоступен (нет Renderer/Material в игре)");
        if (!F.get_scene || !F.scene_name) logf_("имя сцены недоступно, привязка карт к сцене не работает");
        if (!F.cam_main || !F.get_position || !F.get_forward) logf_("позиция камеры недоступна, «перед камерой» не работает");
    }

    int hooks = 0;
    if (hook_time("get_deltaTime", (void*)h_dt, (void**)&o_dt) == 0) ++hooks;
    if (hook_time("get_unscaledDeltaTime", (void*)h_udt, (void**)&o_udt) == 0) ++hooks;
    if (hook_time("get_time", (void*)h_time, (void**)&o_time) == 0) ++hooks;
    if (hook_time("get_unscaledTime", (void*)h_utime, (void**)&o_utime) == 0) ++hooks;
    logf_("движок карт: хуков кадра %d, карты читаются из %s", hooks, g_mapsDir.c_str());
    if (hooks == 0) {
        g_status = "error";
        g_msg = "не удалось повесить хуки кадра";
    }
    write_state("");
}
